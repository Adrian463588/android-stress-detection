package com.stressdetector.mobile.audio

import android.os.Build
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
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
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.stressdetector.mobile.domain.engine.SpeechDecoderEvidence
import com.stressdetector.mobile.domain.engine.SpeechDecoderResult
import com.stressdetector.mobile.domain.engine.SpeechRecognitionStatus
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

/** Local Whisper/legacy ASR, Silero VAD, and independent native speaker evidence. */
class SherpaOnnxSpeechRuntime(private val bundleManager: SpeechModelBundleManager, private val enableDiarization: Boolean = true) :
    LocalSpeechRuntime {
    private val _state = MutableStateFlow<SpeechRuntimeState>(
        SpeechRuntimeState.Checking
    )
    override val state: StateFlow<SpeechRuntimeState> = _state.asStateFlow()

    private var recognizer: OfflineRecognizer? = null
    private var offlineRecognizerLanguage: String? = null
    override val decoderDescription: String?
        get() = loadedManifest?.let { manifest ->
            val finalDecoder = if (manifest.whisperStt !=
                null
            ) {
                "Whisper tiny INT8 · transcribe · $sessionLanguage"
            } else {
                "Legacy ${if (sessionLanguage == "id") "Zipformer Indonesia" else "Omnilingual CTC"} · Whisper belum diprovision"
            }
            if (sessionLanguage == "id") "Partial: Zipformer · Final: $finalDecoder · lokal" else "Partial/final: $finalDecoder · lokal"
        }
    private var indonesianRecognizer: OnlineRecognizer? = null
    private var indonesianStream: OnlineStream? = null
    private var vad: Vad? = null
    private var diarizer: OfflineSpeakerDiarization? = null
    private var speakerExtractor: SpeakerEmbeddingExtractor? = null
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
    private var lastEnglishPartialSamples = 0
    private var precedingPartial: SpeechDecoderResult? = null
    private val diarizationAssigner = StableDiarizationAssigner(maxSpeakers = 2)

    override suspend fun preflight(): SpeechRuntimeState = preflightInternal(null)

    /** Validates the just-activated candidate before the manager commits it. */
    override suspend fun preflight(candidateManifest: SpeechBundleManifest): SpeechRuntimeState = preflightInternal(candidateManifest)

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
                                // Two speakers is a capacity, not evidence that
                                // every window contains two different voices.
                                numClusters = -1
                                threshold = 0.5f
                            }
                            minDurationOn = 0.3f
                            minDurationOff = 0.5f
                        }
                    )
                } else {
                    null
                }
                if (enableDiarization) {
                    speakerExtractor = SpeakerEmbeddingExtractor(
                        config = SpeakerEmbeddingExtractorConfig(
                            File(runtimeRoot, manifest.diarization.embedding).absolutePath,
                            1,
                            false,
                            "cpu"
                        )
                    )
                }
                loadedManifest = manifest
                materializedRoot = runtimeRoot
                if (manifest.whisperStt != null) {
                    recognizer = OfflineRecognizer(assetManager = null, config = offlineSpeechConfig(manifest, runtimeRoot, "id"))
                    recognizer?.createStream()?.release()
                    offlineRecognizerLanguage = "id"
                }
                check(nativePreflightIsReady()) { "Sherpa VAD initialization failed." }
                // Validate candidate Whisper model loading before activation.
                // No synthetic waveform or fabricated decoding is used here.
                // Legacy/streaming ASR is still loaded at beginSession().
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
        diarizationAssigner.reset()
        indonesianPendingSamples = FloatArray(0)
        indonesianPendingStartOffsetMs = 0L
        indonesianUtteranceId = null
        englishPendingSamples = FloatArray(0)
        englishPendingStartOffsetMs = 0L
        englishUtteranceId = null
        lastEnglishPartialSamples = 0
        precedingPartial = null
    }

    override suspend fun detectSpeech(samples: FloatArray): Boolean = withContext(Dispatchers.Default) {
        val activeVad = vad ?: return@withContext false
        if (samples.isEmpty()) return@withContext false
        detectAndDrain(activeVad, samples)
    }

    private fun detectAndDrain(activeVad: Vad, samples: FloatArray): Boolean {
        activeVad.acceptWaveform(samples)
        val detected = activeVad.isSpeechDetected()
        // clear() drains completed PCM only; reset() would discard recurrent VAD state.
        activeVad.clear()
        return detected
    }

    override suspend fun transcribe(samples: FloatArray, startOffsetMs: Long, vadAlreadyAccepted: Boolean): List<SpeechSegment> =
        withContext(Dispatchers.Default) {
            if (samples.isEmpty()) return@withContext emptyList()
            if (!vadAlreadyAccepted) {
                val activeVad = checkNotNull(vad) { "Local VAD is unavailable." }
                if (!detectAndDrain(activeVad, samples)) return@withContext emptyList()
            }
            if (sessionLanguage == "id") {
                return@withContext transcribeIndonesian(samples, startOffsetMs)
            }
            transcribeEnglishPartial(samples, startOffsetMs)
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
        precedingPartial = null
        indonesianUtteranceId = null
        indonesianStream?.release()
        indonesianStream = null
        indonesianRecognizer?.release()
        indonesianRecognizer = null
        recognizer?.release()
        recognizer = null
        offlineRecognizerLanguage = null
        vad?.release()
        vad = null
        diarizer?.release()
        diarizer = null
        speakerExtractor?.release()
        speakerExtractor = null
    }

    private fun nativePreflightIsReady(): Boolean = vad != null &&
        (!enableDiarization || (diarizer != null && speakerExtractor != null)) &&
        (loadedManifest?.whisperStt == null || recognizer != null)

    private fun ensureRecognizerForLanguage(languageCode: String) {
        val manifest = checkNotNull(loadedManifest) {
            "Sherpa speech bundle belum tervalidasi. Jalankan preflight terlebih dahulu."
        }
        val runtimeRoot = checkNotNull(materializedRoot) {
            "Sherpa runtime files belum tersedia."
        }
        if (manifest.whisperStt != null || languageCode == "en") {
            if (offlineRecognizerLanguage != languageCode) {
                recognizer?.release()
                recognizer = null
                offlineRecognizerLanguage = null
                recognizer = OfflineRecognizer(assetManager = null, config = offlineSpeechConfig(manifest, runtimeRoot, languageCode))
                offlineRecognizerLanguage = languageCode
            }
        }
        when (languageCode) {
            "id" -> if (indonesianRecognizer == null) {
                val indonesian = checkNotNull(manifest.indonesianStt) {
                    "Indonesian streaming STT belum tersedia. Unduh speech bundle terbaru melalui Profile."
                }
                indonesianRecognizer = createIndonesianRecognizer(indonesian, runtimeRoot, manifest)
            }
            "en" -> Unit
            else -> error("Unsupported speech session language: $languageCode")
        }
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
     * English ASR is offline, so decode at bounded one-second intervals for a
     * replaceable partial. The same ID is finalized at VAD endpoint after
     * diarization; no live event claims a final speaker identity.
     */
    private fun transcribeEnglishPartial(samples: FloatArray, startOffsetMs: Long): List<SpeechSegment> {
        val activeRecognizer = checkNotNull(recognizer) { "English speech recognizer is unavailable." }
        require(samples.isNotEmpty())
        if (englishPendingSamples.isEmpty()) englishPendingStartOffsetMs = startOffsetMs
        if (englishUtteranceId == null) {
            englishUtteranceId = "${sessionId.ifBlank { sessionStartUtcMillis.toString() }}-$startOffsetMs"
        }
        englishPendingSamples += samples
        if (englishPendingSamples.size - lastEnglishPartialSamples < MIN_OFFLINE_PARTIAL_SAMPLES) return emptyList()
        lastEnglishPartialSamples = englishPendingSamples.size
        val text = decodeOffline(activeRecognizer, englishPendingSamples)
        val endOffsetMs = englishPendingStartOffsetMs + englishPendingSamples.size * 1_000L / SAMPLE_RATE_HZ
        return if (text.isBlank()) {
            emptyList()
        } else {
            val result = decoderResult(text, englishPendingStartOffsetMs, endOffsetMs)
            precedingPartial = result
            listOf(
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
                    isFinal = false,
                    decoderEvidence = SpeechDecoderEvidence(result, status = SpeechRecognitionStatus.PARTIAL)
                )
            )
        }
    }

    private fun finishEnglishUtterance(): List<SpeechSegment> {
        val activeRecognizer = recognizer ?: return emptyList()
        val pending = englishPendingSamples
        if (pending.isEmpty()) return emptyList()
        val startOffsetMs = englishPendingStartOffsetMs
        val utteranceId = englishUtteranceId ?: "${sessionId.ifBlank { sessionStartUtcMillis.toString() }}-$startOffsetMs"
        val partial = precedingPartial
        precedingPartial = null
        englishPendingSamples = FloatArray(0)
        englishPendingStartOffsetMs = 0L
        englishUtteranceId = null
        lastEnglishPartialSamples = 0
        val text = decodeOffline(activeRecognizer, pending)
        val endOffsetMs = startOffsetMs + pending.size * MILLIS_PER_SECOND / SAMPLE_RATE_HZ
        val evidence = SpeechDecoderEvidence(decoderResult(text, startOffsetMs, endOffsetMs), precedingPartial = partial)
        return finalSegments(pending, startOffsetMs, utteranceId, evidence) { decodeOffline(activeRecognizer, it) }
    }

    private fun decodeOffline(activeRecognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream: OfflineStream = activeRecognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
            activeRecognizer.decode(stream)
            activeRecognizer.getResult(stream).text
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
        val partial = activeRecognizer.getResult(stream).text
        if (partial.isBlank()) return emptyList()
        val endOffsetMs = indonesianPendingStartOffsetMs + indonesianPendingSamples.size * 1_000L / SAMPLE_RATE_HZ
        val result = decoderResult(partial, indonesianPendingStartOffsetMs, endOffsetMs, streaming = true)
        precedingPartial = result
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
                isFinal = false,
                decoderEvidence = SpeechDecoderEvidence(result, status = SpeechRecognitionStatus.PARTIAL)
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
        val partial = precedingPartial
        precedingPartial = null
        indonesianPendingSamples = FloatArray(0)
        indonesianPendingStartOffsetMs = 0L
        indonesianUtteranceId = null
        englishPendingSamples = FloatArray(0)
        englishPendingStartOffsetMs = 0L
        englishUtteranceId = null

        val legacyText = try {
            stream.inputFinished()
            while (activeRecognizer.isReady(stream)) activeRecognizer.decode(stream)
            activeRecognizer.getResult(stream).text
        } finally {
            // reset() does not reset the feature extractor's InputFinished.
            // Never feed a second utterance into that terminal native stream.
            indonesianStream = null
            stream.release()
            indonesianStream = activeRecognizer.createStream()
        }
        val whisper = loadedManifest?.whisperStt?.let { checkNotNull(recognizer) { "Verified Whisper decoder is unavailable." } }
        val text = if (whisper != null) decodeOffline(whisper, pending) else legacyText
        val endOffsetMs = startOffsetMs + pending.size * MILLIS_PER_SECOND / SAMPLE_RATE_HZ
        val evidence = SpeechDecoderEvidence(
            wholeUtterance = decoderResult(text, startOffsetMs, endOffsetMs),
            precedingPartial = partial,
            streamingFinal = if (whisper != null) decoderResult(legacyText, startOffsetMs, endOffsetMs, streaming = true) else null
        )

        return finalSegments(pending, startOffsetMs, utteranceId, evidence) { crop ->
            if (whisper != null) decodeOffline(whisper, crop) else decodeIndonesianCrop(activeRecognizer, crop)
        }
    }

    private fun decodeIndonesianCrop(activeRecognizer: OnlineRecognizer, samples: FloatArray): String {
        val stream = activeRecognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
            stream.inputFinished()
            while (activeRecognizer.isReady(stream)) activeRecognizer.decode(stream)
            activeRecognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    private fun finalSegments(
        samples: FloatArray,
        startOffsetMs: Long,
        utteranceId: String,
        evidence: SpeechDecoderEvidence,
        decode: (FloatArray) -> String
    ): List<SpeechSegment> {
        val turns = if (enableDiarization && evidence.wholeUtterance.rawText.isNotBlank()) {
            diarizedTurns(samples, startOffsetMs)
        } else {
            emptyList()
        }
        val decoded = DiarizedUtterancePlanner.recognize(turns, evidence, enableDiarization) { turn ->
            decoderResult(decode(cropSamples(samples, startOffsetMs, turn)), turn.startOffsetMs, turn.endOffsetMs)
        }
        return decoded.mapIndexed { index, result ->
            finalSegment(
                if (index == 0) utteranceId else "$utteranceId-turn-$index",
                result,
                cropSamples(samples, startOffsetMs, result.turn)
            )
        }
    }

    private fun cropSamples(samples: FloatArray, startOffsetMs: Long, turn: StableDiarizationSegment): FloatArray {
        val first = ((turn.startOffsetMs - startOffsetMs) * SAMPLE_RATE_HZ / MILLIS_PER_SECOND).toInt().coerceIn(0, samples.size)
        val last = ((turn.endOffsetMs - startOffsetMs) * SAMPLE_RATE_HZ / MILLIS_PER_SECOND).toInt().coerceIn(first, samples.size)
        return samples.copyOfRange(first, last)
    }

    private fun decoderResult(text: String, start: Long, end: Long, streaming: Boolean = false): SpeechDecoderResult {
        val manifest = checkNotNull(loadedManifest)
        val whisper = manifest.whisperStt.takeUnless { streaming }
        val backend = when {
            whisper != null -> "whisper_tiny_int8"
            sessionLanguage == "id" -> "zipformer2_transducer"
            else -> manifest.stt.modelType
        }
        val revision = when {
            whisper != null -> whisper.revision
            sessionLanguage == "id" -> checkNotNull(manifest.indonesianStt).revision
            else -> "sha256:${checkNotNull(manifest.files[manifest.stt.model])}"
        }
        return SpeechDecoderResult(backend, revision, start, end, text)
    }

    private fun finalSegment(id: String, result: DiarizedRecognition, samples: FloatArray): SpeechSegment = SpeechSegment(
        id = id,
        speakerId = result.turn.stableSpeakerIndex?.let(::speakerLabelFor) ?: "Unknown",
        startOffsetMs = result.turn.startOffsetMs,
        endOffsetMs = result.turn.endOffsetMs,
        startTimestampUtc = sessionStartUtcMillis + result.turn.startOffsetMs,
        endTimestampUtc = sessionStartUtcMillis + result.turn.endOffsetMs,
        rawText = result.rawText,
        confidence = SpeechSegmentQuality.score(samples, result.rawText),
        language = sessionLanguage,
        sessionId = sessionId,
        isFinal = true,
        decoderEvidence = result.evidence
    )

    private fun diarizedTurns(samples: FloatArray, startOffsetMs: Long): List<StableDiarizationSegment> {
        val activeDiarizer = diarizer ?: return emptyList()
        diarizationWindow.append(samples, startOffsetMs)
        val windowStartMs = diarizationWindow.startOffsetMs
        val current = activeDiarizer.process(diarizationWindow.samples)
            .map { segment ->
                DiarizationSegment(
                    startOffsetMs = windowStartMs + (segment.start * 1_000f).toLong(),
                    endOffsetMs = windowStartMs + (segment.end * 1_000f).toLong(),
                    rawCluster = segment.speaker
                )
            }
            .filter { it.endOffsetMs > it.startOffsetMs }
        val exclusive = DiarizedUtterancePlanner.exclusiveSpeakerSpans(current).groupBy(DiarizationSegment::rawCluster)
        val embeddings = current.map(DiarizationSegment::rawCluster).distinct().associateWith { cluster ->
            nativeEmbedding(exclusive[cluster].orEmpty(), windowStartMs)
        }
        return diarizationAssigner.assign(current, embeddings)
    }

    private fun nativeEmbedding(segments: List<DiarizationSegment>, windowStartMs: Long): FloatArray {
        val extractor = speakerExtractor ?: return FloatArray(0)
        val windowPcm = diarizationWindow.samples
        val pcm = FloatArray(MAX_EMBEDDING_SAMPLES)
        var written = 0
        segments.forEach { segment ->
            val first = ((segment.startOffsetMs - windowStartMs) * SAMPLE_RATE_HZ / MILLIS_PER_SECOND).toInt()
                .coerceIn(0, windowPcm.size)
            val last = ((segment.endOffsetMs - windowStartMs) * SAMPLE_RATE_HZ / MILLIS_PER_SECOND).toInt()
                .coerceIn(first, windowPcm.size)
            val count = minOf(last - first, pcm.size - written)
            windowPcm.copyInto(pcm, written, first, first + count)
            written += count
        }
        return if (written < SAMPLE_RATE_HZ) FloatArray(0) else computeEmbedding(extractor, pcm.copyOf(written))
    }

    private fun computeEmbedding(extractor: SpeakerEmbeddingExtractor, pcm: FloatArray): FloatArray {
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(pcm, SAMPLE_RATE_HZ)
            stream.inputFinished()
            if (extractor.isReady(stream)) extractor.compute(stream) else FloatArray(0)
        } finally {
            stream.release()
        }
    }

    private fun speakerLabelFor(identity: Int): String {
        require(identity in 0..1)
        return "Speaker_$identity"
    }

    companion object {
        private const val MIN_OFFLINE_PARTIAL_SAMPLES = 16_000
        private const val MAX_EMBEDDING_SAMPLES = 8 * 16_000
        private const val MILLIS_PER_SECOND = 1_000L
        const val SAMPLE_RATE_HZ = 16_000
    }
}
