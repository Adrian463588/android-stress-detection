package com.stressdetector.mobile.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Quality-derived evidence score for a real PCM transcript segment.
 *
 * This is not calibrated ASR posterior probability. It reports whether the
 * audio presented to the recognizer was finite, energetic, and unclipped.
 */
internal object SpeechSegmentQuality {
    private const val MIN_RMS = 1.0e-6f
    private const val LOW_ENERGY_DB = -55f
    private const val FULL_ENERGY_DB = -20f
    private const val MAX_CLIPPING_RATIO = 0.05f

    fun score(samples: FloatArray, text: String): Float {
        if (samples.isEmpty() || text.isBlank()) return 0f

        var finiteCount = 0
        var squareSum = 0.0
        var clippedCount = 0
        samples.forEach { sample ->
            if (!sample.isFinite()) return@forEach
            finiteCount += 1
            squareSum += sample.toDouble() * sample.toDouble()
            if (kotlin.math.abs(sample) >= 0.98f) clippedCount += 1
        }
        if (finiteCount == 0) return 0f

        val finiteRatio = finiteCount.toFloat() / samples.size
        val rms = sqrt(squareSum / finiteCount).toFloat()
        val rmsDb = (20f * log10(rms.coerceAtLeast(MIN_RMS))).coerceIn(LOW_ENERGY_DB, 0f)
        val energyQuality = ((rmsDb - LOW_ENERGY_DB) / (FULL_ENERGY_DB - LOW_ENERGY_DB))
            .coerceIn(0f, 1f)
        val clippingRatio = clippedCount.toFloat() / finiteCount
        val clippingQuality = (1f - clippingRatio / MAX_CLIPPING_RATIO).coerceIn(0f, 1f)
        return (finiteRatio * energyQuality * clippingQuality).coerceIn(0f, 1f)
    }
}
