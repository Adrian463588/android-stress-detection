package com.stressdetector.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotonicSessionClockTest {
    @Test
    fun elapsedUsesMonotonicSourceAndIsCappedAtSessionDuration() {
        var now = 10_000L
        val clock = MonotonicSessionClock(nowMillis = { now })
        clock.start("session")

        now += 12_000L
        assertEquals(12_000L, clock.elapsed("session"))

        now += 300_000L
        assertTrue(clock.isComplete("session"))
        assertEquals(300_000L, clock.elapsed("session"))
    }

    @Test
    fun stoppingSessionMakesFurtherElapsedReadsZero() {
        var now = 100L
        val clock = MonotonicSessionClock(nowMillis = { now })
        clock.start("session")
        now += 1_000L
        clock.stop("session")

        assertEquals(0L, clock.elapsed("session"))
    }
}
