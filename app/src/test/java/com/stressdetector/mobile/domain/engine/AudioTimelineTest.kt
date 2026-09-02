package com.stressdetector.mobile.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTimelineTest {
    @Test
    fun mapsSessionOffsetsAcrossPauseToCompressedWavOffsets() {
        val timeline = AudioTimeline(
            listOf(AudioGap(startOffsetMs = 1_000L, durationMs = 500L, reason = "PAUSE"))
        )

        assertEquals(900L, timeline.sessionOffsetToAudioOffset(900L))
        assertEquals(1_000L, timeline.sessionOffsetToAudioOffset(1_000L))
        assertEquals(1_000L, timeline.sessionOffsetToAudioOffset(1_250L))
        assertEquals(1_000L, timeline.sessionOffsetToAudioOffset(1_500L))
        assertEquals(1_100L, timeline.sessionOffsetToAudioOffset(1_600L))
        assertEquals(1_500L, timeline.audioOffsetToSessionOffset(1_000L))
        assertEquals(1_600L, timeline.audioOffsetToSessionOffset(1_100L))
        assertEquals(1_500L, timeline.sessionDurationForAudio(1_000L))
    }

    @Test
    fun invalidOrEmptyGapsDoNotCreateNegativePlaybackOffsets() {
        val timeline = AudioTimeline(
            listOf(
                AudioGap(-1L, 50L, "INVALID"),
                AudioGap(10L, 0L, "EMPTY"),
                AudioGap(20L, 10L, "VALID")
            )
        )

        assertEquals(0L, timeline.sessionOffsetToAudioOffset(0L))
        assertEquals(20L, timeline.sessionOffsetToAudioOffset(30L))
    }
}
