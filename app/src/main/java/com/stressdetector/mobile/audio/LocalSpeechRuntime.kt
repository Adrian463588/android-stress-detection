package com.stressdetector.mobile.audio

import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import com.stressdetector.mobile.domain.engine.SpeechSegment
import kotlinx.coroutines.flow.StateFlow

interface LocalSpeechRuntime {
    val state: StateFlow<SpeechRuntimeState>

    suspend fun preflight(): SpeechRuntimeState

    suspend fun beginSession(startedAtUtcMillis: Long, languageCode: String, sessionId: String)

    /** Feeds one frame to the real Silero VAD and returns its current speech decision. */
    suspend fun detectSpeech(samples: FloatArray): Boolean

    suspend fun transcribe(samples: FloatArray, startOffsetMs: Long, vadAlreadyAccepted: Boolean = false): List<SpeechSegment>

    /** Completes the current utterance without resetting the session stream. */
    suspend fun finishUtterance(): List<SpeechSegment> = emptyList()

    suspend fun endSession(): List<SpeechSegment>

    /** Releases native resources and completes before another runtime is loaded. */
    suspend fun closeAndWait() {
        close()
    }

    fun close()
}
