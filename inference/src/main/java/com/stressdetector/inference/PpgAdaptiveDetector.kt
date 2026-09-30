package com.stressdetector.inference

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

private const val MIN_PEAK_INPUT_SAMPLES = 3
private const val MIN_DISTANCE_SAMPLES = 1
private const val MIN_PROMINENCE = Double.MIN_VALUE
private const val RATE_COMPARISON_EPSILON = 1.0e-9

internal fun scipyAdaptive(values: DoubleArray, samplingRateHz: Double, contract: PpgDetectorContract): Pair<IntArray, IntArray> {
    val signalMedian = median(values)
    val centered = DoubleArray(values.size) { values[it] - signalMedian }
    val mad = median(DoubleArray(centered.size) { abs(centered[it]) })
    val prominence = max(MIN_PROMINENCE, contract.prominence_mad_multiplier * mad)
    val distance = max(MIN_DISTANCE_SAMPLES, round(contract.min_distance_seconds * samplingRateHz).toInt())
    val peaks = findPeaks(centered, distance, prominence)
    val troughs = findPeaks(DoubleArray(centered.size) { -centered[it] }, distance, prominence)
    return peaks to troughs
}

private fun findPeaks(values: DoubleArray, distance: Int, minimumProminence: Double): IntArray {
    if (values.size < MIN_PEAK_INPUT_SAMPLES) return IntArray(0)
    val candidates = mutableListOf<Int>()
    var index = 1
    while (index < values.lastIndex) {
        if (values[index] <= values[index - 1]) {
            index += 1
            continue
        }
        var plateauEnd = index
        while (plateauEnd < values.lastIndex && values[plateauEnd + 1] == values[index]) {
            plateauEnd += 1
        }
        if (plateauEnd < values.lastIndex && values[plateauEnd] > values[plateauEnd + 1]) {
            candidates += (index + plateauEnd) / 2
        }
        index = plateauEnd + 1
    }
    val accepted = mutableListOf<Int>()
    val blocked = BooleanArray(values.size)
    candidates.sortedWith(compareByDescending<Int> { values[it] }.thenBy { it }).forEach { candidate ->
        if (blocked[candidate]) return@forEach
        accepted += candidate
        val start = max(0, candidate - distance + 1)
        val end = min(values.lastIndex, candidate + distance - 1)
        for (blockedIndex in start..end) blocked[blockedIndex] = true
    }
    val prominence = ProminenceLookup(values)
    return accepted.filter { prominence.at(it) >= minimumProminence }.sorted().toIntArray()
}

private class ProminenceLookup(private val values: DoubleArray) {
    private val leftGreater = nearestGreaterLeft()
    private val rightGreater = nearestGreaterRight()
    private val logs = IntArray(values.size + 1)
    private val sparseMin: Array<DoubleArray>

    init {
        for (length in 2..values.size) logs[length] = logs[length / 2] + 1
        val levels = logs[values.size] + 1
        sparseMin = Array(levels) { DoubleArray(values.size) }
        values.copyInto(sparseMin[0])
        for (level in 1 until levels) {
            val span = 1 shl level
            val half = span / 2
            val limit = values.size - span + 1
            for (start in 0 until limit) {
                sparseMin[level][start] = min(
                    sparseMin[level - 1][start],
                    sparseMin[level - 1][start + half]
                )
            }
        }
    }

    fun at(peak: Int): Double {
        val leftStart = leftGreater[peak] + 1
        val rightEnd = rightGreater[peak] - 1
        val leftMinimum = rangeMinimum(leftStart, peak)
        val rightMinimum = rangeMinimum(peak, rightEnd)
        return values[peak] - max(leftMinimum, rightMinimum)
    }

    private fun rangeMinimum(start: Int, end: Int): Double {
        val length = end - start + 1
        val level = logs[length]
        val span = 1 shl level
        return min(sparseMin[level][start], sparseMin[level][end - span + 1])
    }

    private fun nearestGreaterLeft(): IntArray {
        val result = IntArray(values.size) { -1 }
        val stack = IntArray(values.size)
        var size = 0
        values.indices.forEach { index ->
            while (size > 0 && values[stack[size - 1]] <= values[index]) size -= 1
            if (size > 0) result[index] = stack[size - 1]
            stack[size++] = index
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
            stack[size++] = index
        }
        return result
    }
}

internal fun median(values: DoubleArray): Double {
    require(values.isNotEmpty()) { "median_requires_values" }
    val sorted = values.copyOf().apply { sort() }
    return if (sorted.size % 2 == 0) {
        (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    } else {
        sorted[sorted.size / 2]
    }
}

internal fun detectorValuesCloseEnough(left: Double, right: Double): Boolean =
    left.isFinite() && right.isFinite() && abs(left - right) <= RATE_COMPARISON_EPSILON
