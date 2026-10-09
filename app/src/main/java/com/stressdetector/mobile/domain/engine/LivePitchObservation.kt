package com.stressdetector.mobile.domain.engine

enum class LivePitchStatus { AVAILABLE, UNVOICED, QUALITY_REJECTED, NATIVE_UNAVAILABLE, PROCESSING_GAP }

/** One-second observed voiced F0 mean; never a speaker identity or stress score. */
data class LivePitchObservation(
    val sessionId: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val frequencyHz: Double?,
    val status: LivePitchStatus,
    val voicedFrameCount: Int = 0,
    val frameCount: Int = 0,
    val reason: String? = null,
    val version: String = "opensmile-3.0.2-egemaps-v02-live-f0-geomean-1s-v1"
) {
    init {
        require(sessionId.isNotBlank() && startOffsetMs >= 0 && endOffsetMs >= startOffsetMs)
        require(frameCount >= 0 && voicedFrameCount in 0..frameCount)
        require(frequencyHz == null || frequencyHz.isFinite() && frequencyHz > 0)
        require((status == LivePitchStatus.AVAILABLE) == (frequencyHz != null))
    }
}

/** Pure bounded projection; null observations break the curve rather than filling it. */
fun appendLivePitch(
    history: List<LivePitchObservation>,
    observation: LivePitchObservation,
    capacity: Int = 60
): List<LivePitchObservation> {
    require(capacity > 0)
    return (history.filter { it.sessionId == observation.sessionId && it.startOffsetMs != observation.startOffsetMs } + observation)
        .distinctBy { it.startOffsetMs }
        .sortedBy { it.startOffsetMs }.takeLast(capacity)
}
