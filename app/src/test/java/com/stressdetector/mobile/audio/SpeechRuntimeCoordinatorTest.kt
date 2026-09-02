package com.stressdetector.mobile.audio

import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import com.stressdetector.mobile.domain.engine.SpeechSegment
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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

    private class FakeRuntime : LocalSpeechRuntime {
        override val state = MutableStateFlow<SpeechRuntimeState>(SpeechRuntimeState.Ready)
        override suspend fun preflight() = SpeechRuntimeState.Ready
        override suspend fun beginSession(startedAtUtcMillis: Long, languageCode: String, sessionId: String) = Unit
        override suspend fun detectSpeech(samples: FloatArray) = true
        override suspend fun transcribe(samples: FloatArray, startOffsetMs: Long, vadAlreadyAccepted: Boolean) = emptyList<SpeechSegment>()
        override suspend fun endSession() = emptyList<SpeechSegment>()
        override fun close() = Unit
    }
}
