@file:Suppress("LongParameterList", "ReturnCount")

package com.stressdetector.mobile.domain.engine

import kotlin.math.abs

data class SpeakerAcousticObservation(
    val segmentId: String,
    val sessionId: String,
    val speakerId: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val f0Semitones: Double?,
    val loudnessDb: Double?,
    val speakingRateWps: Double?,
    val pauseRatio: Double?,
    val voicedRatio: Double,
    val audioQuality: Float,
    val isOverlap: Boolean = false,
    val isFinal: Boolean = true
)

data class SpeakerBaselineDivergence(
    val segmentId: String,
    val speakerId: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val primaryFeature: String,
    val summaryLabel: String,
    val baselineValue: Float,
    val observedValue: Float,
    val deltaValue: Float,
    val zScore: Float,
    val unit: String
)

/**
 * Maintains independent per-speaker rolling medians + MAD scales across prosodic features.
 * Abstains strictly on unattributed ("Unknown"), overlapping, or low-quality segments.
 */
class SpeakerBaselineTracker(
    private val baselineCapacity: Int = DEFAULT_BASELINE_CAPACITY,
    private val warmupUtterances: Int = DEFAULT_WARMUP_UTTERANCES,
    private val enterZScore: Double = DEFAULT_ENTER_Z_SCORE,
    private val minQuality: Float = DEFAULT_MIN_QUALITY,
    private val minVoicedRatio: Double = DEFAULT_MIN_VOICED_RATIO,
    private val minDurationMs: Long = DEFAULT_MIN_DURATION_MS,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    private data class SpeakerWindowState(
        val f0: ArrayDeque<Double> = ArrayDeque(),
        val loudness: ArrayDeque<Double> = ArrayDeque(),
        val rate: ArrayDeque<Double> = ArrayDeque(),
        val pause: ArrayDeque<Double> = ArrayDeque(),
        var lastMarkerStartMs: Long? = null
    )

    private var activeSessionId: String? = null
    private val stateBySpeaker = mutableMapOf<String, SpeakerWindowState>()

    fun reset(sessionId: String? = null) {
        activeSessionId = sessionId
        stateBySpeaker.clear()
    }

    fun relabelSpeaker(oldSpeakerId: String, newSpeakerId: String) {
        val oldKey = canonicalSpeakerKey(oldSpeakerId) ?: return
        val newKey = canonicalSpeakerKey(newSpeakerId) ?: return
        if (oldKey == newKey) return
        val oldState = stateBySpeaker.remove(oldKey) ?: return
        val target = stateBySpeaker.getOrPut(newKey) { SpeakerWindowState() }
        appendBounded(target.f0, oldState.f0)
        appendBounded(target.loudness, oldState.loudness)
        appendBounded(target.rate, oldState.rate)
        appendBounded(target.pause, oldState.pause)
    }

    fun baselineCountFor(speakerId: String): Int {
        val key = canonicalSpeakerKey(speakerId) ?: return 0
        return stateBySpeaker[key]?.f0?.size ?: 0
    }

    fun observe(obs: SpeakerAcousticObservation): SpeakerBaselineDivergence? {
        if (activeSessionId != obs.sessionId) reset(obs.sessionId)
        val speakerKey = canonicalSpeakerKey(obs.speakerId)
        val eligible = speakerKey != null &&
            obs.isFinal &&
            !obs.isOverlap &&
            obs.audioQuality >= minQuality &&
            obs.voicedRatio >= minVoicedRatio &&
            (obs.endOffsetMs - obs.startOffsetMs) >= minDurationMs
        return if (!eligible || speakerKey == null) {
            null
        } else {
            evaluateEligible(speakerKey, obs)
        }
    }

    private fun evaluateEligible(speakerKey: String, obs: SpeakerAcousticObservation): SpeakerBaselineDivergence? {
        val state = stateBySpeaker.getOrPut(speakerKey) { SpeakerWindowState() }
        val candidates = listOfNotNull(
            scoreFeature("f0Semitones", "st", obs.f0Semitones, state.f0, MIN_MAD_SEMITONES) { delta ->
                if (delta >= 0.0) "Pitch meningkat" else "Pitch menurun"
            },
            scoreFeature("loudnessDb", "dB", obs.loudnessDb, state.loudness, MIN_MAD_LOUDNESS) { delta ->
                if (delta >= 0.0) "Energi suara meningkat" else "Energi suara melemah"
            },
            scoreFeature("speakingRateWps", "kata/dtk", obs.speakingRateWps, state.rate, MIN_MAD_RATE) { delta ->
                if (delta >= 0.0) "Tempo bicara lebih cepat" else "Tempo bicara melambat"
            },
            scoreFeature("pauseRatio", "rasio", obs.pauseRatio, state.pause, MIN_MAD_PAUSE) { delta ->
                if (delta >= 0.0) "Jeda bicara meningkat" else "Jeda bicara berkurang"
            }
        )
        val best = candidates.maxByOrNull { abs(it.zScore) }
        val cooldownReady = state.lastMarkerStartMs?.let { obs.startOffsetMs - it >= cooldownMs } ?: true
        val triggered = best != null && abs(best.zScore) >= enterZScore && cooldownReady

        if (!triggered) {
            obs.f0Semitones?.takeIf(Double::isFinite)?.let { pushBounded(state.f0, it) }
            obs.loudnessDb?.takeIf(Double::isFinite)?.let { pushBounded(state.loudness, it) }
            obs.speakingRateWps?.takeIf(Double::isFinite)?.let { pushBounded(state.rate, it) }
            obs.pauseRatio?.takeIf(Double::isFinite)?.let { pushBounded(state.pause, it) }
            return null
        }

        state.lastMarkerStartMs = obs.startOffsetMs
        return SpeakerBaselineDivergence(
            segmentId = obs.segmentId,
            speakerId = obs.speakerId,
            startOffsetMs = obs.startOffsetMs,
            endOffsetMs = obs.endOffsetMs,
            primaryFeature = best.feature,
            summaryLabel = best.summary,
            baselineValue = best.median.toFloat(),
            observedValue = best.observed.toFloat(),
            deltaValue = best.delta.toFloat(),
            zScore = best.zScore.toFloat(),
            unit = best.unit
        )
    }

    private data class FeatureScore(
        val feature: String,
        val unit: String,
        val observed: Double,
        val median: Double,
        val delta: Double,
        val zScore: Double,
        val summary: String
    )

    private fun scoreFeature(
        feature: String,
        unit: String,
        value: Double?,
        history: ArrayDeque<Double>,
        minScale: Double,
        summaryForDelta: (Double) -> String
    ): FeatureScore? {
        if (value == null || !value.isFinite() || history.size < warmupUtterances) return null
        val sorted = history.sorted()
        val med = medianSorted(sorted)
        val deviations = sorted.map { abs(it - med) }.sorted()
        val mad = medianSorted(deviations)
        val scale = maxOf(MAD_CONSISTENCY * mad, minScale)
        val delta = value - med
        val z = delta / scale
        return FeatureScore(feature, unit, value, med, delta, z, summaryForDelta(delta))
    }

    private fun medianSorted(sorted: List<Double>): Double {
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun pushBounded(queue: ArrayDeque<Double>, value: Double) {
        if (queue.size >= baselineCapacity) queue.removeFirst()
        queue.addLast(value)
    }

    private fun appendBounded(target: ArrayDeque<Double>, source: ArrayDeque<Double>) {
        source.forEach { pushBounded(target, it) }
    }

    companion object {
        const val DEFAULT_BASELINE_CAPACITY = 24
        const val DEFAULT_WARMUP_UTTERANCES = 3
        const val DEFAULT_ENTER_Z_SCORE = 2.5
        const val DEFAULT_MIN_QUALITY = 0.6f
        const val DEFAULT_MIN_VOICED_RATIO = 0.2
        const val DEFAULT_MIN_DURATION_MS = 1_500L
        const val DEFAULT_COOLDOWN_MS = 10_000L
        private const val MAD_CONSISTENCY = 1.4826
        private const val MIN_MAD_SEMITONES = 1.2
        private const val MIN_MAD_LOUDNESS = 0.25
        private const val MIN_MAD_RATE = 0.45
        private const val MIN_MAD_PAUSE = 0.12

        fun isAttributedSpeaker(speakerId: String): Boolean = canonicalSpeakerKey(speakerId) != null

        internal fun canonicalSpeakerKey(raw: String): String? {
            val trimmed = raw.trim()
            return if (trimmed.isEmpty() || trimmed.equals("Unknown", ignoreCase = true)) {
                null
            } else {
                trimmed.lowercase()
            }
        }
    }
}
