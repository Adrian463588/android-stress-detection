package com.stressdetector.mobile.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerbatimAudioGapTest {
    @Test
    fun `dropped transcription work does not remove recorded PCM during seek`() {
        val gaps = listOf(
            AudioGap(1_000, 500, "TRANSCRIPTION_QUEUE_FULL", recordedPcmPresent = true),
            AudioGap(3_000, 1_000, "PAUSE")
        )
        val timeline = AudioTimeline(AudioGapCodec.decode(AudioGapCodec.encode(gaps)))
        assertEquals(2_000L, timeline.sessionOffsetToAudioOffset(2_000))
        assertEquals(3_500L, timeline.sessionOffsetToAudioOffset(4_500))
        assertEquals(4_500L, timeline.audioOffsetToSessionOffset(3_500))
        assertEquals(gaps, AudioGapCodec.decode(AudioGapCodec.encode(gaps)))
    }

    @Test
    fun `legacy gaps remain readable without invented PCM provenance`() {
        val legacy = "[{\"startOffsetMs\":1000,\"durationMs\":500,\"reason\":\"PAUSE\"}]"
        val decoded = AudioGapCodec.decode(legacy)
        assertEquals(false, decoded.single().recordedPcmPresent)
        assertEquals(1_500L, AudioTimeline(decoded).sessionOffsetToAudioOffset(2_000))
    }

    @Test
    fun `marker seeks linked utterance not physiological clock and missing link abstains`() {
        val speech = SpeechSegment("utterance", "Speaker_0", 2_000, 4_000, 2_000, 4_000, "mungkin", 0.9f)
        val marker = com.stressdetector.mobile.data.db.DivergenceEventEntity(
            "marker",
            "session",
            12_000,
            null,
            null,
            null,
            "review",
            transcriptSegmentId = "utterance"
        )
        assertEquals(2_000L, TranscriptTimelineLinker.playbackOffset(marker, listOf(speech)))
        assertNull(TranscriptTimelineLinker.playbackOffset(marker.copy(transcriptSegmentId = null), listOf(speech)))
        assertNull(TranscriptTimelineLinker.playbackOffset(marker, listOf(speech.copy(isFinal = false))))
    }
}
