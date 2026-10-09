package com.stressdetector.mobile.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.stressdetector.mobile.domain.engine.AcousticAnalysisSnapshot
import com.stressdetector.mobile.domain.engine.AcousticAudioFrame
import com.stressdetector.mobile.domain.engine.AcousticFrameAnalyzer
import com.stressdetector.mobile.domain.engine.AudioCaptureStart
import com.stressdetector.mobile.domain.engine.AudioCaptureState
import com.stressdetector.mobile.domain.engine.AudioGap
import com.stressdetector.mobile.domain.engine.AudioInputSource
import com.stressdetector.mobile.domain.engine.AudioIntelligenceEngine
import com.stressdetector.mobile.domain.engine.AudioOperationResult
import com.stressdetector.mobile.domain.engine.AudioRecordingStopResult
import com.stressdetector.mobile.domain.engine.RecordedAudio
import com.stressdetector.mobile.domain.engine.SpeechProcessingState
import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import com.stressdetector.mobile.domain.engine.SpeechSegment
import com.stressdetector.mobile.security.EncryptedStorageService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface SpeechTranscriptionWork {
    data class Capture(val reference: File) : SpeechTranscriptionWork
    data class Frame(val reference: File) : SpeechTranscriptionWork
    data class Utterance(val reference: File) : SpeechTranscriptionWork
    data object FlushCapture : SpeechTranscriptionWork
    data object FinalizeUtterance : SpeechTranscriptionWork
}

/** Real PCM capture with fail-closed runtime status and encrypted-at-rest publication. */
@Suppress("LargeClass", "TooManyFunctions")
class RealAudioIntelligenceEngine(
    context: Context,
    private val encryptedStorage: EncryptedStorageService,
    private val speechRuntime: LocalSpeechRuntime,
    private val captureOwnership: AudioCaptureOwnership,
    private val audioFocus: AudioFocusCoordinator? = null
) : AudioIntelligenceEngine {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _captureState = MutableStateFlow<AudioCaptureState>(AudioCaptureState.Idle)
    override val captureState: StateFlow<AudioCaptureState> = _captureState.asStateFlow()
    override val speechRuntimeState: StateFlow<SpeechRuntimeState> = speechRuntime.state
    private val _transcriptFlow = MutableSharedFlow<SpeechSegment>(extraBufferCapacity = 16)
    override val transcriptFlow: Flow<SpeechSegment> = _transcriptFlow.asSharedFlow()
    private val _acousticAnalysisFlow = MutableSharedFlow<AcousticAnalysisSnapshot>(extraBufferCapacity = 64)
    override val acousticAnalysisFlow: Flow<AcousticAnalysisSnapshot> = _acousticAnalysisFlow.asSharedFlow()
    private val _livePitchFlow = MutableSharedFlow<com.stressdetector.mobile.domain.engine.LivePitchObservation>(extraBufferCapacity = 64)
    override val livePitchFlow: Flow<com.stressdetector.mobile.domain.engine.LivePitchObservation> = _livePitchFlow.asSharedFlow()
    private val acousticFrameAnalyzer = AcousticFrameAnalyzer()
    private val prosodyExtractor = OpenSmileProsodyExtractor(appContext)
    private val cueAnalyzer = com.stressdetector.mobile.domain.engine.VerbatimCueAnalyzer()

    private var recorder: AudioRecord? = null
    private var writerJob: Job? = null
    private var temporaryWav: File? = null
    private var currentSessionId: String? = null
    private var currentUserId: String? = null
    private var currentLanguageCode = "id"
    private var startedAtUtcMillis = 0L
    private var captureLease: AudioCaptureLease? = null
    private val vadMutex = Mutex()
    private val recognizerMutex = Mutex()
    private val transcriptById = linkedMapOf<String, SpeechSegment>()
    private val _speechProcessingState = MutableStateFlow(SpeechProcessingState())
    override val speechProcessingState: StateFlow<SpeechProcessingState> = _speechProcessingState.asStateFlow()
    private var pauseStartedAtElapsedNanos: Long? = null
    private var importedSourceFile: File? = null
    private var capturedPcmDurationMs: Long = 0L

    @Volatile private var captureActive = false

    @Volatile private var capturePaused = false

    override suspend fun refreshSpeechRuntime(): SpeechRuntimeState = withContext(Dispatchers.IO) {
        runCatching { speechRuntime.preflight() }
            .getOrElse { error ->
                SpeechRuntimeState.Failed(
                    error.message ?: "Speech runtime initialization failed."
                )
            }
    }

    override suspend fun startRecording(sessionId: String, userId: String, languageCode: String): AudioOperationResult<AudioCaptureStart> =
        startRecording(sessionId, userId, languageCode, AudioInputSource.Microphone)

    override suspend fun startRecording(
        sessionId: String,
        userId: String,
        languageCode: String,
        inputSource: AudioInputSource
    ): AudioOperationResult<AudioCaptureStart> = when (inputSource) {
        AudioInputSource.Microphone -> startMicrophoneRecording(sessionId, userId, languageCode)
        is AudioInputSource.Imported -> startImportedRecording(sessionId, userId, languageCode, inputSource)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun startMicrophoneRecording(
        sessionId: String,
        userId: String,
        languageCode: String
    ): AudioOperationResult<AudioCaptureStart> {
        return withContext(Dispatchers.IO) {
            if (sessionId.isBlank() || userId.isBlank()) {
                return@withContext AudioOperationResult.Failure("sessionId and userId are required.")
            }
            if (captureActive) return@withContext AudioOperationResult.Failure("Audio capture is already active.")
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                _captureState.value = AudioCaptureState.Failed("Microphone permission is not granted.")
                return@withContext AudioOperationResult.Unavailable("Microphone permission is not granted.")
            }

            when (val speechReadiness = refreshSpeechRuntime()) {
                is SpeechRuntimeState.Ready -> Unit
                SpeechRuntimeState.Checking -> {
                    return@withContext AudioOperationResult.Unavailable(
                        "Speech bundle sedang diperiksa. Coba lagi sebentar."
                    )
                }
                is SpeechRuntimeState.Unavailable -> {
                    _captureState.value = AudioCaptureState.Failed(speechReadiness.reason)
                    return@withContext AudioOperationResult.Unavailable(speechReadiness.reason)
                }
                is SpeechRuntimeState.Failed -> {
                    _captureState.value = AudioCaptureState.Failed(speechReadiness.message)
                    return@withContext AudioOperationResult.Failure(speechReadiness.message)
                }
            }

            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minimum <= 0) {
                _captureState.value = AudioCaptureState.Failed("AudioRecord reported an invalid buffer size.")
                return@withContext AudioOperationResult.Unavailable("Microphone capture is unavailable on this device.")
            }

            val lease = captureOwnership.tryAcquire(AudioCaptureOwner.VERBATIM)
                ?: return@withContext AudioOperationResult.Unavailable(
                    "Mikrofon sedang digunakan AI+. Hentikan voice input terlebih dahulu."
                )
            captureLease = lease
            if (audioFocus?.requestRecordingFocus() == false) {
                captureLease?.release()
                captureLease = null
                return@withContext AudioOperationResult.Unavailable("Audio focus tidak tersedia untuk rekaman.")
            }

            try {
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE_HZ,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    minimum * 2
                )
                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord.release()
                    _captureState.value = AudioCaptureState.Failed("AudioRecord initialization failed.")
                    return@withContext AudioOperationResult.Unavailable("Microphone capture could not initialize.")
                }

                val stagingDir = File(appContext.cacheDir, "verbatim_audio").apply { mkdirs() }
                val stagingFile = File(stagingDir, "$sessionId.pcm.wav")
                if (stagingFile.exists() && !stagingFile.delete()) {
                    audioRecord.release()
                    captureLease?.release()
                    captureLease = null
                    audioFocus?.abandonRecordingFocus()
                    return@withContext AudioOperationResult.Failure("Previous audio staging file could not be replaced.")
                }

                currentSessionId = sessionId
                currentUserId = userId
                currentLanguageCode = languageCode
                temporaryWav = stagingFile
                recorder = audioRecord
                capturePaused = false
                captureActive = true
                pauseStartedAtElapsedNanos = null
                _speechProcessingState.value = SpeechProcessingState(sessionId = sessionId)
                audioRecord.startRecording()
                startedAtUtcMillis = System.currentTimeMillis()
                val startedAtMonotonicMillis = SystemClock.elapsedRealtime()
                transcriptById.clear()
                acousticFrameAnalyzer.reset(sessionId)
                speechRuntime.beginSession(startedAtUtcMillis, languageCode, sessionId)
                _speechProcessingState.update { it.copy(decoderDescription = speechRuntime.decoderDescription) }
                _captureState.value = AudioCaptureState.Recording(sessionId, startedAtUtcMillis)
                writerJob = scope.launch { capturePcm(audioRecord, stagingFile, minimum * 2) }
                AudioOperationResult.Success(
                    AudioCaptureStart(startedAtUtcMillis, startedAtMonotonicMillis)
                )
            } catch (error: Exception) {
                captureActive = false
                releaseRecorder()
                temporaryWav?.delete()
                temporaryWav = null
                currentSessionId = null
                currentUserId = null
                _captureState.value = AudioCaptureState.Failed(error.message ?: "Audio capture failed to start.")
                captureLease?.release()
                captureLease = null
                audioFocus?.abandonRecordingFocus()
                AudioOperationResult.Failure("Audio capture failed to start.", error)
            }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun startImportedRecording(
        sessionId: String,
        userId: String,
        languageCode: String,
        source: AudioInputSource.Imported
    ): AudioOperationResult<AudioCaptureStart> = withContext(Dispatchers.IO) {
        if (sessionId.isBlank() || userId.isBlank()) {
            return@withContext AudioOperationResult.Failure("sessionId and userId are required.")
        }
        if (captureActive) return@withContext AudioOperationResult.Failure("Audio capture is already active.")
        when (val speechReadiness = refreshSpeechRuntime()) {
            is SpeechRuntimeState.Ready -> Unit
            SpeechRuntimeState.Checking -> return@withContext AudioOperationResult.Unavailable(
                "Speech bundle sedang diperiksa. Coba lagi sebentar."
            )
            is SpeechRuntimeState.Unavailable -> {
                _captureState.value = AudioCaptureState.Failed(speechReadiness.reason)
                return@withContext AudioOperationResult.Unavailable(speechReadiness.reason)
            }
            is SpeechRuntimeState.Failed -> {
                _captureState.value = AudioCaptureState.Failed(speechReadiness.message)
                return@withContext AudioOperationResult.Failure(speechReadiness.message)
            }
        }

        val stagingDir = File(appContext.cacheDir, "verbatim_audio").apply { mkdirs() }
        val sourceFile = File(stagingDir, "$sessionId.import.source.wav")
        val outputFile = File(stagingDir, "$sessionId.pcm.wav")
        try {
            sourceFile.delete()
            outputFile.delete()
            require(source.uriString.isNotBlank()) { "Imported audio URI is empty." }
            val input = requireNotNull(appContext.contentResolver.openInputStream(Uri.parse(source.uriString))) {
                "Imported audio could not be opened."
            }
            input.use {
                FileOutputStream(sourceFile).use { output -> input.copyTo(output) }
            }
            val format = WavPcm16Reader.readFormat(sourceFile)
            format.requireCanonical()

            currentSessionId = sessionId
            currentUserId = userId
            currentLanguageCode = languageCode
            temporaryWav = outputFile
            importedSourceFile = sourceFile
            recorder = null
            capturePaused = false
            captureActive = true
            pauseStartedAtElapsedNanos = null
            capturedPcmDurationMs = format.durationMs
            _speechProcessingState.value = SpeechProcessingState(sessionId = sessionId)
            startedAtUtcMillis = System.currentTimeMillis()
            val startedAtMonotonicMillis = SystemClock.elapsedRealtime()
            transcriptById.clear()
            acousticFrameAnalyzer.reset(sessionId)
            speechRuntime.beginSession(startedAtUtcMillis, languageCode, sessionId)
            _speechProcessingState.update { it.copy(decoderDescription = speechRuntime.decoderDescription) }
            _captureState.value = AudioCaptureState.Recording(sessionId, startedAtUtcMillis)
            writerJob = scope.launch {
                FileInputStream(sourceFile).use { input ->
                    WavPcm16Reader.skipFully(input, format.dataOffset)
                    var remaining = format.dataLength
                    processPcm(
                        readPcm = { buffer ->
                            if (remaining <= 0L) {
                                -1
                            } else {
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count > 0) remaining -= count
                                count
                            }
                        },
                        destination = outputFile,
                        bufferSize = IMPORT_FRAME_BYTES,
                        endOfInputIsNormal = true,
                        allowPause = false
                    )
                }
            }
            AudioOperationResult.Success(AudioCaptureStart(startedAtUtcMillis, startedAtMonotonicMillis))
        } catch (error: Exception) {
            captureActive = false
            writerJob = null
            sourceFile.delete()
            outputFile.delete()
            importedSourceFile = null
            temporaryWav = null
            currentSessionId = null
            currentUserId = null
            _captureState.value = AudioCaptureState.Failed(error.message ?: "Audio import failed.")
            AudioOperationResult.Failure("Audio import failed.", error)
        }
    }

    override suspend fun pauseRecording(): AudioOperationResult<Unit> = withContext(Dispatchers.IO) {
        val state = _captureState.value as? AudioCaptureState.Recording
            ?: return@withContext AudioOperationResult.Failure("Audio capture is not recording.")
        try {
            pauseStartedAtElapsedNanos = System.nanoTime()
            recorder?.stop()
            capturePaused = true
            _captureState.value = AudioCaptureState.Paused(state.sessionId, state.startedAtUtcMillis)
            AudioOperationResult.Success(Unit)
        } catch (error: Exception) {
            _captureState.value = AudioCaptureState.Failed(error.message ?: "Audio capture could not pause.")
            AudioOperationResult.Failure("Audio capture could not pause.", error)
        }
    }

    override suspend fun resumeRecording(): AudioOperationResult<Unit> = withContext(Dispatchers.IO) {
        val state = _captureState.value as? AudioCaptureState.Paused
            ?: return@withContext AudioOperationResult.Failure("Audio capture is not paused.")
        try {
            recorder?.startRecording()
            capturePaused = false
            _captureState.value = AudioCaptureState.Recording(state.sessionId, state.startedAtUtcMillis)
            AudioOperationResult.Success(Unit)
        } catch (error: Exception) {
            _captureState.value = AudioCaptureState.Failed(error.message ?: "Audio capture could not resume.")
            AudioOperationResult.Failure("Audio capture could not resume.", error)
        }
    }

    override suspend fun stopRecording(): AudioOperationResult<RecordedAudio> = when (val result = stopRecordingWithTranscript()) {
        is AudioOperationResult.Success -> AudioOperationResult.Success(result.value.audio)
        is AudioOperationResult.Unavailable -> result
        is AudioOperationResult.Failure -> result
    }

    override suspend fun stopRecordingWithTranscript(): AudioOperationResult<AudioRecordingStopResult> = withContext(Dispatchers.IO) {
        if (!captureActive && recorder == null && currentSessionId == null) {
            return@withContext AudioOperationResult.Failure("Audio capture is not active.")
        }
        captureActive = false
        runCatching { recorder?.stop() }
        writerJob?.join()
        // Read the failure after the capture/writer barrier. A recorder error
        // can be raised while the final PCM block is still being published.
        val captureFailure = (_captureState.value as? AudioCaptureState.Failed)?.message
        val finalSegments = runCatching { recognizerMutex.withLock { speechRuntime.endSession() } }
            .getOrElse { error ->
                releaseRecorder()
                captureLease?.release()
                captureLease = null
                audioFocus?.abandonRecordingFocus()
                _captureState.value = AudioCaptureState.Failed(
                    error.message ?: "Speech runtime could not finalize the recording."
                )
                return@withContext AudioOperationResult.Failure(
                    "Speech runtime could not finalize the recording.",
                    error
                )
            }
        finalSegments.forEach { segment -> publishTranscript(segment) }
        releaseRecorder()
        captureLease?.release()
        captureLease = null
        audioFocus?.abandonRecordingFocus()

        val sessionId = currentSessionId
            ?: return@withContext AudioOperationResult.Failure("Audio session identity is missing.")
        val userId = currentUserId
            ?: return@withContext AudioOperationResult.Failure("Audio owner identity is missing.")
        val wav = temporaryWav
            ?: return@withContext AudioOperationResult.Failure("Audio staging file is missing.")
        if (captureFailure != null) {
            wav.delete()
            temporaryWav = null
            currentSessionId = null
            currentUserId = null
            currentLanguageCode = "id"
            return@withContext AudioOperationResult.Failure(captureFailure)
        }
        if (!wav.isFile || wav.length() <= WAV_HEADER_BYTES) {
            wav.delete()
            _captureState.value = AudioCaptureState.Failed("No microphone audio was captured.")
            return@withContext AudioOperationResult.Failure("No microphone audio was captured.")
        }

        try {
            val encrypted = FileInputStream(wav).use {
                encryptedStorage.writeEncryptedFile(userId, "verbatim/$sessionId/audio.wav.enc", it)
            }
            val endedAt = System.currentTimeMillis()
            val recorded = RecordedAudio(
                sessionId = sessionId,
                encryptedRelativePath = encrypted.relativePath,
                startedAtUtcMillis = startedAtUtcMillis,
                endedAtUtcMillis = endedAt,
                durationMs = capturedPcmDurationMs.takeIf { it > 0L }
                    ?: (endedAt - startedAtUtcMillis).coerceAtLeast(0L),
                sampleRateHz = SAMPLE_RATE_HZ,
                audioGaps = _speechProcessingState.value.audioGaps
            )
            _captureState.value = AudioCaptureState.Stopped(recorded)
            AudioOperationResult.Success(
                AudioRecordingStopResult(
                    audio = recorded,
                    finalTranscript = synchronized(transcriptById) {
                        transcriptById.values.filter(SpeechSegment::isFinal).toList()
                    },
                    audioGaps = _speechProcessingState.value.audioGaps,
                    droppedFrameCount = _speechProcessingState.value.droppedFrameCount,
                    processingError = _speechProcessingState.value.error
                )
            )
        } catch (error: Exception) {
            _captureState.value = AudioCaptureState.Failed(error.message ?: "Encrypted audio publication failed.")
            AudioOperationResult.Failure("Encrypted audio publication failed.", error)
        } finally {
            wav.delete()
            temporaryWav = null
            currentSessionId = null
            currentUserId = null
            currentLanguageCode = "id"
            importedSourceFile?.delete()
            importedSourceFile = null
            capturedPcmDurationMs = 0L
            writerJob = null
        }
    }

    private suspend fun capturePcm(audioRecord: AudioRecord, destination: File, bufferSize: Int) = processPcm(
        readPcm = { buffer -> audioRecord.read(buffer, 0, buffer.size) },
        destination = destination,
        bufferSize = bufferSize,
        endOfInputIsNormal = false,
        allowPause = true
    )

    /** Shared PCM-to-WAV/STT/acoustic pipeline for microphone and imported WAV input. */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ThrowsCount")
    private suspend fun processPcm(
        readPcm: suspend (ByteArray) -> Int,
        destination: File,
        bufferSize: Int,
        endOfInputIsNormal: Boolean,
        allowPause: Boolean
    ) {
        val sessionId = currentSessionId ?: return
        var audioBytes = 0L
        val sampleClock = AudioSampleClock(SAMPLE_RATE_HZ)
        var sequence = 0L
        var streamingUtteranceActive = false
        var lastCaptureEndMs: Long? = null
        val segmenter = SpeechUtteranceSegmenter(sampleRateHz = SAMPLE_RATE_HZ)
        val utteranceSpool = SpeechUtteranceSpool(
            directory = File(destination.parentFile, "${destination.nameWithoutExtension}.utterances"),
            sampleRateHz = SAMPLE_RATE_HZ
        )
        val frameSpool = SpeechFrameSpool(
            directory = File(destination.parentFile, "${destination.nameWithoutExtension}.frames"),
            sampleRateHz = SAMPLE_RATE_HZ
        )
        val workQueue = SpeechTranscriptionWorkQueue(
            File(destination.parentFile, "${destination.nameWithoutExtension}.work")
        )
        val pendingWork = AtomicInteger(0)
        val captureHandoffPending = AtomicInteger(0)

        fun updateBacklog() {
            _speechProcessingState.update {
                it.copy(backlogFrames = pendingWork.get() + captureHandoffPending.get())
            }
        }

        fun recordGap(gap: AudioGap) {
            _speechProcessingState.update { current ->
                current.copy(audioGaps = (current.audioGaps + gap).takeLast(MAX_DIAGNOSTIC_GAPS))
            }
        }

        fun recordProcessingError(error: Throwable) {
            _speechProcessingState.update {
                it.copy(error = error.message ?: "Speech transcription failed.")
            }
        }

        suspend fun enqueueCaptureFrame(frame: SpeechFrame) {
            val reference = runCatching { frameSpool.write(frame) }.getOrElse { error ->
                recordProcessingError(error)
                recordGap(AudioGap(frame.startOffsetMs, frame.durationMs, "FRAME_SPOOL_WRITE_FAILED"))
                return
            }
            val accepted = workQueue.offer(SpeechTranscriptionWork.Capture(reference))
            if (accepted) {
                pendingWork.incrementAndGet()
                updateBacklog()
            } else {
                frameSpool.delete(reference)
                _speechProcessingState.update { it.copy(droppedFrameCount = it.droppedFrameCount + 1L) }
                recordGap(AudioGap(frame.startOffsetMs, frame.durationMs, "TRANSCRIPTION_QUEUE_FULL", recordedPcmPresent = true))
            }
        }

        suspend fun enqueueTranscriptionFrame(frame: SpeechFrame) {
            val reference = runCatching { frameSpool.write(frame) }.getOrElse { error ->
                recordProcessingError(error)
                recordGap(AudioGap(frame.startOffsetMs, frame.durationMs, "FRAME_SPOOL_WRITE_FAILED", recordedPcmPresent = true))
                return
            }
            val accepted = workQueue.offer(
                SpeechTranscriptionWork.Frame(reference),
                allowAfterClose = true
            )
            if (accepted) {
                pendingWork.incrementAndGet()
                updateBacklog()
            } else {
                frameSpool.delete(reference)
                _speechProcessingState.update { it.copy(droppedFrameCount = it.droppedFrameCount + 1L) }
                recordGap(AudioGap(frame.startOffsetMs, frame.durationMs, "TRANSCRIPTION_QUEUE_CLOSED", recordedPcmPresent = true))
            }
        }

        suspend fun processWork(work: SpeechTranscriptionWork) {
            try {
                when (work) {
                    is SpeechTranscriptionWork.Capture -> {
                        val frame = frameSpool.read(work.reference)
                        if (lastCaptureEndMs?.let { frame.startOffsetMs - it > CAPTURE_GAP_TOLERANCE_MS } == true) {
                            val pending = segmenter.finish()
                            if (streamingUtteranceActive || pending != null) {
                                check(workQueue.offer(SpeechTranscriptionWork.FinalizeUtterance, allowAfterClose = true)) {
                                    "Speech boundary after capture gap could not be queued."
                                }
                            }
                            streamingUtteranceActive = false
                        }
                        lastCaptureEndMs = frame.startOffsetMs + frame.durationMs
                        val speechDetected = vadMutex.withLock {
                            speechRuntime.detectSpeech(frame.samples)
                        }
                        // Both Indonesian and English publish replaceable
                        // partials from the same utterance ID. Native
                        // diarization runs only after endpoint finalization.
                        val emission = segmenter.appendStreaming(frame, speechDetected)
                        if (emission.framesToTranscribe.isNotEmpty()) streamingUtteranceActive = true
                        for (transcriptionFrame in emission.framesToTranscribe) {
                            enqueueTranscriptionFrame(transcriptionFrame)
                        }
                        if (emission.completedUtterance != null) {
                            if (!workQueue.offer(
                                    SpeechTranscriptionWork.FinalizeUtterance,
                                    allowAfterClose = true
                                )
                            ) {
                                recordProcessingError(IllegalStateException("Speech finalization could not be queued."))
                            }
                            streamingUtteranceActive = false
                        }
                        frameSpool.delete(work.reference)
                        pendingWork.decrementAndGet()
                        updateBacklog()
                    }

                    is SpeechTranscriptionWork.Frame -> {
                        val frame = frameSpool.read(work.reference)
                        val segments = recognizerMutex.withLock {
                            speechRuntime.transcribe(
                                samples = frame.samples,
                                startOffsetMs = frame.startOffsetMs,
                                vadAlreadyAccepted = true
                            )
                        }
                        for (segment in segments) publishTranscript(segment)
                        frameSpool.delete(work.reference)
                        pendingWork.decrementAndGet()
                        updateBacklog()
                    }

                    SpeechTranscriptionWork.FlushCapture -> {
                        val finalUtterance = segmenter.finish()
                        if (streamingUtteranceActive || finalUtterance != null) {
                            if (!workQueue.offer(
                                    SpeechTranscriptionWork.FinalizeUtterance,
                                    allowAfterClose = true
                                )
                            ) {
                                recordProcessingError(IllegalStateException("Speech finalization could not be queued."))
                            }
                        }
                        streamingUtteranceActive = false
                    }

                    SpeechTranscriptionWork.FinalizeUtterance -> {
                        val segments = recognizerMutex.withLock { speechRuntime.finishUtterance() }
                        for (segment in segments) publishTranscript(segment)
                    }

                    is SpeechTranscriptionWork.Utterance -> {
                        val utterance = utteranceSpool.read(work.reference)
                        val segments = recognizerMutex.withLock {
                            speechRuntime.transcribe(
                                samples = utterance.samples,
                                startOffsetMs = utterance.startOffsetMs,
                                vadAlreadyAccepted = true
                            ) + speechRuntime.finishUtterance()
                        }
                        for (segment in segments) publishTranscript(segment)
                        utteranceSpool.delete(work.reference)
                        pendingWork.decrementAndGet()
                        updateBacklog()
                    }
                }
            } catch (error: Exception) {
                recordProcessingError(error)
                when (work) {
                    is SpeechTranscriptionWork.Capture -> {
                        frameSpool.delete(work.reference)
                        pendingWork.decrementAndGet()
                        updateBacklog()
                    }
                    is SpeechTranscriptionWork.Frame -> {
                        frameSpool.delete(work.reference)
                        pendingWork.decrementAndGet()
                        updateBacklog()
                    }
                    is SpeechTranscriptionWork.Utterance -> {
                        utteranceSpool.delete(work.reference)
                        pendingWork.decrementAndGet()
                        updateBacklog()
                    }
                    SpeechTranscriptionWork.FlushCapture -> Unit
                    SpeechTranscriptionWork.FinalizeUtterance -> Unit
                }
            }
        }

        val transcriptionJob = scope.launch {
            while (workQueue.signals.receiveCatching().getOrNull() != null) {
                while (true) {
                    val work = workQueue.poll() ?: break
                    processWork(work)
                }
            }
            while (true) {
                val work = workQueue.poll() ?: break
                processWork(work)
            }
        }
        // AudioRecord must never perform per-frame disk I/O. This bounded
        // handoff keeps capture responsive while the disk-backed queue absorbs
        // normal decoder latency. Overflow becomes an explicit diagnostic gap.
        val captureHandoff = Channel<SpeechFrame>(CAPTURE_HANDOFF_CAPACITY)
        val captureHandoffJob = scope.launch {
            for (frame in captureHandoff) {
                try {
                    enqueueCaptureFrame(frame)
                } finally {
                    captureHandoffPending.decrementAndGet()
                    updateBacklog()
                }
            }
        }

        val pitchProcessor = LivePitchProcessor(sessionId, scope, prosodyExtractor::analyze) { observation ->
            _livePitchFlow.emit(observation)
        }

        try {
            FileOutputStream(destination).use { output ->
                output.write(ByteArray(WAV_HEADER_BYTES))
                val buffer = ByteArray(bufferSize)
                var emptyReadCount = 0
                while (scope.isActive && captureActive) {
                    if (allowPause && capturePaused) {
                        delay(20)
                        continue
                    }
                    if (allowPause) {
                        pauseStartedAtElapsedNanos?.let { startedNanos ->
                            val pauseDurationMs = ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0L)
                            if (pauseDurationMs > 0L) {
                                recordGap(sampleClock.addGap(pauseDurationMs, "PAUSE"))
                            }
                            pauseStartedAtElapsedNanos = null
                        }
                    }

                    val count = readPcm(buffer)
                    if (count < 0) {
                        if (endOfInputIsNormal) break
                        throw IllegalStateException("AudioRecord read failed with code $count")
                    }
                    when {
                        count > 0 -> {
                            emptyReadCount = 0
                            val evenCount = count - (count % BYTES_PER_SAMPLE)
                            if (evenCount == 0) continue
                            output.write(buffer, 0, evenCount)
                            audioBytes += evenCount

                            val sampleCount = evenCount / BYTES_PER_SAMPLE
                            val timing = sampleClock.next(sampleCount)
                            val samples = FloatArray(sampleCount)
                            val shorts = ShortArray(sampleCount)
                            ByteBuffer.wrap(buffer, 0, evenCount)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                                .get(shorts)
                            for (index in shorts.indices) samples[index] = shorts[index] / PCM16_SCALE

                            val frame = SpeechFrame(
                                samples = samples,
                                startOffsetMs = timing.startOffsetMs,
                                durationMs = timing.durationMs,
                                sequence = sequence++,
                                sampleStartIndex = timing.sampleStartIndex
                            )
                            _acousticAnalysisFlow.tryEmit(
                                acousticFrameAnalyzer.analyze(
                                    AcousticAudioFrame(
                                        sessionId = sessionId,
                                        samples = samples,
                                        startOffsetMs = timing.startOffsetMs,
                                        durationMs = timing.durationMs
                                    )
                                )
                            )
                            pitchProcessor.offer(frame)
                            captureHandoffPending.incrementAndGet()
                            if (!captureHandoff.trySend(frame).isSuccess) {
                                captureHandoffPending.decrementAndGet()
                                _speechProcessingState.update {
                                    it.copy(droppedFrameCount = it.droppedFrameCount + 1L)
                                }
                                recordGap(
                                    AudioGap(
                                        frame.startOffsetMs,
                                        frame.durationMs,
                                        "CAPTURE_HANDOFF_QUEUE_FULL",
                                        recordedPcmPresent = true
                                    )
                                )
                                updateBacklog()
                            } else {
                                updateBacklog()
                            }
                        }
                        count == 0 -> {
                            emptyReadCount++
                            if (emptyReadCount >= MAX_EMPTY_READS) {
                                throw IllegalStateException("AudioRecord returned no audio data.")
                            }
                            delay(10)
                        }
                        else -> throw IllegalStateException("AudioRecord read failed with code $count")
                    }
                }
                pauseStartedAtElapsedNanos?.let { startedNanos ->
                    val pauseDurationMs = ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0L)
                    if (pauseDurationMs > 0L) {
                        recordGap(sampleClock.addGap(pauseDurationMs, "PAUSE"))
                    }
                    pauseStartedAtElapsedNanos = null
                }
                output.fd.sync()
            }

            captureHandoff.close()
            captureHandoffJob.join()
            pitchProcessor.close()
            if (!workQueue.offer(SpeechTranscriptionWork.FlushCapture)) {
                recordProcessingError(IllegalStateException("Speech capture drain could not be queued."))
            }
            _speechProcessingState.update { it.copy(isDraining = true) }
            workQueue.close()
            transcriptionJob.join()
            _speechProcessingState.update { it.copy(isDraining = false, backlogFrames = pendingWork.get()) }
            writeWavHeader(destination, audioBytes)
            capturedPcmDurationMs = audioBytes * MILLIS_PER_SECOND / (SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE)
            frameSpool.cleanup()
            utteranceSpool.cleanup()
        } catch (error: Exception) {
            pitchProcessor.cancel()
            captureHandoff.close()
            captureHandoffJob.cancelAndJoin()
            workQueue.close()
            transcriptionJob.cancelAndJoin()
            frameSpool.cleanup()
            utteranceSpool.cleanup()
            captureActive = false
            recordProcessingError(error)
            _captureState.value = AudioCaptureState.Failed(error.message ?: "Microphone capture failed.")
        }
    }

    private suspend fun publishTranscript(segment: SpeechSegment) {
        var normalized = if (segment.sessionId.isBlank()) {
            segment.copy(sessionId = currentSessionId.orEmpty())
        } else {
            segment
        }
        if (normalized.isFinal) {
            val cues = cueAnalyzer.analyze(normalized).cues
            normalized = normalized.copy(
                isDisfluent = cues.any {
                    it.type in setOf(
                        com.stressdetector.mobile.domain.engine.VerbatimCueType.FILLED_PAUSE,
                        com.stressdetector.mobile.domain.engine.VerbatimCueType.REPETITION,
                        com.stressdetector.mobile.domain.engine.VerbatimCueType.SELF_REPAIR
                    )
                },
                prosody = analyzeRecordedProsody(normalized)
            )
        }
        synchronized(transcriptById) {
            if (transcriptById[normalized.id]?.isFinal == true && !normalized.isFinal) return
            transcriptById[normalized.id] = normalized
        }
        _transcriptFlow.emit(normalized)
    }

    private fun analyzeRecordedProsody(segment: SpeechSegment): com.stressdetector.mobile.domain.engine.VerbatimProsodyEvidence = try {
        val audioTimeline = com.stressdetector.mobile.domain.engine.AudioTimeline(
            _speechProcessingState.value.audioGaps.filter { it.reason == "PAUSE" }
        )
        val start = audioTimeline.sessionOffsetToAudioOffset(segment.startOffsetMs)
        val end = audioTimeline.sessionOffsetToAudioOffset(segment.endOffsetMs)
        val count = ((end - start) * SAMPLE_RATE_HZ / MILLIS_PER_SECOND).toInt()
        require(count in SAMPLE_RATE_HZ..MAX_PROSODY_SAMPLES) { "Utterance terlalu pendek/panjang untuk openSMILE." }
        val wav = checkNotNull(temporaryWav) { "PCM recording belum tersedia." }
        val bytes = ByteArray(count * BYTES_PER_SAMPLE)
        RandomAccessFile(wav, "r").use { input ->
            input.seek(WAV_HEADER_BYTES + start * SAMPLE_RATE_HZ * BYTES_PER_SAMPLE / MILLIS_PER_SECOND)
            input.readFully(bytes)
        }
        val samples = FloatArray(count) { index ->
            val value = (bytes[index * 2].toInt() and 0xff) or (bytes[index * 2 + 1].toInt() shl 8)
            value.toShort().toFloat() / PCM16_SCALE
        }
        prosodyExtractor.analyze(samples)
    } catch (error: Exception) {
        com.stressdetector.mobile.domain.engine.VerbatimProsodyEvidence(unavailableReason = "Prosodi unavailable: ${error.message}")
    }

    private fun releaseRecorder() {
        runCatching { recorder?.release() }
        recorder = null
        capturePaused = false
        pauseStartedAtElapsedNanos = null
    }

    private fun writeWavHeader(file: File, pcmBytes: Long) {
        val dataLength = pcmBytes + 36
        val byteRate = SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE
        val blockAlign = CHANNEL_COUNT * BYTES_PER_SAMPLE
        val header = ByteArray(WAV_HEADER_BYTES)
        fun ascii(offset: Int, value: String) = value.forEachIndexed { index, char -> header[offset + index] = char.code.toByte() }
        fun littleEndian(offset: Int, value: Long, bytes: Int) = repeat(bytes) { index ->
            header[offset + index] = ((value shr (8 * index)) and 0xff).toByte()
        }
        ascii(0, "RIFF")
        littleEndian(4, dataLength, 4)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        littleEndian(16, 16, 4)
        littleEndian(20, 1, 2)
        littleEndian(22, CHANNEL_COUNT.toLong(), 2)
        littleEndian(24, SAMPLE_RATE_HZ.toLong(), 4)
        littleEndian(28, byteRate.toLong(), 4)
        littleEndian(32, blockAlign.toLong(), 2)
        littleEndian(34, 16, 2)
        ascii(36, "data")
        littleEndian(40, pcmBytes, 4)
        RandomAccessFile(file, "rw").use { it.write(header) }
    }

    private companion object {
        private const val CAPTURE_GAP_TOLERANCE_MS = 1L
        private const val MAX_PROSODY_SAMPLES = 20 * 16_000
        private const val PCM16_SCALE = 32_768f
        const val SAMPLE_RATE_HZ = 16_000
        const val CAPTURE_HANDOFF_CAPACITY = 32
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHANNEL_COUNT = 1
        const val BYTES_PER_SAMPLE = 2
        const val IMPORT_FRAME_BYTES = SAMPLE_RATE_HZ / 10 * BYTES_PER_SAMPLE
        const val WAV_HEADER_BYTES = 44
        const val MAX_EMPTY_READS = 20
        const val MAX_DIAGNOSTIC_GAPS = 64
        const val MILLIS_PER_SECOND = 1_000L
    }
}
