package com.stressdetector.mobile.ml.llm

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelIntegrityVerifierTest {
    @Test
    fun streamingVerificationReportsRealProgress() = runBlocking {
        val file = File.createTempFile("gemma-integrity-", ".litertlm")
        try {
            file.writeText("test")
            val contract = OfficialModelContract(
                fileName = file.name,
                sourceUrl = "https://example.invalid/resolve/test-revision/model",
                sizeBytes = 4L,
                sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                revision = "test-revision"
            )
            val progress = mutableListOf<ModelVerificationProgress>()

            val result = ModelIntegrityVerifier(contract).verify(file) { progress += it }

            assertTrue(result.isSuccess)
            assertEquals(listOf(ModelVerificationProgress(4L, 4L)), progress)
        } finally {
            file.delete()
        }
    }

    @Test
    fun checksumMismatchIsClassifiedForCandidateCleanup() = runBlocking {
        val file = File.createTempFile("gemma-integrity-", ".litertlm")
        try {
            file.writeText("test")
            val contract = OfficialModelContract(
                fileName = file.name,
                sourceUrl = "https://example.invalid/resolve/test-revision/model",
                sizeBytes = 4L,
                sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
                revision = "test-revision"
            )

            val error = ModelIntegrityVerifier(contract).verify(file).exceptionOrNull()

            assertTrue(error is ModelChecksumMismatchException)
        } finally {
            file.delete()
        }
    }
}
