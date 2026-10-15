package com.stressdetector.mobile.domain.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

data class AudioGap(
    val startOffsetMs: Long,
    val durationMs: Long,
    val reason: String,
    /** A dropped ASR work item can still have observed PCM in the recorded WAV. */
    val recordedPcmPresent: Boolean = false
)

data class SpeechProcessingState(
    val sessionId: String? = null,
    val backlogFrames: Int = 0,
    val droppedFrameCount: Long = 0L,
    val audioGaps: List<AudioGap> = emptyList(),
    val error: String? = null,
    val isDraining: Boolean = false,
    val decoderDescription: String? = null
)

data class LiveTranscriptState(
    val sessionId: String? = null,
    val segments: List<SpeechSegment> = emptyList(),
    val processing: SpeechProcessingState = SpeechProcessingState()
)

/**
 * Single immutable live projection consumed by Verbatim+ UI.
 * Audio and biosignal processing remain owned by their respective engines;
 * this type only crosses the engine/ViewModel boundary.
 */
data class UnifiedSessionLiveSnapshot(
    val sessionId: String? = null,
    /** Monotonic generation prevents same-ID process/restart frames crossing sessions. */
    val generation: Long = 0L,
    val biosignal: LiveBiosignalSnapshot = LiveBiosignalSnapshot(),
    val acousticAnalysis: AcousticAnalysisSnapshot? = null,
    val livePitch: List<LivePitchObservation> = emptyList(),
    val audioStartSessionElapsedMs: Long? = null,
    val transcript: LiveTranscriptState = LiveTranscriptState(),
    val backlogFrames: Int = 0,
    val audioGaps: List<AudioGap> = emptyList(),
    val updatedAtMillis: Long = 0L
)

private val EMPTY_SPEECH_PROCESSING_STATE = MutableStateFlow(SpeechProcessingState()).asStateFlow()

sealed interface AudioOperationResult<out T> {
    data class Success<T>(val value: T) : AudioOperationResult<T>
    data class Unavailable(val reason: String) : AudioOperationResult<Nothing>
    data class Failure(val message: String, val cause: Throwable? = null) : AudioOperationResult<Nothing>
}

sealed interface AudioCaptureState {
    data object Idle : AudioCaptureState
    data class Recording(val sessionId: String, val startedAtUtcMillis: Long) : AudioCaptureState
    data class Paused(val sessionId: String, val startedAtUtcMillis: Long) : AudioCaptureState
    data class Stopped(val recording: RecordedAudio) : AudioCaptureState
    data class Failed(val message: String) : AudioCaptureState
}

sealed interface SpeechRuntimeState {
    /** Runtime validity is being reconciled from the active, verified bundle. */
    data object Checking : SpeechRuntimeState
    data object Ready : SpeechRuntimeState
    data class Unavailable(val reason: String) : SpeechRuntimeState
    data class Failed(val message: String) : SpeechRuntimeState
}

data class RecordedAudio(
    val sessionId: String,
    val encryptedRelativePath: String,
    val startedAtUtcMillis: Long,
    val endedAtUtcMillis: Long,
    val durationMs: Long,
    val sampleRateHz: Int,
    val audioGaps: List<AudioGap> = emptyList(),
    /** Optional encrypted measured waveform/F0 archive; null for historical recordings. */
    val signalTraceRoot: String? = null
)

data class AudioCaptureStart(val startedAtUtcMillis: Long, val startedAtMonotonicMillis: Long)

data class AudioRecordingStopResult(
    val audio: RecordedAudio,
    val finalTranscript: List<SpeechSegment>,
    val audioGaps: List<AudioGap> = emptyList(),
    val droppedFrameCount: Long = 0L,
    /** Non-fatal STT/VAD/diarization/signal-archive evidence; finalization must not hide degradation. */
    val processingError: String? = null
)

sealed interface AudioInputSource {
    data object Microphone : AudioInputSource
    data class Imported(val uriString: String) : AudioInputSource
}

interface AudioIntelligenceEngine {
    val captureState: StateFlow<AudioCaptureState>
    val speechRuntimeState: StateFlow<SpeechRuntimeState>

    /**
     * Reconciles the active speech bundle with the native runtime before a
     * session is allowed to create consent, service, or microphone resources.
     */
    suspend fun refreshSpeechRuntime(): SpeechRuntimeState = speechRuntimeState.value

    val transcriptFlow: Flow<SpeechSegment>

    /** Frame-level acoustic evidence derived from the real PCM capture path. */
    val acousticAnalysisFlow: Flow<AcousticAnalysisSnapshot>
        get() = emptyFlow()

    /** Bounded, observed openSMILE pitch windows from the same recorded PCM. */
    val livePitchFlow: Flow<LivePitchObservation>
        get() = emptyFlow()
    val speechProcessingState: StateFlow<SpeechProcessingState>
        get() = EMPTY_SPEECH_PROCESSING_STATE

    suspend fun startRecording(sessionId: String, userId: String, languageCode: String = "id"): AudioOperationResult<AudioCaptureStart>

    /** Uses the same PCM/STT/acoustic pipeline for microphone and supported WAV imports. */
    suspend fun startRecording(
        sessionId: String,
        userId: String,
        languageCode: String,
        inputSource: AudioInputSource
    ): AudioOperationResult<AudioCaptureStart> = when (inputSource) {
        AudioInputSource.Microphone -> startRecording(sessionId, userId, languageCode)
        is AudioInputSource.Imported -> AudioOperationResult.Unavailable(
            "Audio import is not supported by this speech runtime."
        )
    }
    suspend fun pauseRecording(): AudioOperationResult<Unit>
    suspend fun resumeRecording(): AudioOperationResult<Unit>
    suspend fun stopRecording(): AudioOperationResult<RecordedAudio>

    /**
     * Stop barrier that includes the transcript produced before the recorder
     * resources are released. The default preserves compatibility with older
     * capture implementations while production uses the richer result.
     */
    suspend fun stopRecordingWithTranscript(): AudioOperationResult<AudioRecordingStopResult> = when (val result = stopRecording()) {
        is AudioOperationResult.Success -> AudioOperationResult.Success(
            AudioRecordingStopResult(result.value, emptyList())
        )
        is AudioOperationResult.Unavailable -> result
        is AudioOperationResult.Failure -> result
    }
}
