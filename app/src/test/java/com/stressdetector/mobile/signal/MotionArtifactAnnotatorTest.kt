package com.stressdetector.mobile.signal

import com.stressdetector.shared.AccelerometerSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MotionArtifactAnnotatorTest {
    private val annotator = MotionArtifactAnnotator()

    @Test
    fun observedMotionProducesAnnotationWithoutChangingSignal() {
        val samples = (0..100).map { index ->
            val elapsed = index * 40L
            AccelerometerSample(
                sensorTimestampNanos = 1_000_000_000L + elapsed * 1_000_000L,
                elapsedMillis = elapsed,
                x = if (index in 50..55) 4f else 0f,
                y = 9.8f,
                z = 0f
            )
        }
        val result = annotator.annotate(samples, 0L, 4_000L)

        assertNotNull(result.motionScore)
        assertEquals(samples.size, result.sampleCount)
        assertEquals("time_windowed_rolling_variance_robust_mad", result.motionMethod)
    }

    @Test
    fun stationaryAxesDoNotProduceFalseMotionFromVarianceRoundoff() {
        val stationary = (0..100).map { index ->
            val elapsed = index * 40L
            AccelerometerSample(1_000_000_000L + elapsed * 1_000_000L, elapsed, 0f, 9.8f, 0f)
        }

        val result = annotator.annotate(stationary, 0L, 4_000L)

        assertEquals("pass_annotation_only", result.motionAnnotation)
        assertEquals(0.0, result.motionScore!!, 0.0)
    }

    @Test
    fun missingOrGappedSensorEvidenceRemainsUnavailable() {
        assertNull(annotator.annotate(emptyList(), 0L, 4_000L).motionScore)
        val sparse = listOf(
            AccelerometerSample(1_000_000_000L, 0L, 0f, 9.8f, 0f),
            AccelerometerSample(1_100_000_000L, 100L, 0f, 9.8f, 0f),
            AccelerometerSample(5_000_000_000L, 4_000L, 0f, 9.8f, 0f)
        )
        val result = annotator.annotate(sparse, 0L, 4_000L)
        assertNull(result.motionScore)
        assertEquals("accelerometer_gap", result.motionAnnotation)
    }
}
