package com.stressdetector.mobile.domain.engine

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Review-marker taxonomy shared by physiological, acoustic, auxiliary, and
 * manually-created events. None of these values is a deception or diagnostic
 * label; they identify evidence that a clinician may want to review.
 */
enum class SessionMarkerType {
    STRESS_ELEVATED,
    ACOUSTIC_DIVERGENCE,
    AUXILIARY_EVENT,
    INCONSISTENCY_CUE,
    LOW_AUDIO_QUALITY,
    MANUAL
}

/** Bounded measured peaks. Invalid PCM is unavailable, not a zero-filled waveform. */
fun pcmPeakEnvelope(samples: FloatArray, maxPoints: Int = MAX_AUDIO_ENVELOPE_POINTS): List<Float> {
    require(maxPoints in 1..MAX_AUDIO_ENVELOPE_POINTS)
    if (samples.isEmpty() || samples.any { !it.isFinite() || abs(it) > 1f }) return emptyList()
    val count = minOf(maxPoints, samples.size)
    return List(count) { bucket ->
        val start = bucket * samples.size / count
        val end = (bucket + 1) * samples.size / count
        var peak = 0f
        for (index in start until end) peak = maxOf(peak, abs(samples[index]))
        peak
    }
}

data class AcousticAudioFrame(val sessionId: String, val samples: FloatArray, val startOffsetMs: Long, val durationMs: Long)

data class AcousticAnalysisSnapshot(
    val sessionId: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val rmsDb: Float,
    val baselineRmsDb: Float?,
    val zeroCrossingRate: Float,
    val clippingRatio: Float,
    val finiteSampleRatio: Float,
    val qualityScore: Float,
    /** Difference from the running audio baseline, not a stress probability. */
    val baselineDeviationDb: Float?,
    /** Current frame quality state; remains active while consecutive bad frames arrive. */
    val markerType: SessionMarkerType?,
    /** Non-null only when the marker state changes and should be persisted as an event. */
    val markerTransition: SessionMarkerType?,
    /** Stable identifier for one persisted marker episode. */
    val markerEpisodeId: String? = null,
    /** First frame of the episode, before entry persistence elapsed. */
    val markerEpisodeStartOffsetMs: Long? = null,
    /** Quality-derived evidence confidence; it is not a calibrated probability. */
    val evidenceConfidence: Float,
    /** Bounded absolute PCM envelope for the live waveform; derived from this frame only. */
    val waveform: List<Float> = emptyList(),
    val analysisVersion: String = "acoustic-baseline-v2"
)

data class AcousticAnalysisConfig(
    val baselineWarmupFrames: Int = 5,
    val baselineWindowFrames: Int = 150,
    val lowEnergyDb: Float = -55f,
    val divergenceDb: Float = 8f,
    val maxClippingRatio: Float = 0.05f,
    val minimumFiniteSampleRatio: Float = 0.98f,
    val markerEnterPersistenceMillis: Long = 500L,
    val markerExitPersistenceMillis: Long = 1_000L,
    val markerCooldownMillis: Long = 5_000L
)

/** Pure, bounded frame analysis for real microphone PCM. */
class AcousticFrameAnalyzer(private val config: AcousticAnalysisConfig = AcousticAnalysisConfig()) {
    /**
     * Rolling primitive storage keeps the five-minute live path allocation
     * bounded. Values are unordered in the ring, which is sufficient for the
     * robust distribution statistics below.
     */
    private val baselineRmsDb = FloatArray(config.baselineWindowFrames)
    private var baselineSize = 0
    private var baselineWriteIndex = 0
    private var baselineSum = 0.0
    private var activeSessionId: String? = null
    private var candidateMarker: SessionMarkerType? = null
    private var candidateStartedAtMillis: Long? = null
    private var activeMarker: SessionMarkerType? = null
    private var activeEpisodeId: String? = null
    private var activeEpisodeStartedAtMillis: Long? = null
    private var clearingStartedAtMillis: Long? = null
    private val lastEmittedAtByType = mutableMapOf<SessionMarkerType, Long>()

    init {
        require(config.baselineWarmupFrames > 0)
        require(config.baselineWindowFrames >= config.baselineWarmupFrames)
        require(config.divergenceDb > 0f)
        require(config.maxClippingRatio in 0f..1f)
        require(config.minimumFiniteSampleRatio in 0f..1f)
        require(config.markerEnterPersistenceMillis > 0L)
        require(config.markerExitPersistenceMillis > 0L)
        require(config.markerCooldownMillis >= 0L)
    }

    fun reset(sessionId: String) {
        activeSessionId = sessionId
        baselineSize = 0
        baselineWriteIndex = 0
        baselineSum = 0.0
        candidateMarker = null
        candidateStartedAtMillis = null
        activeMarker = null
        activeEpisodeId = null
        activeEpisodeStartedAtMillis = null
        clearingStartedAtMillis = null
        lastEmittedAtByType.clear()
    }

    fun analyze(frame: AcousticAudioFrame): AcousticAnalysisSnapshot {
        if (activeSessionId != frame.sessionId) reset(frame.sessionId)

        val samples = frame.samples
        var finiteCount = 0
        var clippedCount = 0
        var zeroCrossings = 0
        var sumSquares = 0.0
        var hasPreviousFinite = false
        var previousFinite = 0f
        for (sample in samples) {
            if (!sample.isFinite()) continue
            finiteCount += 1
            sumSquares += sample.toDouble() * sample.toDouble()
            if (abs(sample) >= CLIP_THRESHOLD) clippedCount += 1
            if (hasPreviousFinite && crossesZero(previousFinite, sample)) {
                zeroCrossings += 1
            }
            previousFinite = sample
            hasPreviousFinite = true
        }
        val finiteRatio = if (samples.isEmpty()) 0f else finiteCount.toFloat() / samples.size
        val meanSquare = if (finiteCount == 0) 0.0 else sumSquares / finiteCount
        val rms = sqrt(meanSquare).toFloat().coerceIn(0f, 1f)
        val rmsDb = (20.0 * log10(rms.coerceAtLeast(MIN_RMS.toFloat()))).toFloat()
        val clippingRatio = if (finiteCount == 0) {
            1f
        } else {
            clippedCount.toFloat() / finiteCount
        }
        val zeroCrossingRate = if (finiteCount < 2) {
            0f
        } else {
            zeroCrossings.toFloat() / (finiteCount - 1)
        }
        val qualityScore = qualityScore(finiteRatio, rmsDb, clippingRatio)
        val baselineReady = baselineSize >= config.baselineWarmupFrames
        val baseline = if (baselineReady) (baselineSum / baselineSize).toFloat() else null
        val deviation = baseline?.let { rmsDb - it }
        val lowAudioQuality = isLowAudioQuality(samples, finiteRatio, clippingRatio, rmsDb)
        val rawMarker = when {
            lowAudioQuality -> SessionMarkerType.LOW_AUDIO_QUALITY
            baseline != null &&
                qualityScore >= MIN_MARKER_QUALITY &&
                abs(deviation ?: 0f) >= config.divergenceDb ->
                SessionMarkerType.ACOUSTIC_DIVERGENCE
            else -> null
        }
        val markerDecision = advanceMarker(rawMarker, frame)

        if (isBaselineFrame(lowAudioQuality, rawMarker)) {
            if (baselineSize == config.baselineWindowFrames) {
                baselineSum -= baselineRmsDb[baselineWriteIndex]
            } else {
                baselineSize += 1
            }
            baselineRmsDb[baselineWriteIndex] = rmsDb
            baselineSum += rmsDb
            baselineWriteIndex = (baselineWriteIndex + 1) % config.baselineWindowFrames
        }

        return AcousticAnalysisSnapshot(
            sessionId = frame.sessionId,
            startOffsetMs = frame.startOffsetMs,
            endOffsetMs = (frame.startOffsetMs + frame.durationMs).coerceAtLeast(frame.startOffsetMs),
            rmsDb = rmsDb,
            baselineRmsDb = baseline,
            zeroCrossingRate = zeroCrossingRate,
            clippingRatio = clippingRatio,
            finiteSampleRatio = finiteRatio,
            qualityScore = qualityScore,
            baselineDeviationDb = deviation,
            markerType = markerDecision.activeMarker,
            markerTransition = markerDecision.transition,
            markerEpisodeId = markerDecision.episodeId,
            markerEpisodeStartOffsetMs = markerDecision.episodeStartOffsetMs,
            evidenceConfidence = qualityScore,
            waveform = pcmPeakEnvelope(samples)
        )
    }

    private fun crossesZero(previous: Float, current: Float): Boolean = (previous < 0f && current >= 0f) ||
        (previous >= 0f && current < 0f)

    private fun isLowAudioQuality(samples: FloatArray, finiteRatio: Float, clippingRatio: Float, rmsDb: Float): Boolean =
        samples.isEmpty() ||
            finiteRatio < config.minimumFiniteSampleRatio ||
            clippingRatio > config.maxClippingRatio ||
            rmsDb < config.lowEnergyDb

    private fun isBaselineFrame(lowAudioQuality: Boolean, rawMarker: SessionMarkerType?) = !lowAudioQuality && rawMarker == null

    /**
     * Applies entry persistence, exit hysteresis, cooldown, and deterministic
     * episode identity. A single bad frame never creates a persisted marker.
     */
    private fun advanceMarker(rawMarker: SessionMarkerType?, frame: AcousticAudioFrame): MarkerDecision {
        val frameEnd = (frame.startOffsetMs + frame.durationMs).coerceAtLeast(frame.startOffsetMs)
        val currentActive = activeMarker
        if (currentActive != null) {
            if (rawMarker == currentActive) {
                clearingStartedAtMillis = null
                return MarkerDecision(currentActive, null, activeEpisodeId, activeEpisodeStartedAtMillis)
            }
            val clearStart = clearingStartedAtMillis ?: frame.startOffsetMs.also {
                clearingStartedAtMillis = it
            }
            if (frameEnd - clearStart < config.markerExitPersistenceMillis) {
                return MarkerDecision(currentActive, null, activeEpisodeId, activeEpisodeStartedAtMillis)
            }
            activeMarker = null
            activeEpisodeId = null
            activeEpisodeStartedAtMillis = null
            clearingStartedAtMillis = null
            candidateMarker = null
            candidateStartedAtMillis = null
        }

        if (rawMarker == null) {
            candidateMarker = null
            candidateStartedAtMillis = null
            return MarkerDecision(null, null, null, null)
        }
        if (candidateMarker != rawMarker) {
            candidateMarker = rawMarker
            candidateStartedAtMillis = frame.startOffsetMs
        }
        val candidateStart = candidateStartedAtMillis ?: frame.startOffsetMs
        val persistedLongEnough = frameEnd - candidateStart >= config.markerEnterPersistenceMillis
        val lastEmittedAt = lastEmittedAtByType[rawMarker]
        val cooldownElapsed = lastEmittedAt == null || frameEnd - lastEmittedAt >= config.markerCooldownMillis
        if (!persistedLongEnough || !cooldownElapsed) return MarkerDecision(null, null, null, null)

        val episodeId = "${frame.sessionId}:${rawMarker.name}:$candidateStart"
        activeMarker = rawMarker
        activeEpisodeId = episodeId
        activeEpisodeStartedAtMillis = candidateStart
        lastEmittedAtByType[rawMarker] = frameEnd
        candidateMarker = null
        candidateStartedAtMillis = null
        return MarkerDecision(rawMarker, rawMarker, episodeId, candidateStart)
    }

    private fun qualityScore(finiteRatio: Float, rmsDb: Float, clippingRatio: Float): Float {
        if (finiteRatio <= 0f || rmsDb < config.lowEnergyDb) return 0f
        val clippingPenalty = (1f - clippingRatio / config.maxClippingRatio).coerceIn(0f, 1f)
        return (finiteRatio * clippingPenalty).coerceIn(0f, 1f)
    }

    private companion object {
        const val MIN_RMS = 1.0e-6
        const val CLIP_THRESHOLD = 0.98f
        const val MIN_MARKER_QUALITY = 0.5f
    }

    private data class MarkerDecision(
        val activeMarker: SessionMarkerType?,
        val transition: SessionMarkerType?,
        val episodeId: String?,
        val episodeStartOffsetMs: Long?
    )
}
