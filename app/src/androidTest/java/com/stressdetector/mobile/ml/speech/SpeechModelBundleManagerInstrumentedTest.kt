package com.stressdetector.mobile.ml.speech

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechModelBundleManagerInstrumentedTest {
    @Test
    fun candidateIsRuntimeVisibleBeforeReadinessFlowIsPublished() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val isolatedFiles = File(baseContext.cacheDir, "speech-manager-test-${System.nanoTime()}")
        check(isolatedFiles.mkdirs())
        val context = object : ContextWrapper(baseContext) {
            override fun getApplicationContext() = this
            override fun getFilesDir(): File = isolatedFiles
        }

        try {
            val candidate = File(isolatedFiles, "candidate.zip")
            val descriptor = writeValidCandidate(candidate)
            val manager = SpeechModelBundleManager(context, descriptor = descriptor)
            var candidateWasVisible = false

            val result = kotlinx.coroutines.runBlocking {
                manager.installBundleFileWithRuntimeValidation(candidate) {
                    candidateWasVisible = manager.activeReadinessFromDisk() is SpeechBundleReadiness.Ready
                }
            }

            assertTrue(candidateWasVisible)
            assertTrue(result is SpeechBundleReadiness.Ready)
            assertTrue(manager.readiness.value is SpeechBundleReadiness.Ready)
        } finally {
            isolatedFiles.deleteRecursively()
        }
    }

    private fun writeValidCandidate(destination: File): SpeechBundleDescriptor {
        val files = linkedMapOf(
            "asr/model.int8.onnx" to "asr".toByteArray(),
            "asr/tokens.txt" to "tokens".toByteArray(),
            "vad/silero_vad.onnx" to "vad".toByteArray(),
            "diarization/segmentation.onnx" to "segmentation".toByteArray(),
            "diarization/embedding.onnx" to "embedding".toByteArray()
        )
        val fileHashes = files.mapValues { (_, bytes) -> sha256(bytes) }
        val descriptor = SpeechBundleDescriptor(
            bundleVersion = "test-bundle",
            runtimeVersion = "test-runtime",
            sampleRateHz = 16_000,
            languages = listOf("id", "en"),
            speakerCount = 2,
            components = listOf(
                SpeechBundleComponentSpec(
                    id = "test-runtime",
                    url = "https://example.com/test-runtime.zip",
                    downloadFileName = "test-runtime.zip",
                    expectedDownloadBytes = 0L,
                    expectedDownloadSha256 = sha256(ByteArray(0)),
                    license = "Apache-2.0",
                    upstreamSource = "https://example.com/test-runtime",
                    extractedFiles = files.map { (path, bytes) ->
                        SpeechBundleFileSpec(
                            bundlePath = path,
                            archiveEntrySuffix = null,
                            expectedBytes = bytes.size.toLong(),
                            sha256 = fileHashes.getValue(path)
                        )
                    }
                )
            ),
            runtimeSource = "https://example.com/test-runtime"
        )
        val manifest = SpeechBundleManifest(
            schemaVersion = SpeechModelBundleManager.SUPPORTED_SCHEMA,
            bundleVersion = "test-bundle",
            runtimeVersion = "test-runtime",
            sampleRateHz = 16_000,
            languages = listOf("id", "en"),
            stt = SpeechSttManifest("omnilingual_ctc", "asr/model.int8.onnx", "asr/tokens.txt"),
            vad = SpeechVadManifest(true, "vad/silero_vad.onnx"),
            diarization = SpeechDiarizationManifest(
                enabled = true,
                speakerCount = 2,
                segmentation = "diarization/segmentation.onnx",
                embedding = "diarization/embedding.onnx"
            ),
            files = fileHashes,
            fileSizesBytes = files.mapValues { (_, bytes) -> bytes.size.toLong() },
            sources = descriptor.components.associate { it.id to it.upstreamSource },
            licenses = descriptor.components.associate { it.id to it.license }
        )
        ZipOutputStream(destination.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(Gson().toJson(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            files.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return descriptor
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
