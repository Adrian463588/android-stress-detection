package com.stressdetector.mobile.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SessionTimelineTest {
    @Test
    fun `audio and ppg clocks map to one session elapsed timeline`() {
        var monotonic = 1_000L
        val timeline = SessionTimeline.start(
            sessionId = "session-1",
            utcNowMillis = { 10_000L },
            monotonicNowMillis = { monotonic }
        )

        monotonic = 3_000L
        timeline.markAudioStarted()
        monotonic = 8_000L
        timeline.bindPpgClock(ppgElapsedMillis = 1_000L)

        assertEquals(17_000L, timeline.audioOffsetToSessionElapsed(15_000L))
        assertEquals(17_000L, timeline.ppgElapsedToSessionElapsed(11_000L))
    }

    @Test
    fun `each session receives a distinct generation`() {
        val first = SessionTimeline.start("session-1", { 1L }, { 1L })
        val second = SessionTimeline.start("session-2", { 1L }, { 1L })

        assertNotEquals(first.generation, second.generation)
    }

    @Test
    fun `explicit capture and ready receipt anchors are not shifted by later binding`() {
        var monotonic = 1_000L
        val timeline = SessionTimeline.start(
            sessionId = "session-1",
            utcNowMillis = { 10_000L },
            monotonicNowMillis = { monotonic }
        )

        timeline.markAudioStartedAt(monotonicMillis = 4_500L)
        monotonic = 9_000L
        timeline.bindPpgClock(
            ppgElapsedMillis = 1_000L,
            receivedAtMonotonicMillis = 6_000L
        )

        assertEquals(5_500L, timeline.audioOffsetToSessionElapsed(2_000L))
        assertEquals(6_000L, timeline.ppgElapsedToSessionElapsed(2_000L))
    }
}
