package com.stressdetector.mobile.ml.rag

/** Prevents an empty READY generation from replacing a usable knowledge base. */
object KnowledgeBasePublicationPolicy {
    fun canPublish(documentCount: Int, chunkCount: Int): Boolean = documentCount > 0 && chunkCount > 0

    fun retainedReason(documentCount: Int, chunkCount: Int): String = when {
        documentCount <= 0 -> "no_eligible_documents"
        chunkCount <= 0 -> "no_eligible_chunks"
        else -> "ready"
    }
}
