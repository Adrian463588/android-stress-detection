package com.stressdetector.mobile.domain.engine

import kotlinx.coroutines.flow.Flow

interface VerbatimSessionRepository {
    fun observeSessions(): Flow<List<VerbatimSessionSummary>>
    fun observeSession(sessionId: String): Flow<VerbatimSessionDetail?>
    suspend fun updateSpeakerLabel(sessionId: String, segmentId: String, label: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Speaker relabel persistence is unavailable."))

    suspend fun updateTranscriptText(sessionId: String, segmentId: String, text: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Transcript edit persistence is unavailable."))

    suspend fun deleteSession(sessionId: String): Result<Unit>
    suspend fun deleteAllSessions(): Result<Unit>
}

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
    val audioGaps: List<AudioGap> = emptyList()
)

data class VerbatimSessionDetail(
    val summary: VerbatimSessionSummary,
    val transcript: List<SpeechSegment>,
    val divergenceEvents: List<com.stressdetector.mobile.data.db.DivergenceEventEntity>
)
