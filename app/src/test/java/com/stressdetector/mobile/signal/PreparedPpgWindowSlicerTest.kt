package com.stressdetector.mobile.signal

import com.stressdetector.shared.AccelerometerSample
import com.stressdetector.shared.PpgSamplingPolicy
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.PredictionWindowConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class PreparedPpgWindowSlicerTest {
    @Test
    fun eachModelAndBpmWindowUsesObservedEndpointCadence() {
        val elapsed = LongArray(7_501) { index -> (index * 40.01).toLong() }
        val prepared = PreparedPpgWindow(
            type = PpgType.GREEN,
            timestampsMillis = elapsed,
            filteredValues = DoubleArray(elapsed.size) { it.toDouble() },
            rawSampleCount = elapsed.size,
            interpolatedSampleCount = 0,
            elapsedTimestampsMillis = elapsed,
            modelRateHz = 24.91929703690945,
            samplingRatePolicy = PpgSamplingPolicy.RECORDING_OBSERVED_V1,
            windowId = "observed-core"
        )

        val windows = PreparedPpgWindowSlicer().modelWindows(prepared)

        assertEquals(7, windows.size)
        assertEquals(7, windows.map { it.windowId }.distinct().size)
        windows.forEach { window ->
            val expected = (window.size - 1) * 1_000.0 /
                (window.timestampsMillis.last() - window.timestampsMillis.first())
            assertEquals(expected, window.modelRateHz, 0.0)
            assertEquals(expected, requireNotNull(window.observedCadenceHz), 0.0)
        }
        val bpmWindows = PreparedPpgWindowSlicer().bpmWindows(prepared)
        assertEquals(8, bpmWindows.size)
        bpmWindows.forEach { window ->
            assertEquals(PpgSamplingPolicy.observedRateHz(window.timestampsMillis), window.modelRateHz, 0.0)
            assertEquals(
                PpgSamplingPolicy.observedRateHz(window.timestampsMillis),
                requireNotNull(window.observedCadenceHz),
                0.0
            )
        }
    }

    @Test
    fun slicesPreparedSessionWithoutChangingValuesOrTiming() {
        val elapsed = LongArray(7_501) { it * 40L }
        val values = DoubleArray(elapsed.size) { it.toDouble() }
        val motion = elapsed.map { millis ->
            AccelerometerSample(1_000_000_000L + millis * 1_000_000L, millis, 0f, 9.8f, 0f)
        }
        val prepared = PreparedPpgWindow(
            type = PpgType.GREEN,
            timestampsMillis = elapsed.copyOf(),
            filteredValues = values.copyOf(),
            rawSampleCount = values.size,
            interpolatedSampleCount = 0,
            elapsedTimestampsMillis = elapsed.copyOf(),
            observedSampleCount = values.size,
            observedMask = BooleanArray(values.size) { true },
            gridResampledMask = BooleanArray(values.size),
            accelerometerSamples = motion
        )

        val windows = PreparedPpgWindowSlicer(PredictionWindowConfig()).modelWindows(prepared)

        assertEquals(7, windows.size)
        windows.forEachIndexed { index, window ->
            assertEquals(4_500, window.size)
            assertEquals(30_000L + index * 10_000L, window.elapsedTimestampsMillis.first())
            val expectedSampleIndex = ((30_000L + index * 10_000L) / 40L).toInt()
            assertEquals(values[expectedSampleIndex], window.filteredValues.first(), 0.0)
            assertEquals(true, window.elapsedTimestampsMillis.last() < 270_000L)
            assertEquals(window.size, window.observedSampleCount)
            assertEquals(0, window.gridResampledSampleCount)
            assertEquals(4_500, window.motionAnnotation.sampleCount)
            assertEquals("time_windowed_rolling_variance_robust_mad", window.motionAnnotation.motionMethod)
        }
    }

    @Test
    fun bpmWindowsCoverCoreOnceWithoutOverlap() {
        val elapsed = LongArray(7_501) { it * 40L }
        val prepared = PreparedPpgWindow(
            type = PpgType.GREEN,
            timestampsMillis = elapsed.copyOf(),
            filteredValues = DoubleArray(elapsed.size) { it.toDouble() },
            rawSampleCount = elapsed.size,
            interpolatedSampleCount = 0,
            elapsedTimestampsMillis = elapsed.copyOf()
        )

        val windows = PreparedPpgWindowSlicer(PredictionWindowConfig()).bpmWindows(prepared)

        assertEquals(8, windows.size)
        assertEquals(30_000L, windows.first().elapsedTimestampsMillis.first())
        assertEquals(true, windows.last().elapsedTimestampsMillis.last() < 270_000L)
        windows.zipWithNext().forEach { (left, right) ->
            assertEquals(true, left.elapsedTimestampsMillis.last() < right.elapsedTimestampsMillis.first())
        }
        assertEquals(windows.sumOf { it.size }, windows.flatMap { it.elapsedTimestampsMillis.asIterable() }.distinct().size)
    }
}
