package com.stressdetector.mobile.domain.engine

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Metadata fixtures validate archive contracts, not native inference or model quality. */
class VerbatimDecoderArchiveTest {
    private val gson = Gson()

    @Test
    fun `legacy rows without decoder evidence return no invented metadata`() {
        val lines = sequenceOf(" ", "{\"sessionId\":\"session\"}", "{\"sessionId\":\"session\",\"decoderEvidence\":null}")
        assertEquals(emptyMap<String, SpeechDecoderEvidence>(), VerbatimProsodyArchive.parseDecoderEvidence("session", lines))
    }

    @Test
    fun `whole crop partial and streaming decoder results round trip with unchanged raw strings`() {
        val observed = evidence()
        val line = gson.toJson(decoderRow(observed))
        val restored = VerbatimProsodyArchive.parseDecoderEvidence("session", sequenceOf(line)).getValue("segment")
        assertEquals(observed, restored)
        assertEquals("  whole decoder output\n", restored.wholeUtterance.rawText)
        assertEquals("  crop output\n", restored.crop!!.rawText)
    }

    @Test
    fun `blank finals blank crops missing speech spans and both legacy native backends remain readable`() {
        val original = evidence()
        val outcomes = listOf(
            original.copy(
                wholeUtterance = original.wholeUtterance.copy(rawText = " \n"),
                crop = null,
                status = SpeechRecognitionStatus.EMPTY_FINAL
            ),
            original.copy(crop = original.crop!!.copy(rawText = ""), status = SpeechRecognitionStatus.EMPTY_CROP),
            original.copy(crop = null, status = SpeechRecognitionStatus.SPEAKER_SPANS_UNAVAILABLE)
        )
        outcomes.forEach { observed ->
            val restored = VerbatimProsodyArchive.parseDecoderEvidence("session", sequenceOf(gson.toJson(decoderRow(observed))))
            assertEquals(observed, restored.getValue("segment"))
        }
        listOf("zipformer2_transducer" to "id", "omnilingual_ctc" to "en", "whisper_tiny_int8" to "en")
            .forEach { (backend, language) ->
                val revision = if (backend == "omnilingual_ctc") "sha256:${"c".repeat(64)}" else "b".repeat(40)
                val observed = SpeechDecoderEvidence(original.wholeUtterance.copy(backend = backend, modelRevision = revision))
                val restored = VerbatimProsodyArchive.parseDecoderEvidence(
                    "session",
                    sequenceOf(gson.toJson(decoderRow(observed, language)))
                )
                assertEquals(observed, restored.getValue("segment"))
            }
    }

    @Test
    fun `missing numeric blank cross-session and duplicate identities reject`() {
        rejects { it.remove("sessionId") }
        rejects { it.addProperty("sessionId", 7) }
        rejects { it.addProperty("sessionId", "other-session") }
        rejects { it.remove("segmentId") }
        rejects { it.addProperty("segmentId", " ") }
        rejects { it.addProperty("segmentId", 7) }
        val line = gson.toJson(decoderRow())
        assertThrows(IllegalArgumentException::class.java) {
            VerbatimProsodyArchive.parseDecoderEvidence("session", sequenceOf(line, line))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VerbatimProsodyArchive.parseDecoderEvidence("", emptySequence())
        }
    }

    @Test
    fun `fractional overflow inverted mismatched and out-of-utterance offsets reject`() {
        rejects { it.addProperty("startOffsetMs", "1500") }
        rejects { it.addProperty("startOffsetMs", 1500.5) }
        rejects { it.addProperty("startOffsetMs", BigInteger("9223372036854775808")) }
        rejects { it.addProperty("endOffsetMs", 3_501) }
        rejects { it.addProperty("rawText", "different raw output") }
        rejects { it.addProperty("rawText", 7) }
        rejects { it.getAsJsonObject("decoderEvidence").getAsJsonObject("wholeUtterance").addProperty("startOffsetMs", -1) }
        rejects { it.getAsJsonObject("decoderEvidence").getAsJsonObject("wholeUtterance").addProperty("endOffsetMs", 1_000) }
        rejects { row ->
            row.addProperty("endOffsetMs", 6_000)
            row.getAsJsonObject("decoderEvidence").getAsJsonObject("crop").addProperty("endOffsetMs", 6_000)
        }
    }

    @Test
    fun `missing contradictory partial and unknown status or non-native decoders reject`() {
        rejects { it.addProperty("decoderEvidence", "not an object") }
        rejects { it.getAsJsonObject("decoderEvidence").remove("wholeUtterance") }
        rejects { it.getAsJsonObject("decoderEvidence").remove("status") }
        listOf("PARTIAL", "EMPTY_FINAL", "EMPTY_CROP", "SPEAKER_SPANS_UNAVAILABLE", "UNKNOWN").forEach { status ->
            rejects { it.getAsJsonObject("decoderEvidence").addProperty("status", status) }
        }
        rejects { it.getAsJsonObject("decoderEvidence").getAsJsonObject("wholeUtterance").addProperty("backend", "cloud_whisper") }
        rejects { it.getAsJsonObject("decoderEvidence").getAsJsonObject("wholeUtterance").addProperty("modelRevision", "") }
        rejects { it.getAsJsonObject("decoderEvidence").getAsJsonObject("crop").addProperty("modelRevision", "c".repeat(40)) }
        rejects { it.addProperty("language", "fr") }
    }

    @Test
    fun `companion decoders must use the same native language path model and actual PCM bounds`() {
        rejects { it.getAsJsonObject("decoderEvidence").getAsJsonObject("precedingPartial").addProperty("startOffsetMs", 900) }
        rejects { it.getAsJsonObject("decoderEvidence").getAsJsonObject("precedingPartial").addProperty("endOffsetMs", 6_000) }
        rejects { it.getAsJsonObject("decoderEvidence").getAsJsonObject("precedingPartial").addProperty("rawText", "") }
        rejects { it.getAsJsonObject("decoderEvidence").getAsJsonObject("precedingPartial").addProperty("backend", "whisper_tiny_int8") }
        rejects { it.getAsJsonObject("decoderEvidence").getAsJsonObject("streamingFinal").addProperty("endOffsetMs", 4_000) }
        rejects { it.getAsJsonObject("decoderEvidence").getAsJsonObject("streamingFinal").addProperty("modelRevision", "c".repeat(40)) }
        rejects { it.addProperty("language", "en") }
    }

    private fun rejects(change: (JsonObject) -> Unit) {
        val row = decoderRow()
        change(row)
        assertThrows(IllegalArgumentException::class.java) {
            VerbatimProsodyArchive.parseDecoderEvidence("session", sequenceOf(gson.toJson(row)))
        }
    }

    private fun decoderRow(observed: SpeechDecoderEvidence = evidence(), language: String = "id"): JsonObject {
        val selected = observed.crop ?: observed.wholeUtterance
        return gson.toJsonTree(
            mapOf(
                "sessionId" to "session",
                "segmentId" to "segment",
                "language" to language,
                "startOffsetMs" to selected.startOffsetMs,
                "endOffsetMs" to selected.endOffsetMs,
                "rawText" to selected.rawText,
                "decoderEvidence" to observed
            )
        ).asJsonObject
    }

    private fun evidence(): SpeechDecoderEvidence {
        val whole = SpeechDecoderResult("whisper_tiny_int8", "a".repeat(40), 1_000, 5_000, "  whole decoder output\n")
        return SpeechDecoderEvidence(
            wholeUtterance = whole,
            crop = whole.copy(startOffsetMs = 1_500, endOffsetMs = 3_500, rawText = "  crop output\n"),
            precedingPartial = whole.copy(
                backend = "zipformer2_transducer",
                modelRevision = "b".repeat(40),
                endOffsetMs = 4_000,
                rawText = "  prior hypothesis  "
            ),
            streamingFinal = whole.copy(backend = "zipformer2_transducer", modelRevision = "b".repeat(40), rawText = "streaming output")
        )
    }
}
