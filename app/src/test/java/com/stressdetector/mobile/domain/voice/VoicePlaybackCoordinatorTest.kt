package com.stressdetector.mobile.domain.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoicePlaybackCoordinatorTest {
    @Test
    fun `rapid same-message taps do not queue repeated playback cycles`() = runTest {
        val stopRelease = CompletableDeferred<Unit>()
        val engine = FakeVoiceOutputEngine(stopRelease)
        val coordinator = VoicePlaybackCoordinator(engine, this)

        coordinator.toggle("assistant-1", "Halo", VoiceLanguage.INDONESIAN)
        runCurrent()

        val taps = List(30) {
            async {
                coordinator.toggle("assistant-1", "Halo", VoiceLanguage.INDONESIAN)
            }
        }
        runCurrent()

        assertEquals(1, engine.speakCount)
        assertEquals(1, engine.stopCount)
        assertTrue(engine.stopStarted.isCompleted)

        stopRelease.complete(Unit)
        taps.awaitAll()
        advanceUntilIdle()

        assertTrue(coordinator.state.value.state is VoiceOutputState.Stopped)
        assertEquals(1, engine.speakCount)
        assertEquals(1, engine.stopCount)
    }

    @Test
    fun `close waits for scheduled cleanup and rejects later playback`() = runTest {
        val engine = FakeVoiceOutputEngine(CompletableDeferred())
        val coordinator = VoicePlaybackCoordinator(engine, this)

        coordinator.close()
        coordinator.closeAndWait()
        coordinator.toggle("assistant-1", "Halo", VoiceLanguage.INDONESIAN)

        assertTrue(engine.closed)
        assertEquals(0, engine.speakCount)
    }

    private class FakeVoiceOutputEngine(private val stopRelease: CompletableDeferred<Unit>) : VoiceOutputEngine {
        private val _state = MutableStateFlow<VoiceOutputState>(VoiceOutputState.Idle)
        val stopStarted = CompletableDeferred<Unit>()
        var speakCount = 0
        var stopCount = 0
        var closed = false

        override val state: StateFlow<VoiceOutputState> = _state

        override suspend fun speak(messageId: String, text: String, language: VoiceLanguage): Result<VoiceBackend> {
            speakCount++
            _state.value = VoiceOutputState.Speaking(messageId, VoiceBackend.PIPER_LEGACY)
            try {
                awaitCancellation()
            } finally {
                _state.value = VoiceOutputState.Stopped
            }
            return Result.success(VoiceBackend.PIPER_LEGACY)
        }

        override suspend fun stopAndWait(): Result<Unit> {
            stopCount++
            _state.value = VoiceOutputState.Stopping
            stopStarted.complete(Unit)
            stopRelease.await()
            _state.value = VoiceOutputState.Stopped
            return Result.success(Unit)
        }

        override suspend fun closeAndWait() {
            closed = true
        }

        override suspend fun close() = closeAndWait()
    }
}
