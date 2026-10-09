package com.stressdetector.mobile.domain

import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.shared.AccelerometerSample
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.PredictionWindowConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorePpgWindowSlicerTest {
    private val slicer = CorePpgWindowSlicer(PredictionWindowConfig())

    @Test
    fun slicesOnlyThirtyToTwoHundredSeventySeconds() {
        val elapsed = LongArray(301) { it * 1_000L }
        val source = WindowSnapshot(
            type = PpgType.GREEN,
            timestampsMillis = elapsed.copyOf(),
            rawValues = IntArray(elapsed.size) { it },
            sessionElapsedStartMillis = 0L,
            sessionElapsedEndMillis = 300_000L,
            elapsedTimestampsMillis = elapsed,
            accelerometerSamples = listOf(
                AccelerometerSample(11_000_000_000L, 10_000L, 0f, 9.8f, 0f),
                AccelerometerSample(32_000_000_000L, 31_000L, 0f, 9.8f, 0f)
            )
        )

        val result = slicer.slice(source)

        assertNotNull(result.coreWindow)
        assertEquals(30_000L, result.coreWindow!!.elapsedTimestampsMillis.first())
        assertEquals(269_000L, result.coreWindow!!.elapsedTimestampsMillis.last())
        assertEquals(240, result.coreWindow!!.size)
        assertEquals(listOf(31_000L), result.coreWindow!!.accelerometerSamples.map { it.elapsedMillis })
        assertEquals(7, result.expectedModelWindows)
        assertNull(result.rejectionReason)
    }

    @Test
    fun rejectsCoreWithoutTwoHundredFortySecondsCoverage() {
        val elapsed = LongArray(251) { it * 1_000L }
        val source = WindowSnapshot(
            type = PpgType.IR,
            timestampsMillis = elapsed.copyOf(),
            rawValues = IntArray(elapsed.size),
            elapsedTimestampsMillis = elapsed
        )

        val result = slicer.slice(source)

        assertNull(result.coreWindow)
        assertTrue(result.rejectionReason!!.startsWith("core_coverage_insufficient:"))
        assertTrue(result.diagnostics!!.rejectionReasons.any { it.startsWith("core_end_early") })
    }

    @Test
    fun tailAfterCoreMayBeMissingWithoutChangingCoreBoundary() {
        val elapsed = LongArray(289) { it * 1_000L } // actual data ends at 288 s
        val source = WindowSnapshot(
            type = PpgType.GREEN,
            timestampsMillis = elapsed.copyOf(),
            rawValues = IntArray(elapsed.size),
            elapsedTimestampsMillis = elapsed
        )

        val result = slicer.slice(source)

        assertNotNull(result.coreWindow)
        assertEquals(30_000L, result.coreWindow!!.elapsedTimestampsMillis.first())
        assertEquals(269_000L, result.coreWindow!!.elapsedTimestampsMillis.last())
    }

    @Test
    fun reportsActualCadenceGapsRawRangeAndSequenceLossWithoutChangingGate() {
        val elapsed = LongArray(241) { 30_000L + it * 1_000L }
        for (index in 121 until elapsed.size) elapsed[index] += 3_000L
        val source = WindowSnapshot(
            type = PpgType.IR,
            timestampsMillis = elapsed.copyOf(),
            rawValues = IntArray(elapsed.size) { 5_000_000 + it },
            elapsedTimestampsMillis = elapsed
        )

        val result = slicer.slice(source, transportMissingSequenceCount = 2L)

        val diagnostics = result.diagnostics!!
        assertEquals(2L, diagnostics.transportMissingSequenceCount)
        assertTrue(diagnostics.actualCadenceHz!! > 0f)
        assertTrue(diagnostics.significantGapCount > 0)
        assertEquals(5_000_000, diagnostics.rawMinimum)
        assertEquals(5_000_236, diagnostics.rawMaximum)
    }
}
