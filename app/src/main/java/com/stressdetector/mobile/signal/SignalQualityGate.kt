package com.stressdetector.mobile.signal

import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.shared.SignalQualitySummary
import kotlin.math.abs

class SignalQualityGate(private val config: LiveMonitorConfig) {

    fun evaluate(window: WindowSnapshot, requiredSamples: Int): SignalQualitySummary = evaluate(
        type = window.type,
        timestampsMillis = window.timestampsMillis,
        values = DoubleArray(window.rawValues.size) { window.rawValues[it].toDouble() },
        requiredSamples = requiredSamples,
        expectedRateHz = window.type.sourceRateHz.toDouble()
    )

    fun evaluate(window: PreparedPpgWindow): SignalQualitySummary = evaluate(
        type = window.type,
        timestampsMillis = window.timestampsMillis,
        values = window.filteredValues,
        requiredSamples = window.size,
        expectedRateHz = window.modelRateHz,
        providedMissingRatio = window.sourceGapInterpolatedSampleCount.toDouble() /
            window.size.coerceAtLeast(1)
    )

    private fun evaluate(
        type: com.stressdetector.shared.PpgType,
        timestampsMillis: LongArray,
        values: DoubleArray,
        requiredSamples: Int,
        expectedRateHz: Double,
        providedMissingRatio: Double = 0.0
    ): SignalQualitySummary {
        if (values.size < requiredSamples) {
            return SignalQualitySummary(
                isUsable = false,
                tier = "insufficient",
                reason = "Need 3 min rolling window",
                sampleCount = values.size,
                missingRatio = 0.0,
                flatlineRatio = 0.0,
                timestampGapCount = 0
            )
        }
        if (values.isEmpty() || values.any { !it.isFinite() }) {
            return SignalQualitySummary(
                isUsable = false,
                tier = "invalid",
                reason = "No samples",
                sampleCount = 0,
                missingRatio = 1.0,
                flatlineRatio = 1.0,
                timestampGapCount = 0
            )
        }

        var flatlines = 0
        for (i in 1 until values.size) {
            if (values[i] == values[i - 1]) flatlines++
        }
        val flatlineRatio = flatlines.toDouble() / (values.size - 1).coerceAtLeast(1)

        val amplitudeRange = (values.maxOrNull() ?: 0.0) - (values.minOrNull() ?: 0.0)
        if (amplitudeRange <= 0.0) {
            return SignalQualitySummary(
                isUsable = false,
                tier = "invalid",
                reason = "Zero amplitude — signal is flat",
                sampleCount = values.size,
                missingRatio = 0.0,
                flatlineRatio = 1.0,
                timestampGapCount = 0
            )
        }

        var gapCount = 0
        val expectedIntervalMs = 1000.0 / expectedRateHz
        val intervals = if (timestampsMillis.size > 1) {
            DoubleArray(timestampsMillis.size - 1) {
                (timestampsMillis[it + 1] - timestampsMillis[it]).toDouble()
            }
        } else {
            DoubleArray(0)
        }
        for (i in 1 until timestampsMillis.size) {
            val gap = timestampsMillis[i] - timestampsMillis[i - 1]
            if (gap <= 0L || abs(gap - expectedIntervalMs) > config.maxTimestampGapDeltaMs) gapCount++
        }

        val observedRateHz = if (timestampsMillis.size > 1 && timestampsMillis.last() > timestampsMillis.first()) {
            (timestampsMillis.size - 1) * 1_000.0 /
                (timestampsMillis.last() - timestampsMillis.first()).toDouble()
        } else {
            0.0
        }
        val meanInterval = intervals.average().takeIf { it > 0.0 } ?: expectedIntervalMs
        val samplingJitter = intervals
            .filter { it > 0.0 }
            .map { abs(it - meanInterval) }
            .average()
            .let { (it / expectedIntervalMs).coerceIn(0.0, 1.0) }
        val expectedCount = if (timestampsMillis.size > 1) {
            (((timestampsMillis.last() - timestampsMillis.first()) / expectedIntervalMs) + 1.0)
                .coerceAtLeast(values.size.toDouble())
        } else {
            values.size.toDouble()
        }
        val missingRatio = maxOf(
            providedMissingRatio.coerceIn(0.0, 1.0),
            ((expectedCount - values.size) / expectedCount).coerceIn(0.0, 1.0)
        )
        val percentile999 = percentile(values, 0.999)
        val clippingRatio = values.count { it >= percentile999 }.toDouble() / values.size
        val mean = values.average()
        val standardDeviation = sampleStd(values)
        val artifactRatio = if (standardDeviation > 0.0) {
            values.count { abs(it - mean) > 3.0 * standardDeviation }.toDouble() / values.size
        } else {
            0.0
        }

        val durationSeconds = requiredSamples / expectedRateHz
        val maxGapCount = durationSeconds * config.maxTimestampGapCountPerSecond
        val qualityScore = (1.0 - flatlineRatio).coerceIn(0.0, 1.0)

        val rejectionReasons = mutableListOf<String>()
        if (flatlineRatio >= config.maxFlatlineRatioUsable) {
            rejectionReasons.add("flatline ${String.format("%.2f", flatlineRatio)} >= ${config.maxFlatlineRatioUsable}")
        }
        if (gapCount > maxGapCount) {
            rejectionReasons.add("gaps $gapCount > max $maxGapCount")
        }
        if (missingRatio > config.maxMissingRatioUsable) {
            rejectionReasons.add("missingRatio ${String.format("%.3f", missingRatio)} > ${config.maxMissingRatioUsable}")
        }
        if (clippingRatio > config.maxClippingRatioUsable) {
            rejectionReasons.add("clippingRatio ${String.format("%.3f", clippingRatio)} > ${config.maxClippingRatioUsable}")
        }
        if (artifactRatio > config.maxArtifactRatioUsable) {
            rejectionReasons.add("artifactRatio ${String.format("%.3f", artifactRatio)} > ${config.maxArtifactRatioUsable}")
        }
        if (samplingJitter > config.maxSamplingJitterUsable) {
            rejectionReasons.add("samplingJitter ${String.format("%.3f", samplingJitter)} > ${config.maxSamplingJitterUsable}")
        }
        if (qualityScore < config.minimumQualityScore) {
            rejectionReasons.add("qualityScore ${String.format("%.2f", qualityScore)} < ${config.minimumQualityScore}")
        }

        val usable = rejectionReasons.isEmpty()
        val tier = when {
            !usable -> "poor"
            flatlineRatio < config.maxFlatlineRatioExcellent && gapCount == 0 -> "excellent"
            else -> "good"
        }
        val reason = if (usable) "usable" else rejectionReasons.joinToString("; ")

        return SignalQualitySummary(
            isUsable = usable,
            tier = tier,
            reason = reason,
            sampleCount = values.size,
            missingRatio = missingRatio,
            flatlineRatio = flatlineRatio,
            timestampGapCount = gapCount,
            clippingRatio = clippingRatio,
            samplingJitter = samplingJitter,
            artifactRatio = artifactRatio,
            observedRateHz = observedRateHz
        )
    }

    private fun percentile(values: DoubleArray, quantile: Double): Double {
        val sorted = values.sortedArray()
        val position = quantile * (sorted.size - 1)
        val lower = position.toInt()
        val upper = minOf(lower + 1, sorted.lastIndex)
        val weight = position - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    private fun sampleStd(values: DoubleArray): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }
}
