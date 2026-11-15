package com.stressdetector.shared

import kotlin.math.abs

/** Filter at recording cadence; extract features at the observed endpoint cadence without resampling. */
object PpgSamplingPolicy {
    const val RECORDING_OBSERVED_V1 = "recording_filter_observed_window_v1"
    const val MANIFEST_FIXED_V1 = "manifest_fixed_v1"
    const val MAX_RATE_DEVIATION_RATIO = 0.20
    private const val MILLIS_PER_SECOND = 1_000.0

    fun observedRateHz(timestamps: LongArray): Double {
        require(timestamps.size >= 2 && (1 until timestamps.size).all { timestamps[it] > timestamps[it - 1] }) {
            "invalid_observed_ppg_timestamps"
        }
        return (timestamps.size - 1) * MILLIS_PER_SECOND / (timestamps.last() - timestamps.first())
    }

    fun isCompatible(observed: Double, expected: Double): Boolean = observed.isFinite() &&
        expected.isFinite() &&
        observed > 0.0 &&
        expected > 0.0 &&
        abs(observed - expected) / expected <= MAX_RATE_DEVIATION_RATIO
}
