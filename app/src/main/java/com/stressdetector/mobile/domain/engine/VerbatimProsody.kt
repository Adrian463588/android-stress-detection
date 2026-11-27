@file:Suppress("ReturnCount", "CyclomaticComplexMethod", "ComplexCondition", "MagicNumber")

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
    val evidence: VerbatimProsodyEvidence,
    val speakerId: String = "",
    val primaryFeature: String = "f0Semitones",
    val summaryLabel: String = "Perubahan pitch suara",
    val zScore: Float? = null,
    val unit: String = "st"
)

/** Bounded, speaker-specific baseline with MAD z-score support. No cross-speaker or text/PPG fusion. */
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
    private val speakerBaselineTracker = SpeakerBaselineTracker(
        baselineCapacity = baselineCapacity,
        warmupUtterances = warmupUtterances,
        minDurationMs = persistenceMillis,
        cooldownMs = cooldownMillis
    )
    private var sessionId: String? = null

    init {
        require(warmupUtterances > 0 && baselineCapacity >= warmupUtterances)
        require(enterSemitones > exitSemitones && exitSemitones > 0)
        require(persistenceMillis > 0 && cooldownMillis >= 0)
    }

    fun reset() {
        baselines.clear()
        speakerBaselineTracker.reset()
        sessionId = null
    }

    fun relabelSpeaker(oldSpeakerId: String, newSpeakerId: String) {
        speakerBaselineTracker.relabelSpeaker(oldSpeakerId, newSpeakerId)
        val oldKey = SpeakerBaselineTracker.canonicalSpeakerKey(oldSpeakerId) ?: return
        val newKey = SpeakerBaselineTracker.canonicalSpeakerKey(newSpeakerId) ?: return
        if (oldKey == newKey) return
        val existing = baselines.remove(oldKey) ?: return
        val target = baselines.getOrPut(newKey) { Baseline() }
        existing.pitches.forEach { updateBaseline(target, it) }
    }

    fun analyze(segment: SpeechSegment): VerbatimProsodyMarker? {
        if (sessionId != segment.sessionId) {
            reset()
            sessionId = segment.sessionId
        }
        val evidence = segment.prosody?.takeIf { it.isAvailable && isEligible(segment, it) } ?: return null
        val durationSeconds = ((segment.endOffsetMs - segment.startOffsetMs).coerceAtLeast(1L)) / 1_000.0
        val wordCount = segment.rawText.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        val voicedRatio = if (evidence.frameCount > 0) {
            evidence.voicedFrameCount.toDouble() / evidence.frameCount
        } else {
            0.0
        }
        val zMarker = speakerBaselineTracker.observe(
            SpeakerAcousticObservation(
                segmentId = segment.id,
                sessionId = segment.sessionId,
                speakerId = segment.speakerId,
                startOffsetMs = segment.startOffsetMs,
                endOffsetMs = segment.endOffsetMs,
                f0Semitones = evidence.pitchSemitones,
                loudnessDb = evidence.loudness,
                speakingRateWps = if (durationSeconds > 0.0) wordCount / durationSeconds else null,
                pauseRatio = (1.0 - voicedRatio).coerceIn(0.0, 1.0),
                voicedRatio = voicedRatio,
                audioQuality = segment.confidence,
                isOverlap = false,
                isFinal = segment.isFinal
            )
        )
        return evidence.pitchSemitones?.let { pitch -> advance(segment, evidence, pitch, zMarker) }
    }

    private fun isEligible(segment: SpeechSegment, evidence: VerbatimProsodyEvidence): Boolean {
        val identityReady = segment.isFinal && SpeakerBaselineTracker.isAttributedSpeaker(segment.speakerId)
        val qualityReady = segment.confidence >= MIN_AUDIO_QUALITY &&
            evidence.voicedFrameCount.toDouble() / evidence.frameCount >= MIN_VOICED_RATIO
        return identityReady && qualityReady && segment.recognitionReviewReason == null
    }

    private fun advance(
        segment: SpeechSegment,
        evidence: VerbatimProsodyEvidence,
        pitch: Double,
        zMarker: SpeakerBaselineDivergence?
    ): VerbatimProsodyMarker? {
        val key = SpeakerBaselineTracker.canonicalSpeakerKey(segment.speakerId) ?: return null
        val state = baselines.getOrPut(key) { Baseline() }
        val baseline = state.pitches.sorted().let { if (it.size >= warmupUtterances) it[it.size / 2] else null }
        val deviation = baseline?.let { kotlin.math.abs(pitch - it) }
        val pitchTriggered = deviation != null && deviation >= enterSemitones
        if (deviation == null || deviation < enterSemitones) {
            if (deviation == null || deviation < exitSemitones) state.active = false
            if (!state.active) updateBaseline(state, pitch)
        }
        val zTriggered = zMarker != null
        return if (baseline != null && (pitchTriggered || zTriggered) && canEmit(state, segment, evidence)) {
            state.active = true
            state.lastMarker = segment.startOffsetMs
            VerbatimProsodyMarker(
                segmentId = segment.id,
                startOffsetMs = segment.startOffsetMs,
                endOffsetMs = segment.endOffsetMs,
                pitchSemitones = pitch,
                baselineSemitones = baseline,
                evidence = evidence,
                speakerId = segment.speakerId,
                primaryFeature = zMarker?.primaryFeature ?: "f0Semitones",
                summaryLabel = zMarker?.summaryLabel ?: if (pitch >= baseline) "Pitch meningkat" else "Pitch menurun",
                zScore = zMarker?.zScore,
                unit = zMarker?.unit ?: "st"
            )
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
    }
}
