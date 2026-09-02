package com.stressdetector.mobile.ml.rag

import com.stressdetector.mobile.domain.engine.EvidenceSourceTier
import com.stressdetector.mobile.domain.engine.KnowledgeCitation
import com.stressdetector.mobile.domain.engine.RagRetrievalResult
import com.stressdetector.mobile.domain.engine.RetrievalMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CounselorContextReceiptTest {
    @Test
    fun `receipt is deterministic and stores citation provenance without content`() {
        val result = RagRetrievalResult.Retrieved(
            citations = listOf(
                KnowledgeCitation(
                    "citation-b", "B", "page", "secret content", 0.8f,
                    sourcePage = 2,
                    sourceFileName = "local.md",
                    sourceSha256 = "b".repeat(64),
                    sourceTier = EvidenceSourceTier.BUNDLED_LOCAL
                ),
                KnowledgeCitation(
                    "citation-a", "A", "page", "secret content", 0.9f,
                    sourcePage = 1,
                    sourceFileName = "local.md",
                    sourceSha256 = "a".repeat(64),
                    sourceTier = EvidenceSourceTier.BUNDLED_LOCAL
                ),
                KnowledgeCitation(
                    "citation-a", "A", "page", "duplicate", 0.7f,
                    sourcePage = 1,
                    sourceFileName = "local.md",
                    sourceSha256 = "a".repeat(64),
                    sourceTier = EvidenceSourceTier.BUNDLED_LOCAL
                )
            ),
            method = RetrievalMethod.LEXICAL_ONLY,
            sourceTier = EvidenceSourceTier.BUNDLED_LOCAL
        )
        val receipt = CounselorContextReceiptFactory.create(
            retrieval = result,
            policyBinding = RetrievalPolicyBinding.Uncalibrated("benchmark_artifact_missing"),
            personaVersion = "persona-v2",
            stressHistorySessionIds = listOf("session-2", "session-1", "session-1"),
            excludedHistoryReasons = listOf("transport_gap", "transport_gap"),
            llmModelVersion = "gemma-4"
        )

        assertEquals(RetrievalContextStatus.RETRIEVED, receipt.retrievalStatus)
        assertEquals(listOf("citation-a", "citation-b"), receipt.citationReceipts.map(CitationReceipt::citationId))
        assertEquals(listOf("session-1", "session-2"), receipt.stressHistorySessionIds)
        assertEquals(listOf("transport_gap"), receipt.excludedHistoryReasons)
        assertNull(receipt.retrievalPolicyVersion)
        assertEquals(EvidenceSourceTier.BUNDLED_LOCAL, receipt.evidenceSourceTier)
        assertEquals("local.md", receipt.citationReceipts.first().sourceFileName)
        assertEquals(EvidenceSourceTier.BUNDLED_LOCAL, receipt.citationReceipts.first().sourceTier)
    }
}
