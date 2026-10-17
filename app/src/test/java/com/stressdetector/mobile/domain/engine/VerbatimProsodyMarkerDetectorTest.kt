package com.stressdetector.mobile.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VerbatimProsodyMarkerDetectorTest {
    @Test
    fun `warmup missing native feature provisional speaker and unvoiced evidence abstain`() {
        val detector = VerbatimProsodyMarkerDetector(warmupUtterances = 2)
        assertNull(detector.analyze(segment("unknown", 0, 30.0).copy(speakerId = "Unknown")))
        assertNull(detector.analyze(segment("partial", 0, 30.0).copy(isFinal = false)))
        assertNull(detector.analyze(segment("missing", 0, 30.0).copy(prosody = null)))
        assertNull(detector.analyze(segment("silent", 0, 30.0).copy(prosody = VerbatimProsodyEvidence(frameCount = 300))))
        assertNull(detector.analyze(segment("base1", 0, 30.0)))
        assertNull(detector.analyze(segment("base2", 3_000, 30.0)))
        val marker = detector.analyze(segment("change", 6_000, 36.0))
        assertNotNull(marker)
        assertEquals(30.0, marker!!.baselineSemitones, 0.0)
    }

    @Test
    fun `speaker baseline hysteresis cooldown and session reset stay independent`() {
        val detector = VerbatimProsodyMarkerDetector(warmupUtterances = 2)
        detector.analyze(segment("base1", 0, 30.0))
        detector.analyze(segment("base2", 3_000, 30.0))
        assertNull(detector.analyze(segment("other", 6_000, 40.0).copy(speakerId = "Speaker_1")))
        assertNotNull(detector.analyze(segment("rise", 9_000, 36.0)))
        assertNull(detector.analyze(segment("sustained", 12_000, 36.0)))
        assertNull(detector.analyze(segment("recover", 15_000, 30.0)))
        assertNull(detector.analyze(segment("cooldown", 18_000, 36.0)))
        assertNotNull(detector.analyze(segment("second", 21_000, 36.0)))
        assertNull(detector.analyze(segment("new-session", 0, 40.0).copy(sessionId = "next")))
    }

    @Test
    fun `questionable raw speech cannot warm up a speaker baseline`() {
        val detector = VerbatimProsodyMarkerDetector(warmupUtterances = 2)
        val repeated = "kemudian kemudian kemudian kemudian"
        assertNull(detector.analyze(segment("review1", 0, 30.0).copy(rawText = repeated)))
        assertNull(detector.analyze(segment("review2", 3_000, 30.0).copy(rawText = repeated)))
        assertNull(detector.analyze(segment("base", 6_000, 30.0)))
        assertNull(detector.analyze(segment("rise-before-warmup", 9_000, 36.0)))
    }

    @Test
    fun `recognition review never consumes an elevated marker or its cooldown`() {
        val repeated = "kemudian kemudian kemudian kemudian"
        val whole = SpeechDecoderResult("whisper_tiny_int8", "a".repeat(40), 6_000, 9_000, "spoken output")
        val questionable = listOf(
            segment("repeated", 6_000, 36.0).copy(rawText = repeated),
            segment("blank-legacy", 6_000, 36.0).copy(rawText = ""),
            segment("empty-final", 6_000, 36.0).copy(
                rawText = "",
                decoderEvidence = SpeechDecoderEvidence(whole.copy(rawText = ""), status = SpeechRecognitionStatus.EMPTY_FINAL)
            ),
            segment("empty-crop", 6_000, 36.0).copy(
                rawText = "",
                decoderEvidence = SpeechDecoderEvidence(
                    whole,
                    crop = whole.copy(rawText = ""),
                    status = SpeechRecognitionStatus.EMPTY_CROP
                )
            )
        )
        questionable.forEach { candidate ->
            val detector = VerbatimProsodyMarkerDetector(warmupUtterances = 2)
            detector.analyze(segment("base1", 0, 30.0))
            detector.analyze(segment("base2", 3_000, 30.0))
            assertNull(candidate.id, detector.analyze(candidate))
            val accepted = detector.analyze(segment("accepted", 9_000, 36.0))
            assertNotNull(candidate.id, accepted)
            assertEquals(30.0, accepted!!.baselineSemitones, 0.0)
        }
    }

    @Test
    fun `explicit edited evidence can qualify without rewriting questionable raw speech`() {
        val detector = VerbatimProsodyMarkerDetector(warmupUtterances = 2)
        detector.analyze(segment("base1", 0, 30.0))
        detector.analyze(segment("base2", 3_000, 30.0))
        val raw = "kemudian kemudian kemudian kemudian"
        val edited = segment("edited", 6_000, 36.0).copy(rawText = raw, editedText = "Koreksi manual berdasarkan audio.")
        assertNotNull(detector.analyze(edited))
        assertEquals(raw, edited.rawText)
    }

    private fun segment(id: String, start: Long, pitch: Double) = SpeechSegment(
        id, "Speaker_0", start, start + 3_000, start, start + 3_000, "kalimat", 0.9f,
        sessionId = "session",
        prosody = VerbatimProsodyEvidence(mapOf("F0semitoneFrom27.5Hz_sma3nz" to pitch), 300, 200)
    )
}
