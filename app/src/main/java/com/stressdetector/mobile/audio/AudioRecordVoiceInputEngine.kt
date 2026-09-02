package com.stressdetector.mobile.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import com.stressdetector.mobile.domain.voice.VoiceInputEngine
import com.stressdetector.mobile.domain.voice.VoiceInputState
import com.stressdetector.mobile.domain.voice.VoiceLanguage
import com.stressdetector.mobile.domain.voice.VoicePcmFrame
import com.stressdetector.mobile.domain.voice.VoiceTurnAssembler
import com.stressdetector.mobile.domain.voice.VoiceTurnAssemblyResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Foreground-only PCM capture with real Sherpa/Silero VAD and local STT. */
class AudioRecordVoiceInputEngine(
    context: Context,
    private val speechRuntime: LocalSpeechRuntime,
    private val captureOwnership: AudioCaptureOwnership,
    private val audioFocus: AudioFocusCoordinator? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : VoiceInputEngine {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val _state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    private val lock = Any()
    private var captureJob: Job? = null

    @Volatile private var recorder: AudioRecord? = null
    private val stopRequested = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureLease: AudioCaptureLease? = null

    override val state: StateFlow<VoiceInputState> = _state.asStateFlow()

    override suspend fun start(language: VoiceLanguage) {
        synchronized(lock) {
            if (captureJob?.isActive == true) return
            stopRequested.set(false)
            cancelRequested.set(false)
            _state.value = VoiceInputState.RequestingPermission
            if (!hasMicrophonePermission()) {
                _state.value = VoiceInputState.Error(
                    message = "Izin mikrofon diperlukan untuk voice AI.",
                    retryable = false
                )
                return
            }
            captureJob = scope.launch { captureTurn(language) }
        }
    }

    override fun stop() {
        stopRequested.set(true)
        recorder?.let { record ->
            runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
        }
    }

    override fun cancel() {
        cancelRequested.set(true)
        stopRequested.set(true)
        recorder?.let { record -> runCatching { record.stop() } }
        captureJob?.cancel(CancellationException("Voice capture cancelled"))
        _state.value = VoiceInputState.Idle
    }

    override fun close() {
        cancel()
        val job = synchronized(lock) { captureJob }
        closeScope.launch {
            job?.join()
            speechRuntime.close()
            closeScope.cancel()
        }
        scope.cancel()
    }

    private suspend fun captureTurn(language: VoiceLanguage) = withContext(ioDispatcher) {
        var audioRecord: AudioRecord? = null
        var sessionStarted = false
        try {
            captureLease = captureOwnership.tryAcquire(AudioCaptureOwner.AI_VOICE)
                ?: error("Mikrofon sedang digunakan Verbatim+. Hentikan rekaman terlebih dahulu.")
            check(audioFocus?.requestRecordingFocus() != false) {
                "Audio focus tidak tersedia untuk voice AI."
            }
            _state.value = VoiceInputState.Initializing("Memvalidasi speech runtime…")
            val runtimeState = runCatching { speechRuntime.preflight() }.getOrElse { error ->
                SpeechRuntimeState.Failed(error.message ?: "Voice runtime initialization failed.")
            }
            if (runtimeState !is SpeechRuntimeState.Ready) {
                error(runtimeState.actionableMessage())
            }

            val languageCode = if (language == VoiceLanguage.INDONESIAN) "id" else "en"
            _state.value = VoiceInputState.Initializing("Menyiapkan stream $languageCode…")
            val startedAtUtcMillis = System.currentTimeMillis()
            speechRuntime.beginSession(
                startedAtUtcMillis = startedAtUtcMillis,
                languageCode = languageCode,
                sessionId = voiceSessionId(startedAtUtcMillis)
            )
            sessionStarted = true
            _state.value = VoiceInputState.Initializing("Menyiapkan mikrofon…")
            val record = createAudioRecord().also {
                audioRecord = it
                recorder = it
            }
            val assembler = VoiceTurnAssembler()
            val samples = ShortArray(FRAME_SAMPLES)
            var frameOffsetMs = 0L
            var result: VoiceTurnAssemblyResult = VoiceTurnAssemblyResult.AwaitingSpeech
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Microphone could not start."
            }
            _state.value = VoiceInputState.Listening
            var emptyReadCount = 0
            while (!stopRequested.get() && !cancelRequested.get()) {
                ensureActive()
                val count = record.read(samples, 0, samples.size)
                when {
                    count > 0 -> emptyReadCount = 0
                    count == 0 -> {
                        emptyReadCount++
                        if (emptyReadCount >= MAX_EMPTY_READS) {
                            error("Microphone tidak mengembalikan data audio. Silakan coba lagi.")
                        }
                        delay(10)
                        continue
                    }
                    else -> error("Microphone read gagal (kode $count). Periksa perangkat audio lalu coba lagi.")
                }
                val frame = samples.copyOf(count)
                val speechDetected = speechRuntime.detectSpeech(frame.toFloatSamples())
                result = assembler.append(
                    VoicePcmFrame(frame),
                    speechDetectedByVad = speechDetected
                )
                frameOffsetMs += count * 1_000L / SAMPLE_RATE_HZ
                if (result is VoiceTurnAssemblyResult.Completed) break
                if (frameOffsetMs >= VoiceTurnAssembler.MAX_DURATION_MILLIS) break
            }

            val completed = result as? VoiceTurnAssemblyResult.Completed
            val turn = completed?.turn ?: assembler.finish()
            if (cancelRequested.get() || turn == null || !turn.containsSpeech) {
                _state.value = VoiceInputState.Idle
                return@withContext
            }
            _state.value = VoiceInputState.Transcribing
            val segments = speechRuntime.transcribe(
                samples = turn.samples.toFloatSamples(),
                startOffsetMs = 0L,
                vadAlreadyAccepted = true
            ) + speechRuntime.finishUtterance()
            val transcript = segments.asSequence()
                .map { it.rawText.trim() }
                .filter(String::isNotBlank)
                .joinToString(" ")
                .trim()
            _state.value = if (transcript.isBlank()) {
                VoiceInputState.Error("Ucapan belum dapat dikenali. Silakan coba lagi.", retryable = true)
            } else {
                VoiceInputState.TranscriptReady(transcript)
            }
        } catch (_: CancellationException) {
            _state.value = VoiceInputState.Idle
            throw CancellationException("Voice capture cancelled")
        } catch (error: Throwable) {
            _state.value = VoiceInputState.Error(
                error.message ?: "Voice input gagal diproses.",
                retryable = true
            )
        } finally {
            audioRecord?.let { record ->
                runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
                runCatching { record.release() }
            }
            recorder = null
            if (sessionStarted) {
                withContext(NonCancellable) {
                    runCatching { speechRuntime.endSession() }
                }
            }
            stopRequested.set(false)
            cancelRequested.set(false)
            captureLease?.release()
            captureLease = null
            audioFocus?.abandonRecordingFocus()
        }
    }

    private fun SpeechRuntimeState.actionableMessage(): String = when (this) {
        SpeechRuntimeState.Checking -> "Speech bundle sedang diperiksa. Coba lagi sebentar."
        is SpeechRuntimeState.Unavailable -> reason
        is SpeechRuntimeState.Failed -> message
        SpeechRuntimeState.Ready -> "Voice AI belum siap."
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "Unsupported microphone format." }
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME_SAMPLES * BYTES_PER_SAMPLE * 2)
        ).also { record ->
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be initialized." }
        }
    }

    private fun hasMicrophonePermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun ShortArray.toFloatSamples(): FloatArray = FloatArray(size) { index ->
        this[index].toFloat() / Short.MAX_VALUE
    }

    private companion object {
        const val SAMPLE_RATE_HZ = VoiceTurnAssembler.SAMPLE_RATE_HZ
        const val FRAME_SAMPLES = SAMPLE_RATE_HZ / 10
        const val BYTES_PER_SAMPLE = 2
        const val MAX_EMPTY_READS = 20
    }
}

internal fun voiceSessionId(startedAtUtcMillis: Long): String = "voice-$startedAtUtcMillis"
