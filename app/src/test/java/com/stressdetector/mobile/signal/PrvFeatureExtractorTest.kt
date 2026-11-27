package com.stressdetector.mobile.signal

import com.stressdetector.shared.PpgType
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrvFeatureExtractorTest {
    private val extractor = PrvFeatureExtractor()

    @Test
    fun uniformPulseIntervalsProduceNearZeroRmssdAndSdnn() {
        val sampleRate = 25.0
        val seconds = 30
        val n = (sampleRate * seconds).toInt()
        // Exact 75 BPM -> 0.8s period -> 20 samples per beat (sin peak at idx % 20 == 5)
        val peaks = IntArray(36) { idx -> 5 + idx * 20 }.filter { it < n }.toIntArray()
        val values = DoubleArray(n) { i -> 100.0 * sin(2.0 * Math.PI * i / 20.0) }

        val metrics = extractor.extract(
            channel = PpgType.GREEN,
            values = values,
            peaks = peaks,
            samplingRateHz = sampleRate,
            windowSeconds = seconds,
            isSqaValid = true
        )

        assertTrue(metrics.isQualityPassed)
        assertNotNull(metrics.rmssdMs)
        assertEquals(800f, metrics.meanPpiMs!!, 1f)
        assertEquals(800f, metrics.medianPpiMs!!, 1f)
        assertEquals(0f, metrics.rmssdMs!!, 1f)
        assertEquals(0f, metrics.sdnnMs!!, 1f)
        assertEquals(0f, metrics.pnn50Pct!!, 0.1f)
        assertTrue((metrics.pulseAmplitudeMedian ?: 0f) > 50f)
    }

    @Test
    fun alternatingBeatIntervalsProduceExpectedRmssdAndPnn50() {
        val sampleRate = 100.0
        // Alternate 750 ms (75 samples) and 850 ms (85 samples) -> diff = 100 ms -> RMSSD = 100 ms, pNN50 = 100%
        val peakList = mutableListOf(20)
        repeat(16) { idx ->
            val step = if (idx % 2 == 0) 75 else 85
            peakList += peakList.last() + step
        }
        val values = DoubleArray(peakList.last() + 20) { 0.0 }
        peakList.forEach { p ->
            values[p] = 50.0
            values[(p - 15).coerceAtLeast(0)] = -50.0
        }

        val metrics = extractor.extract(
            channel = PpgType.IR,
            values = values,
            peaks = peakList.toIntArray(),
            samplingRateHz = sampleRate,
            windowSeconds = 30,
            isSqaValid = true
        )

        assertTrue(metrics.isQualityPassed)
        assertTrue(abs((metrics.rmssdMs ?: 0f) - 100f) < 1f)
        assertEquals(100f, metrics.pnn50Pct!!, 0.5f)
        assertEquals(50f, metrics.sdnnMs!!, 1f)
    }

    @Test
    fun failsClosedWhenSqaFailsOrIntervalsInsufficient() {
        val rejected = extractor.extract(
            channel = PpgType.GREEN,
            values = DoubleArray(300) { 1.0 },
            peaks = intArrayOf(10, 30, 50, 70, 90, 110, 130),
            samplingRateHz = 25.0,
            windowSeconds = 12,
            isSqaValid = false
        )
        assertFalse(rejected.isQualityPassed)
        assertNull(rejected.rmssdMs)
        assertEquals("sqa_failed", rejected.insufficiencyReason)
    }
}
