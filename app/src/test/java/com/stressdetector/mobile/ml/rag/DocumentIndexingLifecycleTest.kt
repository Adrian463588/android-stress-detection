package com.stressdetector.mobile.ml.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentIndexingLifecycleTest {
    @Test
    fun `partial extraction is not retrieval ready without explicit acknowledgement`() {
        val decision = DocumentIndexingLifecycle.publicationDecision(
            extractionStatus = ExtractionStatus.PARTIAL,
            allowPartialExtraction = false
        )

        assertEquals(DocumentPublicationState.PARTIAL, decision.state)
        assertFalse(decision.publishChunks)
    }

    @Test
    fun `complete extraction can publish atomically`() {
        val decision = DocumentIndexingLifecycle.publicationDecision(
            extractionStatus = ExtractionStatus.COMPLETE,
            allowPartialExtraction = false
        )

        assertEquals(DocumentPublicationState.READY, decision.state)
        assertTrue(decision.publishChunks)
    }

    @Test
    fun `reviewed partial extraction publishes only after acknowledgement`() {
        val decision = DocumentIndexingLifecycle.publicationDecision(
            extractionStatus = ExtractionStatus.PARTIAL,
            allowPartialExtraction = true
        )

        assertEquals(DocumentPublicationState.READY, decision.state)
        assertTrue(decision.publishChunks)
    }

    @Test
    fun `unapproved replacement retains an existing ready index`() {
        assertTrue(
            DocumentIndexingLifecycle.retainsExistingReadyIndex(
                existingReadyIndex = true,
                publishesReplacement = false
            )
        )
        assertFalse(
            DocumentIndexingLifecycle.retainsExistingReadyIndex(
                existingReadyIndex = true,
                publishesReplacement = true
            )
        )
    }

    @Test
    fun `stale indexing uses processing activity rather than upload age`() {
        assertTrue(
            DocumentIndexingLifecycle.requiresReindex(
                processingState = "INDEXING",
                processingUpdatedAtMillis = 1_000L,
                nowMillis = 1_000L + DocumentIndexingLifecycle.STALE_INDEXING_MILLIS
            )
        )
        assertFalse(
            DocumentIndexingLifecycle.requiresReindex(
                processingState = "READY_LEXICAL",
                processingUpdatedAtMillis = 1_000L,
                nowMillis = Long.MAX_VALUE
            )
        )
        assertFalse(
            DocumentIndexingLifecycle.requiresReindex(
                processingState = "INDEXING",
                processingUpdatedAtMillis = 0L,
                nowMillis = Long.MAX_VALUE
            )
        )
    }
}
