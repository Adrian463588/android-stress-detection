package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.db.DivergenceEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultimodalCueAnalyzerTest {

    @Test
    fun producesAllFiveNeutralComparisonCategoriesAndLinksEvidenceIds() {
        val seg = SpeechSegment(
            id = "seg-1",
            speakerId = "Klien",
            startOffsetMs = 18_000L,
            endOffsetMs = 22_000L,
            startTimestampUtc = 18_000L,
            endTimestampUtc = 22_000L,
            rawText = "Saya sulit tidur akhir-akhir ini.",
            confidence = 0.9f,
            sessionId = "s1"
        )
        val physioRisingAligned = physioEvent("p1", 20_000L, bpmDelta = 12f, trend = "RISING")
        val acousticRisingAligned = acousticEvent("a1", 21_000L, delta = 4.5f, segmentId = "seg-1")

        val physioRisingOpposite = physioEvent("p2", 60_000L, bpmDelta = 11f, trend = "RISING")
        val acousticFallingAligned = acousticEvent("a2", 62_000L, delta = -4.2f, copy = "Pitch menurun (-4.2 st)", segmentId = "seg-1")

        val physioIsolated = physioEvent("p3", 120_000L, bpmDelta = 10f, trend = "RISING")
        val segNearP3 = seg.copy(id = "seg-2", startOffsetMs = 118_000L, endOffsetMs = 123_000L)

        val findings = MultimodalCueAnalyzer.analyze(
            sessionId = "s1",
            watchWearerRole = WatchWearerRole.CLIENT,
            events = listOf(
                physioRisingAligned,
                acousticRisingAligned,
                physioRisingOpposite,
                acousticFallingAligned,
                physioIsolated,
                acousticEvent("a4", 160_000L, delta = 3.8f, segmentId = "seg-2")
            ),
            segments = listOf(seg, segNearP3)
        )

        val categories = findings.map { it.category }.toSet()
        assertTrue(MultimodalComparisonCategory.CONCORDANT_BOTH in categories)
        assertTrue(MultimodalComparisonCategory.DIRECTIONAL_DIVERGENCE in categories)
        assertTrue(MultimodalComparisonCategory.PHYSIOLOGICAL_ONLY in categories)
        assertTrue(MultimodalComparisonCategory.ACOUSTIC_ONLY in categories)

        val pair = findings.first { it.category == MultimodalComparisonCategory.CONCORDANT_BOTH }
        assertEquals(listOf("p1", "a1"), pair.evidenceIds)
        val entities = MultimodalCueAnalyzer.toMultimodalEntities(listOf(pair))
        assertEquals("MULTIMODAL_CUE", entities.single().markerType)
        assertEquals(listOf("p1", "a1"), MultimodalCueAnalyzer.decodeEvidenceIds(entities.single().evidenceIdsJson))
    }

    @Test
    fun watchOnPsychologistPreventsPairingWithClientSpeech() {
        val seg = SpeechSegment("seg-1", "Klien", 5_000L, 8_000L, 5_000L, 8_000L, "halo", 0.9f, sessionId = "s1")
        val findings = MultimodalCueAnalyzer.analyze(
            sessionId = "s1",
            watchWearerRole = WatchWearerRole.PSYCHOLOGIST,
            events = listOf(physioEvent("p1", 6_000L, 10f, "RISING"), acousticEvent("a1", 6_500L, 4f, segmentId = "seg-1")),
            segments = listOf(seg)
        )
        assertTrue(findings.all { it.category == MultimodalComparisonCategory.SINGLE_MODALITY_AVAILABLE })
    }

    private fun physioEvent(id: String, elapsed: Long, bpmDelta: Float, trend: String) = DivergenceEventEntity(
        eventId = id,
        sessionId = "s1",
        timestampMs = elapsed,
        delta = bpmDelta,
        currentProbability = null,
        baselineProbability = null,
        copyText = "Denyut nadi meningkat",
        channel = "GREEN",
        markerElapsedMillis = elapsed,
        episodeStartElapsedMillis = elapsed,
        episodeEndElapsedMillis = elapsed + 5_000L,
        markerType = PhysiologicalMarkerType.BPM_TRAJECTORY.name,
        bpm = 72f + bpmDelta,
        baselineBpm = 72f,
        bpmDelta = bpmDelta,
        bpmTrend = trend
    )

    private fun acousticEvent(id: String, elapsed: Long, delta: Float, copy: String = "Pitch meningkat", segmentId: String? = null) =
        DivergenceEventEntity(
            eventId = id,
            sessionId = "s1",
            timestampMs = elapsed,
            delta = null,
            currentProbability = null,
            baselineProbability = null,
            copyText = copy,
            channel = "AUDIO",
            markerElapsedMillis = elapsed,
            episodeStartElapsedMillis = elapsed,
            episodeEndElapsedMillis = elapsed + 2_000L,
            transcriptSegmentId = segmentId,
            markerType = "PROSODY_DIVERGENCE",
            deltaValue = delta
        )
}
