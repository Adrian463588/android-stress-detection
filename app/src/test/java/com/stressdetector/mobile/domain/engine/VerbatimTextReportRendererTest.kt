package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.db.DivergenceEventEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerbatimTextReportRendererTest {
    private val renderer = VerbatimTextReportRenderer()

    @Test
    fun `export includes observed utterance pitch and marks unavailable without zero replacement`() {
        val observed = speech("observed", 1_000, "mungkin").copy(
            prosody = VerbatimProsodyEvidence(
                mapOf("F0semitoneFrom27.5Hz_sma3nz" to 24.0),
                frameCount = 98,
                voicedFrameCount = 90
            )
        )
        val unavailable = speech(
            "unavailable",
            3_000,
            "maybe"
        ).copy(prosody = VerbatimProsodyEvidence(unavailableReason = "native unavailable"))
        val report = renderer.render(detail(listOf(observed, unavailable)))
        assertTrue(report.contains("F0 rerata geometrik ujaran 110.0000 Hz"))
        assertTrue(report.contains("pitch unavailable: native unavailable"))
        assertFalse(report.contains("ujaran 0.0000 Hz"))
        assertTrue(report.contains("bukan trace F0 lengkap"))
    }

    @Test
    fun `export preserves current edited verbatim and exact cue spans without erasing filler`() {
        val segment = speech("first", 1_200, "  hmm\n Mungkin  saya lupa. ")
        val edited = speech("last", 5_000, "raw evidence").copy(editedText = "I am not sure.", language = "en")
        val report = renderer.render(detail(listOf(edited, segment, speech("partial", 9_000, "not final").copy(isFinal = false))))
        assertTrue(report.contains("hmm Mungkin saya lupa."))
        assertTrue(report.contains("[Jeda terisi] «hmm»"))
        assertTrue(report.contains("[Hedging] «Mungkin»; karakter 4..11"))
        assertTrue(report.contains("Speaker_0 [EDIT]"))
        assertTrue(report.contains("USER_EDITED"))
        assertTrue(report.indexOf("hmm Mungkin") < report.indexOf("I am not sure."))
        assertFalse(report.contains("not final"))
        assertFalse(report.contains("raw evidence"))
        assertTrue(report.contains("bukan timestamp per kata"))
    }

    @Test
    fun `physiology and acoustic evidence stay separate with no fabricated probability`() {
        val marker = DivergenceEventEntity(
            "event", "session", 4_000, null, null, null, "openSMILE pitch change", "AUDIO",
            markerType = "PROSODY_DIVERGENCE", episodeStartElapsedMillis = 4_000, episodeEndElapsedMillis = 6_000
        )
        val report = renderer.render(
            detail(listOf(speech("first", 1_000, "maybe"))).copy(divergenceEvents = listOf(marker)),
            listOf(
                DomainAnnotation("note", "session", 5_000, "Pertanyaan tindak lanjut", "user"),
                DomainAnnotation("other", "other-session", 2_000, "private-other-session", "user")
            )
        )
        assertTrue(report.contains("PROSODY_DIVERGENCE; kanal=AUDIO"))
        assertTrue(report.contains("Pertanyaan tindak lanjut"))
        assertFalse(report.contains("private-other-session"))
        assertFalse(report.contains("Probabilitas model ONNX:"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty final transcript is not exported as success`() {
        renderer.render(detail(emptyList()))
    }

    @Test
    fun `review report retains exact decoder evidence and does not label suspected output as a cue`() {
        val raw = "  mungkin mungkin mungkin mungkin\n"
        val whole = SpeechDecoderResult("whisper_tiny_int8", "pinned-revision", 0, 4_000, raw)
        val evidence = SpeechDecoderEvidence(whole, crop = whole.copy(startOffsetMs = 100, endOffsetMs = 1_800))
        val segment = speech("review", 100, raw).copy(endOffsetMs = 1_800, decoderEvidence = evidence)
        val report = renderer.render(detail(listOf(segment)))
        assertTrue(report.contains("[TINJAUAN ASR]"))
        assertTrue(report.contains("Ujaran utuh [00:00:00.000–00:00:04.000]"))
        assertTrue(report.contains("Crop [00:00:00.100–00:00:01.800]"))
        assertTrue(report.contains("backend=whisper_tiny_int8; revision=pinned-revision"))
        assertTrue(report.contains(raw))
        assertFalse(report.contains("[Hedging]"))
        assertFalse(report.contains("[Pengulangan]"))
    }

    @Test
    fun `blank terminal evidence can be exported without inventing a final sentence`() {
        val whole = SpeechDecoderResult("whisper_tiny_int8", "pinned-revision", 1_000, 3_000, "")
        val evidence = SpeechDecoderEvidence(
            whole,
            precedingPartial = whole.copy(backend = "zipformer2_transducer", rawText = "earlier hypothesis"),
            status = SpeechRecognitionStatus.EMPTY_FINAL
        )
        val report = renderer.render(detail(listOf(speech("empty", 1_000, "").copy(decoderEvidence = evidence))))
        assertTrue(report.contains("[TINJAUAN ASR] Hasil akhir ASR kosong"))
        assertTrue(report.contains("[Bukti decoder] EMPTY_FINAL"))
        assertTrue(report.contains("Partial terakhir, bukan teks final"))
        assertTrue(report.contains("earlier hypothesis"))
        assertFalse(report.contains("[Hedging]"))
    }

    @Test
    fun `blank final with unavailable recognition evidence exports the explicit failure`() {
        val reason = "Arsip bukti native tidak tersedia."
        val segment = speech("unavailable", 1_000, "").copy(recognitionEvidenceUnavailableReason = reason)
        val report = renderer.render(detail(listOf(segment)))
        assertTrue(report.contains("[TINJAUAN ASR] $reason"))
        assertFalse(report.contains("[Bukti decoder]"))
        assertFalse(report.contains("[Hedging]"))
    }

    private fun speech(id: String, start: Long, text: String) =
        SpeechSegment(id, "Speaker_0", start, start + 2_000, start, start + 2_000, text, 0.9f)
    private fun detail(transcript: List<SpeechSegment>) = VerbatimSessionDetail(
        VerbatimSessionSummary("session", "Wawancara", 0, 10_000, 10, "", "", "FAILED_WITH_EVIDENCE", "UNAVAILABLE"),
        transcript,
        emptyList()
    )
}
