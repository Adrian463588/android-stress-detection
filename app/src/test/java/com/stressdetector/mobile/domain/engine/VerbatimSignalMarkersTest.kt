package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.db.DivergenceEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerbatimSignalMarkersTest {
    @Test
    fun alignsMeasuredMarkerUsingCaptureOriginWithoutCompressingPause() {
        val event = event().copy(episodeStartElapsedMillis = 9_137, episodeEndElapsedMillis = 10_137)
        val marker = emptyList<SpeechSegment>().verbatimSignalMarkers("session", listOf(event), 137, 0L until 60_000).single()
        assertEquals(9_000L, marker.startOffsetMs)
        assertEquals(10_000L, marker.endOffsetMs)
        assertEquals(9_000L, marker.playbackOffsetMs)
        // Playback alone converts capture time to WAV time; the chart must not apply this twice.
        assertEquals(7_000L, AudioTimeline(listOf(AudioGap(2_000, 2_000, "PAUSE"))).sessionOffsetToAudioOffset(marker.startOffsetMs))
    }

    @Test
    fun legacyUnalignedEventsAbstainOrDiscloseLinkedUtteranceRange() {
        assertTrue(emptyList<SpeechSegment>().verbatimSignalMarkers("session", listOf(event()), null, 0L until 60_000).isEmpty())
        val segment = segment()
        val linked = event().copy(transcriptSegmentId = "segment")
        val marker = listOf(segment).verbatimSignalMarkers("session", listOf(linked), null, 0L until 60_000)
            .first { it.id == "event" }
        assertEquals(5_000L, marker.startOffsetMs)
        assertTrue(marker.explanation.contains("bukan onset sensor"))
        assertTrue(listOf(segment).verbatimSignalMarkers("other", listOf(event()), 0, 0L until 60_000).isEmpty())
    }

    @Test
    fun cuesUseLiteralUtteranceRangeAndUnreviewedRepetitionCannotEmitCues() {
        val cue = listOf(segment()).verbatimSignalMarkers("session", emptyList(), 0, 0L until 60_000).first()
        assertEquals(5_000L, cue.startOffsetMs)
        assertTrue(cue.explanation.contains("bukan per kata"))
        val suspect = segment().copy(rawText = "mungkin mungkin mungkin mungkin mungkin")
        assertTrue(listOf(suspect).verbatimSignalMarkers("session", emptyList(), 0, 0L until 60_000).isEmpty())
    }

    @Test
    fun captureClockNotesAppearOnlyWhenTraceClockProvenanceExists() {
        val note = DomainAnnotation("note", "session", 9_000L, "Periksa jawaban", "user")
        val current = emptyList<SpeechSegment>().verbatimSignalMarkers("session", emptyList(), 137, 0L until 60_000, listOf(note)).single()
        assertEquals(9_000L, current.startOffsetMs)
        assertEquals(9_000L, current.playbackOffsetMs)
        assertTrue(emptyList<SpeechSegment>().verbatimSignalMarkers("session", emptyList(), null, 0L until 60_000, listOf(note)).isEmpty())
    }

    private fun segment() = SpeechSegment(
        "segment", "Speaker 1", 5_000, 7_000, 5_000, 7_000, "Mungkin saya ingat.", 0.8f, sessionId = "session"
    )
    private fun event() = DivergenceEventEntity(
        "event", "session", 9_000, null, null, null, "Periksa bukti", channel = "AUDIO", markerType = "ACOUSTIC_DIVERGENCE"
    )
}
