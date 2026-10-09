package com.stressdetector.mobile.ui.viewmodels

import com.stressdetector.mobile.domain.engine.LiveBiosignalSnapshot
import com.stressdetector.mobile.domain.engine.LiveTranscriptState
import com.stressdetector.mobile.domain.engine.SpeechSegment
import com.stressdetector.mobile.domain.engine.UnifiedSessionLiveSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerbatimLiveReviewStateTest {
    @Test
    fun archivedSessionRejectsBufferedLiveSnapshotEvenForSameGeneration() {
        val archive = state(SessionCommandState.Idle)
        assertFalse(archive.acceptsLiveSnapshot(snapshot()))
        assertFalse(archive.copy(sessionCommandState = SessionCommandState.Error("failed")).acceptsLiveSnapshot(snapshot()))
    }

    @Test
    fun liveTransitionsAcceptOnlyCurrentSessionAndGeneration() {
        val commands = listOf(
            SessionCommandState.Starting,
            SessionCommandState.Recording,
            SessionCommandState.Pausing,
            SessionCommandState.Paused,
            SessionCommandState.Resuming,
            SessionCommandState.Stopping,
            SessionCommandState.Finalizing
        )
        commands.forEach { command -> assertTrue(state(command).acceptsLiveSnapshot(snapshot())) }
        val live = state(SessionCommandState.Recording)
        assertFalse(live.acceptsLiveSnapshot(snapshot().copy(sessionId = "old-session")))
        assertFalse(live.acceptsLiveSnapshot(snapshot().copy(transcript = LiveTranscriptState(sessionId = "old-session"))))
        assertFalse(live.acceptsLiveSnapshot(snapshot().copy(generation = 3L)))
    }

    @Test
    fun cuesAreChronologicalAndPartialUtterancesDoNotProduceMarkers() {
        val later = segment("later", 4_000L, "maybe tomorrow", "en")
        val earlier = segment("earlier", 1_000L, "mungkin besok", "id")
        val partial = earlier.copy(id = "partial", startOffsetMs = 0L, isFinal = false)
        val review = state(SessionCommandState.Recording).copy(transcriptSegments = listOf(later, partial, earlier, earlier))

        assertEquals(listOf("earlier", "later"), review.reviewCues.map { it.segmentId })
        assertEquals(listOf(1_000L, 4_000L), review.reviewCues.map { it.startOffsetMs })
        assertTrue(review.cueAnalyses.getValue("partial").cues.isEmpty())
    }

    private fun state(command: SessionCommandState) = UnifiedSessionUiState(
        selectedSessionId = "session-current",
        sessionGeneration = 4L,
        sessionCommandState = command
    )

    private fun snapshot() = UnifiedSessionLiveSnapshot(
        sessionId = "session-current",
        generation = 4L,
        biosignal = LiveBiosignalSnapshot(sessionId = "session-current", sessionGeneration = 4L),
        transcript = LiveTranscriptState(sessionId = "session-current")
    )

    private fun segment(id: String, offset: Long, text: String, language: String) = SpeechSegment(
        id = id, speakerId = "Speaker_0", startOffsetMs = offset, endOffsetMs = offset + 1_000L,
        startTimestampUtc = offset, endTimestampUtc = offset + 1_000L, rawText = text,
        confidence = 0.9f, language = language, sessionId = "session-current", isFinal = true
    )
}
