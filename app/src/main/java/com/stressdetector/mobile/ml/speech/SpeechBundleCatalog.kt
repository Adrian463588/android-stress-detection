package com.stressdetector.mobile.ml.speech

import android.content.Context
import com.stressdetector.mobile.ml.artifacts.LocalArtifactCatalog

/** A pinned, auditable upstream artifact used by the one-time Profile download. */
data class SpeechBundleFileSpec(val bundlePath: String, val archiveEntrySuffix: String?, val expectedBytes: Long?, val sha256: String?)

data class SpeechBundleComponentSpec(
    val id: String,
    val url: String,
    val downloadFileName: String,
    val expectedDownloadBytes: Long,
    val expectedDownloadSha256: String,
    val license: String,
    val upstreamSource: String,
    val allowedRedirectHosts: Set<String> = emptySet(),
    val extractedFiles: List<SpeechBundleFileSpec>
)

data class SpeechTtsProfileSpec(
    val id: String,
    val language: String,
    val voiceId: String,
    val componentId: String,
    val archivePath: String,
    val modelPath: String,
    val tokensPath: String,
    val dataDirPath: String,
    val sampleRateHz: Int,
    val engineType: String = "piper_vits",
    val supertonic: SpeechSupertonicProfileSpec? = null
)

data class SpeechSupertonicProfileSpec(
    val durationPredictorPath: String,
    val textEncoderPath: String,
    val vectorEstimatorPath: String,
    val vocoderPath: String,
    val ttsJsonPath: String,
    val unicodeIndexerPath: String,
    val voiceStylePath: String,
    val speakerId: Int = 0,
    val speakerCount: Int = 10,
    val numSteps: Int = 8,
    val speed: Float = 1.0f
)

data class IndonesianStreamingSttSpec(
    val revision: String,
    val encoderPath: String,
    val decoderPath: String,
    val joinerPath: String,
    val tokensPath: String,
    val componentId: String
)

data class WhisperSttSpec(
    val revision: String,
    val encoderPath: String,
    val decoderPath: String,
    val tokensPath: String,
    val languages: List<String> = listOf("id", "en"),
    val task: String = "transcribe"
)

data class SpeechBundleDescriptor(
    val bundleVersion: String,
    val runtimeVersion: String,
    val sampleRateHz: Int,
    val languages: List<String>,
    val speakerCount: Int,
    val components: List<SpeechBundleComponentSpec>,
    val runtimeSource: String,
    val ttsProfiles: List<SpeechTtsProfileSpec> = emptyList(),
    val indonesianStt: IndonesianStreamingSttSpec? = null,
    val whisperStt: WhisperSttSpec? = null
) {
    val totalDownloadBytes: Long
        get() = components.sumOf(SpeechBundleComponentSpec::expectedDownloadBytes)

    /** Conservative same-volume peak: downloads, candidate ZIP, active ZIP, runtime and SAF mirror. */
    val requiredFreeStorageBytes: Long
        get() = totalDownloadBytes + extractedBytes() * PEAK_RUNTIME_COPIES + INSTALLATION_MARGIN_BYTES

    private fun extractedBytes(): Long = components.flatMap { it.extractedFiles }
        .mapNotNull(SpeechBundleFileSpec::expectedBytes)
        .sum()

    private companion object {
        const val PEAK_RUNTIME_COPIES = 4
        const val INSTALLATION_MARGIN_BYTES = 64L * 1024L * 1024L
    }
}

/** Builds the speech runtime descriptor exclusively from the APK-owned artifact catalog. */
object SpeechBundleCatalog {
    internal const val LEGACY_BUNDLE_VERSION = "sherpa-onnx-1.13.4-omnilingual-ctc-v3-supertonic3"
    internal val LEGACY_COMPONENT_IDS = setOf(
        "asr-omnilingual-ctc-v2-int8",
        "vad-silero",
        "diarization-pyannote-segmentation",
        "diarization-3dspeaker-embedding",
        "tts-id-id_ID-news_tts-medium",
        "tts-en-en_US-lessac-medium",
        "tts-supertonic3-int8",
        "asr-indonesian-zipformer2-int8",
        "asr-indonesian-zipformer2-int8-decoder",
        "asr-indonesian-zipformer2-int8-joiner",
        "asr-indonesian-zipformer2-int8-tokens"
    )
    internal val WHISPER_COMPONENT_IDS = listOf(
        "asr-whisper-tiny-int8-encoder",
        "asr-whisper-tiny-int8-decoder",
        "asr-whisper-tiny-int8-tokens"
    )

    fun load(context: Context): SpeechBundleDescriptor = from(LocalArtifactCatalog.load(context))

    fun from(catalog: LocalArtifactCatalog): SpeechBundleDescriptor {
        val metadata = catalog.speechBundle
        val components = catalog.speechComponents().map { entry ->
            val download = entry.files.single { it.role == "download" }
            SpeechBundleComponentSpec(
                id = entry.id,
                url = requireNotNull(download.url),
                downloadFileName = download.path,
                expectedDownloadBytes = download.sizeBytes,
                expectedDownloadSha256 = download.sha256,
                license = entry.license,
                upstreamSource = entry.sourcePage,
                allowedRedirectHosts = entry.allowedRedirectHosts.toSet(),
                extractedFiles = entry.files.filter { it.role == "runtime" }.map { file ->
                    SpeechBundleFileSpec(
                        bundlePath = requireNotNull(file.bundlePath),
                        archiveEntrySuffix = file.archiveEntrySuffix,
                        expectedBytes = file.sizeBytes,
                        sha256 = file.sha256
                    )
                }
            )
        }
        val indonesianRevision = catalog.requireById("asr-indonesian-zipformer2-int8").revision
        return SpeechBundleDescriptor(
            bundleVersion = metadata.bundleVersion,
            runtimeVersion = metadata.runtimeVersion,
            sampleRateHz = metadata.sampleRateHz,
            languages = metadata.languages,
            speakerCount = metadata.speakerCount,
            components = components,
            runtimeSource = metadata.runtimeSource,
            ttsProfiles = ttsProfiles(),
            indonesianStt = IndonesianStreamingSttSpec(
                revision = indonesianRevision,
                encoderPath = "asr/id/encoder.int8.onnx",
                decoderPath = "asr/id/decoder.int8.onnx",
                joinerPath = "asr/id/joiner.int8.onnx",
                tokensPath = "asr/id/tokens.txt",
                componentId = "asr-indonesian-zipformer2-int8"
            ),
            whisperStt = whisperProfile(catalog, components)
        )
    }

    private fun whisperProfile(catalog: LocalArtifactCatalog, components: List<SpeechBundleComponentSpec>): WhisperSttSpec {
        val entries = WHISPER_COMPONENT_IDS.map(catalog::requireById)
        require(components.map(SpeechBundleComponentSpec::id).containsAll(WHISPER_COMPONENT_IDS)) {
            "Speech bundle must include every pinned Whisper component."
        }
        require(entries.map { it.revision }.distinct().size == 1) {
            "Whisper components must share one immutable revision."
        }
        val paths = entries.map { entry -> entry.files.single { it.role == "runtime" }.bundlePath }
        require(
            paths == listOf(
                "asr/whisper/tiny-encoder.int8.onnx",
                "asr/whisper/tiny-decoder.int8.onnx",
                "asr/whisper/tiny-tokens.txt"
            )
        ) { "Whisper component roles do not match the canonical paths." }
        return WhisperSttSpec(
            revision = entries.first().revision,
            encoderPath = requireNotNull(paths[0]),
            decoderPath = requireNotNull(paths[1]),
            tokensPath = requireNotNull(paths[2])
        )
    }

    private fun ttsProfiles(): List<SpeechTtsProfileSpec> = listOf(
        SpeechTtsProfileSpec(
            id = "id_ID-news_tts-medium",
            language = "id",
            voiceId = "id_ID-news_tts-medium",
            componentId = "tts-id-id_ID-news_tts-medium",
            archivePath = "tts/id_ID-news_tts-medium.tar.bz2",
            modelPath = "vits-piper-id_ID-news_tts-medium/id_ID-news_tts-medium.onnx",
            tokensPath = "vits-piper-id_ID-news_tts-medium/tokens.txt",
            dataDirPath = "vits-piper-id_ID-news_tts-medium/espeak-ng-data",
            sampleRateHz = 22_050
        ),
        SpeechTtsProfileSpec(
            id = "en_US-lessac-medium",
            language = "en",
            voiceId = "en_US-lessac-medium",
            componentId = "tts-en-en_US-lessac-medium",
            archivePath = "tts/en_US-lessac-medium.tar.bz2",
            modelPath = "vits-piper-en_US-lessac-medium/en_US-lessac-medium.onnx",
            tokensPath = "vits-piper-en_US-lessac-medium/tokens.txt",
            dataDirPath = "vits-piper-en_US-lessac-medium/espeak-ng-data",
            sampleRateHz = 22_050
        ),
        SpeechTtsProfileSpec(
            id = "supertonic3-id",
            language = "id",
            voiceId = "supertonic3-sid0",
            componentId = "tts-supertonic3-int8",
            archivePath = "",
            modelPath = "",
            tokensPath = "",
            dataDirPath = "",
            sampleRateHz = 24_000,
            engineType = "supertonic",
            supertonic = supertonicProfile()
        ),
        SpeechTtsProfileSpec(
            id = "supertonic3-en",
            language = "en",
            voiceId = "supertonic3-sid0",
            componentId = "tts-supertonic3-int8",
            archivePath = "",
            modelPath = "",
            tokensPath = "",
            dataDirPath = "",
            sampleRateHz = 24_000,
            engineType = "supertonic",
            supertonic = supertonicProfile()
        )
    )

    private fun supertonicProfile() = SpeechSupertonicProfileSpec(
        durationPredictorPath = "tts/supertonic3/duration_predictor.int8.onnx",
        textEncoderPath = "tts/supertonic3/text_encoder.int8.onnx",
        vectorEstimatorPath = "tts/supertonic3/vector_estimator.int8.onnx",
        vocoderPath = "tts/supertonic3/vocoder.int8.onnx",
        ttsJsonPath = "tts/supertonic3/tts.json",
        unicodeIndexerPath = "tts/supertonic3/unicode_indexer.bin",
        voiceStylePath = "tts/supertonic3/voice.bin"
    )
}
