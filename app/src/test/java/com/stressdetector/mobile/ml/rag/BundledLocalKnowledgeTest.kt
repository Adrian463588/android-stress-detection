package com.stressdetector.mobile.ml.rag

import com.stressdetector.mobile.domain.engine.EvidenceSourceTier
import com.stressdetector.mobile.domain.engine.KnowledgeCitation
import com.stressdetector.mobile.domain.engine.RagRetrievalResult
import com.stressdetector.mobile.domain.engine.RagRetriever
import com.stressdetector.mobile.domain.engine.RetrievalMethod
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledLocalKnowledgeTest {
    @Test
    fun `assessment follow up cites open questions from the shipped counseling module`() = runBlocking {
        val path = "ai/counseling/bk_05_keterampilan_mikro.md"
        val content = File("src/main/canonicalAssets/$path").readText()
        val source = document(content).copy(documentId = "bk_05_keterampilan_mikro", assetPath = path)

        val result = requireNotNull(assessmentFollowUpReference(FixtureCorpus(source, content)))
        val citation = result.citations.single()
        assertEquals(RetrievalMethod.CURATED_REFERENCE, result.method)
        assertEquals(EvidenceSourceTier.BUNDLED_LOCAL, citation.sourceTier)
        assertEquals(source.sourceSha256, citation.sourceSha256)
        assertTrue(citation.text.contains("Pertanyaan Terbuka"))
        assertTrue(!citation.text.contains("Pertanyaan Tertutup"))
    }

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

    @Test
    fun `packaged BankMateriKonseling json books retrieve cbt rebt group and tiu5 with page provenance`() = runBlocking {
        val context = org.mockito.Mockito.mock(android.content.Context::class.java)
        val assetManager = org.mockito.Mockito.mock(android.content.res.AssetManager::class.java)
        org.mockito.Mockito.`when`(context.assets).thenReturn(assetManager)
        val manifestBytes = File("src/main/canonicalAssets/ai/bundled_knowledge_manifest.json").readBytes()
        org.mockito.Mockito.`when`(assetManager.open("ai/bundled_knowledge_manifest.json")).thenAnswer {
            java.io.ByteArrayInputStream(manifestBytes)
        }
        val corpus = AssetBundledLocalCorpus(context)
        val docs = corpus.loadDocuments().getOrThrow()
        docs.forEach { doc ->
            val bytes = File("src/main/canonicalAssets/${doc.assetPath}").readBytes()
            org.mockito.Mockito.`when`(assetManager.open(doc.assetPath)).thenAnswer {
                java.io.ByteArrayInputStream(bytes)
            }
        }
        val retriever = BundledLocalRagRetriever(
            corpus = corpus,
            policy = LocalRagRetrievalPolicy(
                thresholdPolicy = CalibratedRetrievalThresholdPolicy("bundled-counseling-kb-v2", 0.012f, 0.012f)
            )
        )

        val rebt = retriever.retrieveRelevantPassages("teknik konseling REBT keyakinan irasional ABCDE", maxResults = 3)
        assertTrue("Expected REBT retrieval, got $rebt", rebt is RagRetrievalResult.Retrieved)
        val rebtCitations = (rebt as RagRetrievalResult.Retrieved).citations
        assertTrue(rebtCitations.any { it.text.contains("REBT", ignoreCase = true) || it.text.contains("Rasional", ignoreCase = true) })

        val cbt = retriever.retrieveRelevantPassages("restrukturisasi kognitif CBT pikiran otomatis", maxResults = 3)
        assertTrue("Expected CBT retrieval, got $cbt", cbt is RagRetrievalResult.Retrieved)

        val group = retriever.retrieveRelevantPassages("tahapan konseling kelompok dinamika kelompok", maxResults = 3)
        assertTrue("Expected group counseling retrieval, got $group", group is RagRetrievalResult.Retrieved)

        val tiu5 = retriever.retrieveRelevantPassages("asesmen TIU 5 figural analogi bentuk", maxResults = 3)
        assertTrue("Expected TIU-5 retrieval, got $tiu5", tiu5 is RagRetrievalResult.Retrieved)

        val unknown = retriever.retrieveRelevantPassages("qwertyuiopasdfghjkl", maxResults = 3)
        assertTrue(unknown is RagRetrievalResult.BelowThreshold || unknown is RagRetrievalResult.Unavailable)
    }

    @Test
    fun `restricted psychometric collection is isolated from default counseling retriever`() = runBlocking {
        val restrictedText = "Protokol rahasia kunci jawaban Set A Set B psikotes kognitif."
        val publicText = "Pendekatan konseling CBT dan REBT membantu restrukturisasi kognitif."
        val restrictedDoc = document(restrictedText).copy(
            documentId = "restricted-1",
            collection = BundledLocalDocument.COLLECTION_RESTRICTED_ADMIN,
            usageRestrictions = listOf("restricted_examiner_only")
        )
        val publicDoc = document(publicText).copy(
            documentId = "public-1",
            collection = BundledLocalDocument.COLLECTION_COUNSELING_THEORY
        )
        val corpus = object : BundledLocalCorpus {
            override suspend fun loadDocuments() = Result.success(listOf(restrictedDoc, publicDoc))
            override suspend fun read(document: BundledLocalDocument) = Result.success(
                (if (document.documentId == "restricted-1") restrictedText else publicText).toByteArray(Charsets.UTF_8)
            )
        }
        val retriever = BundledLocalRagRetriever(corpus = corpus, policy = calibratedPolicy())

        val blocked = retriever.retrieveRelevantPassages("kunci jawaban Set A Set B")
        assertTrue("Expected Unavailable or BelowThreshold, got $blocked", blocked !is RagRetrievalResult.Retrieved)

        val allowed = retriever.retrieveRelevantPassages("konseling CBT REBT restrukturisasi") as RagRetrievalResult.Retrieved
        assertEquals("public-1", allowed.citations.single().documentId)
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
