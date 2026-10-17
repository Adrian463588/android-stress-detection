package com.stressdetector.mobile.audio

import com.stressdetector.mobile.domain.engine.SpeechDecoderEvidence
import com.stressdetector.mobile.domain.engine.SpeechDecoderResult
import com.stressdetector.mobile.domain.engine.SpeechRecognitionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Decoder strings here test bookkeeping only, not recognition or speaker accuracy. */
class DiarizedRecognitionTest {
    private val whole = SpeechDecoderResult("test-decoder", "mechanics-only", 0, 4_000, "  whole decoder output\n")

    @Test
    fun `uncovered gaps are never decoded and blank crop retains the other speaker`() {
        val calls = mutableListOf<Pair<Long, Long>>()
        val results = DiarizedUtterancePlanner.recognize(
            listOf(turn(100, 1_800, 0), turn(2_000, 3_500, 1)),
            SpeechDecoderEvidence(whole),
            true
        ) { span ->
            calls += span.startOffsetMs to span.endOffsetMs
            whole.copy(
                startOffsetMs = span.startOffsetMs,
                endOffsetMs = span.endOffsetMs,
                rawText = if (span.stableSpeakerIndex == 0) "  first crop\n" else ""
            )
        }
        assertEquals(listOf(100L to 1_800L, 2_000L to 3_500L), calls)
        assertEquals(listOf(0, 1), results.map { it.turn.stableSpeakerIndex })
        assertEquals(listOf("  first crop\n", ""), results.map { it.rawText })
        assertEquals(listOf(SpeechRecognitionStatus.DECODED, SpeechRecognitionStatus.EMPTY_CROP), results.map { it.evidence.status })
        results.forEach { assertEquals(whole, it.evidence.wholeUtterance) }
        assertEquals(2_000L, results.last().evidence.crop!!.startOffsetMs)
        assertEquals(3_500L, results.last().evidence.crop!!.endOffsetMs)
    }

    @Test
    fun `blank final terminates without borrowing or erasing the preceding partial`() {
        val partial = whole.copy(backend = "streaming-test", rawText = "  earlier hypothesis  ")
        val evidence = SpeechDecoderEvidence(whole.copy(rawText = "\n "), precedingPartial = partial)
        val result = DiarizedUtterancePlanner.recognize(listOf(turn(100, 3_500, 0)), evidence, true) {
            error("Blank final must not request another decode")
        }.single()
        assertEquals(SpeechRecognitionStatus.EMPTY_FINAL, result.evidence.status)
        assertEquals("\n ", result.rawText)
        assertEquals(partial, result.evidence.precedingPartial)
        assertNull(result.turn.stableSpeakerIndex)
    }

    @Test
    fun `only identical complete bounds reuse the whole decoder text`() {
        val complete = DiarizedUtterancePlanner.recognize(listOf(turn(0, 4_000, 0)), SpeechDecoderEvidence(whole), true) {
            error("Identical complete bounds reuse actual whole output")
        }.single()
        assertEquals(whole.rawText, complete.rawText)
        assertNull(complete.evidence.crop)
        val cropped = DiarizedUtterancePlanner.recognize(listOf(turn(500, 3_500, 0)), SpeechDecoderEvidence(whole), true) {
            whole.copy(startOffsetMs = it.startOffsetMs, endOffsetMs = it.endOffsetMs, rawText = "actual crop output")
        }.single()
        assertEquals("actual crop output", cropped.rawText)
        assertEquals(whole, cropped.evidence.wholeUtterance)
    }

    @Test
    fun `native speech with unresolved identity is retained while gaps remain absent`() {
        val results = DiarizedUtterancePlanner.recognize(
            listOf(turn(100, 2_000, 0), turn(1_000, 3_000, 1)),
            SpeechDecoderEvidence(whole),
            true
        ) { whole.copy(startOffsetMs = it.startOffsetMs, endOffsetMs = it.endOffsetMs, rawText = "crop") }
        assertEquals(
            listOf(100L to 1_000L, 1_000L to 2_000L, 2_000L to 3_000L),
            results.map { it.turn.startOffsetMs to it.turn.endOffsetMs }
        )
        assertEquals(listOf(0, null, 1), results.map { it.turn.stableSpeakerIndex })
        val unresolved = DiarizedUtterancePlanner.recognize(
            listOf(StableDiarizationSegment(500, 1_500, 7, null)),
            SpeechDecoderEvidence(whole),
            true
        ) { whole.copy(startOffsetMs = it.startOffsetMs, endOffsetMs = it.endOffsetMs, rawText = "unresolved speech") }.single()
        assertNull(unresolved.turn.stableSpeakerIndex)
        assertEquals(500L, unresolved.turn.startOffsetMs)
        assertEquals(1_500L, unresolved.turn.endOffsetMs)
    }

    @Test
    fun `missing native spans keep reviewable whole evidence without guessing a speaker`() {
        val evidence = SpeechDecoderEvidence(whole)
        val result = DiarizedUtterancePlanner.recognize(emptyList(), evidence, true) { error("No native speech crop") }.single()
        assertEquals(SpeechRecognitionStatus.SPEAKER_SPANS_UNAVAILABLE, result.evidence.status)
        assertEquals(whole.rawText, result.rawText)
        assertNull(result.turn.stableSpeakerIndex)
        val withoutDiarization = DiarizedUtterancePlanner.recognize(emptyList(), evidence, false) { error("No crop") }.single()
        assertEquals(SpeechRecognitionStatus.DECODED, withoutDiarization.evidence.status)
    }

    private fun turn(start: Long, end: Long, identity: Int) = StableDiarizationSegment(start, end, identity, identity)
}
