package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.db.DivergenceEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStopCutoffTest {
    @Test
    fun keepsOnlySameSessionDivergencesAtOrBeforeCutoff() {
        val cutoff = SessionStopCutoff("session-1", generation = 7L, elapsedMillis = 1_000L)

        val kept = cutoff.filter(
            listOf(
                event("before", "session-1", 900L),
                event("at", "session-1", 1_000L),
                event("after", "session-1", 1_001L),
                event("other", "session-2", 100L)
            )
        )

        assertEquals(listOf("before", "at"), kept.map(DivergenceEventEntity::eventId))
    }

    @Test
    fun transcriptEventMustEndBeforeCutoff() {
        val timeline = SessionTimeline.start(
            sessionId = "session-1",
            utcNowMillis = { 0L },
            monotonicNowMillis = { 0L }
        )
        timeline.markAudioStartedAt(100L)
        val cutoff = SessionStopCutoff("session-1", timeline.generation, elapsedMillis = 1_000L)

        assertTrue(cutoff.accepts(segment("session-1", endOffsetMs = 900L), timeline))
        assertFalse(cutoff.accepts(segment("session-1", endOffsetMs = 901L), timeline))
        assertFalse(cutoff.accepts(segment("session-2", endOffsetMs = 100L), timeline))
    }

    @Test
    fun clipsEpisodeCrossingCutoffAndDropsPureTail() {
        val cutoff = SessionStopCutoff("session-1", generation = 7L, elapsedMillis = 1_000L)
        val overlapping = event("overlap", "session-1", 1_100L).copy(
            episodeStartElapsedMillis = 900L,
            episodeEndElapsedMillis = 1_100L,
            contextWindowEndElapsedMillis = 1_100L
        )
        val tail = event("tail", "session-1", 1_100L).copy(
            episodeStartElapsedMillis = 1_050L,
            episodeEndElapsedMillis = 1_100L
        )

        val kept = cutoff.filter(listOf(overlapping, tail))

        assertEquals(listOf("overlap"), kept.map(DivergenceEventEntity::eventId))
        assertEquals(1_000L, kept.single().markerElapsedMillis)
        assertEquals(1_000L, kept.single().episodeEndElapsedMillis)
        assertEquals(1_000L, kept.single().contextWindowEndElapsedMillis)
    }

    @Test
    fun transcriptEventFromDifferentTimelineGenerationIsRejected() {
        val first = SessionTimeline.start("session-1", utcNowMillis = { 0L }, monotonicNowMillis = { 0L })
        val second = SessionTimeline.start("session-1", utcNowMillis = { 0L }, monotonicNowMillis = { 0L })
        val cutoff = SessionStopCutoff("session-1", first.generation, elapsedMillis = 1_000L)

        assertFalse(cutoff.accepts(segment("session-1", endOffsetMs = 100L), second))
    }

    private fun event(id: String, sessionId: String, elapsedMillis: Long) = DivergenceEventEntity(
        eventId = id,
        sessionId = sessionId,
        timestampMs = elapsedMillis,
        delta = 0.3f,
        currentProbability = 0.7f,
        baselineProbability = 0.4f,
        copyText = "test",
        markerElapsedMillis = elapsedMillis
    )

    private fun segment(sessionId: String, endOffsetMs: Long) = SpeechSegment(
        id = "segment-$sessionId-$endOffsetMs",
        speakerId = "Unknown",
        startOffsetMs = 0L,
        endOffsetMs = endOffsetMs,
        startTimestampUtc = 0L,
        endTimestampUtc = endOffsetMs,
        rawText = "test",
        confidence = 1f,
        sessionId = sessionId
    )
}
