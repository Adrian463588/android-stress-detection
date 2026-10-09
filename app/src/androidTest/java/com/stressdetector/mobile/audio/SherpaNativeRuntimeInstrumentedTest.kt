package com.stressdetector.mobile.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stressdetector.mobile.di.AppLocator
import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that the installed Sherpa bundle reaches the native ASR/VAD path. */
@RunWith(AndroidJUnit4::class)
class SherpaNativeRuntimeInstrumentedTest {

    @Test
    fun successiveFinishedIndonesianStreamsCanAcceptMorePcm() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = AppLocator.speechRuntimeCoordinator(context).lease(SpeechRuntimeMode.VERBATIM)
        try {
            val readiness = runtime.preflight()
            assumeTrue("Provision real speech models before this native lifecycle check: $readiness", readiness is SpeechRuntimeState.Ready)
            runtime.beginSession(1_725_000_000_000L, "id", "device-sherpa-stream-boundary")
            // Silence is a numeric native lifecycle fixture, not accuracy evidence.
            repeat(2) { utterance ->
                runtime.transcribe(FloatArray(SherpaOnnxSpeechRuntime.SAMPLE_RATE_HZ), utterance * 1_000L, vadAlreadyAccepted = true)
                runtime.finishUtterance()
            }
            assertTrue(runtime.state.value is SpeechRuntimeState.Ready)
        } finally {
            runtime.endSession()
        }
    }

    @Test
    fun indonesianRuntimePreflightAndRecognizerCreationOrUnavailableState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = AppLocator.speechRuntimeCoordinator(context)
            .lease(SpeechRuntimeMode.VERBATIM)

        try {
            val readiness = runtime.preflight()
            if (readiness is SpeechRuntimeState.Unavailable) {
                assumeTrue("Sherpa runtime unavailable on this device: ${readiness.reason}", false)
            }
            assertTrue("Sherpa preflight failed: $readiness", readiness is SpeechRuntimeState.Ready)

            runtime.beginSession(
                startedAtUtcMillis = 1_725_000_000_000L,
                languageCode = "id",
                sessionId = "device-sherpa-preflight"
            )

            val silence = FloatArray(SherpaOnnxSpeechRuntime.SAMPLE_RATE_HZ)
            runtime.detectSpeech(silence)
            val segments = runtime.transcribe(
                samples = silence,
                startOffsetMs = 0L,
                vadAlreadyAccepted = false
            )
            assertTrue("Silence must not create a transcript", segments.isEmpty())
        } finally {
            runtime.endSession()
        }
    }
}
