package com.stressdetector.mobile.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranscriptReducerTest {
    @Test
    fun partialEventsAreReplacedByFinalAndLateSessionEventsAreIgnored() {
        val reducer = LiveTranscriptReducer()
        val sessionId = "session-current"
        val state = LiveTranscriptState(sessionId = sessionId)
        val partial = segment(sessionId, "halo", isFinal = false)
        val updated = reducer.accept(state, partial, sessionId)
        val final = reducer.accept(updated, partial.copy(rawText = "halo semua", isFinal = true), sessionId)

        assertEquals(1, final.segments.size)
        assertEquals("halo semua", final.segments.single().rawText)
        assertTrue(final.segments.single().isFinal)
        assertEquals("Unknown", final.segments.single().displaySpeakerLabel)

        val late = reducer.accept(
            final,
            segment("session-old", "event lama", isFinal = true),
            sessionId
        )
        assertEquals(final, late)
    }

    @Test
    fun provisionalSegmentNeverClaimsSpeakerIdentity() {
        val provisional = segment("session-current", "halo", isFinal = false).copy(speakerId = "Speaker_0")

        assertEquals("Diarisasi sedang dianalisis", provisional.displaySpeakerLabel)
    }

    @Test
    fun speakerOverrideSurvivesPartialAndFinalEngineReplacement() {
        val reducer = LiveTranscriptReducer()
        val sessionId = "session-current"
        reducer.setSpeakerOverride("utterance-1", "Counselor")

        val partial = reducer.accept(
            LiveTranscriptState(sessionId = sessionId),
            segment(sessionId, "halo", isFinal = false),
            sessionId
        )
        val final = reducer.accept(
            partial,
            segment(sessionId, "halo semua", isFinal = true).copy(speakerId = "Unknown"),
            sessionId
        )

        assertEquals("Counselor", final.segments.single().speakerId)
        assertEquals("halo semua", final.segments.single().rawText)
    }

    @Test
    fun textOverrideSurvivesPartialAndFinalEngineReplacement() {
        val reducer = LiveTranscriptReducer()
        val sessionId = "session-current"
        reducer.setTextOverride("utterance-1", "edited transcript")

        val partial = reducer.accept(
            LiveTranscriptState(sessionId = sessionId),
            segment(sessionId, "partial", isFinal = false),
            sessionId
        )
        val final = reducer.accept(
            partial,
            segment(sessionId, "final", isFinal = true),
            sessionId
        )

        assertEquals("final", final.segments.single().rawText)
        assertEquals("edited transcript", final.segments.single().displayText)
    }

    private fun segment(sessionId: String, text: String, isFinal: Boolean): SpeechSegment = SpeechSegment(
        id = "utterance-1",
        speakerId = "Unknown",
        startOffsetMs = 0L,
        endOffsetMs = 500L,
        startTimestampUtc = 1_000L,
        endTimestampUtc = 1_500L,
        rawText = text,
        confidence = 0f,
        language = "id",
        sessionId = sessionId,
        isFinal = isFinal
    )
}
