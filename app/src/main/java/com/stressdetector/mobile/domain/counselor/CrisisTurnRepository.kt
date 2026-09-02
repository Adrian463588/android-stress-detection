package com.stressdetector.mobile.domain.counselor

/**
 * Stable data for a deterministic safety response. Reusing the same IDs makes
 * persistence retry-safe when a device is low on storage or Room is unavailable.
 */
data class CrisisTurn(
    val sessionId: String,
    val sessionTitle: String,
    val sessionStartedAtMillis: Long,
    val userMessageId: String,
    val userText: String,
    val userTimestampMillis: Long,
    val inputMode: String,
    val crisisMessageId: String,
    val crisisText: String,
    val crisisTimestampMillis: Long,
    val templateVersion: String
)

interface CrisisTurnRepository {
    suspend fun persist(turn: CrisisTurn): Result<Unit>
}
