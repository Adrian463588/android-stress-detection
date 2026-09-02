package com.stressdetector.mobile.domain

import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.signal.PreparedPpgWindow
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * Pure projection boundary for Home PPG charts.
 *
 * It never filters, interpolates, resamples, or normalizes the source signal
 * in-place. Preview points are accepted Watch samples; cleaned points are
 * supplied by the already-prepared inference window.
 */
class RealtimePpgChartProjector(
    private val previewTailMillis: Long = DEFAULT_PREVIEW_TAIL_MILLIS,
    private val maxEnvelopeBuckets: Int = DEFAULT_ENVELOPE_BUCKETS
) {
    fun preview(
        window: WindowSnapshot,
        message: String = PREVIEW_MESSAGE,
        mode: PpgChartMode = PpgChartMode.FULL_SESSION_PREVIEW
    ): PpgChartData {
        if (window.size == 0) return PpgChartData.empty(message)
        val elapsed = elapsedTimes(window)
        val ordered = elapsed.indices.sortedWith(compareBy<Int> { elapsed[it] }.thenBy { it })
        val end = ordered.lastOrNull()?.let(elapsed::get) ?: return PpgChartData.empty(message)
        val start = (end - previewTailMillis).coerceAtLeast(0L)
        val visible = ordered.filter { elapsed[it] >= start }
        return project(
            mode = mode,
            indices = visible,
            elapsedMillis = elapsed,
            values = FloatArray(window.size) { window.rawValues[it].toFloat() },
            interpolatedMask = BooleanArray(window.size),
            acceptedSampleCount = window.size,
            message = message,
            sourceFingerprint = rawFingerprint(window),
            axisStartMillis = start,
            axisEndMillis = end
        )
    }

    /** The supplied [PreparedPpgWindow] has already passed canonical filtering once. */
    fun cleaned(
        window: PreparedPpgWindow,
        message: String,
        mode: PpgChartMode = PpgChartMode.CLEANED_PARTIAL,
        restrictToCore: Boolean = false
    ): PpgChartData {
        if (window.size == 0) return PpgChartData.empty(message)
        val elapsed = elapsedTimes(window)
        val ordered = elapsed.indices.sortedWith(compareBy<Int> { elapsed[it] }.thenBy { it })
        val visible = if (restrictToCore) {
            ordered.filter { elapsed[it] in CORE_START_MILLIS until CORE_END_MILLIS }
        } else {
            ordered
        }
        if (visible.isEmpty()) return PpgChartData.empty(message)
        val axisStart = visible.first().let(elapsed::get)
        val axisEnd = visible.last().let(elapsed::get)
        return project(
            mode = mode,
            indices = visible,
            elapsedMillis = elapsed,
            values = FloatArray(window.size) { window.filteredValues[it].toFloat() },
            interpolatedMask = window.interpolatedMask.copyOf(window.size),
            acceptedSampleCount = window.rawSampleCount,
            message = message,
            sourceFingerprint = preparedFingerprint(window),
            axisStartMillis = axisStart,
            axisEndMillis = axisEnd
        )
    }

    private fun project(
        mode: PpgChartMode,
        indices: List<Int>,
        elapsedMillis: LongArray,
        values: FloatArray,
        interpolatedMask: BooleanArray,
        acceptedSampleCount: Int,
        message: String,
        sourceFingerprint: String,
        axisStartMillis: Long,
        axisEndMillis: Long
    ): PpgChartData {
        val rendered = envelope(indices, elapsedMillis, values)
        if (rendered.isEmpty()) return PpgChartData.empty(message)
        val renderedTimes = LongArray(rendered.size) { elapsedMillis[rendered[it]] }
        val renderedValues = FloatArray(rendered.size) { values[rendered[it]] }
        val renderedMask = BooleanArray(rendered.size) { index ->
            interpolatedMask.getOrElse(rendered[index]) { false }
        }
        val observedMin = renderedValues.minOrNull() ?: 0f
        val observedMax = renderedValues.maxOrNull() ?: observedMin
        val scale = robustScale(renderedValues, observedMin, observedMax)
        val gapThresholdMillis = chartGapThresholdMillis(renderedTimes)
        val telemetry = previewTelemetry(renderedTimes, gapThresholdMillis)
        return PpgChartData(
            mode = mode,
            timestampsMillis = renderedTimes,
            values = renderedValues,
            interpolatedMask = renderedMask,
            min = scale.first,
            max = scale.second,
            observedMin = observedMin,
            observedMax = observedMax,
            acceptedSampleCount = acceptedSampleCount,
            renderedPointCount = rendered.size,
            statusMessage = message,
            axisStartMillis = axisStartMillis,
            axisEndMillis = axisEndMillis,
            normalizedValues = normalizedChartValues(renderedValues, scale.first, scale.second),
            gapThresholdMillis = gapThresholdMillis,
            actualCadenceHz = telemetry.actualCadenceHz,
            significantGapCount = telemetry.significantGapCount,
            largestGapMillis = telemetry.largestGapMillis,
            sourceFingerprint = sourceFingerprint
        )
    }

    /**
     * Keep every original sample in the visible tail. PDSatu renders the
     * actual callback cadence; reducing it to min/max buckets made a normal
     * PPG waveform look like a staircase. Canvas performance is bounded by the
     * ten-second preview window, not by synthetic decimation.
     */
    private fun envelope(indices: List<Int>, elapsedMillis: LongArray, values: FloatArray): IntArray {
        if (indices.isEmpty()) return IntArray(0)
        return indices.toIntArray()
    }

    private fun robustScale(values: FloatArray, observedMin: Float, observedMax: Float): Pair<Float, Float> {
        if (!observedMin.isFinite() || !observedMax.isFinite() || observedMax <= observedMin) {
            val padding = max(1f, kotlin.math.abs(observedMin) * 0.02f)
            return observedMin - padding to observedMax + padding
        }
        val sorted = values.copyOf().apply { sort() }
        val lower = sorted[((sorted.size - 1) * 0.05f).toInt()]
        val upper = sorted[((sorted.size - 1) * 0.95f).toInt()]
        if (upper <= lower) return observedMin to observedMax
        val paddedLower = lower - (upper - lower) * DISPLAY_PADDING_RATIO
        val paddedUpper = upper + (upper - lower) * DISPLAY_PADDING_RATIO
        val step = stableStep(paddedUpper - paddedLower)
        return floor(paddedLower / step) * step to ceil(paddedUpper / step) * step
    }

    /** Rounds the display domain to a stable human-readable scale, not per-batch min/max. */
    private fun stableStep(range: Float): Float {
        if (!range.isFinite() || range <= 0f) return 1f
        val exponent = floor(kotlin.math.log10(range.toDouble())).toInt()
        return 10.0.pow(exponent.toDouble()).toFloat() / 2f
    }

    private fun elapsedTimes(window: WindowSnapshot): LongArray = when {
        window.elapsedTimestampsMillis.size == window.size -> window.elapsedTimestampsMillis.copyOf()
        window.size == 0 -> LongArray(0)
        else -> LongArray(window.size) { index ->
            (
                window.sessionElapsedStartMillis +
                    (window.timestampsMillis[index] - window.timestampsMillis.first())
                ).coerceAtLeast(0L)
        }
    }

    private fun elapsedTimes(window: PreparedPpgWindow): LongArray = when {
        window.elapsedTimestampsMillis.size == window.size -> window.elapsedTimestampsMillis.copyOf()
        window.size == 0 -> LongArray(0)
        else -> LongArray(window.size) { index ->
            (
                window.sessionElapsedStartMillis +
                    (window.timestampsMillis[index] - window.timestampsMillis.first())
                ).coerceAtLeast(0L)
        }
    }

    private fun rawFingerprint(window: WindowSnapshot): String =
        "${window.type.assetSuffix}:${window.size}:${window.elapsedTimestampsMillis.contentHashCode()}:${window.rawValues.contentHashCode()}"

    private fun preparedFingerprint(window: PreparedPpgWindow): String =
        "${window.type.assetSuffix}:${window.size}:${window.elapsedTimestampsMillis.contentHashCode()}:${window.filteredValues.contentHashCode()}:${window.interpolatedMask.contentHashCode()}"

    private companion object {
        const val DEFAULT_PREVIEW_TAIL_MILLIS = 10_000L
        const val DEFAULT_ENVELOPE_BUCKETS = 160
        const val DISPLAY_PADDING_RATIO = 0.1f
        const val CORE_START_MILLIS = 30_000L
        const val CORE_END_MILLIS = 270_000L
        const val PREVIEW_MESSAGE = "PREVIEW / bukan input model"
    }
}

private data class PreviewTelemetry(val actualCadenceHz: Float?, val significantGapCount: Int, val largestGapMillis: Long)

private fun previewTelemetry(timestampsMillis: LongArray, gapThresholdMillis: Long): PreviewTelemetry {
    if (timestampsMillis.size < 2) return PreviewTelemetry(null, 0, 0L)
    var largestGapMillis = 0L
    var significantGapCount = 0
    for (index in 1 until timestampsMillis.size) {
        val gap = (timestampsMillis[index] - timestampsMillis[index - 1]).coerceAtLeast(0L)
        largestGapMillis = maxOf(largestGapMillis, gap)
        if (gap > gapThresholdMillis) significantGapCount += 1
    }
    val span = timestampsMillis.last() - timestampsMillis.first()
    val cadence = span.takeIf { it > 0L }
        ?.let { (timestampsMillis.size - 1) * MILLIS_PER_SECOND / it }
    return PreviewTelemetry(cadence, significantGapCount, largestGapMillis)
}

private const val MILLIS_PER_SECOND = 1_000f
