package com.stressdetector.mobile.domain.engine

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechRecognitionReviewTest {
    @Test
    fun `repeated phrases and words require review without a phrase blacklist or text rewrite`() {
        listOf(
            "  terima kasih. terima kasih. terima kasih. terima kasih.\n",
            "kemudian kemudian kemudian kemudian",
            "thank you thank you thank you thank you",
            "alpha beta alpha beta alpha beta alpha beta"
        ).forEach { raw ->
            val segment = segment(raw)
            assertNotNull(segment.recognitionReviewReason)
            assertEquals(raw, segment.rawText)
            assertEquals(raw, segment.displayText)
        }
        assertNull(segment("Terima kasih, kemudian saya pulang.").recognitionReviewReason)
        assertNull(segment("Saya, saya melihat anak anak jalan jalan.").recognitionReviewReason)
    }

    @Test
    fun `terminal status and whole decoder output remain reviewable after archive round trip`() {
        val whole = SpeechDecoderResult("whisper_tiny_int8", "revision", 0, 4_000, "kemudian kemudian kemudian kemudian")
        val evidence = SpeechDecoderEvidence(
            whole,
            crop = whole.copy(startOffsetMs = 100, endOffsetMs = 1_800, rawText = "mungkin"),
            precedingPartial = whole.copy(backend = "zipformer2_transducer", rawText = "partial"),
            streamingFinal = whole.copy(backend = "zipformer2_transducer", rawText = "streaming final")
        )
        val restored = Gson().fromJson(Gson().toJson(evidence), SpeechDecoderEvidence::class.java)
        assertEquals(evidence, restored)
        assertNotNull(segment("mungkin").copy(decoderEvidence = restored).recognitionReviewReason)
        listOf(SpeechRecognitionStatus.EMPTY_FINAL, SpeechRecognitionStatus.EMPTY_CROP, SpeechRecognitionStatus.SPEAKER_SPANS_UNAVAILABLE)
            .forEach { status ->
                assertNotNull(segment("").copy(decoderEvidence = restored.copy(status = status)).recognitionReviewReason)
            }
    }

    @Test
    fun `explicit edits are analyzable while raw evidence stays unchanged`() {
        val raw = "mungkin mungkin mungkin mungkin"
        val original = segment(raw)
        val edited = original.copy(editedText = "Saya tidak yakin.")
        assertNotNull(original.recognitionReviewReason)
        assertNull(edited.recognitionReviewReason)
        assertEquals(raw, edited.rawText)
        assertEquals("Saya tidak yakin.", edited.displayText)
    }

    @Test
    fun `blank raw text without decoder metadata remains reviewable including empty edits`() {
        val legacy = segment(" \n")
        assertNotNull(legacy.recognitionReviewReason)
        assertNotNull(legacy.copy(editedText = "").recognitionReviewReason)
        assertNotNull(legacy.copy(editedText = " \n").recognitionReviewReason)
        assertNull(legacy.copy(editedText = "Koreksi manual berdasarkan audio.").recognitionReviewReason)
        assertEquals(" \n", legacy.rawText)
    }

    private fun segment(raw: String) = SpeechSegment("utterance", "Unknown", 0, 4_000, 0, 4_000, raw, 1f)
}
