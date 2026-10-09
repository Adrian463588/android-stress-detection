package com.stressdetector.mobile.audio

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.stressdetector.mobile.ml.speech.SpeechBundleManifest
import java.io.File

private const val MEL_FEATURE_BINS = 80

/** Pure configuration boundary; no native loading and no network fallback. */
internal fun offlineSpeechConfig(manifest: SpeechBundleManifest, root: File, language: String): OfflineRecognizerConfig {
    require(language in manifest.languages)
    val profile = manifest.whisperStt
    val model = OfflineModelConfig().apply {
        if (profile != null) {
            require(language in profile.languages && profile.task == "transcribe")
            whisper = OfflineWhisperModelConfig(
                encoder = File(root, profile.encoder).absolutePath,
                decoder = File(root, profile.decoder).absolutePath,
                language = language,
                task = profile.task
            )
            tokens = File(root, profile.tokens).absolutePath
            modelType = "whisper"
        } else {
            require(language == "en") { "Legacy Indonesian STT uses its validated streaming recognizer." }
            omnilingual = OfflineOmnilingualAsrCtcModelConfig(File(root, manifest.stt.model).absolutePath)
            tokens = File(root, manifest.stt.tokens).absolutePath
            modelType = manifest.stt.modelType
        }
        numThreads = 2
        provider = "cpu"
    }
    return OfflineRecognizerConfig().apply {
        featConfig = FeatureConfig(manifest.sampleRateHz, MEL_FEATURE_BINS, 0.0f)
        modelConfig = model
        decodingMethod = "greedy_search"
    }
}
