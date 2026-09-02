package com.stressdetector.mobile.ml.download

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ArtifactVerificationProgress(val bytesProcessed: Long, val totalBytes: Long)

class ArtifactIntegrityException(message: String) : IOException(message)

/** Shared fail-closed size and SHA-256 verification for downloaded artifacts. */
class ArtifactIntegrityVerifier {
    fun verifyBlocking(file: File, expectedBytes: Long, expectedSha256: String, label: String): Result<Unit> = runCatching {
        verifyBasic(file, expectedBytes, expectedSha256, label)
        file.inputStream().use { input ->
            val digest = digest(input)
            checkDigest(digest, expectedSha256, label)
        }
    }

    suspend fun verify(
        file: File,
        expectedBytes: Long,
        expectedSha256: String,
        label: String,
        onProgress: suspend (ArtifactVerificationProgress) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            verifyBasic(file, expectedBytes, expectedSha256, label)
            val digest = MessageDigest.getInstance("SHA-256")
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
                    if (processed - lastProgress >= PROGRESS_INTERVAL_BYTES || processed == expectedBytes) {
                        onProgress(ArtifactVerificationProgress(processed, expectedBytes))
                        lastProgress = processed
                    }
                }
            }
            checkDigest(digest.digest(), expectedSha256, label)
            Result.success(Unit)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun verifyBasic(file: File, expectedBytes: Long, expectedSha256: String, label: String) {
        require(expectedSha256.matches(SHA256_PATTERN)) { "Invalid SHA-256 for $label." }
        if (!file.isFile) throw ArtifactIntegrityException("$label is missing.")
        if (file.length() != expectedBytes) {
            throw ArtifactIntegrityException("$label size mismatch: expected $expectedBytes bytes.")
        }
    }

    private fun digest(input: java.io.InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }

    private fun checkDigest(actual: ByteArray, expected: String, label: String) {
        val actualSha = actual.joinToString("") { "%02x".format(it) }
        if (!actualSha.equals(expected, ignoreCase = true)) {
            throw ArtifactIntegrityException("$label SHA-256 mismatch.")
        }
    }

    private companion object {
        const val BUFFER_SIZE = 1024 * 1024
        const val PROGRESS_INTERVAL_BYTES = 16L * 1024L * 1024L
        val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
    }
}
