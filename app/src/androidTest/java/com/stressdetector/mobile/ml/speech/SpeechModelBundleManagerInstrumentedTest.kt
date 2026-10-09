package com.stressdetector.mobile.ml.speech

import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun runtimeRejectedImportPreservesActiveFileHashAndReadiness() = withReadyBundle { manager, isolatedFiles ->
        val before = manager.readiness.value
        val originalHash = sha256(manager.bundleFile().readBytes())
        val candidate = File(isolatedFiles, "rejected-import.zip")
        writeValidCandidate(candidate, "rejected candidate metadata")
        assertNotEquals(originalHash, sha256(candidate.readBytes()))
        var validatorCalled = false

        val result = withTimeout(IMPORT_TIMEOUT_MILLIS) {
            manager.importBundle(Uri.fromFile(candidate)) {
                validatorCalled = true
                assertTrue(manager.activeReadinessFromDisk() is SpeechBundleReadiness.Ready)
                error("Runtime rejected metadata-only candidate")
            }
        }

        assertTrue(validatorCalled)
        assertTrue(result is SpeechBundleReadiness.Invalid)
        assertActiveUnchanged(manager, originalHash, before)
    }

    @Test
    fun unreadableImportPreservesActiveReadinessWithoutCallingRuntime() = withReadyBundle { manager, isolatedFiles ->
        val before = manager.readiness.value
        val originalHash = sha256(manager.bundleFile().readBytes())
        var validatorCalled = false

        val result = manager.importBundle(Uri.fromFile(File(isolatedFiles, "missing-import.zip"))) {
            validatorCalled = true
        }

        assertFalse(validatorCalled)
        assertTrue(result is SpeechBundleReadiness.Invalid)
        assertActiveUnchanged(manager, originalHash, before)
    }

    @Test
    fun cancelledRuntimeImportRethrowsAndRestoresPreviousBundle() = withReadyBundle { manager, isolatedFiles ->
        val before = manager.readiness.value
        val originalHash = sha256(manager.bundleFile().readBytes())
        val candidate = File(isolatedFiles, "cancelled-import.zip")
        writeValidCandidate(candidate, "cancelled candidate metadata")

        try {
            withTimeout(IMPORT_TIMEOUT_MILLIS) {
                manager.importBundle(Uri.fromFile(candidate)) {
                    throw CancellationException("Runtime validation cancelled")
                }
            }
            fail("Cancellation must reach the importer")
        } catch (error: CancellationException) {
            assertEquals("Runtime validation cancelled", error.message)
        }

        assertActiveUnchanged(manager, originalHash, before)
    }

    private fun withReadyBundle(block: suspend (SpeechModelBundleManager, File) -> Unit) {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val isolatedFiles = File(baseContext.cacheDir, "speech-import-test-${System.nanoTime()}")
        check(isolatedFiles.mkdirs())
        val context = object : ContextWrapper(baseContext) {
            override fun getApplicationContext() = this
            override fun getFilesDir(): File = isolatedFiles
        }
        try {
            val original = File(isolatedFiles, "original.zip")
            val descriptor = writeValidCandidate(original, "accepted original metadata")
            val manager = SpeechModelBundleManager(context, descriptor = descriptor)
            runBlocking {
                // Byte fixtures validate archive/activation mechanics only, never native inference.
                assertTrue(manager.installBundleFileWithRuntimeValidation(original) {} is SpeechBundleReadiness.Ready)
                block(manager, isolatedFiles)
            }
        } finally {
            isolatedFiles.deleteRecursively()
        }
    }

    private fun assertActiveUnchanged(manager: SpeechModelBundleManager, originalHash: String, before: SpeechBundleReadiness) {
        assertTrue(manager.bundleFile().isFile)
        assertEquals(originalHash, sha256(manager.bundleFile().readBytes()))
        assertEquals(before, manager.readiness.value)
        assertTrue(manager.activeReadinessFromDisk() is SpeechBundleReadiness.Ready)
    }

    private fun writeValidCandidate(destination: File, archiveComment: String = "metadata-only fixture"): SpeechBundleDescriptor {
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
            zip.setComment(archiveComment)
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

    private companion object {
        const val IMPORT_TIMEOUT_MILLIS = 10_000L
    }
}
