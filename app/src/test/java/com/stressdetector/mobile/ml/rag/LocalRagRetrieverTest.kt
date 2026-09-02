package com.stressdetector.mobile.ml.rag

import com.stressdetector.mobile.data.db.ClinicalDocumentEntity
import com.stressdetector.mobile.data.db.DocumentChunkEntity
import com.stressdetector.mobile.data.db.DocumentDao
import com.stressdetector.mobile.domain.engine.RagRetrievalResult
import com.stressdetector.mobile.domain.engine.RetrievalMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class LocalRagRetrieverTest {
    @Test
    fun `hybrid ranker applies RRF dedup MMR and evidence limits`() {
        val query = unitVector(0)
        val candidates = listOf(
            candidate("a0", "doc-a", 0, 1, "stress breathing relaxation exercise", unitVector(0)),
            candidate("a1", "doc-a", 1, 1, "stress breathing relaxation exercise slowly", unitVector(0)),
            candidate("a2", "doc-a", 2, 1, "stress counseling support plan", unitVector(1)),
            candidate("a3", "doc-a", 3, 2, "stress sleep hygiene", unitVector(2)),
            candidate("b0", "doc-b", 0, 1, "stress cognitive grounding", unitVector(3)),
            candidate("c0", "doc-c", 0, 1, "stress social support", unitVector(4))
        )

        val ranked = HybridRetrievalRanker().rank("stress relaxation", query, candidates)

        assertTrue(ranked.size <= 4)
        assertTrue(ranked.count { it.candidate.chunk.documentId == "doc-a" } <= 3)
        assertTrue(
            ranked.count {
                it.candidate.chunk.documentId == "doc-a" && it.candidate.chunk.sourcePage == 1
            } <= 2
        )
        assertTrue(ranked.none { it.candidate.chunk.chunkId == "a1" })
        assertTrue(ranked.map { it.candidate.chunk.documentId }.distinct().size >= 2)
        assertTrue(ranked.all { it.fusedScore > 0f })
    }

    @Test
    fun `lexical only mode is explicit when embedding is unavailable`() = runBlocking {
        val embedder = Mockito.mock(LocalEmbeddingModel::class.java)
        Mockito.`when`(embedder.embedQuery("cemas")).thenReturn(EmbeddingResult.Unavailable("missing"))
        val retriever = LocalRagRetrieverImpl(
            documentDao = fakeDao(),
            embedder = embedder,
            policy = LocalRagRetrievalPolicy(
                thresholdPolicy = RetrievalThresholdPolicy { true },
                allowInMemoryBm25ForDevelopment = true
            )
        )

        val result = retriever.retrieveRelevantPassages("cemas") as RagRetrievalResult.Retrieved

        assertEquals(RetrievalMethod.LEXICAL_ONLY, result.method)
        assertTrue(result.citations.isNotEmpty())
    }

    @Test
    fun `uncalibrated threshold abstains instead of using legacy point 65`() = runBlocking {
        val embedder = Mockito.mock(LocalEmbeddingModel::class.java)
        Mockito.`when`(embedder.embedQuery("cemas")).thenReturn(EmbeddingResult.Unavailable("missing"))
        val retriever = LocalRagRetrieverImpl(
            fakeDao(),
            embedder,
            policy = LocalRagRetrievalPolicy(allowInMemoryBm25ForDevelopment = true)
        )

        val result = retriever.retrieveRelevantPassages("cemas", minRelevanceThreshold = 0f)

        assertTrue(result is RagRetrievalResult.BelowThreshold)
        assertEquals(RetrievalMethod.LEXICAL_ONLY, (result as RagRetrievalResult.BelowThreshold).method)
    }

    @Test
    fun `citation keeps exact chunk and locator provenance`() = runBlocking {
        val embedder = Mockito.mock(LocalEmbeddingModel::class.java)
        Mockito.`when`(embedder.embedQuery("cemas")).thenReturn(EmbeddingResult.Unavailable("missing"))
        val retriever = LocalRagRetrieverImpl(
            documentDao = fakeDao(),
            embedder = embedder,
            policy = LocalRagRetrievalPolicy(
                thresholdPolicy = RetrievalThresholdPolicy { true },
                allowInMemoryBm25ForDevelopment = true
            )
        )

        val result = retriever.retrieveRelevantPassages("cemas", maxResults = 1)
        val citation = (result as RagRetrievalResult.Retrieved).citations.single()

        assertEquals("c1", citation.citationId)
        assertEquals(1, citation.sourcePage)
        assertEquals("Panduan", citation.sourceTitle)
        assertEquals("Panduan mengelola cemas dengan latihan napas.", citation.text)
    }

    @Test
    fun `production retrieval blocks the in-memory lexical backend`() = runBlocking {
        val embedder = Mockito.mock(LocalEmbeddingModel::class.java)
        val result = LocalRagRetrieverImpl(fakeDao(), embedder)
            .retrieveRelevantPassages("cemas")

        assertTrue(result is RagRetrievalResult.Unavailable)
        assertTrue((result as RagRetrievalResult.Unavailable).reason.contains("production-blocked"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `calibrated policy rejects anonymous magic threshold`() {
        CalibratedRetrievalThresholdPolicy(
            calibrationVersion = "default",
            semanticHybridMinimumRrf = 0.01f,
            lexicalOnlyMinimumRrf = 0.01f
        )
    }

    private fun fakeDao() = object : DocumentDao {
        private val chunks = listOf(
            DocumentChunkEntity("c1", "doc", 0, "Panduan mengelola cemas dengan latihan napas.", null, sourcePage = 1),
            DocumentChunkEntity("c2", "doc", 1, "Dukungan konseling untuk stres.", null, sourcePage = 2)
        )

        override suspend fun insertDocument(document: ClinicalDocumentEntity) = Unit
        override fun getAllDocuments(): Flow<List<ClinicalDocumentEntity>> = flowOf(
            listOf(
                ClinicalDocumentEntity(
                    documentId = "doc",
                    title = "Panduan",
                    filePath = "/tmp/doc.pdf",
                    uploadedAtMillis = 1L,
                    isProcessed = true,
                    sourceFileName = "doc.pdf",
                    sourceSha256 = "sha",
                    processingState = "READY_LEXICAL"
                )
            )
        )
        override suspend fun getDocument(documentId: String): ClinicalDocumentEntity? = null
        override suspend fun findDocumentBySha256(sha256: String): ClinicalDocumentEntity? = null
        override suspend fun updateSourceReference(documentId: String, filePath: String) = Unit
        override suspend fun updateProcessingState(
            documentId: String,
            isProcessed: Boolean,
            processingState: String,
            processingError: String?,
            embeddingStatus: String
        ) = Unit
        override suspend fun updateProcessingStateDetailed(
            documentId: String,
            isProcessed: Boolean,
            processingState: String,
            processingError: String?,
            processingErrorCode: String?,
            embeddingStatus: String,
            processingUpdatedAtMillis: Long
        ) = Unit
        override suspend fun insertChunks(chunks: List<DocumentChunkEntity>) = Unit
        override suspend fun deleteChunksForDocument(documentId: String) = Unit
        override suspend fun getChunksForDocument(documentId: String): List<DocumentChunkEntity> = chunks
        override suspend fun getAllChunks(): List<DocumentChunkEntity> = chunks
        override suspend fun getChunksPage(limit: Int, offset: Int): List<DocumentChunkEntity> = chunks.drop(offset).take(limit)
        override suspend fun deleteDocument(documentId: String) = Unit
    }

    private fun candidate(id: String, documentId: String, index: Int, page: Int, text: String, vector: FloatArray) = RetrievalCandidate(
        DocumentChunkEntity(id, documentId, index, text, null, sourcePage = page),
        vector
    )

    private fun unitVector(index: Int): FloatArray = FloatArray(384).also { it[index] = 1f }
}
