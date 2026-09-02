package com.stressdetector.mobile.ml.rag

import android.net.Uri

/** Compatibility facade for callers that still use the previous processor name. */
class KnowledgeBaseProcessor(private val ingestionService: DocumentIngestionService) {
    suspend fun processDocument(uri: Uri, title: String): Result<Unit> = runCatching {
        ingestionService.ingest(uri, title)
        Unit
    }
}
