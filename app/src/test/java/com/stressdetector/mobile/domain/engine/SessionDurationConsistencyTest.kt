package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.ui.screens.verbatimSignalViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SessionDurationConsistencyTest {

    @Test
    fun timelineActiveRecordingSubtractsPauseIntervalsAccurately() {
        var monoNow = 10_000L
        val timeline = SessionTimeline.start("s-pause", utcNowMillis = { 1_000_000L }, monotonicNowMillis = { monoNow })

        monoNow = 25_000L // 15s active
        timeline.recordPauseStart(15_000L)
        monoNow = 40_000L // 15s paused
        timeline.recordPauseEnd(30_000L)
        monoNow = 58_000L // +18s active -> total active = 33s

        assertEquals(48_000L, timeline.currentElapsedMillis())
        assertEquals(33_000L, timeline.activeRecordingMillis(48_000L))
    }

    @Test
    fun archiveViewportClampsToActualRecordingDurationInsteadOfHardcodedSixtySeconds() {
        val observations = listOf(
            LivePitchObservation(
                sessionId = "s1",
                startOffsetMs = 0L,
                endOffsetMs = 15_000L,
                frequencyHz = 180.0,
                status = LivePitchStatus.AVAILABLE,
                voicedFrameCount = 10,
                frameCount = 10
            ),
            LivePitchObservation(
                sessionId = "s1",
                startOffsetMs = 15_000L,
                endOffsetMs = 33_000L,
                frequencyHz = 195.0,
                status = LivePitchStatus.AVAILABLE,
                voicedFrameCount = 10,
                frameCount = 10
            )
        )

        val viewport = verbatimSignalViewport(
            observations = observations,
            isArchive = true,
            archiveStartOffsetMs = 0L,
            totalDurationMs = 33_000L
        )
        assertNotNull(viewport)
        assertEquals(0L, viewport!!.startOffsetMs)
        assertEquals(33_000L, viewport.endOffsetMs)
    }
}
