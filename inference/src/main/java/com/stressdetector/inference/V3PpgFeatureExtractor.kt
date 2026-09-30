package com.stressdetector.inference

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
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
data class CanonicalFeatureVector(
    val values: Map<String, Float>,
    val peakIndices: IntArray = IntArray(0),
    val troughIndices: IntArray = IntArray(0),
    val validIbiCount: Int = 0,
    val adjacentIbiCount: Int = 0,
    val physiologicalIbiRatio: Double = 0.0,
    val morphologySupport: Int = 0,
    val detectorMethod: String? = null,
    val detectorFingerprint: String? = null,
    val qualityStatus: String = "unknown"
)

data class PpgFeatureExtractionInput(
    val values: DoubleArray,
    val samplingRateHz: Double,
    val timestampsMillis: LongArray? = null,
    val missingRatio: Double = 0.0,
    val samplingJitter: Double? = null,
    val detectorContract: PpgDetectorContract? = null
)

object V3PpgFeatureExtractor {
    /** Canonical ONNX contract: full 180-second PPG input window. */
    private const val MIN_WINDOW_SECONDS = 180.0
    private const val MILLIS_PER_SECOND = 1_000.0
    private const val MILLIS_PER_MINUTE = 60_000.0
    private const val MIN_PHYSIOLOGICAL_IBI = 300.0
    private const val MAX_PHYSIOLOGICAL_IBI = 1500.0
    private const val MIN_PHYSIOLOGICAL_IBI_COUNT = 12
    private const val MIN_ADJACENT_IBI_COUNT = 3
    private const val FLATLINE_DELTA_EPSILON = 1.0e-6
    private const val OUTLIER_STDDEV_MULTIPLIER = 3.0
    private const val HALF_HEIGHT_FRACTION = 0.5

    fun extract(values: DoubleArray, samplingRateHz: Double): CanonicalFeatureVector = extract(
        PpgFeatureExtractionInput(
            values = values,
            samplingRateHz = samplingRateHz
        )
    )

    fun extract(input: PpgFeatureExtractionInput): CanonicalFeatureVector {
        val context = FeatureContext(input)
        val features = buildFeatureMap(context)
        require(features.values.all(Double::isFinite)) { "non_finite_feature" }
        val safeFeatures = features.mapValues { (_, value) -> value.toFloat() }
        require(safeFeatures.values.all(Float::isFinite)) { "non_finite_feature" }
        return CanonicalFeatureVector(
            values = safeFeatures,
            peakIndices = context.peaks,
            troughIndices = context.troughs,
            validIbiCount = context.ibi.size,
            adjacentIbiCount = context.observedIbiDiff.size,
            physiologicalIbiRatio = context.detection.physiologicalIbiRatio,
            morphologySupport = context.detection.morphologySupport,
            detectorMethod = context.detection.detectorMethod,
            detectorFingerprint = context.detection.detectorFingerprint,
            qualityStatus = "good"
        )
    }

    private fun buildFeatureMap(context: FeatureContext): LinkedHashMap<String, Double> {
        val features = linkedMapOf<String, Double>()
        addIntervalFeatures(features, context)
        addLegacyMorphologyFeatures(features, context)
        addQualityFeatures(features, context)
        addDistributionFeatures(features, context)
        addCanonicalFeatures(features, context)
        return features
    }

    private fun addIntervalFeatures(features: MutableMap<String, Double>, context: FeatureContext) {
        val ibi = context.ibi
        val differences = context.legacyIbiDiff
        features.putAll(
            linkedMapOf(
                "heart_rate" to context.heartRate.average(),
                "ibi_mean" to ibi.average(),
                "ibi_std" to sampleStd(ibi),
                "ibi_min" to ibi.minOrNull().orZero(),
                "ibi_max" to ibi.maxOrNull().orZero(),
                "rmssd" to sqrt(differences.map { it * it }.average()),
                "sdnn" to sampleStd(ibi),
                "pnn50" to differences.count { abs(it) > 50.0 }.toDouble() / differences.size
            )
        )
    }

    private fun addLegacyMorphologyFeatures(features: MutableMap<String, Double>, context: FeatureContext) {
        val morphology = context.morphology
        features.putAll(
            linkedMapOf(
                "pulse_amplitude_mean" to context.peakAmplitudes.average(),
                "pulse_amplitude_std" to sampleStd(context.peakAmplitudes),
                "pulse_width" to morphology.pulseWidth,
                "rise_time" to morphology.riseTime,
                "decay_time" to morphology.decayTime,
                "area_under_pulse" to morphology.areaUnderPulse,
                "slope_features" to morphology.slope
            )
        )
    }

    private fun addQualityFeatures(features: MutableMap<String, Double>, context: FeatureContext) {
        val clippingThreshold = percentileSorted(context.sortedValues, 0.999)
        features.putAll(
            linkedMapOf(
                "signal_quality_index" to 1.0 - context.flatlineRatio - context.outlierRatio,
                "clipping_ratio" to context.values.count { it >= clippingThreshold }.toDouble() /
                    context.values.size,
                "flatline_ratio" to context.flatlineRatio,
                "missing_ratio" to context.input.missingRatio.coerceIn(0.0, 1.0),
                "sampling_jitter" to
                    (context.input.samplingJitter ?: context.timestampMetrics.jitter),
                "artifact_ratio" to context.outlierRatio,
                "valid_peak_ratio" to context.ibi.size.toDouble() / context.peaks.size
            )
        )
    }

    private fun addDistributionFeatures(features: MutableMap<String, Double>, context: FeatureContext) {
        features.putAll(
            linkedMapOf(
                "mean" to context.mean,
                "std" to context.standardDeviation,
                "median" to percentileSorted(context.sortedValues, 0.5),
                "iqr" to percentileSorted(context.sortedValues, 0.75) -
                    percentileSorted(context.sortedValues, 0.25),
                "skewness" to unbiasedSkew(context.values),
                "kurtosis" to unbiasedFisherKurtosis(context.values),
                "rolling_min" to rollingMean(context.values, context.samplingRateHz.toInt(), minimum = true),
                "rolling_max" to rollingMean(context.values, context.samplingRateHz.toInt(), minimum = false),
                "lf_power" to context.frequency.lf,
                "hf_power" to context.frequency.hf,
                "lf_hf_ratio" to context.frequency.lfHfRatio
            )
        )
    }

    private fun addCanonicalFeatures(features: MutableMap<String, Double>, context: FeatureContext) {
        val ibi = context.ibi
        val robust = context.robustMorphology
        val observed = context.observedIbiDiff
        features.putAll(
            linkedMapOf(
                "sampling_rate_hz" to context.samplingRateHz,
                "median_hr_bpm" to percentile(context.heartRate, 0.5),
                "ppi_median_ms" to percentile(ibi, 0.5),
                "ppi_iqr_ms" to (percentile(ibi, 0.75) - percentile(ibi, 0.25)),
                "ppi_cv" to (sampleStd(ibi) / percentile(ibi, 0.5)),
                "rmssd_ms" to sqrt(observed.map { it * it }.average()),
                "sdnn_ms" to sampleStd(ibi),
                "pnn50_observed_adjacent" to observed.count { abs(it) > 50.0 }.toDouble() /
                    observed.size,
                "pulse_amplitude_cv" to robust.pulseAmplitudeCv,
                "normalized_pulse_amplitude_median" to robust.normalizedAmplitudeMedian,
                "pulse_width_half_height_median_ms" to robust.halfHeightWidthMedianMs,
                "rise_time_median_ms" to robust.riseMedianMs,
                "decay_time_median_ms" to robust.decayMedianMs,
                "normalized_pulse_area_median" to robust.normalizedAreaMedian,
                "normalized_rise_slope_median" to robust.normalizedRiseSlopeMedian,
                "normalized_decay_slope_median" to robust.normalizedDecaySlopeMedian
            )
        )
    }

    private class FeatureContext(rawInput: PpgFeatureExtractionInput) {
        val input = validateInput(rawInput)
        val values = input.values
        val sortedValues = values.copyOf().apply { sort() }
        val samplingRateHz = input.samplingRateHz
        val effectiveContract = input.detectorContract
            ?: PpgDetectorContract.defaultFor("ppg_green", samplingRateHz)
        val detection = BackendPpgPeakDetector.detect(values, samplingRateHz, effectiveContract)
        val peaks = detection.peaks
        val troughs = detection.troughs
        val allIbi = DoubleArray(peaks.size - 1) { index ->
            (peaks[index + 1] - peaks[index]) * MILLIS_PER_SECOND / samplingRateHz
        }
        val physiological = BooleanArray(allIbi.size) {
            allIbi[it] in MIN_PHYSIOLOGICAL_IBI..MAX_PHYSIOLOGICAL_IBI
        }
        val ibi = allIbi.filterIndexed { index, _ -> physiological[index] }.toDoubleArray()
        val legacyIbiDiff = DoubleArray(ibi.size - 1) { index -> ibi[index + 1] - ibi[index] }
        val observedIbiDiff = (0 until allIbi.lastIndex)
            .filter { physiological[it] && physiological[it + 1] }
            .map { allIbi[it + 1] - allIbi[it] }
            .toDoubleArray()
        val heartRate = ibi.map { MILLIS_PER_MINUTE / it }.toDoubleArray()
        val mean = values.average()
        val gradient = DoubleArray(values.size - 1) { index -> values[index + 1] - values[index] }
        val peakAmplitudes = DoubleArray(peaks.size) { index -> values[peaks[index]] }
        val morphology = morphologyFeatures(values, peaks, troughs, samplingRateHz, gradient)
        val robustMorphology = robustMorphologyFeatures(values, sortedValues, peaks, troughs, samplingRateHz)
        val frequency = frequencyFeatures(ibi)
        val flatlineRatio = gradient.count { abs(it) < FLATLINE_DELTA_EPSILON }.toDouble() /
            gradient.size.coerceAtLeast(1)
        val standardDeviation = sampleStd(values)
        val outlierRatio = values.count {
            abs(it - mean) > OUTLIER_STDDEV_MULTIPLIER * standardDeviation
        }.toDouble() /
            values.size
        val timestampMetrics = timestampMetrics(
            timestampsMillis = input.timestampsMillis,
            valuesSize = values.size,
            samplingRateHz = samplingRateHz
        )

        init {
            require(ibi.size >= MIN_PHYSIOLOGICAL_IBI_COUNT) {
                "insufficient_physiological_ibi:${ibi.size}"
            }
            require(observedIbiDiff.size >= MIN_ADJACENT_IBI_COUNT) {
                "insufficient_adjacent_physiological_ibi"
            }
        }
    }

    private fun validateInput(input: PpgFeatureExtractionInput): PpgFeatureExtractionInput {
        require(input.values.size >= (MIN_WINDOW_SECONDS * input.samplingRateHz).toInt()) {
            "insufficient_window_samples"
        }
        require(input.samplingRateHz.isFinite() && input.samplingRateHz > 0.0) {
            "invalid_sampling_rate"
        }
        require(input.values.all(Double::isFinite)) { "non_finite_signal" }
        return input
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
        var troughIndex = 0

        for (index in 0 until peaks.lastIndex) {
            val firstPeak = peaks[index]
            val secondPeak = peaks[index + 1]
            while (troughIndex < troughs.size && troughs[troughIndex] <= firstPeak) {
                troughIndex += 1
            }
            val trough = troughs.getOrNull(troughIndex)?.takeIf { it < secondPeak } ?: continue
            decayTimes += (trough - firstPeak) / samplingRateHz
            riseTimes += (secondPeak - trough) / samplingRateHz
            pulseWidths += (secondPeak - firstPeak) / samplingRateHz
            var area = 0.0
            for (sample in firstPeak until secondPeak) area += values[sample]
            areas += area
            if (trough > firstPeak) {
                var minimum = Double.POSITIVE_INFINITY
                for (sample in firstPeak until trough) minimum = minOf(minimum, gradient[sample])
                slopes += minimum
            }
            if (secondPeak > trough) {
                var maximum = Double.NEGATIVE_INFINITY
                for (sample in trough until secondPeak) maximum = maxOf(maximum, gradient[sample])
                slopes += maximum
            }
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
        sortedValues: DoubleArray,
        peaks: IntArray,
        troughs: IntArray,
        samplingRateHz: Double
    ): RobustMorphologyFeatures {
        val robustScale = percentileSorted(sortedValues, 0.75) - percentileSorted(sortedValues, 0.25)
        require(robustScale > 1.0e-12) { "insufficient_waveform_robust_scale" }
        val pulses = peaks.asSequence()
            .mapNotNull { peak -> pulseRegionOrNull(troughs, peak) }
            .mapNotNull { region -> robustPulseFeatures(values, region, samplingRateHz) }
            .toList()
        val widths = pulses.mapNotNull { it.halfHeightWidthMs }
        val riseSlopes = pulses.mapNotNull { it.normalizedRiseSlope }
        val decaySlopes = pulses.mapNotNull { it.normalizedDecaySlope }
        require(pulses.size >= 3 && widths.size >= 3 && riseSlopes.size >= 3 && decaySlopes.size >= 3) {
            "insufficient_pulse_morphology_support"
        }
        val amplitudeArray = pulses.map { it.amplitude }.toDoubleArray()
        val amplitudeMedian = percentile(amplitudeArray, 0.5)
        return RobustMorphologyFeatures(
            pulseAmplitudeCv = sampleStd(amplitudeArray) / amplitudeMedian,
            normalizedAmplitudeMedian = amplitudeMedian / robustScale,
            halfHeightWidthMedianMs = percentile(widths.toDoubleArray(), 0.5),
            riseMedianMs = percentile(pulses.map { it.riseTimeMs }.toDoubleArray(), 0.5),
            decayMedianMs = percentile(pulses.map { it.decayTimeMs }.toDoubleArray(), 0.5),
            normalizedAreaMedian = percentile(pulses.map { it.normalizedArea }.toDoubleArray(), 0.5),
            normalizedRiseSlopeMedian = percentile(riseSlopes.toDoubleArray(), 0.5),
            normalizedDecaySlopeMedian = percentile(decaySlopes.toDoubleArray(), 0.5)
        )
    }

    private fun pulseRegionOrNull(troughs: IntArray, peak: Int): PulseRegion? {
        val left = troughs.getOrNull(lowerBound(troughs, peak) - 1)?.takeIf { it < peak }
        val right = troughs.getOrNull(upperBound(troughs, peak))?.takeIf { it > peak }
        return if (left != null && right != null) PulseRegion(left, peak, right) else null
    }

    private fun robustPulseFeatures(values: DoubleArray, region: PulseRegion, samplingRateHz: Double): PulseMeasurements? {
        val amplitude = values[region.peak] - values[region.left]
        if (amplitude <= 1.0e-12) return null

        val halfHeight = values[region.left] + HALF_HEIGHT_FRACTION * amplitude
        val leftCrossing = (region.left..region.peak).lastOrNull { values[it] <= halfHeight }
        val rightCrossing = (region.peak..region.right).firstOrNull { values[it] <= halfHeight }
        val halfHeightWidthMs = if (
            leftCrossing != null && rightCrossing != null && rightCrossing > leftCrossing
        ) {
            (rightCrossing - leftCrossing) * MILLIS_PER_SECOND / samplingRateHz
        } else {
            null
        }
        val durationSeconds = (region.right - region.left) / samplingRateHz
        val shape = PulseShapeContext(
            values = values,
            left = region.left,
            right = region.right,
            samplingRateHz = samplingRateHz,
            amplitude = amplitude,
            durationSeconds = durationSeconds
        )
        return PulseMeasurements(
            amplitude = amplitude,
            halfHeightWidthMs = halfHeightWidthMs,
            riseTimeMs = (region.peak - region.left) * MILLIS_PER_SECOND / samplingRateHz,
            decayTimeMs = (region.right - region.peak) * MILLIS_PER_SECOND / samplingRateHz,
            normalizedArea = normalizedPulseArea(shape),
            normalizedRiseSlope = normalizedRiseSlopeOrNull(
                values,
                region.left,
                region.peak,
                samplingRateHz,
                amplitude
            ),
            normalizedDecaySlope = normalizedDecaySlopeOrNull(
                values,
                region.peak,
                region.right,
                samplingRateHz,
                amplitude
            )
        )
    }

    private fun normalizedPulseArea(shape: PulseShapeContext): Double {
        var positiveArea = 0.0
        val span = (shape.right - shape.left).toDouble()
        for (index in shape.left until shape.right) {
            val startBaseline = shape.values[shape.left] +
                (index - shape.left) / span * (shape.values[shape.right] - shape.values[shape.left])
            val endBaseline = shape.values[shape.left] +
                (index + 1 - shape.left) / span * (shape.values[shape.right] - shape.values[shape.left])
            val startHeight = maxOf(shape.values[index] - startBaseline, 0.0)
            val endHeight = maxOf(shape.values[index + 1] - endBaseline, 0.0)
            positiveArea += 0.5 * (startHeight + endHeight)
        }
        return positiveArea / (shape.samplingRateHz * shape.amplitude * shape.durationSeconds)
    }

    private fun normalizedRiseSlopeOrNull(values: DoubleArray, left: Int, peak: Int, samplingRateHz: Double, amplitude: Double): Double? =
        (left until peak)
            .map { (values[it + 1] - values[it]) * samplingRateHz }
            .maxOrNull()
            ?.let { it / amplitude }

    private fun normalizedDecaySlopeOrNull(values: DoubleArray, peak: Int, right: Int, samplingRateHz: Double, amplitude: Double): Double? =
        (peak until right)
            .map { (values[it + 1] - values[it]) * samplingRateHz }
            .minOrNull()
            ?.let { abs(it / amplitude) }

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
        return percentileSorted(sorted, quantile)
    }

    private fun percentileSorted(sorted: DoubleArray, quantile: Double): Double {
        require(sorted.isNotEmpty()) { "empty_values" }
        val position = quantile * (sorted.size - 1)
        val lower = position.toInt()
        val upper = minOf(lower + 1, sorted.lastIndex)
        val weight = position - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    private fun lowerBound(values: IntArray, target: Int): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] < target) low = middle + 1 else high = middle
        }
        return low
    }

    private fun upperBound(values: IntArray, target: Int): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] <= target) low = middle + 1 else high = middle
        }
        return low
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

    private data class PulseRegion(val left: Int, val peak: Int, val right: Int)

    private data class PulseMeasurements(
        val amplitude: Double,
        val halfHeightWidthMs: Double?,
        val riseTimeMs: Double,
        val decayTimeMs: Double,
        val normalizedArea: Double,
        val normalizedRiseSlope: Double?,
        val normalizedDecaySlope: Double?
    )

    private data class PulseShapeContext(
        val values: DoubleArray,
        val left: Int,
        val right: Int,
        val samplingRateHz: Double,
        val amplitude: Double,
        val durationSeconds: Double
    )

    private data class FrequencyFeatures(val lf: Double, val hf: Double, val lfHfRatio: Double, val total: Double)

    private fun Iterable<Double>.averageOrZero(): Double = if (none()) 0.0 else average()

    private fun Double?.orZero(): Double = this ?: 0.0
}
