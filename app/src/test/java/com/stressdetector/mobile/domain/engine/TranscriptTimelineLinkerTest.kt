package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.shared.PpgType
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTimelineLinkerTest {
    @Test
    fun `given unknown persisted marker metadata when linking then preserve metadata without channel coercion`() {
        val event = DivergenceEventEntity(
            eventId = "future-marker",
            sessionId = "session",
            timestampMs = 1_000L,
            delta = null,
            currentProbability = null,
            baselineProbability = null,
            copyText = "Future marker",
            channel = "FUTURE_CHANNEL",
            markerElapsedMillis = 1_000L,
            contextWindowStartElapsedMillis = 900L,
            contextWindowEndElapsedMillis = 1_100L,
            markerType = "FUTURE_MARKER"
        )
        val segment = SpeechSegment(
            id = "segment",
            speakerId = "Speaker_0",
            startOffsetMs = 900L,
            endOffsetMs = 1_100L,
            startTimestampUtc = 0L,
            endTimestampUtc = 0L,
            rawText = "review this utterance",
            confidence = 1f,
            sessionId = "session"
        )

        assertEquals("segment", TranscriptTimelineLinker.link(event, listOf(segment), toleranceMillis = 0L))
        assertEquals("FUTURE_CHANNEL", event.channel)
        assertEquals("FUTURE_MARKER", event.markerType)
    }

    @Test
    fun `links canonical ppg marker to audio segment after startup skew`() {
        var monotonic = 0L
        val timeline = SessionTimeline.start("session", { 1_000L }, { monotonic })
        monotonic = 2_000L
        timeline.markAudioStarted()
        monotonic = 7_000L
        timeline.bindPpgClock(1_000L)
        val segment = SpeechSegment(
            id = "segment",
            speakerId = "Speaker_0",
            startOffsetMs = 14_000L,
            endOffsetMs = 16_000L,
            startTimestampUtc = 0L,
            endTimestampUtc = 0L,
            rawText = "test",
            confidence = 1f,
            sessionId = "session"
        )
        val marker = DivergenceEvent(
            id = "marker",
            sessionId = "session",
            timestampMs = 17_000L,
            currentStressProbability = 0.8f,
            baselineStressProbability = 0.2f,
            divergenceDelta = 0.6f,
            isElevatedArousal = true,
            channel = PpgType.GREEN,
            markerElapsedMillis = 17_000L,
            contextWindowStartElapsedMillis = 16_000L,
            contextWindowEndElapsedMillis = 18_000L
        )

        assertEquals(
            "segment",
            TranscriptTimelineLinker.link(marker, listOf(segment), toleranceMillis = 0L, timeline = timeline)
        )
    }

    @Test
    fun `does not link a marker to another session`() {
        val marker = DivergenceEvent(
            id = "marker",
            sessionId = "session-a",
            timestampMs = 1_000L,
            currentStressProbability = 0.8f,
            baselineStressProbability = 0.2f,
            divergenceDelta = 0.6f,
            isElevatedArousal = true,
            channel = PpgType.GREEN,
            markerElapsedMillis = 1_000L,
            contextWindowStartElapsedMillis = 900L,
            contextWindowEndElapsedMillis = 1_100L
        )
        val segment = SpeechSegment(
            id = "other-session-segment",
            speakerId = "Speaker_0",
            startOffsetMs = 900L,
            endOffsetMs = 1_100L,
            startTimestampUtc = 0L,
            endTimestampUtc = 0L,
            rawText = "wrong session",
            confidence = 1f,
            sessionId = "session-b"
        )

        assertEquals(null, TranscriptTimelineLinker.link(marker, listOf(segment), toleranceMillis = 0L))
    }
}
