@file:Suppress("LongParameterList")

package com.stressdetector.mobile.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerBaselineTrackerTest {

    @Test
    fun abstainsOnUnknownAndOverlapAndMaintainsIndependentSpeakerBaselines() {
        val tracker = SpeakerBaselineTracker(cooldownMs = 0L)

        // Unknown / overlap never contaminate baselines or emit markers
        assertNull(tracker.observe(obs("u1", "Unknown", 0L, 2_500L, f0 = 36.0)))
        assertNull(tracker.observe(obs("o1", "Klien", 3_000L, 5_500L, f0 = 36.0, isOverlap = true)))
        assertEquals(0, tracker.baselineCountFor("Klien"))

        // Warm up Klien (3 clean utterances near 20 st) and Psikolog (3 clean utterances near 28 st)
        listOf(0L, 3_000L, 6_000L).forEachIndexed { idx, t ->
            assertNull(tracker.observe(obs("k$idx", "Klien", t, t + 2_000L, f0 = 20.0 + idx * 0.4)))
            assertNull(tracker.observe(obs("p$idx", "Psikolog", t, t + 2_000L, f0 = 28.0 + idx * 0.4)))
        }
        assertEquals(3, tracker.baselineCountFor("Klien"))

        // 28.5 st is normal for Psikolog, so no marker; for Klien (+8 st jump) it triggers MAD divergence
        assertNull(tracker.observe(obs("p3", "Psikolog", 9_000L, 11_000L, f0 = 28.5)))
        val clientSpike = tracker.observe(obs("k3", "Klien", 9_000L, 11_000L, f0 = 29.0))
        assertNotNull(clientSpike)
        assertEquals("Klien", clientSpike!!.speakerId)
        assertEquals("f0Semitones", clientSpike.primaryFeature)
        assertEquals("Pitch meningkat", clientSpike.summaryLabel)
        assertTrue(clientSpike.zScore >= 2.5f)
    }

    @Test
    fun relabelPreservesBaselineHistoryAcrossSpeakerRename() {
        val tracker = SpeakerBaselineTracker(cooldownMs = 0L)
        listOf(0L, 3_000L, 6_000L).forEachIndexed { idx, t ->
            tracker.observe(obs("s$idx", "Speaker_1", t, t + 2_000L, f0 = 18.0))
        }
        assertEquals(3, tracker.baselineCountFor("Speaker_1"))

        tracker.relabelSpeaker("Speaker_1", "Klien")
        assertEquals(0, tracker.baselineCountFor("Speaker_1"))
        assertEquals(3, tracker.baselineCountFor("Klien"))

        val spike = tracker.observe(obs("s3", "Klien", 12_000L, 14_000L, f0 = 25.0))
        assertNotNull(spike)
        assertEquals("Klien", spike!!.speakerId)
    }

    private fun obs(
        id: String,
        speaker: String,
        startMs: Long,
        endMs: Long,
        f0: Double? = 20.0,
        loudness: Double? = 0.5,
        rate: Double? = 2.5,
        pause: Double? = 0.2,
        isOverlap: Boolean = false
    ) = SpeakerAcousticObservation(
        segmentId = id,
        sessionId = "session-1",
        speakerId = speaker,
        startOffsetMs = startMs,
        endOffsetMs = endMs,
        f0Semitones = f0,
        loudnessDb = loudness,
        speakingRateWps = rate,
        pauseRatio = pause,
        voicedRatio = 0.7,
        audioQuality = 0.9f,
        isOverlap = isOverlap
    )
}
