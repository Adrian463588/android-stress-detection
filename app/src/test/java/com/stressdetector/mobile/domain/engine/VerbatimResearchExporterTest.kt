@file:Suppress("LongMethod")

package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.shared.PpgType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerbatimResearchExporterTest {

    @Test
    fun buildCsvAndMetadataJsonStripClientIdentityTranscriptAndAudioPaths() {
        val summary = VerbatimSessionSummary(
            sessionId = "session-secret-01",
            sessionName = "Klien Rahasia Budi",
            startedAtMillis = 1_700_000_000_000L,
            endedAtMillis = 1_700_000_120_000L,
            durationSeconds = 120L,
            audioFilePath = "/data/user/0/secret/audio_budi.wav.enc",
            artifactFolderPath = "/data/user/0/secret/folder",
            finalizationStatus = "COMPLETED",
            summaryStatus = "READY",
            watchWearerRole = "CLIENT",
            languageCode = "id"
        )
        val event = DivergenceEventEntity(
            eventId = "ev-1",
            sessionId = summary.sessionId,
            timestampMs = 1_700_000_045_000L,
            delta = 12f,
            currentProbability = null,
            baselineProbability = null,
            copyText = "Transkrip privat: Klien Rahasia Budi bercerita",
            channel = "GREEN",
            markerElapsedMillis = 45_000L,
            detectedAtElapsedMillis = 45_150L,
            markerType = "BPM_RISE",
            bpm = 84f,
            baselineBpm = 72f,
            bpmDelta = 12f,
            signalQuality = 0.92f,
            speakerId = "Klien"
        )
        val prv = mapOf(
            PpgType.GREEN to PrvMetrics(
                channel = PpgType.GREEN,
                rmssdMs = 34.5f,
                sdnnMs = 48.2f,
                pnn50Pct = 14.0f,
                meanPpiMs = 714f,
                medianPpiMs = 710f,
                validIbiCount = 38,
                candidateIbiCount = 40,
                validIbiRatio = 0.95f,
                windowSeconds = 30,
                isQualityPassed = true
            )
        )

        val csv = VerbatimResearchExporter.buildCsv(summary, listOf(event), prv)
        val meta = VerbatimResearchExporter.buildMetadataJson(summary, listOf(event), prv, "SESSION_READY")

        assertTrue(csv.contains("84.0000"))
        assertTrue(csv.contains("34.5000"))
        assertTrue(csv.contains("BPM_RISE"))
        assertTrue(csv.contains("CLIENT"))
        assertFalse(csv.contains("Klien Rahasia Budi"))
        assertFalse(csv.contains("audio_budi"))
        assertFalse(csv.contains("Transkrip privat"))

        assertTrue(meta.contains("\"de_identified\": true"))
        assertTrue(meta.contains("\"contains_transcript_text\": false"))
        assertTrue(meta.contains("\"contains_raw_audio\": false"))
        assertTrue(meta.contains("SESSION_READY"))
        assertFalse(meta.contains("Klien Rahasia Budi"))
        assertFalse(meta.contains("audio_budi"))
    }
}
