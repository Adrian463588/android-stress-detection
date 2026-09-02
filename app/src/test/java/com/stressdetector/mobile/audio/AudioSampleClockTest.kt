package com.stressdetector.mobile.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSampleClockTest {
    @Test
    fun variableReadSizesKeepSampleClockMonotonicAndPauseGapExplicit() {
        val clock = AudioSampleClock(sampleRateHz = 1_000)

        val first = clock.next(125)
        val second = clock.next(275)
        val gap = clock.addGap(2_000L, "PAUSE")
        val third = clock.next(50)

        assertEquals(0L, first.sampleStartIndex)
        assertEquals(0L, first.startOffsetMs)
        assertEquals(125L, second.sampleStartIndex)
        assertEquals(125L, second.startOffsetMs)
        assertEquals(400L, gap.startOffsetMs)
        assertEquals(2_000L, gap.durationMs)
        assertEquals(400L, third.sampleStartIndex)
        assertEquals(2_400L, third.startOffsetMs)
    }
}
