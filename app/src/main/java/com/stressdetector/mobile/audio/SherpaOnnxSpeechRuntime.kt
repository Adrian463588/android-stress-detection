package com.stressdetector.mobile.audio

import android.os.Build
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import com.stressdetector.mobile.domain.engine.SpeechSegment
import com.stressdetector.mobile.ml.speech.IndonesianStreamingSttManifest
import com.stressdetector.mobile.ml.speech.SpeechBundleManifest
import com.stressdetector.mobile.ml.speech.SpeechBundleReadiness
import com.stressdetector.mobile.ml.speech.SpeechModelBundleManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Official Sherpa runtime for Omnilingual CTC, Silero VAD, and native diarization. */
class SherpaOnnxSpeechRuntime(private val bundleManager: SpeechModelBundleManager, private val enableDiarization: Boolean = true) :
    LocalSpeechRuntime {
    private val _state = MutableStateFlow<SpeechRuntimeState>(
        SpeechRuntimeState.Checking
    )
    override val state: StateFlow<SpeechRuntimeState> = _state.asStateFlow()

    private var recognizer: OfflineRecognizer? = null
    private var indonesianRecognizer: OnlineRecognizer? = null
    private var indonesianStream: OnlineStream? = null
    private var vad: Vad? = null
    private var diarizer: OfflineSpeakerDiarization? = null
    private var sessionStartUtcMillis = 0L
    private var sessionId = ""
    private var sessionLanguage = "id"
    private val diarizationWindow = RollingSpeakerWindow()
    private val runtimeMutex = Mutex()
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loadedFingerprint: String? = null
    private var loadedManifest: SpeechBundleManifest? = null
    private var materializedRoot: File? = null
    private var indonesianPendingSamples = FloatArray(0)
    private var indonesianPendingStartOffsetMs = 0L
    private var indonesianUtteranceId: String? = null
    private var englishPendingSamples = FloatArray(0)
    private var englishPendingStartOffsetMs = 0L
    private var englishUtteranceId: String? = null
    private val speakerLabels = mutableMapOf<Int, String>()
    private var nextSpeakerLabelIndex = 0
    private val diarizationAssigner = StableDiarizationAssigner(maxSpeakers = 2)

    override suspend fun preflight(): SpeechRuntimeState = preflightInternal(null)

    /** Validates the just-activated candidate before the manager commits it. */
    internal suspend fun preflight(candidateManifest: SpeechBundleManifest): SpeechRuntimeState = preflightInternal(candidateManifest)

    private suspend fun preflightInternal(candidateManifest: SpeechBundleManifest?): SpeechRuntimeState = runtimeMutex.withLock {
        withContext(Dispatchers.IO) {
            _state.value = SpeechRuntimeState.Checking
            val readiness = candidateManifest?.let(SpeechBundleReadiness::Ready)
                ?: bundleManager.ensureReadiness()
            if (readiness !is SpeechBundleReadiness.Ready) {
                val result = SpeechRuntimeState.Unavailable(
                    when (readiness) {
                        SpeechBundleReadiness.Checking -> "Speech bundle sedang diperiksa. Coba lagi sebentar."
                        SpeechBundleReadiness.Missing -> "Sherpa Speech Bundle belum siap. Unduh atau import melalui Profile."
                        is SpeechBundleReadiness.Invalid -> readiness.reason
                        is SpeechBundleReadiness.Ready -> "Sherpa bundle belum siap."
                    }
                )
                _state.value = result
                return@withContext result
            }

            val manifest = readiness.manifest
            val fingerprint = runtimeFingerprint(manifest)
            if (loadedFingerprint == fingerprint && nativePreflightIsReady()) {
                return@withContext SpeechRuntimeState.Ready.also { _state.value = it }
            }

            runCatching {
                releaseNative()
                val runtimeRoot = bundleManager.materializeRuntimeFiles(readiness.manifest)
                vad = Vad(
                    assetManager = null,
                    config = VadModelConfig().apply {
                        sileroVadModelConfig = SileroVadModelConfig(
                            File(runtimeRoot, manifest.vad.model).absolutePath,
                            0.5f,
                            0.5f,
                            0.25f,
                            512,
                            20.0f
                        )
                        sampleRate = manifest.sampleRateHz
                        numThreads = 1
                        provider = "cpu"
                    }
                )
                diarizer = if (enableDiarization) {
                    OfflineSpeakerDiarization(
                        assetManager = null,
                        config = OfflineSpeakerDiarizationConfig().apply {
                            segmentation = OfflineSpeakerSegmentationModelConfig().apply {
                                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                                    File(runtimeRoot, manifest.diarization.segmentation).absolutePath
                                )
                                numThreads = 1
                                provider = "cpu"
                            }
                            embedding = SpeakerEmbeddingExtractorConfig(
                                File(runtimeRoot, manifest.diarization.embedding).absolutePath,
                                1,
                                false,
                                "cpu"
                            )
                            clustering = FastClusteringConfig().apply {
                                numClusters = manifest.diarization.speakerCount
                                threshold = 0.5f
                            }
                            minDurationOn = 0.3f
                            minDurationOff = 0.5f
                        }
                    )
                } else {
                    null
                }
                loadedManifest = manifest
                materializedRoot = runtimeRoot
                check(nativePreflightIsReady()) { "Sherpa VAD initialization failed." }
                // ASR is loaded in beginSession() for the selected language. This
                // avoids loading Omnilingual CTC and Indonesian streaming ASR at
                // the same time during Profile preflight.
                SpeechRuntimeState.Ready
            }.onSuccess {
                loadedFingerprint = fingerprint
            }.getOrElse { error ->
                releaseNative()
                SpeechRuntimeState.Failed(error.message ?: "Sherpa runtime initialization failed.")
            }.also { _state.value = it }
        }
    }

    override suspend fun beginSession(startedAtUtcMillis: Long, languageCode: String, sessionId: String) {
        require(sessionId.isNotBlank()) { "Speech session ID must not be blank." }
        check(
            vad != null &&
                (!enableDiarization || diarizer != null) &&
                _state.value is SpeechRuntimeState.Ready
        ) {
            "Sherpa speech runtime is not ready."
        }
        sessionStartUtcMillis = startedAtUtcMillis
        this.sessionId = sessionId
        sessionLanguage = languageCode.takeIf { it == "id" || it == "en" }
            ?: error("Unsupported speech session language: $languageCode")
        ensureRecognizerForLanguage(sessionLanguage)
        if (sessionLanguage == "id") {
            indonesianStream?.release()
            indonesianStream = indonesianRecognizer?.createStream()
        } else {
            indonesianStream?.release()
            indonesianStream = null
        }
        vad?.reset()
        diarizationWindow.reset()
        speakerLabels.clear()
        nextSpeakerLabelIndex = 0
        diarizationAssigner.reset()
        indonesianPendingSamples = FloatArray(0)
        indonesianPendingStartOffsetMs = 0L
        indonesianUtteranceId = null
        englishPendingSamples = FloatArray(0)
        englishPendingStartOffsetMs = 0L
        englishUtteranceId = null
    }

    override suspend fun detectSpeech(samples: FloatArray): Boolean = withContext(Dispatchers.Default) {
        val activeVad = vad ?: return@withContext false
        if (samples.isEmpty()) return@withContext false
        activeVad.acceptWaveform(samples)
        activeVad.isSpeechDetected()
    }

    override suspend fun transcribe(samples: FloatArray, startOffsetMs: Long, vadAlreadyAccepted: Boolean): List<SpeechSegment> =
        withContext(Dispatchers.Default) {
            if (sessionLanguage == "id") {
                return@withContext transcribeIndonesian(samples, startOffsetMs)
            }
            transcribeEnglishPartial(samples, startOffsetMs, vadAlreadyAccepted)
        }

    override suspend fun finishUtterance(): List<SpeechSegment> = withContext(Dispatchers.Default) {
        if (sessionLanguage == "id") finishIndonesianUtterance() else finishEnglishUtterance()
    }

    override suspend fun endSession(): List<SpeechSegment> = withContext(Dispatchers.Default) {
        if (sessionLanguage == "id") finishIndonesianUtterance() else finishEnglishUtterance()
    }

    override fun close() {
        closeScope.launch {
            closeAndWait()
        }
    }

    override suspend fun closeAndWait() {
        runtimeMutex.withLock { releaseNative() }
    }

    private fun releaseNative() {
        loadedFingerprint = null
        loadedManifest = null
        materializedRoot = null
        sessionId = ""
        diarizationWindow.reset()
        diarizationAssigner.reset()
        speakerLabels.clear()
        nextSpeakerLabelIndex = 0
        indonesianUtteranceId = null
        indonesianStream?.release()
        indonesianStream = null
        indonesianRecognizer?.release()
        indonesianRecognizer = null
        recognizer?.release()
        recognizer = null
        vad?.release()
        vad = null
        diarizer?.release()
        diarizer = null
    }

    private fun nativePreflightIsReady(): Boolean = vad != null && (!enableDiarization || diarizer != null)

    private fun ensureRecognizerForLanguage(languageCode: String) {
        val manifest = checkNotNull(loadedManifest) {
            "Sherpa speech bundle belum tervalidasi. Jalankan preflight terlebih dahulu."
        }
        val runtimeRoot = checkNotNull(materializedRoot) {
            "Sherpa runtime files belum tersedia."
        }
        when (languageCode) {
            "id" -> if (indonesianRecognizer == null) {
                val indonesian = checkNotNull(manifest.indonesianStt) {
                    "Indonesian streaming STT belum tersedia. Unduh speech bundle terbaru melalui Profile."
                }
                indonesianRecognizer = createIndonesianRecognizer(indonesian, runtimeRoot, manifest)
            }
            "en" -> if (recognizer == null) {
                recognizer = createEnglishRecognizer(runtimeRoot, manifest)
            }
            else -> error("Unsupported speech session language: $languageCode")
        }
    }

    private fun createEnglishRecognizer(runtimeRoot: File, manifest: SpeechBundleManifest): OfflineRecognizer {
        val model = OfflineModelConfig().apply {
            omnilingual = OfflineOmnilingualAsrCtcModelConfig(
                File(runtimeRoot, manifest.stt.model).absolutePath
            )
            tokens = File(runtimeRoot, manifest.stt.tokens).absolutePath
            modelType = manifest.stt.modelType
            numThreads = 2
            provider = "cpu"
        }
        return OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig().apply {
                featConfig = FeatureConfig(manifest.sampleRateHz, 80, 0.0f)
                modelConfig = model
                decodingMethod = "greedy_search"
            }
        )
    }

    private fun createIndonesianRecognizer(
        indonesian: IndonesianStreamingSttManifest,
        runtimeRoot: File,
        manifest: SpeechBundleManifest
    ): OnlineRecognizer = OnlineRecognizer(
        assetManager = null,
        config = OnlineRecognizerConfig().apply {
            featConfig = FeatureConfig(manifest.sampleRateHz, 80, 0.0f)
            modelConfig = OnlineModelConfig().apply {
                transducer = OnlineTransducerModelConfig().apply {
                    encoder = File(runtimeRoot, indonesian.encoder).absolutePath
                    decoder = File(runtimeRoot, indonesian.decoder).absolutePath
                    joiner = File(runtimeRoot, indonesian.joiner).absolutePath
                }
                tokens = File(runtimeRoot, indonesian.tokens).absolutePath
                numThreads = 2
                provider = "cpu"
                modelType = "zipformer2"
                // The Indonesian model uses character/subword tokens. Sherpa's
                // native config treats an empty modeling unit as fatal and exits
                // the process instead of returning a Kotlin exception.
                modelingUnit = "cjkchar"
            }
            // Endpointing is owned by SpeechUtteranceSegmenter. Keeping a
            // second native endpoint boundary causes inputFinished/reset to
            // race with the external VAD lifecycle.
            enableEndpoint = false
            decodingMethod = "greedy_search"
        }
    )

    private fun runtimeFingerprint(manifest: SpeechBundleManifest): String = buildString {
        append(manifest.bundleVersion)
        append('|').append(manifest.runtimeVersion)
        append('|').append(Build.VERSION.SDK_INT)
        append('|').append(Build.DEVICE)
        append('|').append(enableDiarization)
        manifest.files.toSortedMap().forEach { (path, checksum) ->
            append('|').append(path).append(':').append(checksum)
        }
    }

    /**
     * Omnilingual CTC is offline, so decode the bounded active utterance for a
     * replaceable partial. The same ID is finalized at VAD endpoint after
     * diarization; no live event claims a final speaker identity.
     */
    private fun transcribeEnglishPartial(samples: FloatArray, startOffsetMs: Long, vadAlreadyAccepted: Boolean): List<SpeechSegment> {
        val activeRecognizer = recognizer ?: return emptyList()
        if (samples.isEmpty()) return emptyList()
        if (!vadAlreadyAccepted) {
            val activeVad = vad ?: return emptyList()
            activeVad.acceptWaveform(samples)
            if (!activeVad.isSpeechDetected()) return emptyList()
        }
        if (englishPendingSamples.isEmpty()) englishPendingStartOffsetMs = startOffsetMs
        if (englishUtteranceId == null) {
            englishUtteranceId = "${sessionId.ifBlank { sessionStartUtcMillis.toString() }}-$startOffsetMs"
        }
        englishPendingSamples += samples
        val text = decodeEnglish(activeRecognizer, englishPendingSamples)
        if (text.isBlank()) return emptyList()
        val endOffsetMs = englishPendingStartOffsetMs + englishPendingSamples.size * 1_000L / SAMPLE_RATE_HZ
        return listOf(
            SpeechSegment(
                id = checkNotNull(englishUtteranceId),
                speakerId = "Unknown",
                startOffsetMs = englishPendingStartOffsetMs,
                endOffsetMs = endOffsetMs,
                startTimestampUtc = sessionStartUtcMillis + englishPendingStartOffsetMs,
                endTimestampUtc = sessionStartUtcMillis + endOffsetMs,
                rawText = text,
                confidence = SpeechSegmentQuality.score(englishPendingSamples, text),
                language = sessionLanguage,
                sessionId = sessionId,
                isFinal = false
            )
        )
    }

    private fun finishEnglishUtterance(): List<SpeechSegment> {
        val activeRecognizer = recognizer ?: return emptyList()
        val pending = englishPendingSamples
        if (pending.isEmpty()) return emptyList()
        val startOffsetMs = englishPendingStartOffsetMs
        val utteranceId = englishUtteranceId ?: "${sessionId.ifBlank { sessionStartUtcMillis.toString() }}-$startOffsetMs"
        englishPendingSamples = FloatArray(0)
        englishPendingStartOffsetMs = 0L
        englishUtteranceId = null
        val text = decodeEnglish(activeRecognizer, pending)
        if (text.isBlank()) return emptyList()
        val endOffsetMs = startOffsetMs + pending.size * 1_000L / SAMPLE_RATE_HZ
        val speaker = if (enableDiarization) speakerFor(pending, startOffsetMs) else null
        return listOf(
            SpeechSegment(
                id = utteranceId,
                speakerId = speaker?.let(::speakerLabelFor) ?: "Unknown",
                startOffsetMs = startOffsetMs,
                endOffsetMs = endOffsetMs,
                startTimestampUtc = sessionStartUtcMillis + startOffsetMs,
                endTimestampUtc = sessionStartUtcMillis + endOffsetMs,
                rawText = text,
                confidence = SpeechSegmentQuality.score(pending, text),
                language = sessionLanguage,
                sessionId = sessionId,
                isFinal = true
            )
        )
    }

    private fun decodeEnglish(activeRecognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream: OfflineStream = activeRecognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
            activeRecognizer.decode(stream)
            activeRecognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun transcribeIndonesian(samples: FloatArray, startOffsetMs: Long): List<SpeechSegment> {
        val activeRecognizer = indonesianRecognizer ?: return emptyList()
        val stream = indonesianStream ?: return emptyList()
        if (samples.isEmpty()) return emptyList()

        if (indonesianPendingSamples.isEmpty()) indonesianPendingStartOffsetMs = startOffsetMs
        if (indonesianUtteranceId == null) {
            indonesianUtteranceId = "${sessionId.ifBlank { sessionStartUtcMillis.toString() }}-$startOffsetMs"
        }
        indonesianPendingSamples += samples
        stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
        while (activeRecognizer.isReady(stream)) activeRecognizer.decode(stream)
        val partial = activeRecognizer.getResult(stream).text.trim()
        if (partial.isBlank()) return emptyList()
        val endOffsetMs = indonesianPendingStartOffsetMs + indonesianPendingSamples.size * 1_000L / SAMPLE_RATE_HZ
        return listOf(
            SpeechSegment(
                id = checkNotNull(indonesianUtteranceId),
                // Partial text is provisional. Diarization is resolved once
                // when the utterance is finalized so the rolling window is
                // not appended twice for the same audio.
                speakerId = "Unknown",
                startOffsetMs = indonesianPendingStartOffsetMs,
                endOffsetMs = endOffsetMs,
                startTimestampUtc = sessionStartUtcMillis + indonesianPendingStartOffsetMs,
                endTimestampUtc = sessionStartUtcMillis + endOffsetMs,
                rawText = partial,
                confidence = SpeechSegmentQuality.score(indonesianPendingSamples, partial),
                language = sessionLanguage,
                sessionId = sessionId,
                isFinal = false
            )
        )
    }

    private fun finishIndonesianUtterance(): List<SpeechSegment> {
        val activeRecognizer = indonesianRecognizer ?: return emptyList()
        val stream = indonesianStream ?: return emptyList()
        val pending = indonesianPendingSamples
        if (pending.isEmpty()) return emptyList()
        val startOffsetMs = indonesianPendingStartOffsetMs
        val utteranceId = indonesianUtteranceId ?: "${sessionId.ifBlank { sessionStartUtcMillis.toString() }}-$startOffsetMs"
        indonesianPendingSamples = FloatArray(0)
        indonesianPendingStartOffsetMs = 0L
        indonesianUtteranceId = null
        englishPendingSamples = FloatArray(0)
        englishPendingStartOffsetMs = 0L
        englishUtteranceId = null

        stream.inputFinished()
        while (activeRecognizer.isReady(stream)) activeRecognizer.decode(stream)
        val result = activeRecognizer.getResult(stream)
        val text = result.text.trim()
        val endOffsetMs = startOffsetMs + pending.size * 1_000L / SAMPLE_RATE_HZ
        activeRecognizer.reset(stream)
        if (text.isBlank()) return emptyList()

        val speaker = if (enableDiarization) speakerFor(pending, startOffsetMs) else null
        return listOf(
            SpeechSegment(
                id = utteranceId,
                speakerId = speaker?.let(::speakerLabelFor) ?: "Unknown",
                startOffsetMs = startOffsetMs,
                endOffsetMs = endOffsetMs,
                startTimestampUtc = sessionStartUtcMillis + startOffsetMs,
                endTimestampUtc = sessionStartUtcMillis + endOffsetMs,
                rawText = text,
                // Sherpa does not expose calibrated ASR posteriors. Store a
                // quality-derived evidence score, never a fabricated probability.
                confidence = SpeechSegmentQuality.score(pending, text),
                language = sessionLanguage,
                sessionId = sessionId,
                isFinal = true
            )
        )
    }

    @Suppress("ReturnCount")
    private fun speakerFor(samples: FloatArray, startOffsetMs: Long): Int? {
        val activeDiarizer = diarizer ?: return null
        diarizationWindow.append(samples, startOffsetMs)
        val windowStartMs = diarizationWindow.startOffsetMs
        val chunkStartMs = startOffsetMs.coerceAtLeast(windowStartMs)
        val chunkEndMs = chunkStartMs + samples.size * 1_000L / SAMPLE_RATE_HZ
        val current = activeDiarizer.process(diarizationWindow.samples)
            .map { segment ->
                DiarizationSegment(
                    startOffsetMs = windowStartMs + (segment.start * 1_000f).toLong(),
                    endOffsetMs = windowStartMs + (segment.end * 1_000f).toLong(),
                    rawCluster = segment.speaker
                )
            }
            .filter { it.endOffsetMs > it.startOffsetMs }
        if (current.isEmpty()) return null

        val stabilized = diarizationAssigner.assign(current)
        return stabilized
            .maxByOrNull { overlapMs(it.startOffsetMs, it.endOffsetMs, chunkStartMs, chunkEndMs) }
            ?.takeIf { overlapMs(it.startOffsetMs, it.endOffsetMs, chunkStartMs, chunkEndMs) > 0L }
            ?.stableSpeakerIndex
    }

    private fun speakerLabelFor(cluster: Int): String {
        val normalized = cluster.coerceIn(0, 1)
        return speakerLabels.getOrPut(normalized) {
            val label = "Speaker_$nextSpeakerLabelIndex"
            nextSpeakerLabelIndex = (nextSpeakerLabelIndex + 1).coerceAtMost(1)
            label
        }
    }

    private fun overlapMs(startA: Long, endA: Long, startB: Long, endB: Long): Long =
        (minOf(endA, endB) - maxOf(startA, startB)).coerceAtLeast(0L)

    private class RollingSpeakerWindow {
        var samples: FloatArray = FloatArray(0)
            private set
        var startOffsetMs: Long = 0L
            private set

        fun reset() {
            samples = FloatArray(0)
            startOffsetMs = 0L
        }

        fun append(chunk: FloatArray, chunkStartOffsetMs: Long) {
            if (samples.isEmpty() || chunkStartOffsetMs - (startOffsetMs + samples.size * 1000L / SAMPLE_RATE_HZ) > MAX_GAP_MS) {
                samples = FloatArray(0)
                startOffsetMs = chunkStartOffsetMs
            }
            samples = samples + chunk
            val excess = samples.size - MAX_SAMPLES
            if (excess > 0) {
                samples = samples.copyOfRange(excess, samples.size)
                startOffsetMs += excess * 1000L / SAMPLE_RATE_HZ
            }
        }

        companion object {
            const val MAX_SAMPLES = SAMPLE_RATE_HZ * 15
            const val MAX_GAP_MS = 250L
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
    }
}
