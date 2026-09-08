package com.stressdetector.wear.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PpgElapsedTimelineTest {
    @Test
    fun sourceTimestampKeepsSamplesInsideOneCallbackOnTheirRealTimeline() {
        val timeline = PpgElapsedTimeline(sessionStartEpochMillis = 1_700_000_000_000L)

        assertEquals(0L, timeline.elapsed(1_700_000_000_000L))
        assertEquals(40L, timeline.elapsed(1_700_000_000_040L))
        assertEquals(80L, timeline.elapsed(1_700_000_000_080L))
    }

    @Test
    fun sourceClockBeforeSessionStartIsClampedWithoutInventingSamples() {
        val timeline = PpgElapsedTimeline(sessionStartEpochMillis = 1_700_000_000_100L)

        assertEquals(0L, timeline.elapsed(1_700_000_000_080L))
    }
}
