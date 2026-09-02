package com.stressdetector.mobile.signal

/**
 * Motion metadata only. This contract deliberately does not contain a corrected
 * waveform: motion handling must never fabricate or replace PPG samples.
 */
data class MotionAnnotation(
    val motionScore: Double? = null,
    val motionAnnotation: String = "unavailable",
    val accelerometerScaleStatus: String = "unavailable",
    val motionMethod: String = "annotation_only_no_accelerometer"
)

/**
 * Annotation-only motion detector boundary. The current PPG batch contract does
 * not carry accelerometer samples, so it reports provenance instead of guessing.
 */
class MotionArtifactAnnotator {
    fun annotate(ppgValues: DoubleArray, accelerometer: DoubleArray? = null): MotionAnnotation {
        require(ppgValues.all(Double::isFinite)) { "ppg_values_non_finite" }
        if (accelerometer == null || accelerometer.isEmpty()) {
            return MotionAnnotation()
        }
        val accelerometerValues = accelerometer
        require(accelerometerValues.all(Double::isFinite)) { "accelerometer_non_finite" }

        val median = accelerometerValues.sorted()[accelerometerValues.size / 2]
        val deviations = accelerometerValues.map { kotlin.math.abs(it - median) }.sorted()
        val robustScale = deviations[deviations.size / 2].coerceAtLeast(1.0e-9)
        val score = deviations.average() / robustScale
        return MotionAnnotation(
            motionScore = score,
            motionAnnotation = if (score >= MOTION_SCORE_THRESHOLD) "elevated" else "nominal",
            accelerometerScaleStatus = "provided_unverified",
            motionMethod = "robust_annotation_only"
        )
    }

    private companion object {
        const val MOTION_SCORE_THRESHOLD = 5.0
    }
}
