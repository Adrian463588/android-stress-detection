package com.stressdetector.mobile.ml.rag

import com.stressdetector.mobile.domain.engine.CitationSource
import com.stressdetector.mobile.domain.engine.EvidenceSourceTier
import com.stressdetector.mobile.domain.engine.KnowledgeCitation
import com.stressdetector.mobile.domain.engine.RagRetrievalResult
import com.stressdetector.mobile.domain.engine.RagRetriever

/**
 * Single local-first retrieval projection. Web grounding remains outside this boundary and is
 * invoked by the ViewModel only after this resolver has abstained.
 */
class TieredRagRetriever(private val appRag: RagRetriever, private val bundledLocal: RagRetriever) : RagRetriever {
    override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float): RagRetrievalResult {
        val appResult = retrieveFrom(appRag, query, maxResults, minRelevanceThreshold, "APP_RAG")
        val bundledResult = retrieveFrom(
            bundledLocal,
            query,
            maxResults,
            minRelevanceThreshold,
            "Bundled local corpus"
        )
        return when {
            appResult.hasUsableEvidence() -> appResult.withTier(EvidenceSourceTier.APP_RAG)
            bundledResult.hasUsableEvidence() -> bundledResult.withTier(EvidenceSourceTier.BUNDLED_LOCAL)
            else -> RagRetrievalResult.Unavailable(
                "Tidak ada evidence lokal yang valid dari APP_RAG atau dataset bawaan.",
                sourceTier = EvidenceSourceTier.NONE
            )
        }
    }

    private suspend fun retrieveFrom(
        retriever: RagRetriever,
        query: String,
        maxResults: Int,
        minRelevanceThreshold: Float,
        label: String
    ): RagRetrievalResult = runCatching {
        retriever.retrieveRelevantPassages(query, maxResults, minRelevanceThreshold)
    }.getOrElse { error ->
        RagRetrievalResult.Unavailable("$label unavailable: ${error.message ?: "retrieval failed"}")
    }
}

private fun RagRetrievalResult.hasUsableEvidence(): Boolean = this is RagRetrievalResult.Retrieved &&
    citations.any(KnowledgeCitation::hasUsableProvenance)

private fun KnowledgeCitation.hasUsableProvenance(): Boolean {
    val commonFieldsPresent = citationId.isNotBlank() &&
        sourceTitle.isNotBlank() &&
        section.isNotBlank() &&
        text.isNotBlank()
    val localProvenancePresent = !sourceFileName.isNullOrBlank() ||
        !sourceSha256.isNullOrBlank() ||
        (!documentId.isNullOrBlank() && !chunkId.isNullOrBlank())
    val webProvenancePresent = sourceType == CitationSource.WEB && !sourceUrl.isNullOrBlank()
    return commonFieldsPresent && (localProvenancePresent || webProvenancePresent)
}

private fun RagRetrievalResult.withTier(tier: EvidenceSourceTier): RagRetrievalResult = when (this) {
    is RagRetrievalResult.Retrieved -> copy(
        citations = citations.map { citation ->
            citation.copy(
                sourceType = if (tier == EvidenceSourceTier.WEB) CitationSource.WEB else CitationSource.LOCAL_DOCUMENT,
                sourceTier = tier
            )
        },
        sourceTier = tier
    )
    is RagRetrievalResult.BelowThreshold -> copy(sourceTier = tier)
    is RagRetrievalResult.Unavailable -> copy(sourceTier = tier)
}
