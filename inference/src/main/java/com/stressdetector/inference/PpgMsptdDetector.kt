package com.stressdetector.inference

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

private const val MSPTD_WINDOW_SECONDS = 6.0
private const val MSPTD_OVERLAP = 0.20
private const val MSPTD_MIN_SCALE_SECONDS = 0.50
private const val MSPTD_TOLERANCE_SECONDS = 0.15
private const val MSPTD_MIN_INPUT_SAMPLES = 5
private const val MIN_WINDOW_SAMPLES = 3
private const val LOW_RATE_HZ = 10.0
private const val MID_RATE_HZ = 20.0
private const val LOW_RATE_TOLERANCE_SECONDS = 0.20
private const val MID_RATE_TOLERANCE_SECONDS = 0.10
private const val MIN_TOLERANCE_SAMPLES = 1
private const val SAMPLE_INDEX_OFFSET = 1

internal fun msptdFast(values: DoubleArray, samplingRateHz: Double): Pair<IntArray, IntArray> {
    val windowSamples = max(MIN_WINDOW_SAMPLES, round(MSPTD_WINDOW_SECONDS * samplingRateHz).toInt())
    val windows = msptdWindows(values.size, windowSamples)
    val peaks = mutableListOf<Int>()
    val troughs = mutableListOf<Int>()
    windows.forEach { (start, end) ->
        val segment = values.copyOfRange(start, end)
        val local = msptdWindow(segment, samplingRateHz)
        val toleranceSeconds = when {
            samplingRateHz < LOW_RATE_HZ -> LOW_RATE_TOLERANCE_SECONDS
            samplingRateHz < MID_RATE_HZ -> MID_RATE_TOLERANCE_SECONDS
            else -> MSPTD_TOLERANCE_SECONDS
        }
        val tolerance = max(MIN_TOLERANCE_SAMPLES, ceil(samplingRateHz * toleranceSeconds).toInt())
        local.first.forEach { index ->
            peaks += start + refine(segment, index, tolerance, findMaximum = true)
        }
        local.second.forEach { index ->
            troughs += start + refine(segment, index, tolerance, findMaximum = false)
        }
    }
    return peaks.distinct().sorted().toIntArray() to troughs.distinct().sorted().toIntArray()
}

/** Matches NeuroKit2's inclusive-end window construction for MSPTDfast. */
private fun msptdWindows(sampleCount: Int, windowSamples: Int): List<Pair<Int, Int>> {
    if (sampleCount <= windowSamples) return listOf(0 to max(0, sampleCount - SAMPLE_INDEX_OFFSET))
    val offset = max(
        MIN_TOLERANCE_SAMPLES,
        round(windowSamples * (1.0 - MSPTD_OVERLAP)).toInt()
    )
    val windows = mutableListOf<Pair<Int, Int>>()
    var start = 0
    while (start + windowSamples <= sampleCount) {
        windows += start to min(sampleCount, start + SAMPLE_INDEX_OFFSET + windowSamples)
        start += offset
    }
    val lastEnd = windows.lastOrNull()?.second ?: 0
    if (lastEnd < sampleCount) {
        windows += (sampleCount - SAMPLE_INDEX_OFFSET - windowSamples).coerceAtLeast(0) to sampleCount
    }
    return windows
}

private fun msptdWindow(values: DoubleArray, samplingRateHz: Double): Pair<IntArray, IntArray> =
    if (values.size < MSPTD_MIN_INPUT_SAMPLES) {
        IntArray(0) to IntArray(0)
    } else {
        val maxScale = ceil(values.size / 2.0).toInt() - SAMPLE_INDEX_OFFSET
        val signalDurationSeconds = values.size / samplingRateHz
        val scales = (1..maxScale).filter {
            (maxScale.toDouble() / it.toDouble()) / signalDurationSeconds >= MSPTD_MIN_SCALE_SECONDS
        }
        if (scales.isEmpty()) {
            IntArray(0) to IntArray(0)
        } else {
            val detrended = detrend(values)
            val maxima = Array(scales.size) { BooleanArray(values.size) }
            val minima = Array(scales.size) { BooleanArray(values.size) }
            scales.forEachIndexed { row, scale ->
                for (index in scale until values.size - scale) {
                    maxima[row][index] =
                        detrended[index] > detrended[index - scale] &&
                        detrended[index] > detrended[index + scale]
                    minima[row][index] =
                        detrended[index] < detrended[index - scale] &&
                        detrended[index] < detrended[index + scale]
                }
            }
            val maxLambda = argMax(IntArray(scales.size) { row -> maxima[row].count { it } })
            val minLambda = argMax(IntArray(scales.size) { row -> minima[row].count { it } })
            consensus(maxima, maxLambda) to consensus(minima, minLambda)
        }
    }

private fun consensus(scalogram: Array<BooleanArray>, lambda: Int): IntArray {
    if (lambda < 0) return IntArray(0)
    val accepted = mutableListOf<Int>()
    for (index in scalogram.first().indices) {
        if ((0..lambda).all { row -> scalogram[row][index] }) accepted += index
    }
    return accepted.toIntArray()
}

private fun detrend(values: DoubleArray): DoubleArray {
    val meanX = values.lastIndex / 2.0
    val meanY = values.average()
    var numerator = 0.0
    var denominator = 0.0
    values.indices.forEach { index ->
        val x = index - meanX
        numerator += x * (values[index] - meanY)
        denominator += x * x
    }
    val slope = if (denominator > 0.0) numerator / denominator else 0.0
    val intercept = meanY - slope * meanX
    return DoubleArray(values.size) { index -> values[index] - (intercept + slope * index) }
}

private fun refine(values: DoubleArray, index: Int, tolerance: Int, findMaximum: Boolean): Int {
    val start = max(0, index - tolerance)
    val end = min(values.lastIndex, index + tolerance)
    var best = start
    for (candidate in start + SAMPLE_INDEX_OFFSET..end) {
        val better = if (findMaximum) values[candidate] > values[best] else values[candidate] < values[best]
        if (better) best = candidate
    }
    return best
}

private fun argMax(values: IntArray): Int {
    if (values.isEmpty()) return -1
    var result = 0
    for (index in 1 until values.size) if (values[index] > values[result]) result = index
    return result
}
