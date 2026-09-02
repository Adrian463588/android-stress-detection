package com.stressdetector.mobile.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTurnAssemblerTest {
    @Test
    fun silenceAfterSpeechCompletesAtEndpoint() {
        val assembler = VoiceTurnAssembler(
            VoiceTurnAssembler.Config(
                silenceEndpointMillis = 900,
                preSpeechBufferMillis = 0
            )
        )

        assertTrue(assembler.append(frameForMillis(300, amplitude = 2_000)) is VoiceTurnAssemblyResult.Collecting)
        assertTrue(assembler.append(frameForMillis(300, amplitude = 0)) is VoiceTurnAssemblyResult.Collecting)
        val result = assembler.append(frameForMillis(600, amplitude = 0))

        assertTrue(result is VoiceTurnAssemblyResult.Completed)
        assertEquals(VoiceTurnEndReason.SILENCE_ENDPOINT, (result as VoiceTurnAssemblyResult.Completed).reason)
    }

    @Test
    fun turnIsBoundedAtThirtySeconds() {
        val assembler = VoiceTurnAssembler(
            VoiceTurnAssembler.Config(preSpeechBufferMillis = 0)
        )

        val result = assembler.append(
            VoicePcmFrame(
                samples = ShortArray(VoiceTurnAssembler.SAMPLE_RATE_HZ * 30) { 2_000 }
            )
        )

        assertTrue(result is VoiceTurnAssemblyResult.Completed)
        val completed = result as VoiceTurnAssemblyResult.Completed
        assertEquals(VoiceTurnEndReason.MAX_DURATION, completed.reason)
        assertEquals(30_000L, completed.turn.durationMillis)
        assertEquals(VoiceTurnAssembler.SAMPLE_RATE_HZ * 30, completed.turn.samples.size)
    }

    @Test
    fun cancelResetsAssemblerAndAllowsNewTurn() {
        val assembler = VoiceTurnAssembler(
            VoiceTurnAssembler.Config(preSpeechBufferMillis = 0)
        )

        assembler.append(frameForMillis(300, amplitude = 2_000))
        assembler.cancel()

        assertEquals(VoiceTurnAssemblyResult.AwaitingSpeech, assembler.append(frameForMillis(300, amplitude = 0)))
        assertTrue(assembler.append(frameForMillis(300, amplitude = 2_000)) is VoiceTurnAssemblyResult.Collecting)
    }

    @Test
    fun invalidPcmFormatIsRejected() {
        val assembler = VoiceTurnAssembler()

        try {
            assembler.append(VoicePcmFrame(ShortArray(10), sampleRateHz = 8_000))
        } catch (error: IllegalArgumentException) {
            assertFalse(error.message.isNullOrBlank())
            return
        }

        throw AssertionError("Expected invalid sample rate to be rejected")
    }

    @Test
    fun userStopFinishesCollectedSpeechTurn() {
        val assembler = VoiceTurnAssembler(
            VoiceTurnAssembler.Config(preSpeechBufferMillis = 0)
        )

        assembler.append(frameForMillis(300, amplitude = 2_000))

        val turn = assembler.finish()

        assertEquals(300L, turn?.durationMillis)
        assertTrue(turn?.containsSpeech == true)
    }

    private fun frameForMillis(durationMillis: Long, amplitude: Short): VoicePcmFrame {
        val sampleCount = durationMillis * VoiceTurnAssembler.SAMPLE_RATE_HZ / 1_000
        return VoicePcmFrame(ShortArray(sampleCount.toInt()) { amplitude })
    }
}
