package com.stressdetector.mobile.audio

import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import com.stressdetector.mobile.domain.engine.SpeechSegment
import com.stressdetector.mobile.ml.speech.SpeechBundleManifest
import com.stressdetector.mobile.ml.speech.SpeechDiarizationManifest
import com.stressdetector.mobile.ml.speech.SpeechSttManifest
import com.stressdetector.mobile.ml.speech.SpeechVadManifest
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRuntimeCoordinatorTest {
    @Test
    fun secondFeatureWaitsUntilFirstSessionEnds() {
        runBlocking {
            val coordinator = SpeechRuntimeCoordinator { FakeRuntime() }
            val first = coordinator.lease(SpeechRuntimeMode.VERBATIM)
            val second = coordinator.lease(SpeechRuntimeMode.AI_VOICE)

            first.preflight()
            first.beginSession(1L, "id", "verbatim-1")
            val pending = async { second.beginSession(2L, "id", "voice-2") }
            kotlinx.coroutines.yield()
            assertEquals(false, pending.isCompleted)

            first.endSession()
            withTimeout(1_000L) { pending.await() }
            second.endSession()
        }
    }

    @Test
    fun candidateValidationRejectsActiveSessionWithoutWaitingOrReplacingItsRuntime() {
        runBlocking {
            val runtime = FakeRuntime()
            val lease = SpeechRuntimeCoordinator { runtime }.lease(SpeechRuntimeMode.VERBATIM)
            lease.beginSession(1L, "id", "active-session")
            val result = withTimeout(1_000L) { lease.preflight(candidateManifest()) }
            assertTrue(result is SpeechRuntimeState.Unavailable)
            assertNull(runtime.validatedCandidate)
            lease.endSession()
        }
    }

    @Test
    fun downloadCandidateLeaseRejectsOtherFeatureSessionAndCanValidateAfterItEnds() {
        runBlocking {
            val runtimes = mutableListOf<FakeRuntime>()
            val coordinator = SpeechRuntimeCoordinator { FakeRuntime().also(runtimes::add) }
            val activeSession = coordinator.lease(SpeechRuntimeMode.AI_VOICE)
            val downloadCandidate = coordinator.lease(SpeechRuntimeMode.VERBATIM)
            val candidate = candidateManifest()

            activeSession.beginSession(1L, "id", "active-voice-session")
            val rejected = withTimeout(1_000L) { downloadCandidate.preflight(candidate) }
            assertTrue(rejected is SpeechRuntimeState.Unavailable)
            assertEquals(1, runtimes.size)
            assertNull(runtimes.single().validatedCandidate)

            activeSession.endSession()
            assertEquals(SpeechRuntimeState.Ready, downloadCandidate.preflight(candidate))
            assertEquals(2, runtimes.size)
            assertEquals(candidate, runtimes.last().validatedCandidate)
        }
    }

    @Test
    fun candidateValidationUsesExplicitManifestRatherThanActiveReadiness() {
        runBlocking {
            val runtime = FakeRuntime()
            val lease = SpeechRuntimeCoordinator { runtime }.lease(SpeechRuntimeMode.VERBATIM)
            val candidate = candidateManifest()
            assertEquals(SpeechRuntimeState.Ready, lease.preflight(candidate))
            assertEquals(candidate, runtime.validatedCandidate)
            lease.beginSession(1L, "en", "after-validation")
            lease.endSession()
        }
    }

    private fun candidateManifest() = SpeechBundleManifest(
        schemaVersion = "2.0.0",
        bundleVersion = "candidate-test",
        sampleRateHz = 16_000,
        languages = listOf("id", "en"),
        stt = SpeechSttManifest("omnilingual_ctc"),
        vad = SpeechVadManifest(true),
        diarization = SpeechDiarizationManifest(true, 2),
        files = emptyMap()
    )

    private class FakeRuntime : LocalSpeechRuntime {
        var validatedCandidate: SpeechBundleManifest? = null
        override val state = MutableStateFlow<SpeechRuntimeState>(SpeechRuntimeState.Ready)
        override suspend fun preflight() = SpeechRuntimeState.Ready
        override suspend fun preflight(candidateManifest: SpeechBundleManifest): SpeechRuntimeState {
            validatedCandidate = candidateManifest
            return SpeechRuntimeState.Ready
        }
        override suspend fun beginSession(startedAtUtcMillis: Long, languageCode: String, sessionId: String) = Unit
        override suspend fun detectSpeech(samples: FloatArray) = true
        override suspend fun transcribe(samples: FloatArray, startOffsetMs: Long, vadAlreadyAccepted: Boolean) = emptyList<SpeechSegment>()
        override suspend fun endSession() = emptyList<SpeechSegment>()
        override fun close() = Unit
    }
}
