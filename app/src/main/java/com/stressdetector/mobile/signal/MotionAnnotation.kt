package com.stressdetector.mobile.signal

import com.stressdetector.shared.AccelerometerSample
import kotlin.math.abs
import kotlin.math.sqrt

/** Annotation only: no PPG correction, model feature, or quality-gate decision. */
data class MotionAnnotation(
    val motionScore: Double? = null,
    val motionAnnotation: String = "unavailable",
    val accelerometerScaleStatus: String = "unavailable",
    val motionMethod: String = "annotation_only_no_accelerometer",
    val sampleCount: Int = 0,
    val heavyMotionRatio: Double? = null
)

/** Time-windowed variance and robust-MAD scoring, matching Python's MADC annotation policy. */
class MotionArtifactAnnotator {
    fun annotate(samples: List<AccelerometerSample>, startMillis: Long, endMillis: Long): MotionAnnotation {
        val observed = samples.filter { it.elapsedMillis in startMillis..endMillis }
            .sortedBy(AccelerometerSample::sensorTimestampNanos)
        return when {
            observed.size < MIN_SAMPLES || endMillis <= startMillis -> MotionAnnotation()
            hasCoverageGap(observed, startMillis, endMillis) ->
                MotionAnnotation(motionAnnotation = "accelerometer_gap", sampleCount = observed.size)
            else -> annotateCovered(observed)
        }
    }

    private fun annotateCovered(observed: List<AccelerometerSample>): MotionAnnotation {
        val energy = rollingVariance(observed)
        val scores = robustScores(energy)
        val heavyRatio = scores.count { it >= HEAVY_THRESHOLD }.toDouble() / scores.size
        val annotation = when {
            heavyRatio > HEAVY_RATIO_THRESHOLD -> "motion_heavy_annotated"
            scores.any { it >= LIGHT_THRESHOLD } -> "motion_annotated"
            else -> "pass_annotation_only"
        }
        return MotionAnnotation(
            motionScore = scores.average(),
            motionAnnotation = annotation,
            accelerometerScaleStatus = "android_mps2_uncalibrated",
            motionMethod = "time_windowed_rolling_variance_robust_mad",
            sampleCount = observed.size,
            heavyMotionRatio = heavyRatio
        )
    }

    private fun hasCoverageGap(observed: List<AccelerometerSample>, startMillis: Long, endMillis: Long): Boolean {
        val maxGap = observed.zipWithNext().maxOfOrNull { (left, right) ->
            right.elapsedMillis - left.elapsedMillis
        } ?: Long.MAX_VALUE
        return observed.first().elapsedMillis - startMillis > MAX_EDGE_GAP_MILLIS ||
            endMillis - observed.last().elapsedMillis > MAX_EDGE_GAP_MILLIS ||
            maxGap > MAX_SENSOR_GAP_MILLIS
    }

    private fun rollingVariance(observed: List<AccelerometerSample>): List<Double> {
        val magnitudes = observed.map { sample ->
            sqrt(sample.x.toDouble() * sample.x + sample.y.toDouble() * sample.y + sample.z.toDouble() * sample.z)
        }
        val baseline = magnitudes.first()
        val sums = DoubleArray(magnitudes.size + 1)
        val squares = DoubleArray(magnitudes.size + 1)
        magnitudes.forEachIndexed { index, value ->
            val centered = value - baseline
            sums[index + 1] = sums[index] + centered
            squares[index + 1] = squares[index] + centered * centered
        }
        var left = 0
        var right = 0
        return observed.indices.map { index ->
            val center = observed[index].elapsedMillis
            while (left < index && observed[left].elapsedMillis < center - HALF_WINDOW_MILLIS) left++
            while (right < observed.size && observed[right].elapsedMillis <= center + HALF_WINDOW_MILLIS) right++
            val count = right - left
            if (count < 2) {
                0.0
            } else {
                val sum = sums[right] - sums[left]
                ((squares[right] - squares[left] - sum * sum / count) / (count - 1)).coerceAtLeast(0.0)
            }
        }
    }

    private fun robustScores(energy: List<Double>): List<Double> {
        val median = median(energy)
        val mad = median(energy.map { abs(it - median) })
        val scale = (MAD_SCALE * mad).coerceAtLeast(Math.ulp(1.0))
        return energy.map { (abs(it - median) / scale / ROBUST_Z_THRESHOLD).coerceIn(0.0, 1.0) }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private companion object {
        const val MIN_SAMPLES = 3
        const val HALF_WINDOW_MILLIS = 1_000L // 2 s MADC window.
        const val MAX_EDGE_GAP_MILLIS = 2_000L
        const val MAX_SENSOR_GAP_MILLIS = 2_000L
        const val MAD_SCALE = 1.4826
        const val ROBUST_Z_THRESHOLD = 6.0
        const val HEAVY_THRESHOLD = 0.8
        const val HEAVY_RATIO_THRESHOLD = 0.3
        const val LIGHT_THRESHOLD = 0.2
    }
}
