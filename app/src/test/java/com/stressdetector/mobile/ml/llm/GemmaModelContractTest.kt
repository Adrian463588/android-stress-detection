package com.stressdetector.mobile.ml.llm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaModelContractTest {

    @Test
    fun officialContractUsesTheCanonicalGemma4E2BDownload() {
        assertEquals("gemma-4-E2B-it.litertlm", ModelManager.MODEL_FILE_NAME)
        assertEquals(ModelManager.MODEL_FILE_NAME, ModelManager.MODEL_FILE)
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94/gemma-4-E2B-it.litertlm",
            ModelManager.MODEL_BASE_URL
        )
        assertEquals(ModelManager.MODEL_BASE_URL, ModelManager.MODEL_DOWNLOAD_URL)
        assertEquals(2_588_147_712L, ModelManager.MODEL_SIZE_BYTES)
        assertEquals(
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            ModelManager.MODEL_SHA256
        )
        assertFalse(ModelManager.MODEL_BASE_URL.contains("/resolve/main/"))
        assertTrue(ModelManager.MODEL_BASE_URL.contains("6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94"))
        assertFalse(ModelManager.MODEL_BASE_URL.contains("/raw/"))
        assertFalse(ModelManager.MODEL_BASE_URL.contains("?"))
    }

    @Test
    fun verifierAcceptsExactSizeAndChecksum() {
        val file = File.createTempFile("gemma-contract-", ".litertlm")
        try {
            file.writeText("test")
            val contract = OfficialModelContract(
                fileName = file.name,
                sourceUrl = "https://example.invalid/resolve/test-revision/model",
                sizeBytes = 4L,
                sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                revision = "test-revision"
            )
            assertTrue(verifyOfficialModelFile(file, contract, requireCanonicalName = true).isSuccess)
        } finally {
            file.delete()
        }
    }

    @Test
    fun verifierRejectsWrongSizeBeforePublish() {
        val file = File.createTempFile("gemma-contract-", ".litertlm")
        try {
            file.writeText("bad")
            val contract = OfficialModelContract(
                fileName = file.name,
                sourceUrl = "https://example.invalid/resolve/test-revision/model",
                sizeBytes = 4L,
                sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                revision = "test-revision"
            )
            assertTrue(verifyOfficialModelFile(file, contract, requireCanonicalName = true).isFailure)
        } finally {
            file.delete()
        }
    }

    @Test
    fun verifierRejectsWrongChecksum() {
        val file = File.createTempFile("gemma-contract-", ".litertlm")
        try {
            file.writeText("test")
            val contract = OfficialModelContract(
                fileName = file.name,
                sourceUrl = "https://example.invalid/resolve/test-revision/model",
                sizeBytes = 4L,
                sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
                revision = "test-revision"
            )
            assertTrue(verifyOfficialModelFile(file, contract, requireCanonicalName = true).isFailure)
        } finally {
            file.delete()
        }
    }
}
