package com.stressdetector.mobile.data.repository

import com.stressdetector.mobile.data.db.AiAnalysisEntity
import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.data.db.LiveSessionEntity
import com.stressdetector.mobile.data.db.SkinTemperatureSampleEntity
import com.stressdetector.mobile.data.db.StressDatabase
import com.stressdetector.mobile.data.db.TranscriptSegmentEntity
import com.stressdetector.mobile.data.db.VerbatimSessionEntity
import com.stressdetector.mobile.domain.engine.AudioGap
import com.stressdetector.mobile.domain.engine.AudioGapCodec
import com.stressdetector.mobile.domain.engine.SkinTemperatureObservation
import com.stressdetector.mobile.domain.engine.SpeechSegment
import com.stressdetector.mobile.domain.engine.VerbatimImportedSessionRecord
import com.stressdetector.mobile.domain.engine.VerbatimSessionDetail
import com.stressdetector.mobile.domain.engine.VerbatimSessionRepository
import com.stressdetector.mobile.domain.engine.VerbatimSessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class UnifiedSessionRepository(private val database: StressDatabase) : VerbatimSessionRepository {
    private val verbatimDao = database.verbatimDao()
    private val liveDao = database.liveMonitorDao()
    private val temperatureDao = database.skinTemperatureDao()

    @Suppress("LongParameterList")
    suspend fun startSession(
        sessionId: String,
        timestampMs: Long,
        consentGranted: Boolean,
        sessionName: String = "Sesi Verbatim",
        watchWearerRole: String = "CLIENT",
        languageCode: String = "id"
    ) {
        withContext(Dispatchers.IO) {
            val verbatim = VerbatimSessionEntity(
                sessionId = sessionId,
                startedAtMillis = timestampMs,
                endedAtMillis = 0L,
                audioFilePath = "", // Not populated yet
                consentGranted = consentGranted,
                durationSeconds = 0L,
                status = "RECORDING",
                sessionName = sessionName.trim().ifBlank { "Sesi Verbatim" },
                watchWearerRole = watchWearerRole,
                languageCode = languageCode
            )

            val live = LiveSessionEntity(
                sessionId = sessionId,
                startedAtMillis = timestampMs,
                endedAtMillis = null,
                durationSeconds = 0L,
                consentGranted = consentGranted,
                averageStressProbability = 0.0f
            )

            // Insert into both domains to maintain the unified session logic
            verbatimDao.insertSession(verbatim)
            liveDao.insertLiveSession(live)
        }
    }

    @Suppress("LongParameterList")
    suspend fun endSession(
        sessionId: String,
        timestampMs: Long,
        audioPath: String = "",
        artifactFolderPath: String = "",
        summaryStatus: String = "NOT_REQUESTED",
        finalizationStatus: String = "COMPLETED",
        audioGaps: List<AudioGap> = emptyList(),
        durationSecondsOverride: Long? = null,
        audioDurationMs: Long = 0L
    ) {
        withContext(Dispatchers.IO) {
            val verbatim = verbatimDao.getSessionById(sessionId)
            if (verbatim != null) {
                val duration = (durationSecondsOverride ?: ((timestampMs - verbatim.startedAtMillis) / 1000L))
                    .coerceAtLeast(0L)
                verbatimDao.updateSession(
                    verbatim.copy(
                        endedAtMillis = timestampMs,
                        durationSeconds = duration,
                        status = sessionStatusForFinalization(finalizationStatus),
                        audioFilePath = audioPath,
                        artifactFolderPath = artifactFolderPath,
                        summaryStatus = summaryStatus,
                        finalizationStatus = finalizationStatus,
                        audioGapsJson = AudioGapCodec.encode(audioGaps),
                        audioDurationMs = audioDurationMs.coerceAtLeast(0L)
                    )
                )
            }

            val live = liveDao.getLiveSessionById(sessionId)
            if (live != null) {
                val duration = (durationSecondsOverride ?: ((timestampMs - live.startedAtMillis) / 1000L))
                    .coerceAtLeast(0L)
                liveDao.updateLiveSession(
                    live.copy(
                        endedAtMillis = timestampMs,
                        durationSeconds = duration
                    )
                )
            }
        }
    }

    /**
     * Process-death recovery is deliberately fail-safe: orphaned recordings are
     * marked interrupted and remain reviewable, never promoted to completed.
     */
    suspend fun recoverInterruptedSessions(timestampMs: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val interrupted = verbatimDao.getAllSessionsOnce().filter { it.status == "RECORDING" }
        interrupted.forEach { session ->
            val duration = ((timestampMs - session.startedAtMillis).coerceAtLeast(0L)) / 1000L
            verbatimDao.updateSession(
                session.copy(
                    endedAtMillis = timestampMs,
                    durationSeconds = duration,
                    status = "INTERRUPTED",
                    finalizationStatus = "INTERRUPTED_PROCESS_DEATH",
                    summaryStatus = "NOT_REQUESTED"
                )
            )
            liveDao.getLiveSessionById(session.sessionId)?.let { live ->
                liveDao.updateLiveSession(live.copy(endedAtMillis = timestampMs, durationSeconds = duration))
            }
        }
        interrupted.size
    }

    suspend fun saveTranscript(sessionId: String, segments: List<SpeechSegment>) {
        if (segments.isEmpty()) return
        withContext(Dispatchers.IO) {
            verbatimDao.insertSegments(
                segments.map { segment ->
                    TranscriptSegmentEntity(
                        segmentId = segment.id,
                        sessionId = sessionId,
                        speakerId = segment.speakerId,
                        startOffsetMs = segment.startOffsetMs,
                        endOffsetMs = segment.endOffsetMs,
                        rawText = segment.rawText,
                        cleanedText = segment.editedText ?: segment.rawText,
                        confidence = segment.confidence,
                        language = segment.language
                    )
                }
            )
        }
    }

    suspend fun saveDivergenceEvents(events: List<com.stressdetector.mobile.data.db.DivergenceEventEntity>) {
        if (events.isEmpty()) return
        withContext(Dispatchers.IO) { liveDao.insertDivergenceEvents(events) }
    }

    /** Final snapshot is authoritative; remove live tail rows outside its cutoff. */
    suspend fun replaceDivergenceEvents(sessionId: String, events: List<DivergenceEventEntity>) {
        require(events.all { it.sessionId == sessionId }) { "Divergence event belongs to another session" }
        withContext(Dispatchers.IO) { liveDao.replaceDivergenceEvents(sessionId, events) }
    }

    suspend fun saveSkinTemperatureSamples(sessionId: String, samples: List<SkinTemperatureObservation>) {
        if (samples.isEmpty()) return
        require(samples.all { it.sessionId == sessionId }) { "Skin temperature sample belongs to another session" }
        withContext(Dispatchers.IO) {
            temperatureDao.insertAll(samples.map { it.toEntity() })
        }
    }

    override suspend fun hasSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        verbatimDao.getSessionById(sessionId) != null
    }

    @Suppress("LongParameterList")
    override suspend fun importSessionAtomically(record: VerbatimImportedSessionRecord): Result<Boolean> = runCatching {
        val session = record.session
        val segments = record.segments
        val events = record.events
        val annotations = record.annotations
        val temperatureSamples = record.temperatureSamples
        val aiSummaryText = record.aiSummaryText
        withContext(Dispatchers.IO) {
            var inserted = false
            database.runInTransaction {
                kotlinx.coroutines.runBlocking {
                    if (verbatimDao.getSessionById(session.sessionId) != null) {
                        inserted = false
                        return@runBlocking
                    }
                    verbatimDao.insertSession(session)
                    liveDao.insertLiveSession(
                        LiveSessionEntity(
                            sessionId = session.sessionId,
                            startedAtMillis = session.startedAtMillis,
                            endedAtMillis = session.endedAtMillis,
                            durationSeconds = session.durationSeconds,
                            consentGranted = session.consentGranted,
                            averageStressProbability = 0f
                        )
                    )
                    if (segments.isNotEmpty()) verbatimDao.insertSegments(segments)
                    if (events.isNotEmpty()) liveDao.insertDivergenceEvents(events)
                    annotations.forEach { database.annotationDao().insertAnnotation(it) }
                    if (temperatureSamples.isNotEmpty()) {
                        temperatureDao.insertAll(temperatureSamples.map { it.toEntity() })
                    }
                    if (!aiSummaryText.isNullOrBlank()) {
                        verbatimDao.insertAnalysis(
                            AiAnalysisEntity(
                                analysisId = "${session.sessionId}:imported-summary",
                                sessionId = session.sessionId,
                                version = 1,
                                summaryText = aiSummaryText,
                                keyInsightsJson = "[]",
                                generatedAtMillis = session.endedAtMillis
                            )
                        )
                    }
                    inserted = true
                }
            }
            inserted
        }
    }

    override fun observeSessions(): Flow<List<VerbatimSessionSummary>> = verbatimDao.getAllSessions().map { sessions ->
        sessions.map(::toSummary)
    }

    override fun observeSession(sessionId: String): Flow<VerbatimSessionDetail?> = combine(
        verbatimDao.observeSessionById(sessionId),
        verbatimDao.getSegmentsForSession(sessionId),
        liveDao.getDivergenceEventsForSession(sessionId),
        temperatureDao.observeForSession(sessionId)
    ) { session, segments, events, temperatureRows ->
        session?.let {
            VerbatimSessionDetail(
                summary = toSummary(it),
                transcript = segments.map { segment -> segment.toSpeechSegment(it.startedAtMillis) },
                divergenceEvents = events,
                skinTemperatureSamples = temperatureRows.map(SkinTemperatureSampleEntity::toObservation)
            )
        }
    }

    override suspend fun updateSpeakerLabel(sessionId: String, segmentId: String, label: String): Result<Unit> = runCatching {
        require(label.isNotBlank()) { "Speaker label must not be blank." }
        withContext(Dispatchers.IO) {
            check(verbatimDao.updateSpeakerLabel(sessionId, segmentId, label) == 1) {
                "Transcript segment was not found for this session."
            }
        }
    }

    override suspend fun updateTranscriptText(sessionId: String, segmentId: String, text: String): Result<Unit> = runCatching {
        require(text.isNotBlank()) { "Transcript text must not be blank." }
        withContext(Dispatchers.IO) {
            check(verbatimDao.updateCleanedText(sessionId, segmentId, text) == 1) {
                "Transcript segment was not found for this session."
            }
        }
    }

    override suspend fun getLatestSessionNotes(sessionId: String): String? = withContext(Dispatchers.IO) {
        verbatimDao.getLatestAnalysis(sessionId)?.summaryText
    }

    override suspend fun deleteSession(sessionId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            verbatimDao.deleteSessionGraph(sessionId)
            liveDao.deleteSessionGraph(sessionId)
            temperatureDao.deleteForSession(sessionId)
            database.annotationDao().deleteAnnotationsForSession(sessionId)
        }
    }

    override suspend fun deleteAllSessions(): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            verbatimDao.getAllSessionsOnce().forEach { session ->
                verbatimDao.deleteSessionGraph(session.sessionId)
                liveDao.deleteSessionGraph(session.sessionId)
                temperatureDao.deleteForSession(session.sessionId)
                database.annotationDao().deleteAnnotationsForSession(session.sessionId)
            }
        }
    }

    private fun toSummary(session: VerbatimSessionEntity) = VerbatimSessionSummary(
        sessionId = session.sessionId,
        sessionName = session.sessionName,
        startedAtMillis = session.startedAtMillis,
        endedAtMillis = session.endedAtMillis,
        durationSeconds = session.durationSeconds,
        audioFilePath = session.audioFilePath,
        artifactFolderPath = session.artifactFolderPath,
        finalizationStatus = session.finalizationStatus,
        summaryStatus = session.summaryStatus,
        audioGaps = AudioGapCodec.decode(session.audioGapsJson),
        audioDurationMs = session.audioDurationMs,
        watchWearerRole = session.watchWearerRole,
        languageCode = session.languageCode
    )

    override suspend fun updateWatchWearerRole(sessionId: String, role: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val existing = verbatimDao.getSessionById(sessionId) ?: return@withContext
            verbatimDao.updateSession(existing.copy(watchWearerRole = role))
        }
    }
}

internal fun SkinTemperatureObservation.toEntity(): SkinTemperatureSampleEntity = SkinTemperatureSampleEntity(
    sampleId = sampleId,
    sessionId = sessionId,
    generation = generation,
    sequenceNumber = sequenceNumber,
    sensorTimestampMs = sensorTimestampMs,
    receivedTimestampMs = receivedTimestampMs,
    elapsedMillis = elapsedMillis,
    clockUncertaintyMs = clockUncertaintyMs,
    skinTemperatureC = skinTemperatureC,
    ambientTemperatureC = ambientTemperatureC,
    measurementStatus = measurementStatus,
    isValid = isValid,
    wearerRole = wearerRole,
    source = source,
    isPaused = isPaused
)

internal fun SkinTemperatureSampleEntity.toObservation(): SkinTemperatureObservation = SkinTemperatureObservation(
    sampleId = sampleId,
    sessionId = sessionId,
    generation = generation,
    sequenceNumber = sequenceNumber,
    sensorTimestampMs = sensorTimestampMs,
    receivedTimestampMs = receivedTimestampMs,
    elapsedMillis = elapsedMillis,
    clockUncertaintyMs = clockUncertaintyMs,
    skinTemperatureC = skinTemperatureC,
    ambientTemperatureC = ambientTemperatureC,
    measurementStatus = measurementStatus,
    isValid = isValid,
    wearerRole = wearerRole,
    source = source,
    isPaused = isPaused
)

internal fun TranscriptSegmentEntity.toSpeechSegment(sessionStartedAtMillis: Long): SpeechSegment = SpeechSegment(
    id = segmentId,
    speakerId = speakerId,
    startOffsetMs = startOffsetMs,
    endOffsetMs = endOffsetMs,
    startTimestampUtc = sessionStartedAtMillis + startOffsetMs,
    endTimestampUtc = sessionStartedAtMillis + endOffsetMs,
    rawText = rawText,
    editedText = cleanedText?.takeIf { it != rawText },
    confidence = confidence,
    language = language,
    sessionId = sessionId
)

internal fun sessionStatusForFinalization(finalizationStatus: String): String = when (finalizationStatus) {
    "COMPLETED" -> "COMPLETED"
    "INTERRUPTED_PROCESS_DEATH" -> "INTERRUPTED"
    else -> "FAILED"
}
