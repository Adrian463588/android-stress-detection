package com.stressdetector.mobile.domain.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes message-scoped voice commands and owns the latest-request-wins
 * lifecycle. The presentation layer never calls a renderer directly.
 */
class VoicePlaybackCoordinator(
    private val engine: VoiceOutputEngine,
    private val scope: CoroutineScope,
    private val stopTimeoutMs: Long = DEFAULT_STOP_TIMEOUT_MS
) {
    private val commandMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(VoicePlaybackUiState())
    private var playbackJob: Job? = null
    private var requestId = 0L
    private val closeLock = Any()
    private var closeCompletion: CompletableDeferred<Unit>? = null

    @Volatile
    private var closeRequested = false

    val state: StateFlow<VoicePlaybackUiState> = _state.asStateFlow()

    suspend fun toggle(messageId: String, text: String, language: VoiceLanguage) {
        if (closeRequested || !commandMutex.tryLock()) return
        try {
            val current = _state.value
            if (current.state is VoiceOutputState.Stopping) return
            val sameActiveMessage = current.activeMessageId == messageId &&
                (current.state is VoiceOutputState.Preparing || current.state is VoiceOutputState.Speaking)
            if (sameActiveMessage) {
                stopLocked()
            } else {
                stopLocked()
                startLocked(messageId, text, language)
            }
        } finally {
            commandMutex.unlock()
        }
    }

    suspend fun stopAndWait() {
        if (closeRequested) return
        commandMutex.withLock {
            if (!closeRequested) stopLocked()
        }
    }

    suspend fun closeAndWait() {
        val completion = scheduleClose()
        withContext(NonCancellable) { completion.await() }
        cleanupScope.cancel()
    }

    /** Compatibility lifecycle entry point; cleanup runs outside viewModelScope. */
    fun close() {
        scheduleClose()
    }

    private fun scheduleClose(): CompletableDeferred<Unit> = synchronized(closeLock) {
        closeCompletion?.let { return@synchronized it }
        closeRequested = true
        val completion = CompletableDeferred<Unit>()
        closeCompletion = completion
        cleanupScope.launch {
            try {
                closeInternal()
            } catch (error: Throwable) {
                completion.completeExceptionally(error)
            } finally {
                if (!completion.isCompleted) completion.complete(Unit)
                cleanupScope.cancel()
            }
        }
        completion
    }

    private suspend fun closeInternal() = withContext(NonCancellable) {
        commandMutex.withLock {
            stopLocked()
            engine.closeAndWait()
        }
    }

    private fun startLocked(messageId: String, text: String, language: VoiceLanguage) {
        val normalizedText = VoiceTextNormalizer.normalize(text)
        if (normalizedText.isBlank()) {
            _state.value = VoicePlaybackUiState(state = VoiceOutputState.Stopped)
            return
        }

        val currentRequestId = ++requestId
        _state.value = VoicePlaybackUiState(
            activeMessageId = messageId,
            state = VoiceOutputState.Preparing(messageId = messageId),
            error = null
        )
        playbackJob = scope.launch {
            val engineStateJob = launch {
                engine.state.collect { engineState ->
                    if (currentRequestId == requestId) {
                        _state.value = _state.value.copy(
                            state = engineState,
                            backend = when (engineState) {
                                is VoiceOutputState.Preparing -> engineState.backend
                                is VoiceOutputState.Speaking -> engineState.backend
                                is VoiceOutputState.Error -> engineState.backend
                                else -> _state.value.backend
                            },
                            error = (engineState as? VoiceOutputState.Error)?.message
                        )
                    }
                }
            }
            val result = try {
                engine.speak(messageId, normalizedText, language)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            } finally {
                engineStateJob.cancelAndJoin()
            }

            if (currentRequestId != requestId) return@launch
            result.fold(
                onSuccess = { backend ->
                    _state.value = VoicePlaybackUiState(
                        state = VoiceOutputState.Stopped,
                        backend = backend
                    )
                },
                onFailure = { error ->
                    val message = error.message ?: "Voice reply gagal diputar."
                    _state.value = VoicePlaybackUiState(
                        activeMessageId = messageId,
                        state = VoiceOutputState.Error(message),
                        error = message
                    )
                }
            )
        }
    }

    private suspend fun stopLocked() {
        val job = playbackJob
        val hadPlayback = job?.isActive == true || _state.value.activeMessageId != null
        ++requestId
        playbackJob = null

        if (hadPlayback) {
            _state.value = _state.value.copy(
                state = VoiceOutputState.Stopping,
                error = null
            )
            job?.cancel(CancellationException("Voice reply stopped by user"))
            engine.stopAndWait().onFailure { error ->
                _state.value = VoicePlaybackUiState(
                    state = VoiceOutputState.Error(error.message ?: "Voice reply gagal dihentikan."),
                    error = error.message ?: "Voice reply gagal dihentikan."
                )
            }
        }

        if (job != null) {
            withTimeoutOrNull(stopTimeoutMs) { job.join() }
        }
        _state.value = VoicePlaybackUiState(state = VoiceOutputState.Stopped)
    }

    private companion object {
        const val DEFAULT_STOP_TIMEOUT_MS = 5_000L
    }
}
