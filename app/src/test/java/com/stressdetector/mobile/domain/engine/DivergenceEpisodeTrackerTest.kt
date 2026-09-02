package com.stressdetector.mobile.domain.engine

import com.stressdetector.shared.PpgType
import org.junit.Assert.assertEquals
import org.junit.Test

class DivergenceEpisodeTrackerTest {
    @Test
    fun consecutiveMarkersAreMergedPerChannel() {
        val tracker = DivergenceEpisodeTracker(10_000L)
        val first = tracker.candidate("one", "session", PpgType.GREEN, 180_000L, 0L, 180_000L, 0.8f, 0.5f, 0.9f, 0.9f)
        val second = tracker.candidate("two", "session", PpgType.GREEN, 190_000L, 10_000L, 190_000L, 0.85f, 0.5f, 0.9f, 0.9f)

        val merged = tracker.add(tracker.add(emptyList(), first), second)

        assertEquals(1, merged.size)
        assertEquals(180_000L, merged.single().episodeStartElapsedMillis)
        assertEquals(190_000L, merged.single().episodeEndElapsedMillis)
    }

    @Test
    fun independentChannelsNeverMergeIntoOneEpisode() {
        val tracker = DivergenceEpisodeTracker(10_000L)
        val green = tracker.candidate("green", "session", PpgType.GREEN, 180_000L, 0L, 180_000L, 0.8f, 0.5f, 0.9f, 0.9f)
        val ir = tracker.candidate("ir", "session", PpgType.IR, 180_000L, 0L, 180_000L, 0.8f, 0.5f, 0.9f, 0.9f)

        val merged = tracker.add(tracker.add(emptyList(), green), ir)

        assertEquals(2, merged.size)
        assertEquals(setOf(PpgType.GREEN), merged[0].sourceChannels)
        assertEquals(setOf(PpgType.IR), merged[1].sourceChannels)
    }

    @Test
    fun markerTypesPreserveTemporalChannelAndBpmSemantics() {
        val tracker = DivergenceEpisodeTracker(10_000L)
        val temporal = tracker.candidate(
            id = "temporal",
            sessionId = "session",
            channel = PpgType.GREEN,
            markerElapsedMillis = 180_000L,
            contextStartElapsedMillis = 120_000L,
            contextEndElapsedMillis = 180_000L,
            currentProbability = 0.9f,
            baselineProbability = 0.6f,
            confidence = 0.8f,
            signalQuality = 0.9f,
            markerType = PhysiologicalMarkerType.TEMPORAL_BASELINE_AROUSAL
        )
        val channel = tracker.candidate(
            id = "channel",
            sessionId = "session",
            channel = PpgType.GREEN,
            markerElapsedMillis = 180_000L,
            contextStartElapsedMillis = 120_000L,
            contextEndElapsedMillis = 180_000L,
            currentProbability = 0.9f,
            baselineProbability = 0.6f,
            confidence = 0.8f,
            signalQuality = 0.9f,
            markerType = PhysiologicalMarkerType.CHANNEL_AGREEMENT,
            isElevatedArousal = false,
            sourceChannels = setOf(PpgType.GREEN, PpgType.IR)
        )
        val bpm = tracker.candidate(
            id = "bpm",
            sessionId = "session",
            channel = PpgType.IR,
            markerElapsedMillis = 180_000L,
            contextStartElapsedMillis = 120_000L,
            contextEndElapsedMillis = 180_000L,
            currentProbability = 0.7f,
            baselineProbability = 0.6f,
            confidence = 0.8f,
            signalQuality = 0.9f,
            markerType = PhysiologicalMarkerType.BPM_TRAJECTORY,
            bpmTrend = "RISING"
        )

        assertEquals(PhysiologicalMarkerType.TEMPORAL_BASELINE_AROUSAL, temporal.markerType)
        assertEquals(PhysiologicalMarkerType.CHANNEL_AGREEMENT, channel.markerType)
        assertEquals(setOf(PpgType.GREEN, PpgType.IR), channel.sourceChannels)
        assertEquals(false, channel.isElevatedArousal)
        assertEquals(PhysiologicalMarkerType.BPM_TRAJECTORY, bpm.markerType)
        assertEquals("RISING", bpm.bpmTrend)
    }

    @Test
    fun markersFromAnotherSessionOrEarlierTimeDoNotMerge() {
        val tracker = DivergenceEpisodeTracker(10_000L)
        val first = tracker.candidate("one", "session-a", PpgType.GREEN, 180_000L, 180_000L, 180_000L, 0.8f, 0.5f, 0.9f, 0.9f)
        val otherSession = tracker.candidate("two", "session-b", PpgType.GREEN, 185_000L, 185_000L, 185_000L, 0.8f, 0.5f, 0.9f, 0.9f)
        val regressing = tracker.candidate("three", "session-a", PpgType.GREEN, 170_000L, 170_000L, 170_000L, 0.8f, 0.5f, 0.9f, 0.9f)

        val result = tracker.add(tracker.add(tracker.add(emptyList(), first), otherSession), regressing)

        assertEquals(3, result.size)
    }
}
