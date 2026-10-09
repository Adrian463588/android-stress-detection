package com.stressdetector.mobile.ml.speech

import com.stressdetector.mobile.ml.artifacts.LocalArtifactCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechBundleCatalogTest {
    @Test
    fun canonicalCatalogIsPinnedAndComplete() {
        val catalogFile = listOf(
            File("src/main/canonicalAssets/ai/artifact_catalog.json"),
            File("app/src/main/canonicalAssets/ai/artifact_catalog.json")
        ).first(File::isFile)
        val descriptor = SpeechBundleCatalog.from(LocalArtifactCatalog.parse(catalogFile.readText()))

        assertEquals("sherpa-onnx-1.13.4-omnilingual-ctc-v3-supertonic3-whisper-tiny-v4", descriptor.bundleVersion)
        assertEquals("1.13.4", descriptor.runtimeVersion)
        assertEquals(listOf("id", "en"), descriptor.languages)
        assertEquals(2, descriptor.speakerCount)
        assertEquals(14, descriptor.components.size)
        assertEquals(
            "4e5a13cbe3e9cd4e3775447d86178ef51759096f",
            descriptor.indonesianStt?.revision
        )
        val indonesianComponents = descriptor.components.filter {
            it.id.startsWith("asr-indonesian-zipformer2-int8")
        }
        assertEquals(4, indonesianComponents.size)
        assertEquals(
            70_103_186L,
            indonesianComponents.first { it.id == "asr-indonesian-zipformer2-int8" }.expectedDownloadBytes
        )
        assertEquals(
            "3a6f85f5d199ad0d495562988af017a75d4ae81b51126db240d959b065d6eaad",
            indonesianComponents.first { it.id == "asr-indonesian-zipformer2-int8" }.expectedDownloadSha256
        )
        assertEquals(
            "f0b6f5bf602d96f60d79bb17192c51eeffb6189250f132b1dfdf72b130d66968",
            indonesianComponents.first { it.id.endsWith("tokens") }.expectedDownloadSha256
        )
        val whisper = requireNotNull(descriptor.whisperStt)
        assertEquals("65176e2deb88badc814a94058666cadccc29b61c", whisper.revision)
        assertEquals(listOf("id", "en"), whisper.languages)
        assertEquals("transcribe", whisper.task)
        assertEquals("asr/whisper/tiny-encoder.int8.onnx", whisper.encoderPath)
        assertEquals("asr/whisper/tiny-decoder.int8.onnx", whisper.decoderPath)
        assertEquals("asr/whisper/tiny-tokens.txt", whisper.tokensPath)
        val whisperPins = mapOf(
            "asr-whisper-tiny-int8-encoder" to (12_937_772L to "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434"),
            "asr-whisper-tiny-int8-decoder" to (89_855_401L to "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"),
            "asr-whisper-tiny-int8-tokens" to (816_730L to "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126")
        )
        whisperPins.forEach { (id, pin) ->
            val component = descriptor.components.single { it.id == id }
            assertEquals(pin.first, component.expectedDownloadBytes)
            assertEquals(pin.second, component.expectedDownloadSha256)
            assertTrue(component.url.contains("/resolve/${whisper.revision}/tiny-"))
            assertEquals(pin.first, component.extractedFiles.single().expectedBytes)
            assertEquals(pin.second, component.extractedFiles.single().sha256)
        }
        assertEquals(
            SpeechBundleCatalog.LEGACY_COMPONENT_IDS,
            descriptor.components.filterNot { it.id in whisperPins }.map { it.id }.toSet()
        )
        assertEquals(
            setOf("id_ID-news_tts-medium", "en_US-lessac-medium", "supertonic3-id", "supertonic3-en"),
            descriptor.ttsProfiles.map { it.id }.toSet()
        )
        assertEquals(
            "9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e",
            descriptor.components.first { it.id == "tts-en-en_US-lessac-medium" }.expectedDownloadSha256
        )
        assertEquals(
            128_774_318L,
            descriptor.components.first { it.id == "tts-supertonic3-int8" }.expectedDownloadBytes
        )
        assertEquals(
            "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427",
            descriptor.components.first { it.id == "tts-supertonic3-int8" }.expectedDownloadSha256
        )
        assertTrue(descriptor.totalDownloadBytes > 400L * 1024L * 1024L)
        assertTrue(descriptor.requiredFreeStorageBytes > descriptor.totalDownloadBytes)
        val extractedBytes = descriptor.components.flatMap { it.extractedFiles }.sumOf { checkNotNull(it.expectedBytes) }
        assertEquals(descriptor.totalDownloadBytes + 4L * extractedBytes + 64L * 1024L * 1024L, descriptor.requiredFreeStorageBytes)

        descriptor.components.forEach { component ->
            assertTrue(
                component.url.startsWith("https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/assets/") ||
                    component.url.startsWith("https://huggingface.co/spacewave/") ||
                    component.url.startsWith("https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/${whisper.revision}/")
            )
            assertTrue(component.expectedDownloadBytes > 0L)
            assertTrue(component.expectedDownloadSha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(component.license.isNotBlank())
            assertTrue(component.upstreamSource.startsWith("https://"))
            assertFalse(component.extractedFiles.isEmpty())
            component.extractedFiles.forEach { file ->
                assertFalse(file.bundlePath.startsWith("/"))
                assertFalse(file.bundlePath.contains(".."))
                assertTrue(file.expectedBytes == null || file.expectedBytes > 0L)
                assertTrue(file.sha256 == null || file.sha256.matches(Regex("[0-9a-f]{64}")))
            }
        }
    }
}
