package com.stressdetector.mobile.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LivePitchObservationTest {
    @Test
    fun historyIsBoundedOrderedDeduplicatedAndSessionScoped() {
        val first = observation("one", 0)
        val later = observation("one", 2_000)
        val inserted = appendLivePitch(listOf(first, later, later), observation("one", 1_000), capacity = 2)
        // Each offset may appear once even if the source history was repeated.
        assertEquals(listOf(1_000L, 2_000L), inserted.map { it.startOffsetMs })
        assertEquals(listOf(observation("two", 0)), appendLivePitch(inserted, observation("two", 0)))
    }

    @Test
    fun unavailableOrNonFiniteProsodyCannotBecomeHz() {
        assertNull(VerbatimProsodyEvidence(features = mapOf("F0semitoneFrom27.5Hz" to 24.0)).pitchHz)
        assertNull(
            VerbatimProsodyEvidence(features = mapOf("F0semitoneFrom27.5Hz" to Double.NaN), frameCount = 90, voicedFrameCount = 80).pitchHz
        )
        assertNull(
            VerbatimProsodyEvidence(
                features = mapOf("F0semitoneFrom27.5Hz" to 24.0),
                frameCount = 90,
                voicedFrameCount = 80,
                unavailableReason = "test"
            ).pitchHz
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun unavailableObservationCannotContainAReplacementNumber() {
        LivePitchObservation("one", 0, 1_000, 0.0, LivePitchStatus.UNVOICED)
    }

    private fun observation(session: String, start: Long) =
        LivePitchObservation(session, start, start + 1_000, 110.0, LivePitchStatus.AVAILABLE)
}
