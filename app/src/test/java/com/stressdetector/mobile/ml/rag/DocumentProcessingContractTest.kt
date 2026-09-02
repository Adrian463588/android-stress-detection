package com.stressdetector.mobile.ml.rag

import com.stressdetector.mobile.data.db.ClinicalDocumentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentProcessingContractTest {
    @Test
    fun `permission failure is persisted as a safe structured code`() {
        val failure = DocumentProcessingFailure.from(DocumentAccessException("provider denied"))

        assertEquals(DocumentProcessingFailureCode.PERMISSION_REVOKED, failure.code)
        assertTrue(failure.userMessage.contains("Akses dokumen"))
    }

    @Test
    fun `partial document projects as non-retrieval-ready with explicit failure`() {
        val snapshot = ClinicalDocumentEntity(
            documentId = "doc",
            title = "Clinical",
            filePath = "encrypted://doc",
            uploadedAtMillis = 1L,
            isProcessed = false,
            processingState = "PARTIAL",
            processingErrorCode = DocumentProcessingFailureCode.PARTIAL_EXTRACTION.name,
            processingStartedAtMillis = 10L,
            processingUpdatedAtMillis = 20L,
            processingRevision = 2
        ).toProcessingSnapshot()

        assertEquals(DocumentProcessingState.PARTIAL, snapshot.state)
        assertEquals(DocumentProcessingFailureCode.PARTIAL_EXTRACTION, snapshot.failureCode)
        assertFalse(snapshot.isRetrievalReady)
        assertEquals(2, snapshot.processingRevision)
    }

    @Test
    fun `unknown persisted state fails closed`() {
        val snapshot = ClinicalDocumentEntity(
            documentId = "doc",
            title = "Clinical",
            filePath = "encrypted://doc",
            uploadedAtMillis = 1L,
            isProcessed = true,
            processingState = "FUTURE_UNMAPPED_STATE"
        ).toProcessingSnapshot()

        assertEquals(DocumentProcessingState.FAILED, snapshot.state)
        assertFalse(snapshot.isRetrievalReady)
    }

    @Test
    fun `partial processing limit has a distinct safe failure code`() {
        val failure = DocumentProcessingFailure.from(
            IllegalStateException("PARTIAL_LIMIT_REACHED while processing PDF")
        )

        assertEquals(DocumentProcessingFailureCode.PARTIAL_LIMIT_REACHED, failure.code)
    }

    @Test
    fun `weighted rag progress is bounded and stage ordered`() {
        assertEquals(
            0.40f,
            RagImportProgressCalculator.fraction(DocumentIngestionProgress.Stage.CHUNKING, 0, 10)
        )
        assertEquals(
            0.75f,
            RagImportProgressCalculator.fraction(DocumentIngestionProgress.Stage.EMBEDDING, 5, 10)
        )
        assertEquals(
            1.0f,
            RagImportProgressCalculator.fraction(DocumentIngestionProgress.Stage.SAVING, 10, 10)
        )
        val bounded = requireNotNull(
            RagImportProgressCalculator.fraction(DocumentIngestionProgress.Stage.EMBEDDING, 100, 10)
        )
        assertTrue(bounded in 0f..1f)
    }

    @Test
    fun `unknown stage total remains indeterminate`() {
        assertEquals(
            null,
            RagImportProgressCalculator.fraction(DocumentIngestionProgress.Stage.EXTRACTING, 0, 0)
        )
    }
}
