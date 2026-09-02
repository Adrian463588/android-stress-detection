package com.stressdetector.mobile.domain.search

import com.stressdetector.mobile.domain.engine.KnowledgeCitation

enum class WebSearchMode {
    Disabled,
    NextMessage,
    Persistent
}

data class WebGroundingResult(val query: String, val citations: List<KnowledgeCitation>, val retrievedAtMillis: Long)

/** Automatic HTTPS discovery boundary. UI never supplies URLs or opens a picker. */
interface WebGroundingGateway {
    suspend fun retrieveAutomatically(query: String): Result<WebGroundingResult>

    data object Unavailable : WebGroundingGateway {
        override suspend fun retrieveAutomatically(query: String): Result<WebGroundingResult> =
            Result.failure(IllegalStateException("Web grounding belum tersedia pada build ini."))
    }
}
