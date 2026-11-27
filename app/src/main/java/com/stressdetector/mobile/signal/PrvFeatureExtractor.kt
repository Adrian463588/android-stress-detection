@file:Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "MagicNumber")

package com.stressdetector.mobile.signal

import com.stressdetector.mobile.domain.engine.PrvMetrics
import com.stressdetector.shared.PpgType
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Extracts quality-gated Pulse Rate Variability (PRV) evidence from peak intervals.
 * Refuses to emit fabricated zero metrics when the beat series fails plausibility gates.
 */
class PrvFeatureExtractor(
    private val minValidIbiRatio: Float = DEFAULT_MIN_VALID_IBI_RATIO,
    private val minIntervalsShortWindow: Int = DEFAULT_MIN_INTERVALS_SHORT,
    private val minIntervalsCanonicalWindow: Int = DEFAULT_MIN_INTERVALS_CANONICAL
) {
    fun extract(
        channel: PpgType,
        values: DoubleArray,
        peaks: IntArray,
        samplingRateHz: Double,
        windowSeconds: Int,
        isSqaValid: Boolean
    ): PrvMetrics {
        if (!isSqaValid || windowSeconds < MIN_PRV_WINDOW_SECONDS || samplingRateHz <= 0.0) {
            val reason = if (!isSqaValid) "sqa_failed" else "window_too_short"
            return PrvMetrics(channel = channel, windowSeconds = windowSeconds, isQualityPassed = false, insufficiencyReason = reason)
        }
        val candidateCount = (peaks.size - 1).coerceAtLeast(0)
        val validIntervals = mutableListOf<Double>()
        val successivePairs = mutableListOf<Double>()
        var previousValidPeakIndex: Int? = null

        for (index in 0 until peaks.lastIndex) {
            val intervalMs = (peaks[index + 1] - peaks[index]) * 1_000.0 / samplingRateHz
            if (intervalMs in MIN_PLAUSIBLE_PPI_MS..MAX_PLAUSIBLE_PPI_MS) {
                if (previousValidPeakIndex == index && validIntervals.isNotEmpty()) {
                    successivePairs.add(abs(intervalMs - validIntervals.last()))
                }
                validIntervals.add(intervalMs)
                previousValidPeakIndex = index + 1
            }
        }

        val ratio = if (candidateCount > 0) validIntervals.size.toFloat() / candidateCount else 0f
        val requiredIntervals = if (windowSeconds >= CANONICAL_WINDOW_SECONDS) minIntervalsCanonicalWindow else minIntervalsShortWindow
        val insufficient = validIntervals.size < requiredIntervals || ratio < minValidIbiRatio || successivePairs.isEmpty()
        return if (insufficient) {
            PrvMetrics(
                channel = channel,
                validIbiCount = validIntervals.size,
                candidateIbiCount = candidateCount,
                validIbiRatio = ratio,
                windowSeconds = windowSeconds,
                isQualityPassed = false,
                insufficiencyReason = "insufficient_valid_ppi"
            )
        } else {
            val sorted = validIntervals.sorted()
            val meanPpi = validIntervals.average()
            val medianPpi = if (sorted.size % 2 == 0) {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            } else {
                sorted[sorted.size / 2]
            }
            val variance = validIntervals.sumOf { (it - meanPpi) * (it - meanPpi) } / validIntervals.size
            val sdnn = sqrt(variance)
            val rmssd = sqrt(successivePairs.sumOf { it * it } / successivePairs.size)
            val pnn50 = successivePairs.count { it > PNN50_THRESHOLD_MS }.toFloat() * 100f / successivePairs.size
            val ampMedian = computeTroughToPeakMedian(values, peaks, samplingRateHz)

            PrvMetrics(
                channel = channel,
                rmssdMs = rmssd.toFloat(),
                sdnnMs = sdnn.toFloat(),
                pnn50Pct = pnn50,
                meanPpiMs = meanPpi.toFloat(),
                medianPpiMs = medianPpi.toFloat(),
                pulseAmplitudeMedian = ampMedian,
                validIbiCount = validIntervals.size,
                candidateIbiCount = candidateCount,
                validIbiRatio = ratio,
                windowSeconds = windowSeconds,
                isQualityPassed = true
            )
        }
    }

    private fun computeTroughToPeakMedian(values: DoubleArray, peaks: IntArray, samplingRateHz: Double): Float? {
        if (values.isEmpty() || peaks.isEmpty()) return null
        val lookbackSamples = (samplingRateHz * TROUGH_LOOKBACK_SECONDS).toInt().coerceAtLeast(2)
        val amplitudes = peaks.asList().mapNotNull { peakIndex ->
            if (peakIndex !in values.indices || !values[peakIndex].isFinite()) return@mapNotNull null
            val start = (peakIndex - lookbackSamples).coerceAtLeast(0)
            var trough = values[peakIndex]
            for (idx in start until peakIndex) {
                val sample = values[idx]
                if (sample.isFinite() && sample < trough) trough = sample
            }
            (values[peakIndex] - trough).takeIf { it.isFinite() && it > 0.0 }
        }.sorted()
        return if (amplitudes.isEmpty()) {
            null
        } else if (amplitudes.size % 2 == 0) {
            ((amplitudes[amplitudes.size / 2 - 1] + amplitudes[amplitudes.size / 2]) / 2.0).toFloat()
        } else {
            amplitudes[amplitudes.size / 2].toFloat()
        }
    }

    companion object {
        const val MIN_PRV_WINDOW_SECONDS = 10
        const val CANONICAL_WINDOW_SECONDS = 30
        const val DEFAULT_MIN_VALID_IBI_RATIO = 0.70f
        const val DEFAULT_MIN_INTERVALS_SHORT = 5
        const val DEFAULT_MIN_INTERVALS_CANONICAL = 10
        const val MIN_PLAUSIBLE_PPI_MS = 300.0
        const val MAX_PLAUSIBLE_PPI_MS = 1500.0
        const val PNN50_THRESHOLD_MS = 50.0
        private const val TROUGH_LOOKBACK_SECONDS = 0.40
    }
}
