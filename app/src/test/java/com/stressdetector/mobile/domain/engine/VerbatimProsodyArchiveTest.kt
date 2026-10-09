package com.stressdetector.mobile.domain.engine

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VerbatimProsodyArchiveTest {
    @Test
    fun `actual feature names round trip and old archives abstain`() {
        val observed = VerbatimProsodyEvidence(
            features = mapOf("Loudness_sma3" to 0.3, "F0semitoneFrom27.5Hz_sma3nz" to 30.0),
            frameCount = 200,
            voicedFrameCount = 150
        )
        val line = Gson().toJson(mapOf("sessionId" to "session", "segmentId" to "segment", "prosodyEvidence" to observed))
        val restored = VerbatimProsodyArchive.parse("session", sequenceOf(line)).getValue("segment")
        assertEquals(observed, restored)
        assertEquals(0.3, restored.loudness!!, 0.0)
        assertEquals(
            emptyMap<String, VerbatimProsodyEvidence>(),
            VerbatimProsodyArchive.parse("session", sequenceOf("{\"sessionId\":\"session\"}"))
        )
    }

    @Test
    fun `wrong session duplicated segments and invalid voiced counts reject`() {
        assertThrows(IllegalArgumentException::class.java) {
            VerbatimProsodyArchive.parse("session", sequenceOf("{\"sessionId\":\"other\"}"))
        }
        val invalid = VerbatimProsodyEvidence(frameCount = 1, voicedFrameCount = 2)
        val line = Gson().toJson(mapOf("sessionId" to "session", "segmentId" to "segment", "prosodyEvidence" to invalid))
        assertThrows(IllegalArgumentException::class.java) { VerbatimProsodyArchive.parse("session", sequenceOf(line)) }
        val valid = line.replace("\"voicedFrameCount\":2", "\"voicedFrameCount\":1")
        assertThrows(IllegalArgumentException::class.java) { VerbatimProsodyArchive.parse("session", sequenceOf(valid, valid)) }
    }
}
