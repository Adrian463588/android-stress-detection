package com.stressdetector.mobile.domain.history

import com.stressdetector.shared.PpgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryChartProjectorTest {
    @Test
    fun projectionIsBoundedAndNeverBridgesRecordedGaps() {
        val series = HistoryChartSeries(
            sessionId = "session",
            channel = PpgType.GREEN,
            elapsedMillis = longArrayOf(0L, 10L, 20L, 30L, 50L, 100L, 110L, 120L, 130L),
            displayValues = floatArrayOf(0f, 5f, 1f, 3f, 99f, 2f, 9f, 4f, 6f),
            interpolatedMask = booleanArrayOf(false, true, false, false, false, false, false, false, false),
            gapSegments = listOf(HistoryGapSegment(30L, 100L)),
            processingFingerprint = "cleaned-core"
        )

        val result = GapAwareHistoryChartProjector().project(
            series = series,
            pixelWidth = 2,
            maxRenderedPoints = 4
        )

        assertEquals(2, result.segments.size)
        assertTrue(result.renderedPointCount <= 4)
        assertTrue(result.segments[0].points.all { it.elapsedMillis <= 30L })
        assertTrue(result.segments[1].points.all { it.elapsedMillis >= 100L })
        assertTrue(result.segments.flatMap { it.points }.none { it.elapsedMillis == 50L })
        result.segments.flatMap { it.points }.forEach { point ->
            assertTrue(
                series.elapsedMillis.indices.any { index ->
                    series.elapsedMillis[index] == point.elapsedMillis && series.displayValues[index] == point.value
                }
            )
        }
    }

    @Test
    fun projectionRetainsInterpolationProvenanceFromSelectedSamples() {
        val series = HistoryChartSeries(
            sessionId = "session",
            channel = PpgType.IR,
            elapsedMillis = longArrayOf(0L, 10L, 20L),
            displayValues = floatArrayOf(1f, 2f, 3f),
            interpolatedMask = booleanArrayOf(false, true, false),
            gapSegments = emptyList(),
            processingFingerprint = "cleaned-core"
        )

        val result = GapAwareHistoryChartProjector().project(series, pixelWidth = 4)

        assertTrue(result.segments.single().points.any { it.elapsedMillis == 10L && it.interpolated })
    }

    @Test
    fun projectionPreservesObservedExtremaWithinThePointBudget() {
        val series = HistoryChartSeries(
            sessionId = "session",
            channel = PpgType.GREEN,
            elapsedMillis = longArrayOf(0L, 10L, 20L, 30L, 40L),
            displayValues = floatArrayOf(0f, 10f, 2f, 8f, 3f),
            interpolatedMask = BooleanArray(5),
            gapSegments = emptyList(),
            processingFingerprint = "cleaned-core"
        )

        val result = GapAwareHistoryChartProjector().project(
            series = series,
            pixelWidth = 2,
            maxRenderedPoints = 3
        )

        val values = result.segments.single().points.map(HistoryChartPoint::value)
        assertTrue(values.contains(0f))
        assertTrue(values.contains(10f))
        assertTrue(result.renderedPointCount <= 3)
    }
}
