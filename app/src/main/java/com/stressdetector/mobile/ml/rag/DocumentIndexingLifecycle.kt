package com.stressdetector.mobile.ml.rag

enum class DocumentPublicationState {
    READY,
    PARTIAL,
    FAILED
}

data class DocumentPublicationDecision(val state: DocumentPublicationState, val publishChunks: Boolean)

/** Pure policy for fail-closed publication and interrupted-index recovery. */
object DocumentIndexingLifecycle {
    const val STALE_INDEXING_MILLIS = 15L * 60L * 1_000L

    fun publicationDecision(extractionStatus: ExtractionStatus, allowPartialExtraction: Boolean): DocumentPublicationDecision =
        when (extractionStatus) {
            ExtractionStatus.COMPLETE,
            ExtractionStatus.OCR_COMPLETE
            -> DocumentPublicationDecision(DocumentPublicationState.READY, publishChunks = true)

            ExtractionStatus.PARTIAL -> if (allowPartialExtraction) {
                DocumentPublicationDecision(DocumentPublicationState.READY, publishChunks = true)
            } else {
                DocumentPublicationDecision(DocumentPublicationState.PARTIAL, publishChunks = false)
            }

            ExtractionStatus.FAILED -> DocumentPublicationDecision(DocumentPublicationState.FAILED, publishChunks = false)
        }

    fun requiresReindex(processingState: String, processingUpdatedAtMillis: Long, nowMillis: Long): Boolean =
        processingState == PROCESSING_STATE_INDEXING &&
            processingUpdatedAtMillis > 0L &&
            nowMillis - processingUpdatedAtMillis >= STALE_INDEXING_MILLIS

    /** A failed or unapproved replacement never evicts a usable published index. */
    fun retainsExistingReadyIndex(existingReadyIndex: Boolean, publishesReplacement: Boolean): Boolean =
        existingReadyIndex && !publishesReplacement

    const val PROCESSING_STATE_INDEXING = "INDEXING"
    const val PROCESSING_STATE_REINDEX_REQUIRED = "REINDEX_REQUIRED"
}
