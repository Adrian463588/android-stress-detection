package com.stressdetector.mobile.domain.stress

import com.stressdetector.mobile.data.db.StressSessionDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** A presentation-safe projection of one local stress-detection session. */
data class StressHistoryItem(
    val sessionId: String,
    val endedAtMillis: Long,
    val finalLabel: String,
    val confidence: Float,
    val qualityTier: String,
    val stressProbability: Float
)

interface StressHistoryReader {
    fun observeLatest(limit: Int = DEFAULT_LIMIT): Flow<List<StressHistoryItem>>

    suspend fun latest(limit: Int = DEFAULT_LIMIT): List<StressHistoryItem>

    data object Unavailable : StressHistoryReader {
        override fun observeLatest(limit: Int): Flow<List<StressHistoryItem>> = flow {
            error("Riwayat stress tidak tersedia.")
        }

        override suspend fun latest(limit: Int): List<StressHistoryItem> = error("Riwayat stress tidak tersedia.")
    }

    companion object {
        const val DEFAULT_LIMIT = 7
    }
}

class RoomStressHistoryReader(private val dao: StressSessionDao) : StressHistoryReader {
    override fun observeLatest(limit: Int): Flow<List<StressHistoryItem>> = dao.latest(limit.coerceIn(1, StressHistoryReader.DEFAULT_LIMIT))
        .map { sessions -> sessions.map { it.toHistoryItem() } }

    override suspend fun latest(limit: Int): List<StressHistoryItem> = observeLatest(limit).first()
}

object StressHistoryFormatter {
    private val timestampFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID"))

    fun formatTimestamp(timestampMillis: Long): String = synchronized(timestampFormat) { timestampFormat.format(Date(timestampMillis)) }

    fun formatLabel(rawLabel: String): String = when (rawLabel.trim().lowercase(Locale.US)) {
        "stress", "stres", "1" -> "Stres"
        "normal", "relax", "relaxed", "0" -> "Normal"
        else -> rawLabel.trim().ifBlank { "Tidak tersedia" }
    }

    fun formatProbability(probability: Float): String = if (probability.isFinite() && probability in 0f..1f) {
        "${(probability * 100f).toInt()}%"
    } else {
        "Tidak tersedia"
    }

    fun formatConfidence(confidence: Float): String = if (confidence.isFinite() && confidence in 0f..1f) {
        "${(confidence * 100f).toInt()}%"
    } else {
        "Tidak tersedia"
    }

    fun toPromptContext(items: List<StressHistoryItem>): String {
        if (items.isEmpty()) return "No stored stress history is available."
        return buildString {
            append("Stored local stress-session summary (bounded to 7 sessions):\n")
            items.take(StressHistoryReader.DEFAULT_LIMIT).forEach { item ->
                append("- timestamp=${formatTimestamp(item.endedAtMillis)}; label=${formatLabel(item.finalLabel)}; ")
                append("probability=${formatPromptNumber(item.stressProbability)}; ")
                append("confidence=${formatPromptNumber(item.confidence)}; quality=${item.qualityTier}\n")
            }
            append("This context describes physiological arousal only and is not a diagnosis.")
        }
    }

    private fun formatPromptNumber(value: Float): String = if (value.isFinite()) "%.3f".format(Locale.US, value) else "unavailable"
}

private fun com.stressdetector.mobile.data.db.StressSessionEntity.toHistoryItem(): StressHistoryItem = StressHistoryItem(
    sessionId = sessionId,
    endedAtMillis = endedAtMillis,
    finalLabel = finalLabel,
    confidence = confidence,
    qualityTier = qualityTier,
    stressProbability = stressProbability
)
