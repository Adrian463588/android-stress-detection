package com.stressdetector.mobile.ml.llm

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ModelVerificationProgress(val bytesProcessed: Long, val totalBytes: Long)

class ModelChecksumMismatchException(message: String) : IOException(message)

/** Performs one canonical, streaming size and SHA-256 verification for model files. */
class ModelIntegrityVerifier(private val contract: OfficialModelContract) {
    companion object {
        private const val BUFFER_SIZE = 1024 * 1024
        private const val PROGRESS_INTERVAL_BYTES = 16L * 1024L * 1024L
    }

    fun verifyBlocking(file: File, requireCanonicalName: Boolean): Result<Unit> = runCatching {
        verifyBasicFile(file, requireCanonicalName)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        checkDigest(digest.digest())
    }

    suspend fun verify(
        file: File,
        requireCanonicalName: Boolean = false,
        onProgress: suspend (ModelVerificationProgress) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            verifyBasicFile(file, requireCanonicalName)
            val digest = MessageDigest.getInstance("SHA-256")
            val total = file.length()
            var processed = 0L
            var lastProgress = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    processed += count
                    if (processed - lastProgress >= PROGRESS_INTERVAL_BYTES || processed == total) {
                        onProgress(ModelVerificationProgress(processed, total))
                        lastProgress = processed
                    }
                }
            }
            checkDigest(digest.digest())
            Result.success(Unit)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun verifyBasicFile(file: File, requireCanonicalName: Boolean) {
        check(file.isFile) { "Downloaded model file is missing" }
        if (requireCanonicalName) {
            check(file.name == contract.fileName) { "Unsupported model filename" }
        }
        if (file.length() != contract.sizeBytes) {
            throw ModelChecksumMismatchException(
                "Model size does not match the official Gemma 4 E2B contract"
            )
        }
    }

    private fun checkDigest(bytes: ByteArray) {
        val actualSha = bytes.joinToString("") { "%02x".format(it) }
        if (!actualSha.equals(contract.sha256, ignoreCase = true)) {
            throw ModelChecksumMismatchException(
                "Model checksum does not match the official Gemma 4 E2B contract"
            )
        }
    }
}
