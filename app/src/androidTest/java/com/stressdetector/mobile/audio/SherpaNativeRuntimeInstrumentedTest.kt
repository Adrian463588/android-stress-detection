package com.stressdetector.mobile.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stressdetector.mobile.di.AppLocator
import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that the installed Sherpa bundle reaches the native ASR/VAD path. */
@RunWith(AndroidJUnit4::class)
class SherpaNativeRuntimeInstrumentedTest {

    @Test
    fun indonesianRuntimePreflightAndRecognizerCreationSucceed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = AppLocator.speechRuntimeCoordinator(context)
            .lease(SpeechRuntimeMode.VERBATIM)

        try {
            val readiness = runtime.preflight()
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
