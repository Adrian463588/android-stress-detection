package com.stressdetector.mobile.ml.speech

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpeechBundleArchiveAssemblerTest {
    @Test
    fun assemblesManifestAndAllRuntimeFilesIntoCanonicalZip() {
        val root = Files.createTempDirectory("speech-assembler-test").toFile()
        try {
            val inputs = writeFixtureInputs(root)
            val descriptor = descriptorFor(inputs).copy(
                indonesianStt = IndonesianStreamingSttSpec(
                    "fixture-indonesian-revision",
                    "asr/id/encoder.int8.onnx",
                    "asr/id/decoder.int8.onnx",
                    "asr/id/joiner.int8.onnx",
                    "asr/id/tokens.txt",
                    "component-2"
                ),
                whisperStt = WhisperSttSpec(
                    "fixture-whisper-revision",
                    "asr/whisper/tiny-encoder.int8.onnx",
                    "asr/whisper/tiny-decoder.int8.onnx",
                    "asr/whisper/tiny-tokens.txt"
                )
            )
            val downloads = descriptor.components.associate { component ->
                component.id to inputs.getValue(component.extractedFiles.single().bundlePath)
            }
            val destination = File(root, "bundle.zip")
            val progress = mutableListOf<SpeechAssemblyProgress>()
            val manifest = SpeechBundleArchiveAssembler().assemble(
                descriptor = descriptor,
                downloadedFiles = downloads,
                destination = destination
            ) { progress += it }

            assertTrue(destination.isFile)
            assertTrue(progress.any { it.stage == SpeechAssemblyStage.EXTRACTING })
            assertTrue(progress.any { it.stage == SpeechAssemblyStage.EXTRACTED_SHA256 })
            assertTrue(progress.any { it.stage == SpeechAssemblyStage.BUILDING_ZIP })
            assertEquals("2.0.0", manifest.schemaVersion)
            assertEquals(12, manifest.files.size)
            assertEquals("asr/model.int8.onnx", manifest.stt.model)
            assertEquals("asr/id/encoder.int8.onnx", manifest.indonesianStt?.encoder)
            assertWhisperProfile(manifest)
            ZipFile(destination).use { zip ->
                assertTrue(zip.getEntry("manifest.json") != null)
                val serialized = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }
                assertTrue(serialized.contains("\"whisper_stt\""))
                assertEquals(manifest, Gson().fromJson(serialized, SpeechBundleManifest::class.java))
                manifest.files.forEach { (path, checksum) ->
                    val entry = zip.getEntry(path) ?: error("Missing $path")
                    assertEquals(manifest.fileSizesBytes.getValue(path), entry.size)
                    assertEquals(checksum, sha256(zip.getInputStream(entry)))
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertWhisperProfile(manifest: SpeechBundleManifest) {
        assertEquals(
            WhisperSttManifest(
                "asr/whisper/tiny-encoder.int8.onnx",
                "asr/whisper/tiny-decoder.int8.onnx",
                "asr/whisper/tiny-tokens.txt",
                "fixture-whisper-revision"
            ),
            manifest.whisperStt
        )
    }

    private fun writeFixtureInputs(root: File): Map<String, File> = mapOf(
        "asr/model.int8.onnx" to "asr-model".toByteArray(),
        "asr/tokens.txt" to "tokens".toByteArray(),
        "asr/id/encoder.int8.onnx" to "indonesian-encoder-fixture".toByteArray(),
        "asr/id/decoder.int8.onnx" to "indonesian-decoder-fixture".toByteArray(),
        "asr/id/joiner.int8.onnx" to "indonesian-joiner-fixture".toByteArray(),
        "asr/id/tokens.txt" to "indonesian-tokens-fixture".toByteArray(),
        "asr/whisper/tiny-encoder.int8.onnx" to "whisper-encoder-fixture".toByteArray(),
        "asr/whisper/tiny-decoder.int8.onnx" to "whisper-decoder-fixture".toByteArray(),
        "asr/whisper/tiny-tokens.txt" to "whisper-tokens-fixture".toByteArray(),
        "vad/silero_vad.onnx" to "vad".toByteArray(),
        "diarization/segmentation.onnx" to "segmentation".toByteArray(),
        "diarization/embedding.onnx" to "embedding".toByteArray()
    ).mapValues { (path, bytes) ->
        File(root, path).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    @Test
    fun rejectsPathTraversalBeforeMaterializingCandidate() {
        val root = Files.createTempDirectory("speech-path-test").toFile()
        try {
            val unsafe = File(root, "safe-input.bin").apply { writeBytes("unsafe".toByteArray()) }
            val component = SpeechBundleComponentSpec(
                id = "unsafe",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/test",
                downloadFileName = "unsafe.bin",
                expectedDownloadBytes = unsafe.length(),
                expectedDownloadSha256 = sha256(unsafe),
                license = "test",
                upstreamSource = "https://example.com",
                extractedFiles = listOf(SpeechBundleFileSpec("../outside.bin", null, unsafe.length(), sha256(unsafe)))
            )
            val descriptor =
                SpeechBundleDescriptor("test", "1.13.4", 16_000, listOf("id", "en"), 2, listOf(component), "https://example.com")
            try {
                SpeechBundleArchiveAssembler().assemble(descriptor, mapOf("unsafe" to unsafe), File(root, "bundle.zip"))
                fail("Unsafe path should be rejected")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("Unsafe archive path"))
            }
            assertTrue(!File(root, "outside.bin").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun descriptorFor(inputs: Map<String, File>): SpeechBundleDescriptor {
        val components = inputs.entries.chunked(1).mapIndexed { index, entry ->
            val (path, file) = entry.single()
            SpeechBundleComponentSpec(
                id = "component-$index",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/test",
                downloadFileName = "component-$index.bin",
                expectedDownloadBytes = file.length(),
                expectedDownloadSha256 = sha256(file),
                license = "Apache-2.0",
                upstreamSource = "https://example.com",
                extractedFiles = listOf(SpeechBundleFileSpec(path, null, file.length(), sha256(file)))
            )
        }
        return SpeechBundleDescriptor("test", "1.13.4", 16_000, listOf("id", "en"), 2, components, "https://example.com")
    }

    private fun sha256(file: File): String = file.inputStream().use(::sha256)

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { stream ->
            val buffer = ByteArray(1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
