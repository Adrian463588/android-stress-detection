package com.stressdetector.mobile.ml.rag

import com.stressdetector.mobile.domain.engine.EvidenceSourceTier
import com.stressdetector.mobile.domain.engine.KnowledgeCitation
import com.stressdetector.mobile.domain.engine.RagRetrievalResult
import com.stressdetector.mobile.domain.engine.RagRetriever
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledLocalKnowledgeTest {
    @Test
    fun `bundled retriever admits only checksum verified evidence`() = runBlocking {
        val text = "Coping dapat mencakup latihan napas dan dukungan sosial."
        val document = document(text)
        val result = BundledLocalRagRetriever(FixtureCorpus(document, text), clock = { 42L })
            .retrieveRelevantPassages("apa itu coping", maxResults = 2)

        val retrieved = result as RagRetrievalResult.Retrieved
        assertEquals(EvidenceSourceTier.BUNDLED_LOCAL, retrieved.sourceTier)
        assertEquals(EvidenceSourceTier.BUNDLED_LOCAL, retrieved.citations.single().sourceTier)
        assertEquals(document.sourceSha256, retrieved.citations.single().sourceSha256)
        assertEquals(42L, retrieved.citations.single().retrievedAtMillis)
    }

    @Test
    fun `checksum mismatch abstains without citation`() = runBlocking {
        val document = document("Coping lokal")
        val result = BundledLocalRagRetriever(
            FixtureCorpus(document.copy(sourceSha256 = "b".repeat(64)), "Coping lokal")
        ).retrieveRelevantPassages("coping")

        assertTrue(result is RagRetrievalResult.BelowThreshold)
        assertEquals(EvidenceSourceTier.BUNDLED_LOCAL, (result as RagRetrievalResult.BelowThreshold).sourceTier)
    }

    @Test
    fun `tiered resolver falls back only after app rag abstains`() = runBlocking {
        var appCalls = 0
        var bundledCalls = 0
        val resolver = TieredRagRetriever(
            appRag = countingRetriever {
                appCalls++
                RagRetrievalResult.Unavailable("not ready")
            },
            bundledLocal = countingRetriever {
                bundledCalls++
                RagRetrievalResult.Retrieved(
                    citations = listOf(validCitation()),
                    sourceTier = EvidenceSourceTier.BUNDLED_LOCAL
                )
            }
        )

        val result = resolver.retrieveRelevantPassages("coping") as RagRetrievalResult.Retrieved

        assertEquals(1, appCalls)
        assertEquals(1, bundledCalls)
        assertEquals(EvidenceSourceTier.BUNDLED_LOCAL, result.sourceTier)
        assertEquals(EvidenceSourceTier.BUNDLED_LOCAL, result.citations.single().sourceTier)
    }

    @Test
    fun `tiered resolver returns none when both local sources lack evidence`() = runBlocking {
        val resolver = TieredRagRetriever(
            appRag = countingRetriever { RagRetrievalResult.BelowThreshold(0.1f) },
            bundledLocal = countingRetriever { RagRetrievalResult.Unavailable("empty manifest") }
        )

        val result = resolver.retrieveRelevantPassages("factual question")

        assertTrue(result is RagRetrievalResult.Unavailable)
        assertEquals(EvidenceSourceTier.NONE, (result as RagRetrievalResult.Unavailable).sourceTier)
    }

    private fun document(text: String) = BundledLocalDocument(
        documentId = "bundled-1",
        title = "Panduan coping",
        assetPath = "ai/knowledge/coping.md",
        format = "markdown",
        sourceSha256 = sha256(text),
        license = "test-fixture-license",
        provenance = "test-fixture-provenance"
    )

    private fun validCitation() = KnowledgeCitation(
        citationId = "app-1",
        sourceTitle = "RAG lokal",
        section = "Bagian 1",
        text = "Coping lokal",
        relevanceScore = 0.8f,
        sourceFileName = "local.md",
        sourceSha256 = "a".repeat(64)
    )

    private fun countingRetriever(block: suspend () -> RagRetrievalResult) = object : RagRetriever {
        override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float): RagRetrievalResult =
            block()
    }

    private class FixtureCorpus(private val document: BundledLocalDocument, private val content: String) : BundledLocalCorpus {
        override suspend fun loadDocuments(): Result<List<BundledLocalDocument>> = Result.success(listOf(document))

        override suspend fun read(document: BundledLocalDocument): Result<ByteArray> = Result.success(
            content.toByteArray(Charsets.UTF_8)
        )
    }

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
