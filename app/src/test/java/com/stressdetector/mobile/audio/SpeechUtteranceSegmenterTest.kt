package com.stressdetector.mobile.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechUtteranceSegmenterTest {
    @Test
    fun silenceEndpointKeepsPreRollSpeechAndTrailingFramesTogether() {
        val segmenter = SpeechUtteranceSegmenter(
            sampleRateHz = 1_000,
            frameDurationMs = 100L,
            preRollMs = 300L,
            trailingSilenceMs = 300L,
            maxUtteranceMs = 10_000L
        )
        val frame = FloatArray(100)
        var offset = 0L
        repeat(3) {
            segmenter.append(frame, speechDetected = false, frameStartOffsetMs = offset)
            offset += 100L
        }
        repeat(3) {
            segmenter.append(frame, speechDetected = true, frameStartOffsetMs = offset)
            offset += 100L
        }
        var result: SpeechUtterance? = null
        repeat(3) {
            result = segmenter.append(frame, speechDetected = false, frameStartOffsetMs = offset)
            offset += 100L
        }

        assertNotNull(result)
        assertEquals(900, result?.samples?.size)
        assertEquals(0L, result?.startOffsetMs)
    }

    @Test
    fun maxUtteranceIsBoundedWithoutTwoSecondChunking() {
        val segmenter = SpeechUtteranceSegmenter(
            sampleRateHz = 1_000,
            frameDurationMs = 100L,
            preRollMs = 300L,
            trailingSilenceMs = 900L,
            maxUtteranceMs = 1_000L
        )
        val frame = FloatArray(100)
        var result: SpeechUtterance? = null
        repeat(20) { index ->
            result = segmenter.append(frame, speechDetected = true, frameStartOffsetMs = index * 100L)
            if (result != null) return@repeat
        }

        assertNotNull(result)
        assertEquals(1_000, result?.samples?.size)
    }

    @Test
    fun streamingEmissionIncludesPreRollExactlyOnceAndFinalizesOneUtterance() {
        val segmenter = SpeechUtteranceSegmenter(
            sampleRateHz = 1_000,
            preRollMs = 300L,
            trailingSilenceMs = 300L,
            maxUtteranceMs = 10_000L
        )
        val emissions = mutableListOf<SpeechSegmenterEmission>()
        var offset = 0L
        repeat(3) {
            emissions += segmenter.appendStreaming(
                SpeechFrame(
                    samples = FloatArray(100),
                    startOffsetMs = offset,
                    durationMs = 100L,
                    sequence = it.toLong()
                ),
                speechDetected = false
            )
            offset += 100L
        }
        emissions += segmenter.appendStreaming(
            SpeechFrame(
                samples = FloatArray(100),
                startOffsetMs = offset,
                durationMs = 100L,
                sequence = 3L
            ),
            speechDetected = true
        )

        val onset = emissions.last()
        assertEquals(listOf(0L, 100L, 200L, 300L), onset.framesToTranscribe.map { it.startOffsetMs })

        offset += 100L
        repeat(3) { index ->
            emissions += segmenter.appendStreaming(
                SpeechFrame(
                    samples = FloatArray(100),
                    startOffsetMs = offset,
                    durationMs = 100L,
                    sequence = (4 + index).toLong()
                ),
                speechDetected = false
            )
            offset += 100L
        }

        val completed = emissions.mapNotNull { it.completedUtterance }
        assertEquals(1, completed.size)
        assertEquals(700, completed.single().samples.size)
        assertTrue(
            emissions.flatMap { it.framesToTranscribe.map(SpeechFrame::startOffsetMs) }
                .count { it == 0L } == 1
        )
    }
}
