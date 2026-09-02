package com.stressdetector.mobile.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.stressdetector.mobile.domain.voice.VoiceBackend
import com.stressdetector.mobile.domain.voice.VoiceLanguage
import com.stressdetector.mobile.domain.voice.VoiceOutputEngine
import com.stressdetector.mobile.domain.voice.VoiceOutputState
import com.stressdetector.mobile.domain.voice.VoiceTextNormalizer
import com.stressdetector.mobile.ml.speech.SpeechBundleReadiness
import com.stressdetector.mobile.ml.speech.SpeechModelBundleManager
import com.stressdetector.mobile.ml.speech.SpeechTtsProfileManifest
import com.stressdetector.mobile.ml.speech.effectiveEngineType
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Sherpa TTS engine with Supertonic3 preference and legacy Piper compatibility. */
class PiperVoiceOutputEngine(
    private val bundleManager: SpeechModelBundleManager,
    private val audioSink: AudioSink = AndroidAudioSink(),
    private val audioFocus: AudioFocusCoordinator? = null
) : VoiceOutputEngine {
    private val _state = MutableStateFlow<VoiceOutputState>(VoiceOutputState.Idle)
    private val commandMutex = Mutex()
    private var tts: OfflineTts? = null
    private var ttsProfileKey: String? = null
    private var activeRequestId: Long? = null
    private var activeCompletion: CompletableDeferred<Unit>? = null
    private var stopGeneration = 0L
    private var closed = false
    private val requestCounter = AtomicLong()

    override val state: StateFlow<VoiceOutputState> = _state.asStateFlow()

    override suspend fun speak(messageId: String, text: String, language: VoiceLanguage): Result<VoiceBackend> {
        val cleanText = VoiceTextNormalizer.normalize(text)
        if (cleanText.isBlank()) {
            return Result.failure(IllegalArgumentException("Tidak ada teks untuk dibacakan."))
        }
        val requestId = requestCounter.incrementAndGet()
        val completion = CompletableDeferred<Unit>()
        return try {
            commandMutex.withLock {
                check(!closed) { "Voice output engine sudah ditutup." }
                activeRequestId = requestId
                activeCompletion = completion
            }
            ensureRequestActive(requestId)
            _state.value = VoiceOutputState.Preparing(messageId = messageId)

            val profile = withContext(Dispatchers.IO) { findProfile(language) }
            val backend = profile.backend()
            _state.value = VoiceOutputState.Preparing(messageId = messageId, backend = backend)
            val renderer = withContext(Dispatchers.IO) {
                val runtimeRoot = bundleManager.materializeRuntimeFiles()
                val engineType = profile.effectiveEngineType
                val profileRoot = if (engineType == SUPER_TONIC_ENGINE) {
                    runtimeRoot
                } else {
                    TtsArchiveExtractor.ensureExtracted(
                        runtimeRoot = runtimeRoot,
                        profile = profile,
                        destination = bundleManager.ttsMaterializedRoot(),
                        archiveFingerprint = bundleManager.manifest()?.files?.get(profile.archive).orEmpty()
                    )
                }
                commandMutex.withLock {
                    ensureRequestActive(requestId)
                    getOrCreateTts(profile, profileRoot)
                }
            }
            ensureRequestActive(requestId)
            val generated = withContext(Dispatchers.Default) {
                ensureRequestActive(requestId)
                if (profile.effectiveEngineType == SUPER_TONIC_ENGINE) {
                    val model = checkNotNull(profile.supertonic)
                    val config = GenerationConfig().apply {
                        sid = model.speakerId
                        numSteps = model.numSteps
                        speed = model.speed
                        extra = mapOf("lang" to languageCode(language))
                    }
                    renderer.generateWithConfig(cleanText, config)
                } else {
                    renderer.generate(cleanText, 0, PIPER_SPEED)
                }
            }
            ensureRequestActive(requestId)
            require(generated.samples.isNotEmpty()) { "Voice renderer returned empty audio." }
            require(generated.samples.all(Float::isFinite)) { "Voice renderer returned invalid PCM." }
            require(generated.samples.maxOf { kotlin.math.abs(it) } >= MIN_PCM_AMPLITUDE) {
                "Voice renderer returned silent PCM."
            }
            _state.value = VoiceOutputState.Speaking(messageId = messageId, backend = backend)
            check(audioFocus?.requestPlaybackFocus() != false) { "Audio focus tidak tersedia." }
            try {
                audioSink.play(generated.samples, generated.sampleRate, ::isCurrentRequest)
            } finally {
                audioFocus?.abandonPlaybackFocus()
            }
            _state.value = VoiceOutputState.Stopped
            Result.success(backend)
        } catch (cancelled: CancellationException) {
            _state.value = VoiceOutputState.Stopped
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message ?: "Voice output gagal diputar."
            _state.value = VoiceOutputState.Error(message)
            Result.failure(IllegalStateException(message, error))
        } finally {
            commandMutex.withLock {
                if (activeRequestId == requestId) {
                    activeRequestId = null
                    activeCompletion = null
                }
            }
            completion.complete(Unit)
            if (closed) commandMutex.withLock { releaseTts() }
        }
    }

    override suspend fun stopAndWait(): Result<Unit> = runCatching {
        val completion = commandMutex.withLock {
            stopGeneration = requestCounter.incrementAndGet()
            activeCompletion
        }
        _state.value = VoiceOutputState.Stopping
        audioSink.stopAndWait().getOrThrow()
        completion?.let { signal ->
            check(
                withTimeout(STOP_TIMEOUT_MS) {
                    signal.await()
                    true
                }
            ) {
                "Voice output masih menghentikan renderer native."
            }
        }
        _state.value = VoiceOutputState.Stopped
    }

    override suspend fun closeAndWait() {
        closed = true
        stopAndWait().getOrThrow()
        commandMutex.withLock { releaseTts() }
        audioSink.close()
    }

    override suspend fun close() = closeAndWait()

    private suspend fun findProfile(language: VoiceLanguage): SpeechTtsProfileManifest {
        val readiness = bundleManager.ensureReadiness()
        val manifest = (readiness as? SpeechBundleReadiness.Ready)?.manifest
            ?: error("Voice model belum tersedia. Unduh Voice AI melalui Profile.")
        val code = languageCode(language)
        return manifest.ttsProfiles.values
            .filter { it.language.equals(code, ignoreCase = true) }
            .sortedBy { if (it.effectiveEngineType == SUPER_TONIC_ENGINE) 0 else 1 }
            .firstOrNull()
            ?: error("Voice ${code.uppercase(Locale.ROOT)} belum tersedia pada speech bundle.")
    }

    private fun getOrCreateTts(profile: SpeechTtsProfileManifest, root: File): OfflineTts {
        val key = "${profile.effectiveEngineType}:${profile.voiceId}"
        tts?.takeIf { ttsProfileKey == key }?.let { return it }
        releaseTts()
        val modelConfig = OfflineTtsModelConfig().apply {
            numThreads = 2
            provider = "cpu"
            when (profile.effectiveEngineType) {
                PIPER_ENGINE -> {
                    require(File(root, profile.model).isFile) { "Piper model file is missing." }
                    require(File(root, profile.tokens).isFile) { "Piper tokens file is missing." }
                    require(File(root, profile.dataDir).isDirectory) { "Piper data directory is missing." }
                    vits = OfflineTtsVitsModelConfig().apply {
                        model = File(root, profile.model).absolutePath
                        tokens = File(root, profile.tokens).absolutePath
                        dataDir = File(root, profile.dataDir).absolutePath
                    }
                }
                SUPER_TONIC_ENGINE -> {
                    val model = checkNotNull(profile.supertonic)
                    listOf(
                        model.durationPredictor,
                        model.textEncoder,
                        model.vectorEstimator,
                        model.vocoder,
                        model.ttsJson,
                        model.unicodeIndexer,
                        model.voiceStyle
                    ).forEach { path ->
                        require(File(root, path).isFile) { "Supertonic file is missing: $path" }
                    }
                    supertonic = OfflineTtsSupertonicModelConfig().apply {
                        durationPredictor = File(root, model.durationPredictor).absolutePath
                        textEncoder = File(root, model.textEncoder).absolutePath
                        vectorEstimator = File(root, model.vectorEstimator).absolutePath
                        vocoder = File(root, model.vocoder).absolutePath
                        ttsJson = File(root, model.ttsJson).absolutePath
                        unicodeIndexer = File(root, model.unicodeIndexer).absolutePath
                        voiceStyle = File(root, model.voiceStyle).absolutePath
                    }
                }
                else -> error("Unsupported TTS engine type: ${profile.engineType}")
            }
        }
        return OfflineTts(
            null,
            OfflineTtsConfig().apply {
                model = modelConfig
                maxNumSentences = 1
            }
        ).also {
            tts = it
            ttsProfileKey = key
        }
    }

    private fun releaseTts() {
        runCatching { tts?.release() }
        tts = null
        ttsProfileKey = null
    }

    private fun isCurrentRequest(): Boolean = activeRequestId?.let(::isRequestActive) == true && audioFocus?.hasPlaybackFocus() != false

    private fun ensureRequestActive(requestId: Long) {
        if (closed || activeRequestId != requestId || requestId <= stopGeneration) {
            throw CancellationException("Voice output request was stopped.")
        }
    }

    private fun isRequestActive(requestId: Long): Boolean = !closed && activeRequestId == requestId && requestId > stopGeneration

    private companion object {
        const val PIPER_ENGINE = "piper_vits"
        const val SUPER_TONIC_ENGINE = "supertonic"
        const val PIPER_SPEED = 0.96f
        const val STOP_TIMEOUT_MS = 5_000L
        const val MIN_PCM_AMPLITUDE = 0.00001f
    }
}

private fun SpeechTtsProfileManifest.backend(): VoiceBackend =
    if (effectiveEngineType == "supertonic") VoiceBackend.SUPERTONIC3 else VoiceBackend.PIPER_LEGACY

private fun languageCode(language: VoiceLanguage): String = if (language == VoiceLanguage.INDONESIAN) "id" else "en"

/** Explicit local Android TTS option. It is not selected silently by the neural backend. */
class AndroidTextToSpeechVoiceOutputEngine(context: Context) : VoiceOutputEngine {
    private val _state = MutableStateFlow<VoiceOutputState>(VoiceOutputState.Idle)
    private val appContext = context.applicationContext
    private val commandMutex = Mutex()
    private var engine: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private var completion: CompletableDeferred<Unit>? = null
    private var closed = false

    override val state: StateFlow<VoiceOutputState> = _state.asStateFlow()

    override suspend fun speak(messageId: String, text: String, language: VoiceLanguage): Result<VoiceBackend> {
        val cleanText = VoiceTextNormalizer.normalize(text)
        if (cleanText.isBlank()) return Result.failure(IllegalArgumentException("Tidak ada teks untuk dibacakan."))
        var signal: CompletableDeferred<Unit>? = null
        return try {
            signal = commandMutex.withLock {
                check(!closed) { "Android TTS sudah ditutup." }
                _state.value = VoiceOutputState.Preparing(messageId, VoiceBackend.ANDROID_TTS)
                val activeEngine = obtainEngine()
                val locale = if (language == VoiceLanguage.INDONESIAN) Locale("id", "ID") else Locale.US
                require(activeEngine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                    "Voice ${locale.displayLanguage} tidak tersedia pada Android TTS."
                }
                val playbackCompletion = CompletableDeferred<Unit>()
                completion = playbackCompletion
                withContext(Dispatchers.Main.immediate) {
                    activeEngine.setLanguage(locale)
                    activeEngine.setSpeechRate(0.98f)
                    activeEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _state.value = VoiceOutputState.Speaking(messageId, VoiceBackend.ANDROID_TTS)
                        }

                        override fun onDone(utteranceId: String?) {
                            playbackCompletion.complete(Unit)
                        }

                        override fun onError(utteranceId: String?) {
                            playbackCompletion.completeExceptionally(Exception("Android TTS playback failed."))
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
                    })
                    check(
                        activeEngine.speak(cleanText, TextToSpeech.QUEUE_FLUSH, Bundle(), UUID.randomUUID().toString()) ==
                            TextToSpeech.SUCCESS
                    ) {
                        "Android TTS could not start."
                    }
                }
                playbackCompletion
            }
            withTimeout(60_000L) { checkNotNull(signal).await() }
            _state.value = VoiceOutputState.Stopped
            Result.success(VoiceBackend.ANDROID_TTS)
        } catch (cancelled: CancellationException) {
            _state.value = VoiceOutputState.Stopped
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message ?: "Android TTS playback failed."
            _state.value = VoiceOutputState.Error(message, VoiceBackend.ANDROID_TTS)
            Result.failure(error)
        } finally {
            signal?.let { playbackCompletion ->
                commandMutex.withLock {
                    if (completion === playbackCompletion) completion = null
                }
            }
        }
    }

    override suspend fun stopAndWait(): Result<Unit> = runCatching {
        _state.value = VoiceOutputState.Stopping
        withContext(Dispatchers.Main.immediate) { engine?.stop() }
        completion?.complete(Unit)
        commandMutex.withLock { Unit }
        _state.value = VoiceOutputState.Stopped
    }

    override suspend fun closeAndWait() {
        stopAndWait()
        withContext(Dispatchers.Main.immediate) { engine?.shutdown() }
        engine = null
        ready = null
        closed = true
    }

    override suspend fun close() = closeAndWait()

    private suspend fun obtainEngine(): TextToSpeech {
        val current = engine
        val signal = ready
        if (current != null && signal != null) {
            check(withTimeout(5_000L) { signal.await() }) { "Android TTS tidak tersedia." }
            return current
        }
        return withContext(Dispatchers.Main.immediate) {
            val readySignal = CompletableDeferred<Boolean>()
            val candidate = TextToSpeech(appContext) { status -> readySignal.complete(status == TextToSpeech.SUCCESS) }
            engine = candidate
            ready = readySignal
            check(withTimeout(5_000L) { readySignal.await() }) { "Android TTS tidak tersedia." }
            candidate
        }
    }
}

internal object TtsArchiveExtractor {
    fun ensureExtracted(runtimeRoot: File, profile: SpeechTtsProfileManifest, destination: File, archiveFingerprint: String): File {
        val target = File(destination, profile.voiceId)
        val marker = File(target, ".archive-sha256")
        if (File(target, profile.model).isFile &&
            File(target, profile.tokens).isFile &&
            File(target, profile.dataDir).isDirectory &&
            marker.isFile &&
            marker.readText() == archiveFingerprint
        ) {
            return target
        }

        val temporary = File(destination, ".${profile.voiceId}-${System.nanoTime()}")
        require(temporary.mkdirs()) { "TTS extraction directory could not be created." }
        try {
            val archive = File(runtimeRoot, profile.archive)
            require(archive.isFile) { "TTS archive is missing: ${profile.voiceId}" }
            BufferedInputStream(FileInputStream(archive)).use { input ->
                BZip2CompressorInputStream(input).use { bzip ->
                    TarArchiveInputStream(bzip).use { tar ->
                        while (true) {
                            val entry = tar.nextEntry ?: break
                            val normalized = entry.name.replace('\\', '/')
                            requireSafePath(normalized)
                            val file = File(temporary, normalized)
                            if (entry.isDirectory) {
                                file.mkdirs()
                            } else if (entry.isFile) {
                                file.parentFile?.mkdirs()
                                FileOutputStream(file).use { output -> tar.copyTo(output) }
                            }
                        }
                    }
                }
            }
            target.deleteRecursively()
            require(temporary.renameTo(target)) { "TTS archive could not be activated." }
            File(target, ".archive-sha256").writeText(archiveFingerprint)
            return target
        } catch (error: Throwable) {
            temporary.deleteRecursively()
            throw error
        }
    }

    private fun requireSafePath(path: String) {
        val normalized = path.trimEnd('/')
        require(
            normalized.isNotBlank() &&
                !normalized.startsWith('/') &&
                !normalized.contains('\\') &&
                normalized.split('/').none { it.isBlank() || it == ".." }
        ) {
            "Unsafe TTS archive path."
        }
    }
}
