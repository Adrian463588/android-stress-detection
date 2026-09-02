package com.stressdetector.inference

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Canonical PPG feature extraction shared by the Android inference boundary.
 *
 * The implementation mirrors `PipelinePython/common/features_v3.py`. The manifest owns
 * the final feature order; this object owns the feature names and formulas.
 * Keeping the extractor in :inference prevents Home and Live Monitor from
 * silently diverging again.
 */
data class CanonicalFeatureVector(val values: Map<String, Float>)

object V3PpgFeatureExtractor {
    /** Canonical ONNX contract: full 180-second PPG input window. */
    private const val MIN_WINDOW_SECONDS = 180.0
    private const val MIN_PHYSIOLOGICAL_IBI = 300.0
    private const val MAX_PHYSIOLOGICAL_IBI = 1500.0

    fun extract(
        values: DoubleArray,
        samplingRateHz: Double,
        timestampsMillis: LongArray? = null,
        missingRatio: Double = 0.0,
        samplingJitter: Double? = null
    ): CanonicalFeatureVector {
        require(values.size >= (MIN_WINDOW_SECONDS * samplingRateHz).toInt()) {
            "insufficient_window_samples"
        }
        require(samplingRateHz.isFinite() && samplingRateHz > 0.0) {
            "invalid_sampling_rate"
        }
        require(values.all(Double::isFinite)) { "non_finite_signal" }

        val (peaks, troughs) = robustPeaks(values, samplingRateHz)
        require(peaks.size >= 15) { "insufficient_peaks:${peaks.size}" }

        val allIbi =
            DoubleArray(peaks.size - 1) { index ->
                (peaks[index + 1] - peaks[index]) * 1000.0 / samplingRateHz
            }
        val physiological =
            BooleanArray(allIbi.size) {
                allIbi[it] in MIN_PHYSIOLOGICAL_IBI..MAX_PHYSIOLOGICAL_IBI
            }
        val ibi = allIbi.filterIndexed { index, _ -> physiological[index] }.toDoubleArray()
        require(ibi.size >= 12) { "insufficient_physiological_ibi:${ibi.size}" }

        val legacyIbiDiff = DoubleArray(ibi.size - 1) { index -> ibi[index + 1] - ibi[index] }
        val observedIbiDiff =
            (0 until allIbi.lastIndex)
                .filter { physiological[it] && physiological[it + 1] }
                .map { allIbi[it + 1] - allIbi[it] }
                .toDoubleArray()
        require(observedIbiDiff.size >= 3) { "insufficient_adjacent_physiological_ibi" }
        val heartRate = ibi.map { 60_000.0 / it }.toDoubleArray()
        val mean = values.average()
        val centered = DoubleArray(values.size) { index -> values[index] - mean }
        val gradient = DoubleArray(values.size - 1) { index -> values[index + 1] - values[index] }
        val peakAmplitudes = DoubleArray(peaks.size) { index -> values[peaks[index]] }
        val morphology = morphologyFeatures(values, peaks, troughs, samplingRateHz, gradient)
        val robustMorphology = robustMorphologyFeatures(values, peaks, troughs, samplingRateHz)
        val frequency = frequencyFeatures(ibi)
        val flatlineRatio =
            gradient.count { abs(it) < 1.0e-6 }.toDouble() /
                gradient.size.coerceAtLeast(1)
        val standardDeviation = sampleStd(values)
        val outlierRatio =
            values
                .count { abs(it - mean) > 3.0 * standardDeviation }
                .toDouble() / values.size
        val timestampMetrics =
            timestampMetrics(
                timestampsMillis = timestampsMillis,
                valuesSize = values.size,
                samplingRateHz = samplingRateHz
            )

        val features =
            linkedMapOf(
                "heart_rate" to heartRate.average(),
                "ibi_mean" to ibi.average(),
                "ibi_std" to sampleStd(ibi),
                "ibi_min" to ibi.minOrNull().orZero(),
                "ibi_max" to ibi.maxOrNull().orZero(),
                "rmssd" to sqrt(legacyIbiDiff.map { it * it }.average()),
                "sdnn" to sampleStd(ibi),
                "pnn50" to legacyIbiDiff.count { abs(it) > 50.0 }.toDouble() / legacyIbiDiff.size,
                "pulse_amplitude_mean" to peakAmplitudes.average(),
                "pulse_amplitude_std" to sampleStd(peakAmplitudes),
                "pulse_width" to morphology.pulseWidth,
                "rise_time" to morphology.riseTime,
                "decay_time" to morphology.decayTime,
                "area_under_pulse" to morphology.areaUnderPulse,
                "slope_features" to morphology.slope,
                "signal_quality_index" to 1.0 - flatlineRatio - outlierRatio,
                "clipping_ratio" to
                    {
                        val clippingThreshold = percentile(values, 0.999)
                        values.count { it >= clippingThreshold }.toDouble() / values.size
                    }(),
                "flatline_ratio" to flatlineRatio,
                "missing_ratio" to missingRatio.coerceIn(0.0, 1.0),
                "sampling_jitter" to (samplingJitter ?: timestampMetrics.jitter),
                "artifact_ratio" to outlierRatio,
                "valid_peak_ratio" to ibi.size.toDouble() / peaks.size,
                "mean" to mean,
                "std" to standardDeviation,
                "median" to percentile(values, 0.5),
                "iqr" to percentile(values, 0.75) - percentile(values, 0.25),
                "skewness" to unbiasedSkew(values),
                "kurtosis" to unbiasedFisherKurtosis(values),
                "rolling_min" to rollingMean(values, samplingRateHz.toInt(), minimum = true),
                "rolling_max" to rollingMean(values, samplingRateHz.toInt(), minimum = false),
                "lf_power" to frequency.lf,
                "hf_power" to frequency.hf,
                "lf_hf_ratio" to frequency.lfHfRatio,
                "sampling_rate_hz" to samplingRateHz,
                "median_hr_bpm" to percentile(heartRate, 0.5),
                "ppi_median_ms" to percentile(ibi, 0.5),
                "ppi_iqr_ms" to (percentile(ibi, 0.75) - percentile(ibi, 0.25)),
                "ppi_cv" to (sampleStd(ibi) / percentile(ibi, 0.5)),
                "rmssd_ms" to sqrt(observedIbiDiff.map { it * it }.average()),
                "sdnn_ms" to sampleStd(ibi),
                "pnn50_observed_adjacent" to observedIbiDiff.count { abs(it) > 50.0 }.toDouble() /
                    observedIbiDiff.size,
                "pulse_amplitude_cv" to robustMorphology.pulseAmplitudeCv,
                "normalized_pulse_amplitude_median" to robustMorphology.normalizedAmplitudeMedian,
                "pulse_width_half_height_median_ms" to robustMorphology.halfHeightWidthMedianMs,
                "rise_time_median_ms" to robustMorphology.riseMedianMs,
                "decay_time_median_ms" to robustMorphology.decayMedianMs,
                "normalized_pulse_area_median" to robustMorphology.normalizedAreaMedian,
                "normalized_rise_slope_median" to robustMorphology.normalizedRiseSlopeMedian,
                "normalized_decay_slope_median" to robustMorphology.normalizedDecaySlopeMedian
            )

        require(features.values.all(Double::isFinite)) { "non_finite_feature" }
        val safeFeatures = features.mapValues { (_, value) -> value.toFloat() }
        require(safeFeatures.values.all(Float::isFinite)) { "non_finite_feature" }
        return CanonicalFeatureVector(safeFeatures)
    }

    private fun timestampMetrics(timestampsMillis: LongArray?, valuesSize: Int, samplingRateHz: Double): TimestampMetrics {
        if (timestampsMillis == null || timestampsMillis.size != valuesSize || valuesSize < 2) {
            return TimestampMetrics(0.0)
        }
        val expected = 1_000.0 / samplingRateHz
        val intervals =
            DoubleArray(timestampsMillis.size - 1) {
                (timestampsMillis[it + 1] - timestampsMillis[it]).toDouble()
            }
        if (intervals.any { it <= 0.0 }) return TimestampMetrics(1.0)
        val mean = intervals.average()
        val jitter = intervals.map { abs(it - mean) }.average() / expected
        return TimestampMetrics(jitter.coerceIn(0.0, 1.0))
    }

    private data class TimestampMetrics(val jitter: Double)

    private fun robustPeaks(values: DoubleArray, samplingRateHz: Double): Pair<IntArray, IntArray> {
        val centered = DoubleArray(values.size) { index -> values[index] - percentile(values, 0.5) }
        val mad = percentile(centered.map(::abs).toDoubleArray(), 0.5)
        val prominence = maxOf(1.0e-9, 0.8 * mad)
        val distance = maxOf(1, kotlin.math.round(0.30 * samplingRateHz).toInt())
        return findLocalExtrema(centered, distance, prominence) to
            findLocalExtrema(DoubleArray(centered.size) { -centered[it] }, distance, prominence)
    }

    private fun findLocalExtrema(values: DoubleArray, distance: Int, minimumProminence: Double): IntArray {
        val candidates =
            (1 until values.lastIndex).filter { index ->
                values[index] > values[index - 1] && values[index] >= values[index + 1]
            }
        val accepted = mutableListOf<Int>()
        val blocked = BooleanArray(values.size)
        candidates.sortedByDescending { values[it] }.forEach { candidate ->
            if (blocked[candidate]) return@forEach
            accepted += candidate
            val start = (candidate - distance + 1).coerceAtLeast(0)
            val end = (candidate + distance - 1).coerceAtMost(values.lastIndex)
            for (index in start..end) blocked[index] = true
        }
        val prominence = ProminenceLookup(values)
        return accepted.filter { prominence.at(it) >= minimumProminence }.sorted().toIntArray()
    }

    /** Exact equivalent of the previous prominence scan with bounded lookup cost. */
    private class ProminenceLookup(private val values: DoubleArray) {
        private val leftGreater = nearestGreaterLeft()
        private val rightGreater = nearestGreaterRight()
        private val log2 = IntArray(values.size + 1)
        private val sparseMin: Array<DoubleArray>

        init {
            for (length in 2..values.size) log2[length] = log2[length / 2] + 1
            val levels = log2[values.size] + 1
            sparseMin = Array(levels) { DoubleArray(values.size) }
            values.copyInto(sparseMin[0])
            for (level in 1 until levels) {
                val span = 1 shl level
                val half = span / 2
                val limit = values.size - span + 1
                for (start in 0 until limit) {
                    sparseMin[level][start] =
                        minOf(
                            sparseMin[level - 1][start],
                            sparseMin[level - 1][start + half]
                        )
                }
            }
        }

        fun at(peak: Int): Double {
            val leftEnd = peak
            val leftStart = leftGreater[peak] + 1
            val rightStart = peak
            val rightEnd = rightGreater[peak] - 1
            val leftMinimum = rangeMinimum(leftStart, leftEnd)
            val rightMinimum = rangeMinimum(rightStart, rightEnd)
            return values[peak] - maxOf(leftMinimum, rightMinimum)
        }

        private fun rangeMinimum(start: Int, end: Int): Double {
            val length = end - start + 1
            val level = log2[length]
            val span = 1 shl level
            return minOf(
                sparseMin[level][start],
                sparseMin[level][end - span + 1]
            )
        }

        private fun nearestGreaterLeft(): IntArray {
            val result = IntArray(values.size) { -1 }
            val stack = IntArray(values.size)
            var size = 0
            values.indices.forEach { index ->
                while (size > 0 && values[stack[size - 1]] <= values[index]) size -= 1
                if (size > 0) result[index] = stack[size - 1]
                stack[size] = index
                size += 1
            }
            return result
        }

        private fun nearestGreaterRight(): IntArray {
            val result = IntArray(values.size) { values.size }
            val stack = IntArray(values.size)
            var size = 0
            for (index in values.lastIndex downTo 0) {
                while (size > 0 && values[stack[size - 1]] <= values[index]) size -= 1
                if (size > 0) result[index] = stack[size - 1]
                stack[size] = index
                size += 1
            }
            return result
        }
    }

    private fun morphologyFeatures(
        values: DoubleArray,
        peaks: IntArray,
        troughs: IntArray,
        samplingRateHz: Double,
        gradient: DoubleArray
    ): MorphologyFeatures {
        val pulseWidths = mutableListOf<Double>()
        val riseTimes = mutableListOf<Double>()
        val decayTimes = mutableListOf<Double>()
        val areas = mutableListOf<Double>()
        val slopes = mutableListOf<Double>()

        for (index in 0 until peaks.lastIndex) {
            val firstPeak = peaks[index]
            val secondPeak = peaks[index + 1]
            val trough = troughs.firstOrNull { it > firstPeak && it < secondPeak } ?: continue
            decayTimes += (trough - firstPeak) / samplingRateHz
            riseTimes += (secondPeak - trough) / samplingRateHz
            pulseWidths += (secondPeak - firstPeak) / samplingRateHz
            areas += values.sliceArray(firstPeak until secondPeak).sum()
            if (trough > firstPeak) slopes += gradient.sliceArray(firstPeak until trough).minOrNull().orZero()
            if (secondPeak > trough) slopes += gradient.sliceArray(trough until secondPeak).maxOrNull().orZero()
        }

        return MorphologyFeatures(
            pulseWidth = pulseWidths.averageOrZero(),
            riseTime = riseTimes.averageOrZero(),
            decayTime = decayTimes.averageOrZero(),
            areaUnderPulse = areas.averageOrZero(),
            slope = slopes.map(::abs).averageOrZero()
        )
    }

    private fun robustMorphologyFeatures(
        values: DoubleArray,
        peaks: IntArray,
        troughs: IntArray,
        samplingRateHz: Double
    ): RobustMorphologyFeatures {
        val robustScale = percentile(values, 0.75) - percentile(values, 0.25)
        require(robustScale > 1.0e-12) { "insufficient_waveform_robust_scale" }
        val amplitudes = mutableListOf<Double>()
        val widths = mutableListOf<Double>()
        val riseTimes = mutableListOf<Double>()
        val decayTimes = mutableListOf<Double>()
        val areas = mutableListOf<Double>()
        val riseSlopes = mutableListOf<Double>()
        val decaySlopes = mutableListOf<Double>()

        peaks.forEach { peak ->
            val left = troughs.lastOrNull { it < peak } ?: return@forEach
            val right = troughs.firstOrNull { it > peak } ?: return@forEach
            val amplitude = values[peak] - values[left]
            if (left >= peak || peak >= right || amplitude <= 1.0e-12) return@forEach
            val durationSeconds = (right - left) / samplingRateHz
            amplitudes += amplitude
            riseTimes += (peak - left) * 1_000.0 / samplingRateHz
            decayTimes += (right - peak) * 1_000.0 / samplingRateHz

            val halfHeight = values[left] + 0.5 * amplitude
            val leftCrossing = (left..peak).lastOrNull { values[it] <= halfHeight }
            val rightCrossing = (peak..right).firstOrNull { values[it] <= halfHeight }
            if (leftCrossing != null && rightCrossing != null && rightCrossing > leftCrossing) {
                widths += (rightCrossing - leftCrossing) * 1_000.0 / samplingRateHz
            }

            var positiveArea = 0.0
            for (index in left until right) {
                val fraction = (index - left).toDouble() / (right - left)
                val baseline = values[left] + fraction * (values[right] - values[left])
                positiveArea += maxOf(values[index] - baseline, 0.0)
            }
            areas += positiveArea / (samplingRateHz * amplitude * durationSeconds)
            riseSlopes += (left until peak)
                .maxOf { (values[it + 1] - values[it]) * samplingRateHz } / amplitude
            decaySlopes +=
                abs(
                    (peak until right)
                        .minOf { (values[it + 1] - values[it]) * samplingRateHz } / amplitude
                )
        }
        require(
            listOf(amplitudes, widths, riseTimes, decayTimes, areas, riseSlopes, decaySlopes)
                .all { it.size >= 3 }
        ) { "insufficient_pulse_morphology_support" }
        val amplitudeArray = amplitudes.toDoubleArray()
        val amplitudeMedian = percentile(amplitudeArray, 0.5)
        return RobustMorphologyFeatures(
            pulseAmplitudeCv = sampleStd(amplitudeArray) / amplitudeMedian,
            normalizedAmplitudeMedian = amplitudeMedian / robustScale,
            halfHeightWidthMedianMs = percentile(widths.toDoubleArray(), 0.5),
            riseMedianMs = percentile(riseTimes.toDoubleArray(), 0.5),
            decayMedianMs = percentile(decayTimes.toDoubleArray(), 0.5),
            normalizedAreaMedian = percentile(areas.toDoubleArray(), 0.5),
            normalizedRiseSlopeMedian = percentile(riseSlopes.toDoubleArray(), 0.5),
            normalizedDecaySlopeMedian = percentile(decaySlopes.toDoubleArray(), 0.5)
        )
    }

    private fun frequencyFeatures(ibi: DoubleArray): FrequencyFeatures {
        if (ibi.size < 8) return FrequencyFeatures(0.0, 0.0, 0.0, 0.0)
        val beatTime = DoubleArray(ibi.size)
        var cumulative = 0.0
        ibi.indices.forEach { index ->
            cumulative += ibi[index] / 1000.0
            beatTime[index] = cumulative
        }
        val origin = beatTime.first()
        beatTime.indices.forEach { index -> beatTime[index] -= origin }
        if (beatTime.last() <= 2.0) return FrequencyFeatures(0.0, 0.0, 0.0, 0.0)

        val gridSize = ceil(beatTime.last() / 0.25).toInt().coerceAtLeast(1)
        val interpolated =
            DoubleArray(gridSize) { index ->
                interpolate(index * 0.25, beatTime, ibi)
            }
        val spectrum = welch(interpolated, 4.0)
        val lf = trapezoidBand(spectrum.first, spectrum.second, 0.04, 0.15, includeUpper = false)
        val hf = trapezoidBand(spectrum.first, spectrum.second, 0.15, 0.40, includeUpper = true)
        val total = trapezoidBand(spectrum.first, spectrum.second, 0.04, 0.40, includeUpper = true)
        return FrequencyFeatures(lf, hf, if (hf > 0.0) lf / hf else 0.0, total)
    }

    private fun interpolate(x: Double, xp: DoubleArray, fp: DoubleArray): Double {
        if (x <= xp.first()) return fp.first()
        if (x >= xp.last()) return fp.last()
        var low = 0
        var high = xp.lastIndex
        while (high - low > 1) {
            val middle = (low + high) / 2
            if (xp[middle] <= x) low = middle else high = middle
        }
        val weight = (x - xp[low]) / (xp[high] - xp[low])
        return fp[low] * (1.0 - weight) + fp[high] * weight
    }

    private fun welch(values: DoubleArray, samplingRateHz: Double): Pair<DoubleArray, DoubleArray> {
        val segmentSize = minOf(256, values.size)
        val overlap = segmentSize / 2
        val step = (segmentSize - overlap).coerceAtLeast(1)
        val starts =
            if (values.size == segmentSize) {
                listOf(0)
            } else {
                (0..values.size - segmentSize step step).toList()
            }
        val table = WelchTableCache.get(segmentSize)
        val window = table.window
        val scale = 1.0 / (samplingRateHz * table.windowEnergy)
        val frequency = DoubleArray(segmentSize / 2 + 1) { it * samplingRateHz / segmentSize }
        val power = DoubleArray(frequency.size)
        starts.forEach { start ->
            val mean = (start until start + segmentSize).map { values[it] }.average()
            frequency.indices.forEach { k ->
                var real = 0.0
                var imaginary = 0.0
                for (n in 0 until segmentSize) {
                    val sample = (values[start + n] - mean) * window[n]
                    real += sample * table.cosine[k][n]
                    imaginary -= sample * table.sine[k][n]
                }
                var density = (real * real + imaginary * imaginary) * scale
                if (k != 0 && !(segmentSize % 2 == 0 && k == segmentSize / 2)) density *= 2.0
                power[k] += density
            }
        }
        power.indices.forEach { power[it] /= starts.size.toDouble() }
        return frequency to power
    }

    private data class WelchTable(
        val window: DoubleArray,
        val windowEnergy: Double,
        val cosine: Array<DoubleArray>,
        val sine: Array<DoubleArray>
    )

    private object WelchTableCache {
        private val tables = mutableMapOf<Int, WelchTable>()

        @Synchronized
        fun get(segmentSize: Int): WelchTable = tables.getOrPut(segmentSize) {
            val window =
                DoubleArray(segmentSize) { index ->
                    0.5 - 0.5 * cos(2.0 * PI * index / segmentSize)
                }
            val frequencyCount = segmentSize / 2 + 1
            val cosine =
                Array(frequencyCount) { k ->
                    DoubleArray(segmentSize) { n -> 2.0 * PI * k * n / segmentSize }.also { angles ->
                        angles.indices.forEach { n -> angles[n] = cos(angles[n]) }
                    }
                }
            val sine =
                Array(frequencyCount) { k ->
                    DoubleArray(segmentSize) { n -> 2.0 * PI * k * n / segmentSize }.also { angles ->
                        angles.indices.forEach { n -> angles[n] = sin(angles[n]) }
                    }
                }
            WelchTable(window, window.sumOf { it * it }, cosine, sine)
        }
    }

    private fun trapezoidBand(frequency: DoubleArray, power: DoubleArray, lower: Double, upper: Double, includeUpper: Boolean): Double {
        val indices =
            frequency.indices.filter {
                frequency[it] >= lower && (frequency[it] < upper || (includeUpper && frequency[it] <= upper))
            }
        if (indices.size < 2) return 0.0
        return indices.zipWithNext().sumOf { (left, right) ->
            (frequency[right] - frequency[left]) * (power[left] + power[right]) / 2.0
        }
    }

    private fun sampleStd(values: DoubleArray): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean).pow(2) } / (values.size - 1))
    }

    private fun percentile(values: DoubleArray, quantile: Double): Double {
        require(values.isNotEmpty()) { "empty_values" }
        val sorted = values.sortedArray()
        val position = quantile * (sorted.size - 1)
        val lower = position.toInt()
        val upper = minOf(lower + 1, sorted.lastIndex)
        val weight = position - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    private fun rollingMean(values: DoubleArray, window: Int, minimum: Boolean): Double {
        if (window <= 0 || values.size < window) return 0.0
        val deque = IntArray(values.size)
        var head = 0
        var tail = 0
        var total = 0.0
        values.indices.forEach { index ->
            while (head < tail && deque[head] <= index - window) head += 1
            while (head < tail) {
                val previous = values[deque[tail - 1]]
                val current = values[index]
                val dominated = if (minimum) previous >= current else previous <= current
                if (!dominated) break
                tail -= 1
            }
            deque[tail] = index
            tail += 1
            if (index >= window - 1) total += values[deque[head]]
        }
        return total / (values.size - window + 1)
    }

    private fun unbiasedSkew(values: DoubleArray): Double {
        if (values.size < 3) return 0.0
        val n = values.size.toDouble()
        val mean = values.average()
        val m2 = values.sumOf { (it - mean).pow(2) } / n
        if (m2 <= 0.0) return 0.0
        val m3 = values.sumOf { (it - mean).pow(3) } / n
        return sqrt(n * (n - 1.0)) / (n - 2.0) * (m3 / m2.pow(1.5))
    }

    private fun unbiasedFisherKurtosis(values: DoubleArray): Double {
        if (values.size < 4) return 0.0
        val n = values.size.toDouble()
        val mean = values.average()
        val m2 = values.sumOf { (it - mean).pow(2) } / n
        if (m2 <= 0.0) return 0.0
        val m4 = values.sumOf { (it - mean).pow(4) } / n
        val biased = m4 / m2.pow(2) - 3.0
        return ((n - 1.0) / ((n - 2.0) * (n - 3.0))) * ((n + 1.0) * biased + 6.0)
    }

    private data class MorphologyFeatures(
        val pulseWidth: Double,
        val riseTime: Double,
        val decayTime: Double,
        val areaUnderPulse: Double,
        val slope: Double
    )

    private data class RobustMorphologyFeatures(
        val pulseAmplitudeCv: Double,
        val normalizedAmplitudeMedian: Double,
        val halfHeightWidthMedianMs: Double,
        val riseMedianMs: Double,
        val decayMedianMs: Double,
        val normalizedAreaMedian: Double,
        val normalizedRiseSlopeMedian: Double,
        val normalizedDecaySlopeMedian: Double
    )

    private data class FrequencyFeatures(val lf: Double, val hf: Double, val lfHfRatio: Double, val total: Double)

    private fun Iterable<Double>.averageOrZero(): Double = if (none()) 0.0 else average()

    private fun Double?.orZero(): Double = this ?: 0.0
}
