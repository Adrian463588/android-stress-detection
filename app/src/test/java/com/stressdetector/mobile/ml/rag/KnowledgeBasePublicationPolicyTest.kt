package com.stressdetector.mobile.ml.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeBasePublicationPolicyTest {
    @Test
    fun `empty snapshots cannot publish as ready`() {
        assertFalse(KnowledgeBasePublicationPolicy.canPublish(documentCount = 0, chunkCount = 0))
        assertFalse(KnowledgeBasePublicationPolicy.canPublish(documentCount = 1, chunkCount = 0))
        assertEquals("no_eligible_chunks", KnowledgeBasePublicationPolicy.retainedReason(1, 0))
    }

    @Test
    fun `nonempty snapshot is eligible for atomic publication`() {
        assertTrue(KnowledgeBasePublicationPolicy.canPublish(documentCount = 1, chunkCount = 1))
    }
}
