package com.stressdetector.mobile.audio

import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import com.stressdetector.mobile.domain.engine.SpeechSegment
import com.stressdetector.mobile.ml.speech.SpeechBundleManifest
import kotlinx.coroutines.flow.StateFlow

interface LocalSpeechRuntime {
    val state: StateFlow<SpeechRuntimeState>

    /** Actual session decoder path, not an accuracy or readiness claim. */
    val decoderDescription: String? get() = null

    suspend fun preflight(): SpeechRuntimeState

    /** Candidate validation must not consult cached readiness or silently accept an unsupported runtime. */
    suspend fun preflight(candidateManifest: SpeechBundleManifest): SpeechRuntimeState =
        SpeechRuntimeState.Unavailable("Runtime tidak mendukung validasi kandidat speech bundle.")

    suspend fun beginSession(startedAtUtcMillis: Long, languageCode: String, sessionId: String)

    /** Feeds one frame to the real Silero VAD and returns its current speech decision. */
    suspend fun detectSpeech(samples: FloatArray): Boolean

    suspend fun transcribe(samples: FloatArray, startOffsetMs: Long, vadAlreadyAccepted: Boolean = false): List<SpeechSegment>

    /** Completes the utterance; implementations renew any input-finished stream. */
    suspend fun finishUtterance(): List<SpeechSegment> = emptyList()

    suspend fun endSession(): List<SpeechSegment>

    /** Releases native resources and completes before another runtime is loaded. */
    suspend fun closeAndWait() {
        close()
    }

    fun close()
}
