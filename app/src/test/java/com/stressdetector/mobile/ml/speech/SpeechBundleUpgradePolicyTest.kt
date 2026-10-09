package com.stressdetector.mobile.ml.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechBundleUpgradePolicyTest {
    private val profile = WhisperSttSpec("test-revision", "encoder", "decoder", "tokens")
    private val descriptor = SpeechBundleDescriptor(
        "current",
        "1.13.4",
        16_000,
        listOf("id", "en"),
        2,
        emptyList(),
        "unit-test",
        whisperStt = profile
    )

    @Test
    fun usableLegacyRemainsReadyButDoesNotBlockExplicitUpgrade() {
        val legacy = SpeechBundleReadiness.Ready(
            manifest.copy(bundleVersion = SpeechBundleCatalog.LEGACY_BUNDLE_VERSION, whisperStt = null)
        )
        assertFalse(isCurrentSpeechBundleComplete(legacy, descriptor))
        assertTrue(isCurrentSpeechBundleComplete(SpeechBundleReadiness.Ready(manifest), descriptor))
    }

    @Test
    fun missingWhisperOrIndonesianNeverAdvertisesCurrentCompletion() {
        assertFalse(isCurrentSpeechBundleComplete(SpeechBundleReadiness.Ready(manifest.copy(whisperStt = null)), descriptor))
        assertFalse(isCurrentSpeechBundleComplete(SpeechBundleReadiness.Ready(manifest.copy(indonesianStt = null)), descriptor))
        assertFalse(isCurrentSpeechBundleComplete(SpeechBundleReadiness.Missing, descriptor))
    }

    @Test
    fun cachedLegacyAndCurrentReadyDoNotRequestIdleDiskHashing() {
        val legacy = SpeechBundleReadiness.Ready(
            manifest.copy(bundleVersion = SpeechBundleCatalog.LEGACY_BUNDLE_VERSION, whisperStt = null)
        )
        assertFalse(shouldRevalidateIdleSpeechBundle(legacy, hasActiveWork = false))
        assertFalse(shouldRevalidateIdleSpeechBundle(SpeechBundleReadiness.Ready(manifest), hasActiveWork = false))
        assertFalse(isCurrentSpeechBundleComplete(legacy, descriptor))
    }

    @Test
    fun activeUpgradeSkipsDiskRevalidationRegardlessOfInstalledReadiness() {
        listOf(
            SpeechBundleReadiness.Ready(manifest),
            SpeechBundleReadiness.Ready(manifest.copy(bundleVersion = SpeechBundleCatalog.LEGACY_BUNDLE_VERSION)),
            SpeechBundleReadiness.Checking,
            SpeechBundleReadiness.Missing,
            SpeechBundleReadiness.Invalid("metadata validation failure")
        ).forEach { readiness ->
            assertFalse(shouldRevalidateIdleSpeechBundle(readiness, hasActiveWork = true))
        }
    }

    @Test
    fun idleUnverifiedReadinessStillRequestsValidation() {
        listOf(
            SpeechBundleReadiness.Checking,
            SpeechBundleReadiness.Missing,
            SpeechBundleReadiness.Invalid("metadata validation failure")
        ).forEach { readiness ->
            assertTrue(shouldRevalidateIdleSpeechBundle(readiness, hasActiveWork = false))
        }
    }

    private val manifest = SpeechBundleManifest(
        schemaVersion = "2.0.0", bundleVersion = "current", runtimeVersion = "1.13.4", sampleRateHz = 16_000,
        languages = listOf("id", "en"), stt = SpeechSttManifest("omnilingual_ctc", "legacy", "tokens"),
        indonesianStt = IndonesianStreamingSttManifest("zipformer2_transducer", "id-encoder", "id-decoder", "id-joiner", "id-tokens"),
        vad = SpeechVadManifest(true, "vad"), diarization = SpeechDiarizationManifest(true, 2, "segmentation", "embedding"),
        ttsProfiles = listOf("id", "en").associateWith {
            SpeechTtsProfileManifest(
                language = it,
                voiceId = "unit-voice",
                archive = "archive",
                model = "model",
                tokens = "tokens",
                dataDir = "data",
                sampleRateHz = 16_000
            )
        },
        files = emptyMap(), whisperStt = WhisperSttManifest(profile.encoderPath, profile.decoderPath, profile.tokensPath, profile.revision)
    )
}
