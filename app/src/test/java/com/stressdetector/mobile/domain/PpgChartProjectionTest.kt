package com.stressdetector.mobile.domain

import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.signal.PreparedPpgWindow
import com.stressdetector.shared.PpgType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgChartProjectionTest {
    @Test
    fun `normalization is prepared before canvas rendering`() {
        assertArrayEquals(
            floatArrayOf(0f, 0.5f, 1f),
            normalizedChartValues(floatArrayOf(10f, 15f, 20f), 10f, 20f),
            0.0001f
        )
    }

    @Test
    fun `gap threshold follows median cadence but keeps visible floor`() {
        assertEquals(500L, chartGapThresholdMillis(longArrayOf(0L, 40L, 80L, 1_000L)))
        assertEquals(1_000L, chartGapThresholdMillis(longArrayOf(0L, 200L, 400L, 600L)))
    }

    @Test
    fun `preview uses elapsed axis actual accepted samples and preserves long gaps`() {
        val elapsed = longArrayOf(0L, 40L, 80L, 120L, 160L, 2_000L, 2_040L, 2_080L, 2_120L, 2_160L)
        val raw = IntArray(elapsed.size) { 48_000 + it * 5 }
        val chart = RealtimePpgChartProjector(maxEnvelopeBuckets = 2).preview(
            WindowSnapshot(
                type = PpgType.GREEN,
                timestampsMillis = elapsed.map { it + 1_000_000L }.toLongArray(),
                rawValues = raw,
                elapsedTimestampsMillis = elapsed
            )
        )

        assertEquals(0L, chart.axisStartMillis)
        assertEquals(2_160L, chart.axisEndMillis)
        assertEquals(raw.size, chart.acceptedSampleCount)
        assertTrue(chart.renderedPointCount <= raw.size)
        assertTrue(chart.timestampsMillis.all { timestamp -> elapsed.contains(timestamp) })
        assertTrue(
            chart.timestampsMillis.indices.drop(1).any { index ->
                chart.timestampsMillis[index] - chart.timestampsMillis[index - 1] > chart.gapThresholdMillis
            }
        )
        assertFalse(chart.interpolatedMask.any { it })
        assertTrue(chart.min < chart.max)
        assertTrue(chart.actualCadenceHz!! > 0f)
        assertEquals(1, chart.significantGapCount)
        assertTrue(chart.largestGapMillis > chart.gapThresholdMillis)
    }

    @Test
    fun `cleaned core chart consumes prepared values without raw preview fallback`() {
        val elapsed = longArrayOf(29_000L, 30_000L, 31_000L, 269_000L, 270_000L)
        val prepared = PreparedPpgWindow(
            type = PpgType.GREEN,
            timestampsMillis = elapsed.map { it + 1_000_000L }.toLongArray(),
            filteredValues = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0),
            rawSampleCount = 99,
            interpolatedSampleCount = 1,
            elapsedTimestampsMillis = elapsed,
            interpolatedMask = booleanArrayOf(false, true, false, false, false)
        )

        val chart = RealtimePpgChartProjector().cleaned(
            window = prepared,
            message = "CLEANED_CORE",
            mode = PpgChartMode.CLEANED_CORE,
            restrictToCore = true
        )

        assertArrayEquals(floatArrayOf(2f, 3f, 4f), chart.values, 0.0001f)
        assertEquals(99, chart.acceptedSampleCount)
        assertEquals(3, chart.renderedPointCount)
        assertTrue(chart.interpolatedMask[0])
    }
}
