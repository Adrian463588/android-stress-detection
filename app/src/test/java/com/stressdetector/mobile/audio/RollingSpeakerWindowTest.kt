package com.stressdetector.mobile.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RollingSpeakerWindowTest {
    @Test
    fun shortPauseDoesNotCompressAbsoluteTimeline() {
        val window = RollingSpeakerWindow(sampleRateHz = 1_000, maxSamples = 30_000)
        window.append(FloatArray(1_000) { 0.1f }, 0)
        window.append(FloatArray(1_000) { 0.2f }, 1_200)
        assertEquals(1_200L, window.startOffsetMs)
        assertArrayEquals(FloatArray(1_000) { 0.2f }, window.samples, 0f)
    }

    @Test
    fun fullUtteranceSurvivesOlderContextEviction() {
        val window = RollingSpeakerWindow(sampleRateHz = 1_000, maxSamples = 30_000)
        window.append(FloatArray(15_000) { 0.1f }, 0)
        window.append(FloatArray(16_000) { 0.2f }, 15_000)
        assertEquals(1_000L, window.startOffsetMs)
        assertEquals(30_000, window.samples.size)
        assertArrayEquals(FloatArray(16_000) { 0.2f }, window.samples.takeLast(16_000).toFloatArray(), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedUtteranceFailsInsteadOfSilentlyDroppingItsOnset() {
        RollingSpeakerWindow(sampleRateHz = 1_000, maxSamples = 30_000).append(FloatArray(30_001), 0)
    }
}
