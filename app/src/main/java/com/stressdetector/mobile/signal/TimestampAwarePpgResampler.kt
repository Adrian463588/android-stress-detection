package com.stressdetector.mobile.signal

import kotlin.math.roundToLong

/** Result of converting timestamped raw samples to a model-rate timeline. */
data class TimestampResampledPpg(
    val timestampsMillis: LongArray,
    val values: DoubleArray,
    val interpolatedMask: BooleanArray,
    val observedRateHz: Double
) {
    init {
        require(timestampsMillis.size == values.size) { "resampled_shape_mismatch" }
        require(values.size == interpolatedMask.size) { "resampled_mask_mismatch" }
    }
}

/**
 * Deterministic linear resampler for Samsung timestamped PPG.
 *
 * Duplicate timestamps use the first value, matching the Python ingestion
 * contract. A target sample is never extrapolated and no interpolation crosses
 * a gap larger than [maxGapMillis].
 */
object TimestampAwarePpgResampler {
    fun resample(timestampsMillis: LongArray, values: DoubleArray, targetRateHz: Int, maxGapMillis: Long = 500L): TimestampResampledPpg? {
        require(targetRateHz > 0) { "target_rate_must_be_positive" }
        require(maxGapMillis > 0L) { "max_gap_must_be_positive" }
        if (timestampsMillis.isEmpty() || timestampsMillis.size != values.size) return null
        if (values.any { !it.isFinite() }) return null

        val sourceTimes = ArrayList<Long>(timestampsMillis.size)
        val sourceValues = ArrayList<Double>(values.size)
        var previous = Long.MIN_VALUE
        timestampsMillis.indices.forEach { index ->
            val timestamp = timestampsMillis[index]
            if (timestamp < previous) return null
            if (timestamp > previous) {
                sourceTimes += timestamp
                sourceValues += values[index]
            }
            previous = timestamp
        }
        if (sourceTimes.size < 2) return null
        val times = sourceTimes.toLongArray()
        val samples = sourceValues.toDoubleArray()
        val intervals = LongArray(times.size - 1) { times[it + 1] - times[it] }
        if (intervals.any { it <= 0L || it > maxGapMillis }) return null
        val span = times.last() - times.first()
        val observedRateHz = (times.size - 1) * 1_000.0 / span.toDouble()
        val stepMillis = 1_000.0 / targetRateHz.toDouble()
        val count = (span / stepMillis).toInt() + 1
        if (count < 2) return null

        val targetTimes = LongArray(count) { offset ->
            (times.first() + offset * stepMillis).roundToLong()
        }
        val output = DoubleArray(count)
        val interpolated = BooleanArray(count)
        var right = 0
        targetTimes.forEachIndexed { index, target ->
            while (right < times.size && times[right] < target) right++
            if (right < times.size && times[right] == target) {
                output[index] = samples[right]
            } else {
                val left = right - 1
                if (left < 0 || right >= times.size) return null
                val gap = times[right] - times[left]
                if (gap <= 0L || gap > maxGapMillis) return null
                val weight = (target - times[left]).toDouble() / gap.toDouble()
                output[index] = samples[left] * (1.0 - weight) + samples[right] * weight
                interpolated[index] = true
            }
        }
        return TimestampResampledPpg(targetTimes, output, interpolated, observedRateHz)
    }
}
