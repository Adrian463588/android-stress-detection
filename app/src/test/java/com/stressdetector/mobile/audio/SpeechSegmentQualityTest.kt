package com.stressdetector.mobile.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmentQualityTest {
    @Test
    fun blankTextOrEmptyAudioHasNoQualityEvidence() {
        assertEquals(0f, SpeechSegmentQuality.score(FloatArray(0), "text"), 0f)
        assertEquals(0f, SpeechSegmentQuality.score(FloatArray(1600), " "), 0f)
    }

    @Test
    fun finiteUnclippedSpeechAudioProducesBoundedQualityEvidence() {
        val samples = FloatArray(16_000) { index ->
            (0.15 * sin(2.0 * PI * 220.0 * index / 16_000.0)).toFloat()
        }

        val score = SpeechSegmentQuality.score(samples, "halo")

        assertTrue(score in 0f..1f)
        assertTrue(score > 0f)
    }

    @Test
    fun clippingAndNonFiniteAudioLowerQualityEvidence() {
        val clean = FloatArray(16_000) { 0.15f }
        val degraded = FloatArray(16_000) { index ->
            when {
                index % 4 == 0 -> 1f
                index % 11 == 0 -> Float.NaN
                else -> 0.15f
            }
        }

        assertTrue(
            SpeechSegmentQuality.score(clean, "halo") >
                SpeechSegmentQuality.score(degraded, "halo")
        )
    }
}
