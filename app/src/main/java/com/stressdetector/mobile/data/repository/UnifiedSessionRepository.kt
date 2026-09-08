package com.stressdetector.mobile.data.repository

import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.data.db.LiveSessionEntity
import com.stressdetector.mobile.data.db.StressDatabase
import com.stressdetector.mobile.data.db.TranscriptSegmentEntity
import com.stressdetector.mobile.data.db.VerbatimSessionEntity
import com.stressdetector.mobile.domain.engine.AudioGap
import com.stressdetector.mobile.domain.engine.AudioGapCodec
import com.stressdetector.mobile.domain.engine.SpeechSegment
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

    suspend fun startSession(sessionId: String, timestampMs: Long, consentGranted: Boolean, sessionName: String = "Sesi Verbatim") {
        withContext(Dispatchers.IO) {
            val verbatim = VerbatimSessionEntity(
                sessionId = sessionId,
                startedAtMillis = timestampMs,
                endedAtMillis = 0L,
                audioFilePath = "", // Not populated yet
                consentGranted = consentGranted,
                durationSeconds = 0L,
                status = "RECORDING",
                sessionName = sessionName.trim().ifBlank { "Sesi Verbatim" }
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
        audioGaps: List<AudioGap> = emptyList()
    ) {
        withContext(Dispatchers.IO) {
            val verbatim = verbatimDao.getSessionById(sessionId)
            if (verbatim != null) {
                val duration = (timestampMs - verbatim.startedAtMillis) / 1000
                verbatimDao.updateSession(
                    verbatim.copy(
                        endedAtMillis = timestampMs,
                        durationSeconds = duration,
                        status = sessionStatusForFinalization(finalizationStatus),
                        audioFilePath = audioPath,
                        artifactFolderPath = artifactFolderPath,
                        summaryStatus = summaryStatus,
                        finalizationStatus = finalizationStatus,
                        audioGapsJson = AudioGapCodec.encode(audioGaps)
                    )
                )
            }

            val live = liveDao.getLiveSessionById(sessionId)
            if (live != null) {
                val duration = (timestampMs - live.startedAtMillis) / 1000
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

    override fun observeSessions(): Flow<List<VerbatimSessionSummary>> = verbatimDao.getAllSessions().map { sessions ->
        sessions.map(::toSummary)
    }

    override fun observeSession(sessionId: String): Flow<VerbatimSessionDetail?> = combine(
        verbatimDao.observeSessionById(sessionId),
        verbatimDao.getSegmentsForSession(sessionId),
        liveDao.getDivergenceEventsForSession(sessionId)
    ) { session, segments, events ->
        session?.let {
            VerbatimSessionDetail(
                summary = toSummary(it),
                transcript = segments.map { segment ->
                    SpeechSegment(
                        id = segment.segmentId,
                        speakerId = segment.speakerId,
                        startOffsetMs = segment.startOffsetMs,
                        endOffsetMs = segment.endOffsetMs,
                        startTimestampUtc = it.startedAtMillis + segment.startOffsetMs,
                        endTimestampUtc = it.startedAtMillis + segment.endOffsetMs,
                        rawText = segment.rawText,
                        editedText = segment.cleanedText?.takeIf { it != segment.rawText },
                        confidence = segment.confidence,
                        language = segment.language
                    )
                },
                divergenceEvents = events
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

    override suspend fun deleteSession(sessionId: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            verbatimDao.deleteSessionGraph(sessionId)
            liveDao.deleteSessionGraph(sessionId)
            database.annotationDao().deleteAnnotationsForSession(sessionId)
        }
    }

    override suspend fun deleteAllSessions(): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            verbatimDao.getAllSessionsOnce().forEach { session ->
                verbatimDao.deleteSessionGraph(session.sessionId)
                liveDao.deleteSessionGraph(session.sessionId)
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
        audioGaps = AudioGapCodec.decode(session.audioGapsJson)
    )
}

internal fun sessionStatusForFinalization(finalizationStatus: String): String = when (finalizationStatus) {
    "COMPLETED" -> "COMPLETED"
    "INTERRUPTED_PROCESS_DEATH" -> "INTERRUPTED"
    else -> "FAILED"
}
