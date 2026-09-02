package com.stressdetector.mobile.signal

import com.stressdetector.shared.PpgType
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgBpmMarkerDetectorTest {
    @Test
    fun summarizesObservedBpmPerIndependentChannelAccumulator() {
        val green = BpmSummaryAccumulator()
        val ir = BpmSummaryAccumulator()

        green.add(estimate(60f, 30_000L).copy(peakCount = 30))
        green.add(estimate(80f, 40_000L).copy(peakCount = 40))
        ir.add(estimate(100f, 30_000L).copy(peakCount = 50))

        val greenSummary = requireNotNull(green.summary())
        val irSummary = requireNotNull(ir.summary())
        assertEquals(70f, greenSummary.meanBpm)
        assertEquals(70f, greenSummary.medianBpm)
        assertEquals(60f, greenSummary.minBpm)
        assertEquals(80f, greenSummary.maxBpm)
        assertEquals(2, greenSummary.observationCount)
        assertEquals(70, greenSummary.validPeakCount)
        assertEquals(100f, irSummary.meanBpm)
        assertEquals(1, irSummary.observationCount)
        assertNull(BpmSummaryAccumulator().summary())
    }

    @Test
    fun estimatesBpmFromThirtySecondValidPpgTail() {
        val samplingRate = PpgType.GREEN.samplingRateHz
        val count = samplingRate * 30
        val bpm = 80.0
        val window = PreparedPpgWindow(
            type = PpgType.GREEN,
            timestampsMillis = LongArray(count) { index -> index * 1_000L / samplingRate },
            filteredValues = DoubleArray(count) { index ->
                sin(2.0 * PI * bpm / 60.0 * index / samplingRate)
            },
            rawSampleCount = count,
            interpolatedSampleCount = 0,
            sessionElapsedStartMillis = 0L,
            sessionElapsedEndMillis = 30_000L,
            elapsedTimestampsMillis = LongArray(count) { index -> index * 1_000L / samplingRate }
        )

        val estimate = PpgBpmEstimator(windowSeconds = 30).estimate(window)

        assertNotNull(estimate)
        assertTrue(requireNotNull(estimate).bpm in 78f..82f)
    }

    @Test
    fun emitsSustainedRiseThenRecoveryUsingPrecedingBaseline() {
        val detector = BpmPhysiologicalMarkerDetector(config())

        listOf(30_000L, 40_000L, 50_000L, 60_000L, 70_000L, 80_000L).forEach { end ->
            detector.observe(estimate(70f, end))
        }
        // Baseline must cover full preceding 60 seconds; no 180-second ONNX
        // window is involved in this marker path.
        assertTrue(detector.observe(estimate(83f, 90_000L)).isEmpty())

        val rise = detector.observe(estimate(84f, 100_000L)).single()
        val recovery = detector.observe(estimate(72f, 110_000L)).single()

        assertEquals(BpmMarkerTrend.RISING, rise.trend)
        assertEquals(70f, rise.baselineBpm)
        assertEquals(84f, rise.peakBpm)
        assertEquals(BpmMarkerTrend.RECOVERING, recovery.trend)
        assertEquals(84f, recovery.peakBpm)
    }

    @Test
    fun transportGapResetsBaselineAndDoesNotEmitRise() {
        val detector = BpmPhysiologicalMarkerDetector(config())

        listOf(30_000L, 40_000L, 50_000L, 60_000L, 70_000L, 80_000L).forEach { end ->
            detector.observe(estimate(70f, end))
        }

        assertTrue(detector.observe(estimate(84f, 90_000L)).isEmpty())
        assertTrue(detector.observe(estimate(84f, 110_000L)).isEmpty())
        assertTrue(detector.observe(estimate(84f, 120_000L)).isEmpty())
    }

    private fun estimate(bpm: Float, endMillis: Long) = BpmEstimate(
        bpm = bpm,
        peakCount = 10,
        windowStartElapsedMillis = (endMillis - 30_000L).coerceAtLeast(0L),
        windowEndElapsedMillis = endMillis
    )

    private fun config() = LiveMonitorConfig(
        adrReference = "ADR-012",
        inferenceStepSeconds = 10,
        minimumQualityScore = 0.4f,
        maxTimestampGapDeltaMs = 500.0,
        maxTimestampGapCountPerSecond = 5,
        maxFlatlineRatioUsable = 0.25,
        maxFlatlineRatioExcellent = 0.05,
        divergenceDeltaMin = 0.2f,
        divergenceProbabilityMin = 0.65f,
        baselineWindowCount = 10
    )
}
