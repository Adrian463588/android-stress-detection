package com.stressdetector.mobile.ml.rag

import com.stressdetector.mobile.domain.engine.EvidenceSourceTier
import com.stressdetector.mobile.domain.engine.KnowledgeCitation
import com.stressdetector.mobile.domain.engine.RagRetrievalResult
import com.stressdetector.mobile.domain.engine.RagRetriever
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledLocalKnowledgeTest {
    @Test
    fun `bundled retriever admits only checksum verified evidence`() = runBlocking {
        val text = "Coping dapat mencakup latihan napas dan dukungan sosial."
        val document = document(text)
        val result = BundledLocalRagRetriever(FixtureCorpus(document, text), clock = { 42L }, policy = calibratedPolicy())
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
            FixtureCorpus(document.copy(sourceSha256 = "b".repeat(64)), "Coping lokal"),
            policy = calibratedPolicy()
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
    fun `app evidence short circuits bundled retrieval`() = runBlocking {
        var bundledCalls = 0
        val resolver = TieredRagRetriever(
            appRag = countingRetriever { RagRetrievalResult.Retrieved(listOf(validCitation())) },
            bundledLocal = countingRetriever {
                bundledCalls++
                RagRetrievalResult.Unavailable("must not be called")
            }
        )

        val result = resolver.retrieveRelevantPassages("coping") as RagRetrievalResult.Retrieved

        assertEquals(0, bundledCalls)
        assertEquals(EvidenceSourceTier.APP_RAG, result.sourceTier)
    }

    @Test
    fun `app cancellation propagates without bundled fallback`() = runBlocking {
        val cancellation = CancellationException("app retrieval cancelled")
        var bundledCalls = 0
        val resolver = TieredRagRetriever(
            appRag = countingRetriever { throw cancellation },
            bundledLocal = countingRetriever {
                bundledCalls++
                RagRetrievalResult.Unavailable("must not be called")
            }
        )

        val result = runCatching { resolver.retrieveRelevantPassages("coping") }

        assertTrue(result.exceptionOrNull() === cancellation)
        assertEquals(0, bundledCalls)
    }

    @Test
    fun `bundled cancellation propagates`() = runBlocking {
        val cancellation = CancellationException("bundled retrieval cancelled")
        val resolver = TieredRagRetriever(
            appRag = countingRetriever { RagRetrievalResult.Unavailable("no app evidence") },
            bundledLocal = countingRetriever { throw cancellation }
        )

        assertTrue(runCatching { resolver.retrieveRelevantPassages("coping") }.exceptionOrNull() === cancellation)
    }

    @Test
    fun `uncalibrated bundled retrieval cannot be enabled by legacy threshold`() = runBlocking {
        val text = "Coping lokal"
        val retriever = BundledLocalRagRetriever(FixtureCorpus(document(text), text))

        assertTrue(retriever.retrieveRelevantPassages("coping") is RagRetrievalResult.BelowThreshold)
        assertTrue(
            retriever.retrieveRelevantPassages("coping", minRelevanceThreshold = 0f) is RagRetrievalResult.BelowThreshold
        )
    }

    @Test
    fun `bundled evidence uses injected policy with lexical RRF scores`() = runBlocking {
        val text = "Coping lokal"
        var policyCalls = 0
        val policy = LocalRagRetrievalPolicy(
            thresholdPolicy = RetrievalThresholdPolicy { context ->
                policyCalls++
                assertEquals("coping", context.query)
                assertEquals(LocalRetrievalMode.LEXICAL_ONLY, context.mode)
                assertEquals(1f / 61f, context.candidates.single().fusedScore, 0.000001f)
                false
            }
        )
        val retriever = BundledLocalRagRetriever(FixtureCorpus(document(text), text), policy = policy)

        assertTrue(retriever.retrieveRelevantPassages("coping", minRelevanceThreshold = 0f) is RagRetrievalResult.BelowThreshold)
        assertEquals(1, policyCalls)
    }

    @Test
    fun `corpus cancellation failures propagate from manifest and document reads`() = runBlocking {
        listOf(true, false).forEach { cancelManifest ->
            val cancellation = CancellationException("corpus cancelled")
            val text = "Coping lokal"
            val corpus = object : BundledLocalCorpus {
                override suspend fun loadDocuments(): Result<List<BundledLocalDocument>> = if (cancelManifest) {
                    Result.failure(cancellation)
                } else {
                    Result.success(listOf(document(text)))
                }

                override suspend fun read(document: BundledLocalDocument): Result<ByteArray> = Result.failure(cancellation)
            }
            val result = runCatching { BundledLocalRagRetriever(corpus, policy = calibratedPolicy()).retrieveRelevantPassages("coping") }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(cancellation.message, result.exceptionOrNull()?.message)
        }
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

    private fun calibratedPolicy() = LocalRagRetrievalPolicy(
        thresholdPolicy = CalibratedRetrievalThresholdPolicy("test-calibration-v1", 0.01f, 0.01f)
    )

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
