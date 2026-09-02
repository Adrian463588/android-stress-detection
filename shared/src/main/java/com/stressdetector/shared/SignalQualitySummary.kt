package com.stressdetector.shared

data class SignalQualitySummary(
    val isUsable: Boolean,
    val tier: String,
    val reason: String,
    val sampleCount: Int,
    val missingRatio: Double,
    val flatlineRatio: Double,
    val timestampGapCount: Int,
    val clippingRatio: Double = 0.0,
    val samplingJitter: Double = 0.0,
    val artifactRatio: Double = 0.0,
    val validPeakRatio: Double = 0.0,
    val observedRateHz: Double = 0.0
) {
    /** Canonical reliability score shared by live and persisted predictions. */
    val qualityScore: Float
        get() = (
            1.0 - flatlineRatio - missingRatio - clippingRatio - samplingJitter
            ).toFloat().coerceIn(0f, 1f)
}
