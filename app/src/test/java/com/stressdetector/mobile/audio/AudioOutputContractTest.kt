package com.stressdetector.mobile.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputContractTest {
    @Test
    fun `resampler preserves duration and produces non empty pcm`() {
        val source = FloatArray(16_000) { index -> index / 16_000f }

        val result = PcmResampler.resample(source, sourceRateHz = 16_000, targetRateHz = 24_000)

        assertEquals(24_000, result.size)
        assertEquals(source.first(), result.first(), 0.0001f)
        assertTrue(result.last() > 0.99f)
    }

    @Test
    fun `capture ownership rejects a second recorder and releases idempotently`() {
        val ownership = AudioCaptureOwnership()
        val first = ownership.tryAcquire(AudioCaptureOwner.AI_VOICE)
        val second = ownership.tryAcquire(AudioCaptureOwner.VERBATIM)

        assertNotNull(first)
        assertNull(second)
        assertEquals(AudioCaptureOwner.AI_VOICE, ownership.activeOwner())

        first?.release()
        first?.release()

        assertNull(ownership.activeOwner())
        assertNotNull(ownership.tryAcquire(AudioCaptureOwner.VERBATIM))
    }
}
