package com.stressdetector.mobile.audio

import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import com.stressdetector.mobile.domain.engine.SpeechSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Identifies the feature holding the process-wide Sherpa native runtime. */
enum class SpeechRuntimeMode {
    AI_VOICE,
    VERBATIM
}

/**
 * Serializes native speech sessions and loads only the capability required by
 * the active feature. AI voice does not load the memory-heavy diarizer; the
 * Verbatim lease does. A candidate runtime is closed before another mode is
 * created, so native models are never resident twice in this process.
 */
class SpeechRuntimeCoordinator(private val runtimeFactory: (SpeechRuntimeMode) -> LocalSpeechRuntime) {
    private val sessionMutex = Mutex()
    private val mutableState = MutableStateFlow<SpeechRuntimeState>(
        SpeechRuntimeState.Checking
    )

    @Volatile
    private var activeRuntime: LocalSpeechRuntime? = null

    @Volatile
    private var activeMode: SpeechRuntimeMode? = null

    fun lease(mode: SpeechRuntimeMode): LocalSpeechRuntime = Lease(this, mode)

    private suspend fun prepareLocked(mode: SpeechRuntimeMode): LocalSpeechRuntime {
        if (activeMode != mode) {
            activeRuntime?.closeAndWait()
            activeRuntime = runtimeFactory(mode)
            activeMode = mode
        }
        return checkNotNull(activeRuntime)
    }

    private suspend fun preflight(mode: SpeechRuntimeMode): SpeechRuntimeState = sessionMutex.withLock {
        mutableState.value = SpeechRuntimeState.Checking
        val runtime = prepareLocked(mode)
        runCatching { runtime.preflight() }
            .getOrElse { error ->
                SpeechRuntimeState.Failed(
                    error.message ?: "Speech runtime initialization failed."
                )
            }
            .also { mutableState.value = it }
    }

    private suspend fun begin(
        mode: SpeechRuntimeMode,
        startedAtUtcMillis: Long,
        languageCode: String,
        sessionId: String
    ): LocalSpeechRuntime {
        sessionMutex.lock()
        try {
            mutableState.value = SpeechRuntimeState.Checking
            val runtime = prepareLocked(mode)
            val readiness = runCatching { runtime.preflight() }
                .getOrElse { error ->
                    SpeechRuntimeState.Failed(
                        error.message ?: "Speech runtime initialization failed."
                    )
                }
            mutableState.value = readiness
            check(readiness is SpeechRuntimeState.Ready) {
                readiness.actionableMessage()
            }
            runtime.beginSession(startedAtUtcMillis, languageCode, sessionId)
            return runtime
        } catch (error: Throwable) {
            sessionMutex.unlock()
            throw error
        }
    }

    private suspend fun end(runtime: LocalSpeechRuntime): List<SpeechSegment> = try {
        runtime.endSession()
    } finally {
        runCatching { runtime.closeAndWait() }
        activeRuntime = null
        activeMode = null
        sessionMutex.unlock()
    }

    private class Lease(private val coordinator: SpeechRuntimeCoordinator, private val mode: SpeechRuntimeMode) : LocalSpeechRuntime {
        private var delegate: LocalSpeechRuntime? = null

        override val state: StateFlow<SpeechRuntimeState> = coordinator.mutableState.asStateFlow()

        override suspend fun preflight(): SpeechRuntimeState = coordinator.preflight(mode)

        override suspend fun beginSession(startedAtUtcMillis: Long, languageCode: String, sessionId: String) {
            delegate = coordinator.begin(mode, startedAtUtcMillis, languageCode, sessionId)
        }

        override suspend fun detectSpeech(samples: FloatArray): Boolean = checkNotNull(delegate).detectSpeech(samples)

        override suspend fun transcribe(samples: FloatArray, startOffsetMs: Long, vadAlreadyAccepted: Boolean): List<SpeechSegment> =
            checkNotNull(delegate).transcribe(
                samples,
                startOffsetMs,
                vadAlreadyAccepted
            )

        override suspend fun finishUtterance(): List<SpeechSegment> = delegate?.finishUtterance().orEmpty()

        override suspend fun endSession(): List<SpeechSegment> {
            val runtime = delegate ?: return emptyList()
            delegate = null
            return coordinator.end(runtime)
        }

        override fun close() {
            // A session owner must call endSession() so the coordinator can
            // release the mutex after native resources have been joined.
        }
    }

    private fun SpeechRuntimeState.actionableMessage(): String = when (this) {
        SpeechRuntimeState.Checking -> "Speech bundle sedang diperiksa. Coba lagi sebentar."
        is SpeechRuntimeState.Unavailable -> reason
        is SpeechRuntimeState.Failed -> message
        SpeechRuntimeState.Ready -> "Speech runtime belum siap."
    }
}
