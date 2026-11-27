package com.stressdetector.mobile.domain.engine

import kotlinx.coroutines.flow.Flow

interface VerbatimSessionRepository {
    fun observeSessions(): Flow<List<VerbatimSessionSummary>>
    fun observeSession(sessionId: String): Flow<VerbatimSessionDetail?>
    suspend fun updateSpeakerLabel(sessionId: String, segmentId: String, label: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Speaker relabel persistence is unavailable."))

    suspend fun updateTranscriptText(sessionId: String, segmentId: String, text: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Transcript edit persistence is unavailable."))

    suspend fun getLatestSessionNotes(sessionId: String): String? = null

    suspend fun deleteSession(sessionId: String): Result<Unit>
    suspend fun deleteAllSessions(): Result<Unit>
    suspend fun updateWatchWearerRole(sessionId: String, role: String): Result<Unit> = Result.success(Unit)
    suspend fun hasSession(sessionId: String): Boolean = false
    suspend fun importSessionAtomically(record: VerbatimImportedSessionRecord): Result<Boolean> =
        Result.failure(UnsupportedOperationException("Session package import is unavailable."))
}

data class VerbatimImportedSessionRecord(
    val session: com.stressdetector.mobile.data.db.VerbatimSessionEntity,
    val segments: List<com.stressdetector.mobile.data.db.TranscriptSegmentEntity>,
    val events: List<com.stressdetector.mobile.data.db.DivergenceEventEntity>,
    val annotations: List<com.stressdetector.mobile.data.db.AnnotationEntity>,
    val temperatureSamples: List<SkinTemperatureObservation>,
    val aiSummaryText: String?
)

data class VerbatimSessionSummary(
    val sessionId: String,
    val sessionName: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val durationSeconds: Long,
    val audioFilePath: String,
    val artifactFolderPath: String,
    val finalizationStatus: String,
    val summaryStatus: String,
    val audioGaps: List<AudioGap> = emptyList(),
    val audioDurationMs: Long = 0L,
    val watchWearerRole: String = "CLIENT",
    val languageCode: String = "id"
)

data class VerbatimSessionDetail(
    val summary: VerbatimSessionSummary,
    val transcript: List<SpeechSegment>,
    val divergenceEvents: List<com.stressdetector.mobile.data.db.DivergenceEventEntity>,
    val skinTemperatureSamples: List<SkinTemperatureObservation> = emptyList()
)
