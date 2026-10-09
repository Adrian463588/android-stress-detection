package com.stressdetector.mobile.domain.stress

import com.stressdetector.mobile.data.db.StressSessionDao
import com.stressdetector.mobile.data.db.StressSessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Prompt-safe projection of one stored stress session. Raw PPG never crosses this boundary. */
data class StressHistoryContextEntry(
    val sessionId: String,
    val endedAtMillis: Long,
    val durationMillis: Long,
    val label: String,
    val stressProbability: Float?,
    val confidence: Float?,
    val qualityTier: String,
    val valid: Boolean,
    val rejectionReason: String?,
    val arousalMarkerCount: Int = 0
)

data class StressHistoryContextSnapshot(
    val version: String,
    val generatedAtMillis: Long,
    val entries: List<StressHistoryContextEntry>,
    val trend: String
) {
    val sourceSessionIds: List<String> get() = entries.filter(StressHistoryContextEntry::valid).map(StressHistoryContextEntry::sessionId)

    fun toPromptContext(maxChars: Int = DEFAULT_PROMPT_CHARS): String {
        val validEntries = entries.filter(StressHistoryContextEntry::valid)
        if (validEntries.isEmpty()) return "No valid stored stress history is available."

        val lines = buildList {
            add("Local PPG history (not a diagnosis):")
            add("Trend: $trend")
            validEntries.forEach { entry ->
                add(
                    buildString {
                        append("- ").append(formatTimestamp(entry.endedAtMillis))
                        append("; label=").append(entry.label)
                        append("; stressProbability=").append(formatNumber(entry.stressProbability))
                        append("; confidence=").append(formatNumber(entry.confidence))
                        append("; quality=").append(entry.qualityTier)
                        if (entry.durationMillis > 0) append("; durationSeconds=").append(entry.durationMillis / 1_000L)
                        if (entry.arousalMarkerCount > 0) append("; arousalMarkers=").append(entry.arousalMarkerCount)
                    }
                )
            }
            add("Physiological arousal only. Do not diagnose or infer deception.")
        }
        val bounded = StringBuilder()
        lines.forEach { line ->
            val separator = if (bounded.isEmpty()) "" else "\n"
            if (bounded.length + separator.length + line.length <= maxChars) {
                bounded.append(separator).append(line)
            }
        }
        return bounded.toString().ifBlank { lines.first().take(maxChars) }
    }

    private fun formatNumber(value: Float?): String = value?.takeIf(Float::isFinite)?.let { "%.3f".format(Locale.US, it) } ?: "unavailable"

    private fun formatTimestamp(timestampMillis: Long): String =
        synchronized(TIMESTAMP_FORMAT) { TIMESTAMP_FORMAT.format(Date(timestampMillis)) }

    private companion object {
        const val DEFAULT_PROMPT_CHARS = 300
        val TIMESTAMP_FORMAT = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID"))
    }
}

sealed interface StressHistoryContextState {
    data object Disabled : StressHistoryContextState
    data class Ready(val snapshot: StressHistoryContextSnapshot) : StressHistoryContextState
    data class Unavailable(val reason: String) : StressHistoryContextState
}

interface StressHistoryContextSettings {
    val enabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)
}

class InMemoryStressHistoryContextSettings(initialEnabled: Boolean = false) : StressHistoryContextSettings {
    private val mutableEnabled = MutableStateFlow(initialEnabled)
    override val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()
    override fun setEnabled(enabled: Boolean) {
        mutableEnabled.value = enabled
    }
}

interface StressHistoryContextProvider {
    fun observe(): Flow<StressHistoryContextState>
    suspend fun snapshot(): StressHistoryContextSnapshot
    fun setEnabled(enabled: Boolean)

    data object Unavailable : StressHistoryContextProvider {
        override fun observe(): Flow<StressHistoryContextState> =
            kotlinx.coroutines.flow.flowOf(StressHistoryContextState.Unavailable("Stress history provider unavailable"))

        override suspend fun snapshot(): StressHistoryContextSnapshot =
            StressHistoryContextSnapshot("stress-history-unavailable", System.currentTimeMillis(), emptyList(), "unavailable")

        override fun setEnabled(enabled: Boolean) = Unit
    }
}

class RoomStressHistoryContextProvider(
    private val dao: StressSessionDao,
    private val settings: StressHistoryContextSettings,
    private val aiConsent: Flow<Boolean> = flowOf(true),
    private val clock: () -> Long = { System.currentTimeMillis() }
) : StressHistoryContextProvider {
    override fun observe(): Flow<StressHistoryContextState> = dao.latest(MAX_CONTEXT_ENTRIES)
        .combine(settings.enabled) { sessions, enabled ->
            sessions to enabled
        }
        .combine(aiConsent) { (sessions, enabled), consentGranted ->
            Triple(sessions, enabled, consentGranted)
        }
        .map { (sessions, enabled, consentGranted) ->
            when {
                !enabled -> StressHistoryContextState.Disabled
                !consentGranted -> StressHistoryContextState.Unavailable(
                    "AI counseling consent is not granted"
                )
                else -> StressHistoryContextState.Ready(buildSnapshot(sessions))
            }
        }
        .catch { error ->
            emit(
                StressHistoryContextState.Unavailable(
                    error.message ?: "Stress history storage is unavailable"
                )
            )
        }

    override suspend fun snapshot(): StressHistoryContextSnapshot {
        try {
            if (!settings.enabled.value) return emptySnapshot("disabled")
            if (!aiConsent.first()) return emptySnapshot("AI counseling consent is not granted")
            return buildSnapshot(dao.latest(MAX_CONTEXT_ENTRIES).first())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw StressHistoryUnavailableException(
                error.message ?: "Stress history storage is unavailable",
                error
            )
        }
    }

    override fun setEnabled(enabled: Boolean) = settings.setEnabled(enabled)

    private fun buildSnapshot(sessions: List<StressSessionEntity>): StressHistoryContextSnapshot {
        val entries = sessions.sortedByDescending { it.endedAtMillis }.take(MAX_CONTEXT_ENTRIES).map { it.toContextEntry() }
        val valid = entries.filter(StressHistoryContextEntry::valid)
        val trend = when {
            valid.isEmpty() -> "insufficient valid sessions"
            valid.count { it.label == "Stres" } > valid.count { it.label == "Normal" } -> "more stress-labeled sessions recently"
            valid.count { it.label == "Normal" } > valid.count { it.label == "Stres" } -> "more normal-labeled sessions recently"
            else -> "mixed"
        }
        return StressHistoryContextSnapshot(
            version = CONTEXT_VERSION,
            generatedAtMillis = clock(),
            entries = entries,
            trend = trend
        )
    }

    private fun emptySnapshot(reason: String): StressHistoryContextSnapshot = StressHistoryContextSnapshot(
        version = "$CONTEXT_VERSION-$reason",
        generatedAtMillis = clock(),
        entries = emptyList(),
        trend = reason
    )

    private companion object {
        const val CONTEXT_VERSION = "stress-history-v2"
        const val MAX_CONTEXT_ENTRIES = 7
    }
}

class StressHistoryUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

private fun StressSessionEntity.toContextEntry(): StressHistoryContextEntry {
    val probabilityUsable = stressProbability?.let { it.isFinite() && it in 0f..1f } == true
    val confidenceUsable = confidence.isFinite() && confidence in 0f..1f
    val label = StressHistoryFormatter.formatLabel(finalLabel)
    val quality = qualityTier.trim().lowercase(Locale.US)
    val completed = durationMillis >= HOME_HISTORY_DURATION_MILLIS && endedAtMillis > startedAtMillis && startedAtMillis > 0L
    // A direct-message transport warning is evidence for review, but it does
    // not invalidate an otherwise complete core/model decision. The Home
    // finalizer persists it separately for exactly this reason. Keep only
    // objective prediction/SQA failures out of the AI+ history context.
    val rejectionReason = errorReason?.takeIf(String::isNotBlank) ?: when {
        !completed -> "session_incomplete_or_invalid_time"
        label !in setOf("Stres", "Normal") -> "prediction_label_unsupported"
        quality !in setOf("excellent", "good", "acceptable") -> "quality_not_accepted"
        !probabilityUsable || !confidenceUsable -> "prediction_out_of_range_or_non_finite"
        else -> null
    }
    return StressHistoryContextEntry(
        sessionId = sessionId,
        endedAtMillis = endedAtMillis,
        durationMillis = durationMillis,
        label = label,
        stressProbability = stressProbability.takeIf { probabilityUsable },
        confidence = confidence.takeIf { confidenceUsable },
        qualityTier = quality.ifBlank { "unavailable" },
        valid = rejectionReason == null,
        rejectionReason = rejectionReason
    )
}

private const val HOME_HISTORY_DURATION_MILLIS = 300_000L
