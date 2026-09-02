package com.stressdetector.mobile.ml.rag

import com.stressdetector.mobile.domain.engine.EvidenceSourceTier
import com.stressdetector.mobile.domain.engine.KnowledgeCitation
import com.stressdetector.mobile.domain.engine.RagRetrievalResult

/**
 * Auditable, prompt-safe receipt for the future AI integration boundary.
 * It carries provenance identifiers rather than document text or raw history.
 */
data class CounselorContextReceipt(
    val receiptVersion: String = VERSION,
    val retrievalStatus: RetrievalContextStatus,
    val retrievalPolicyVersion: String?,
    val citationReceipts: List<CitationReceipt>,
    val personaVersion: String?,
    val stressHistorySessionIds: List<String>,
    val excludedHistoryReasons: List<String>,
    val llmModelVersion: String?,
    /** Tier that supplied the evidence admitted to the prompt. NONE means abstention. */
    val evidenceSourceTier: EvidenceSourceTier = EvidenceSourceTier.NONE
) {
    companion object {
        const val VERSION = "counselor-context-receipt-v2"
    }
}

enum class RetrievalContextStatus {
    RETRIEVED,
    BELOW_THRESHOLD,
    UNAVAILABLE
}

data class CitationReceipt(
    val citationId: String,
    val sourceTitle: String,
    val sourcePage: Int?,
    val sourceLocator: String?,
    val generationId: String? = null,
    val documentId: String? = null,
    val sourceSha256: String? = null,
    val chunkId: String? = null,
    val contentHash: String? = null,
    val relevanceScore: Float? = null,
    val sourceFileName: String? = null,
    val sourceUrl: String? = null,
    val sourceDomain: String? = null,
    val retrievedAtMillis: Long? = null,
    val sourceTier: EvidenceSourceTier = EvidenceSourceTier.NONE
)

object CounselorContextReceiptFactory {
    fun create(
        retrieval: RagRetrievalResult,
        policyBinding: RetrievalPolicyBinding,
        personaVersion: String?,
        stressHistorySessionIds: Collection<String>,
        excludedHistoryReasons: Collection<String>,
        llmModelVersion: String?
    ): CounselorContextReceipt {
        val citations = (retrieval as? RagRetrievalResult.Retrieved)
            ?.citations
            .orEmpty()
            .map(::toReceipt)
            .distinctBy(CitationReceipt::citationId)
            .sortedBy(CitationReceipt::citationId)
        val evidenceSourceTier = (retrieval as? RagRetrievalResult.Retrieved)
            ?.sourceTier
            ?.takeUnless { it == EvidenceSourceTier.NONE }
            ?: citations.firstOrNull()?.sourceTier
            ?: EvidenceSourceTier.NONE
        return CounselorContextReceipt(
            retrievalStatus = when (retrieval) {
                is RagRetrievalResult.Retrieved -> RetrievalContextStatus.RETRIEVED
                is RagRetrievalResult.BelowThreshold -> RetrievalContextStatus.BELOW_THRESHOLD
                is RagRetrievalResult.Unavailable -> RetrievalContextStatus.UNAVAILABLE
            },
            retrievalPolicyVersion = (policyBinding as? RetrievalPolicyBinding.Calibrated)
                ?.thresholdPolicy
                ?.calibrationVersion,
            citationReceipts = citations,
            personaVersion = personaVersion?.takeIf(String::isNotBlank),
            stressHistorySessionIds = stressHistorySessionIds.filter(String::isNotBlank).distinct().sorted(),
            excludedHistoryReasons = excludedHistoryReasons.filter(String::isNotBlank).distinct().sorted(),
            llmModelVersion = llmModelVersion?.takeIf(String::isNotBlank),
            evidenceSourceTier = evidenceSourceTier
        )
    }

    private fun toReceipt(citation: KnowledgeCitation): CitationReceipt = CitationReceipt(
        citationId = citation.citationId,
        sourceTitle = citation.sourceTitle,
        sourcePage = citation.sourcePage,
        sourceLocator = citation.sourceLocator,
        generationId = citation.generationId,
        documentId = citation.documentId,
        sourceSha256 = citation.sourceSha256,
        chunkId = citation.chunkId,
        contentHash = citation.contentHash,
        relevanceScore = citation.relevanceScore,
        sourceFileName = citation.sourceFileName,
        sourceUrl = citation.sourceUrl,
        sourceDomain = citation.sourceDomain,
        retrievedAtMillis = citation.retrievedAtMillis,
        sourceTier = citation.sourceTier
    )
}
