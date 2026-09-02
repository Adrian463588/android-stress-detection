package com.stressdetector.mobile.signal

import com.stressdetector.shared.PpgType
import com.stressdetector.shared.PredictionWindowConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class PreparedPpgWindowSlicerTest {
    @Test
    fun slicesPreparedSessionWithoutChangingValuesOrTiming() {
        val elapsed = LongArray(7_501) { it * 40L }
        val values = DoubleArray(elapsed.size) { it.toDouble() }
        val prepared = PreparedPpgWindow(
            type = PpgType.GREEN,
            timestampsMillis = elapsed.copyOf(),
            filteredValues = values.copyOf(),
            rawSampleCount = values.size,
            interpolatedSampleCount = 0,
            elapsedTimestampsMillis = elapsed.copyOf()
        )

        val windows = PreparedPpgWindowSlicer(PredictionWindowConfig()).modelWindows(prepared)

        assertEquals(7, windows.size)
        windows.forEachIndexed { index, window ->
            assertEquals(4_500, window.size)
            assertEquals(30_000L + index * 10_000L, window.elapsedTimestampsMillis.first())
            val expectedSampleIndex = ((30_000L + index * 10_000L) / 40L).toInt()
            assertEquals(values[expectedSampleIndex], window.filteredValues.first(), 0.0)
            assertEquals(true, window.elapsedTimestampsMillis.last() < 270_000L)
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
