package com.stressdetector.mobile.domain.engine

/** Observed native features. An unavailable result is not silence or zero stress. */
data class VerbatimProsodyEvidence(
    val features: Map<String, Double> = emptyMap(),
    val frameCount: Int = 0,
    val voicedFrameCount: Int = 0,
    val unavailableReason: String? = null,
    val version: String = "opensmile-3.0.2-egemaps-v02-lld-means-v1"
) {
    val isAvailable: Boolean
        get() = unavailableReason == null && frameCount > 0 && voicedFrameCount > 0

    val pitchSemitones: Double?
        get() = feature("F0semitoneFrom27.5Hz")

    /** Geometric mean of observed voiced F0, not an instantaneous pitch sample. */
    val pitchHz: Double?
        get() = pitchSemitones?.takeIf { isAvailable }?.let { semitones ->
            (F0_REFERENCE_HZ * Math.pow(2.0, semitones / SEMITONES_PER_OCTAVE))
                .takeIf { it.isFinite() && it > 0.0 }
        }
    val loudness: Double?
        get() = feature("loudness")
    val jitter: Double?
        get() = feature("jitterLocal")
    val shimmer: Double?
        get() = feature("shimmerLocaldB")

    private fun feature(prefix: String): Double? = features.entries
        .firstOrNull { it.key.startsWith(prefix, ignoreCase = true) }?.value?.takeIf(Double::isFinite)

    private companion object {
        const val F0_REFERENCE_HZ = 27.5
        const val SEMITONES_PER_OCTAVE = 12.0
    }
}

data class VerbatimProsodyMarker(
    val segmentId: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val pitchSemitones: Double,
    val baselineSemitones: Double,
    val evidence: VerbatimProsodyEvidence
)

/** Bounded, speaker-specific baseline. No cross-speaker or text/PPG fusion. */
class VerbatimProsodyMarkerDetector(
    private val warmupUtterances: Int = 5,
    private val baselineCapacity: Int = 20,
    private val enterSemitones: Double = 4.0,
    private val exitSemitones: Double = 2.0,
    private val persistenceMillis: Long = 1_000,
    private val cooldownMillis: Long = 10_000
) {
    private data class Baseline(val pitches: ArrayDeque<Double> = ArrayDeque(), var active: Boolean = false, var lastMarker: Long? = null)
    private val baselines = mutableMapOf<String, Baseline>()
    private var sessionId: String? = null

    init {
        require(warmupUtterances > 0 && baselineCapacity >= warmupUtterances)
        require(enterSemitones > exitSemitones && exitSemitones > 0)
        require(persistenceMillis > 0 && cooldownMillis >= 0)
    }

    fun reset() {
        baselines.clear()
        sessionId = null
    }

    fun analyze(segment: SpeechSegment): VerbatimProsodyMarker? {
        if (sessionId != segment.sessionId) {
            reset()
            sessionId = segment.sessionId
        }
        val evidence = segment.prosody?.takeIf { it.isAvailable && isEligible(segment, it) }
        return evidence?.pitchSemitones?.let { pitch -> advance(segment, evidence, pitch) }
    }

    private fun isEligible(segment: SpeechSegment, evidence: VerbatimProsodyEvidence): Boolean {
        val identityReady = segment.isFinal && segment.speakerId in SPEAKERS
        val qualityReady = segment.confidence >= MIN_AUDIO_QUALITY &&
            evidence.voicedFrameCount.toDouble() / evidence.frameCount >= MIN_VOICED_RATIO
        return identityReady && qualityReady && segment.recognitionReviewReason == null
    }

    private fun advance(segment: SpeechSegment, evidence: VerbatimProsodyEvidence, pitch: Double): VerbatimProsodyMarker? {
        val state = baselines.getOrPut(segment.speakerId) { Baseline() }
        val baseline = state.pitches.sorted().let { if (it.size >= warmupUtterances) it[it.size / 2] else null }
        val deviation = baseline?.let { kotlin.math.abs(pitch - it) }
        if (deviation == null || deviation < enterSemitones) {
            if (deviation == null || deviation < exitSemitones) state.active = false
            if (!state.active) updateBaseline(state, pitch)
        }
        val rising = deviation != null && deviation >= enterSemitones
        return if (baseline != null && rising && canEmit(state, segment, evidence)) {
            state.active = true
            state.lastMarker = segment.startOffsetMs
            VerbatimProsodyMarker(segment.id, segment.startOffsetMs, segment.endOffsetMs, pitch, baseline, evidence)
        } else {
            null
        }
    }

    private fun updateBaseline(state: Baseline, pitch: Double) {
        if (state.pitches.size == baselineCapacity) state.pitches.removeFirst()
        state.pitches.addLast(pitch)
    }

    private fun canEmit(state: Baseline, segment: SpeechSegment, evidence: VerbatimProsodyEvidence): Boolean {
        val durationReady = segment.endOffsetMs - segment.startOffsetMs >= persistenceMillis &&
            evidence.voicedFrameCount * LLD_FRAME_STEP_MS >= persistenceMillis
        val cooldownReady = state.lastMarker?.let { segment.startOffsetMs - it >= cooldownMillis } ?: true
        return !state.active && durationReady && cooldownReady
    }

    companion object {
        private const val MIN_VOICED_RATIO = 0.2
        private const val MIN_AUDIO_QUALITY = 0.6f
        private const val LLD_FRAME_STEP_MS = 10L
        private val SPEAKERS = setOf("Speaker_0", "Speaker_1")
    }
}
