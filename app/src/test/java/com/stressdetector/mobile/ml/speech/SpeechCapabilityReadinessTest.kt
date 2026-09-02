package com.stressdetector.mobile.ml.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCapabilityReadinessTest {

    @Test
    fun missingTtsDoesNotHideReadyVerbatimCapability() {
        val readiness = SpeechBundleReadiness.Ready(manifest(ttsProfiles = emptyMap(), hasIndonesianStt = true))

        val capabilities = projectSpeechCapabilities(
            readiness = readiness,
            requiredTtsProfileIds = setOf("id", "en")
        )

        assertTrue(capabilities.verbatimReady)
        assertFalse(capabilities.voiceAiReady)
        assertTrue(capabilities.missingComponents.contains("id"))
    }

    @Test
    fun completeTtsProjectionMakesBothCapabilitiesReady() {
        val profiles = mapOf(
            "id" to ttsProfile("id"),
            "en" to ttsProfile("en")
        )
        val capabilities = projectSpeechCapabilities(
            readiness = SpeechBundleReadiness.Ready(manifest(profiles, hasIndonesianStt = true)),
            requiredTtsProfileIds = profiles.keys
        )

        assertTrue(capabilities.verbatimReady)
        assertTrue(capabilities.voiceAiReady)
        assertTrue(capabilities.missingComponents.isEmpty())
    }

    @Test
    fun missingIndonesianStreamingModelDoesNotAdvertiseVerbatimReady() {
        val capabilities = projectSpeechCapabilities(
            readiness = SpeechBundleReadiness.Ready(manifest(emptyMap(), hasIndonesianStt = false)),
            requiredTtsProfileIds = emptySet()
        )

        assertFalse(capabilities.verbatimReady)
        assertFalse(capabilities.indonesianSttReady)
        assertTrue(capabilities.missingComponents.contains("Indonesian streaming STT"))
    }

    private fun manifest(ttsProfiles: Map<String, SpeechTtsProfileManifest>, hasIndonesianStt: Boolean): SpeechBundleManifest =
        SpeechBundleManifest(
            schemaVersion = "2.0.0",
            bundleVersion = "test",
            runtimeVersion = "test",
            sampleRateHz = 16_000,
            languages = listOf("id", "en"),
            stt = SpeechSttManifest("omnilingual_ctc", model = "asr.onnx", tokens = "tokens.txt"),
            indonesianStt = if (hasIndonesianStt) {
                IndonesianStreamingSttManifest(
                    modelType = "zipformer2_transducer",
                    encoder = "id-encoder.onnx",
                    decoder = "id-decoder.onnx",
                    joiner = "id-joiner.onnx",
                    tokens = "id-tokens.txt"
                )
            } else {
                null
            },
            vad = SpeechVadManifest(enabled = true, model = "vad.onnx"),
            diarization = SpeechDiarizationManifest(
                enabled = true,
                speakerCount = 2,
                segmentation = "seg.onnx",
                embedding = "embedding.onnx"
            ),
            ttsProfiles = ttsProfiles,
            files = emptyMap()
        )

    private fun ttsProfile(language: String): SpeechTtsProfileManifest = SpeechTtsProfileManifest(
        language = language,
        voiceId = "$language-test",
        archive = "$language.tar.bz2",
        model = "model.onnx",
        tokens = "tokens.txt",
        dataDir = "data",
        sampleRateHz = 22_050
    )
}
