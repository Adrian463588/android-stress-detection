package com.stressdetector.mobile.ml.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentWorkIdentityTest {
    @Test
    fun `generic worker preserves the legacy WorkManager tag and source key`() {
        assertEquals("pdf_ingestion", DocumentIngestionWorker.WORK_TAG)
        assertEquals("pdf_uri", DocumentIngestionWorker.DATA_KEY_SOURCE_URI)
        val legacyWorker = Class.forName("com.stressdetector.mobile.ml.rag.PdfIngestionWorker")
        assertTrue(DocumentIngestionWorker::class.java.isAssignableFrom(legacyWorker))
    }

    @Test
    fun `import identity is stable and does not disclose source uri`() {
        val uri = "content://local.provider/private/document.pdf"

        val first = DocumentWorkIdentity.importUniqueName(uri)
        val second = DocumentWorkIdentity.importUniqueName(uri)

        assertEquals(first, second)
        assertFalse(first.contains(uri))
    }

    @Test
    fun `reindex identity is stable for the same document revision`() {
        val first = DocumentWorkIdentity.reindexUniqueName("doc-1", "a".repeat(64), 3)
        val second = DocumentWorkIdentity.reindexUniqueName("doc-1", "a".repeat(64), 3)

        assertEquals(first, second)
    }

    @Test
    fun `reindex identity changes when content or revision changes`() {
        val original = DocumentWorkIdentity.reindexUniqueName("doc-1", "a".repeat(64), 3)

        assertNotEquals(original, DocumentWorkIdentity.reindexUniqueName("doc-1", "b".repeat(64), 3))
        assertNotEquals(original, DocumentWorkIdentity.reindexUniqueName("doc-1", "a".repeat(64), 4))
    }
}
