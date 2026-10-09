package com.stressdetector.mobile.ml.speech

import com.stressdetector.mobile.ml.artifacts.LocalArtifactCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Metadata/hash mechanics only; no model execution or inference evidence. */
class SpeechBundleGenerationTest {
    private val descriptor = SpeechBundleCatalog.from(
        LocalArtifactCatalog.parse(
            listOf(
                File("src/main/canonicalAssets/ai/artifact_catalog.json"),
                File("app/src/main/canonicalAssets/ai/artifact_catalog.json")
            ).first(File::isFile).readText()
        )
    )

    @Test
    fun legacyGenerationMatchesOriginalCatalogFingerprintExactly() {
        assertEquals(
            "sherpa-onnx-1.13.4-omnilingual-ctc-v3-supertonic3-9771b0466ac4",
            speechBundleGeneration(descriptor, SpeechBundleCatalog.LEGACY_BUNDLE_VERSION)
        )
    }

    @Test
    fun currentGenerationUsesCurrentVersionAndEveryCurrentComponent() {
        assertEquals(
            "sherpa-onnx-1.13.4-omnilingual-ctc-v3-supertonic3-whisper-tiny-v4-faa5eba53fdd",
            speechBundleGeneration(descriptor)
        )
    }

    @Test
    fun generationIsIndependentOfComponentOrder() {
        val reordered = descriptor.copy(components = descriptor.components.reversed())
        assertEquals(speechBundleGeneration(descriptor), speechBundleGeneration(reordered))
        assertEquals(
            speechBundleGeneration(descriptor, SpeechBundleCatalog.LEGACY_BUNDLE_VERSION),
            speechBundleGeneration(reordered, SpeechBundleCatalog.LEGACY_BUNDLE_VERSION)
        )
    }

    @Test
    fun whisperMetadataCannotRelabelOrChangeLegacyGeneration() {
        val changed = descriptor.copy(
            components = descriptor.components.map { component ->
                if (component.id in SpeechBundleCatalog.WHISPER_COMPONENT_IDS) {
                    component.copy(expectedDownloadSha256 = "0".repeat(64))
                } else {
                    component
                }
            }
        )
        assertEquals(
            speechBundleGeneration(descriptor, SpeechBundleCatalog.LEGACY_BUNDLE_VERSION),
            speechBundleGeneration(changed, SpeechBundleCatalog.LEGACY_BUNDLE_VERSION)
        )
        assertNotEquals(speechBundleGeneration(descriptor), speechBundleGeneration(changed))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownVersionCannotProduceAnAcceptedMirrorGeneration() {
        speechBundleGeneration(descriptor, "unapproved-version")
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteLegacyComponentSetCannotProduceLegacyGeneration() {
        speechBundleGeneration(
            descriptor.copy(components = descriptor.components.filterNot { it.id == "vad-silero" }),
            SpeechBundleCatalog.LEGACY_BUNDLE_VERSION
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicatedLegacyComponentCannotProduceLegacyGeneration() {
        speechBundleGeneration(
            descriptor.copy(components = descriptor.components + descriptor.components.first()),
            SpeechBundleCatalog.LEGACY_BUNDLE_VERSION
        )
    }
}
