package com.stressdetector.mobile.audio

import com.stressdetector.mobile.ml.speech.SpeechBundleManifest
import com.stressdetector.mobile.ml.speech.SpeechDiarizationManifest
import com.stressdetector.mobile.ml.speech.SpeechSttManifest
import com.stressdetector.mobile.ml.speech.SpeechVadManifest
import com.stressdetector.mobile.ml.speech.WhisperSttManifest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OfflineSpeechConfigTest {
    @Test
    fun multilingualWhisperUsesSelectedLanguageAndTranscriptionNotTranslation() {
        listOf("id", "en").forEach { language ->
            val config = offlineSpeechConfig(manifest(whisper()), File("runtime-test"), language)
            assertEquals("whisper", config.modelConfig.modelType)
            assertEquals(language, config.modelConfig.whisper.language)
            assertEquals("transcribe", config.modelConfig.whisper.task)
            assertEquals("cpu", config.modelConfig.provider)
            assertFalse(config.modelConfig.whisper.encoder.contains(".en-"))
            assertEquals(File("runtime-test/whisper/tiny-decoder.int8.onnx").absolutePath, config.modelConfig.whisper.decoder)
        }
    }

    @Test
    fun knownLegacyEnglishUsesDeclaredLocalModel() {
        val config = offlineSpeechConfig(manifest(null), File("runtime-test"), "en")
        assertEquals("omnilingual_ctc", config.modelConfig.modelType)
        assertEquals(File("runtime-test/legacy.onnx").absolutePath, config.modelConfig.omnilingual.model)
    }

    @Test(expected = IllegalArgumentException::class)
    fun translationIsRejectedBeforeNativeLoading() {
        offlineSpeechConfig(manifest(whisper().copy(task = "translate")), File("runtime-test"), "id")
    }

    @Test(expected = IllegalArgumentException::class)
    fun englishOnlyProfileCannotBeUsedForIndonesian() {
        offlineSpeechConfig(manifest(whisper().copy(languages = listOf("en"))), File("runtime-test"), "id")
    }

    private fun whisper() = WhisperSttManifest(
        "whisper/tiny-encoder.int8.onnx",
        "whisper/tiny-decoder.int8.onnx",
        "whisper/tiny-tokens.txt",
        "test-revision"
    )

    private fun manifest(whisper: WhisperSttManifest?) = SpeechBundleManifest(
        schemaVersion = "2.0.0", bundleVersion = "unit-test", runtimeVersion = "1.13.4",
        sampleRateHz = 16_000, languages = listOf("id", "en"),
        stt = SpeechSttManifest("omnilingual_ctc", "legacy.onnx", "tokens.txt"),
        vad = SpeechVadManifest(true, "vad.onnx"),
        diarization = SpeechDiarizationManifest(true, 2, "segmentation.onnx", "embedding.onnx"),
        files = emptyMap(), whisperStt = whisper
    )
}
