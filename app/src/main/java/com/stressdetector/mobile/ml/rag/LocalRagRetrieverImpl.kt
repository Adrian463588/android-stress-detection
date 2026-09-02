package com.stressdetector.mobile.ml.rag

import com.stressdetector.mobile.data.db.DocumentChunkEntity
import com.stressdetector.mobile.data.db.DocumentDao
import com.stressdetector.mobile.data.db.KnowledgeBaseChunkEntity
import com.stressdetector.mobile.data.db.KnowledgeBaseDao
import com.stressdetector.mobile.data.db.KnowledgeBaseDocumentEntity
import com.stressdetector.mobile.domain.engine.KnowledgeCitation
import com.stressdetector.mobile.domain.engine.RagRetrievalResult
import com.stressdetector.mobile.domain.engine.RagRetriever
import com.stressdetector.mobile.domain.engine.RetrievalMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class LocalRagRetrieverImpl private constructor(
    private val knowledgeBaseDao: KnowledgeBaseDao?,
    private val documentDao: DocumentDao?,
    private val embedder: LocalEmbeddingModel,
    private val policy: LocalRagRetrievalPolicy = LocalRagRetrievalPolicy(),
    private val ranker: HybridRetrievalRanker = HybridRetrievalRanker()
) : RagRetriever {

    constructor(
        documentDao: DocumentDao,
        embedder: LocalEmbeddingModel,
        policy: LocalRagRetrievalPolicy = LocalRagRetrievalPolicy(),
        ranker: HybridRetrievalRanker = HybridRetrievalRanker()
    ) : this(null, documentDao, embedder, policy, ranker)

    constructor(
        knowledgeBaseDao: KnowledgeBaseDao,
        embedder: LocalEmbeddingModel,
        policy: LocalRagRetrievalPolicy = LocalRagRetrievalPolicy(),
        ranker: HybridRetrievalRanker = HybridRetrievalRanker()
    ) : this(knowledgeBaseDao, null, embedder, policy, ranker)

    @Suppress("UNUSED_PARAMETER")
    override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float): RagRetrievalResult =
        withContext(Dispatchers.IO) {
            knowledgeBaseDao?.let { return@withContext retrieveFromEncryptedIndex(it, query, maxResults) }
            val legacyDao = requireNotNull(documentDao)
            if (!policy.allowInMemoryBm25ForDevelopment) {
                runCatching { ranker.requireProductionLexicalBackend() }
                    .onFailure { error ->
                        return@withContext RagRetrievalResult.Unavailable(
                            error.message ?: "Production lexical retrieval is unavailable"
                        )
                    }
            }
            val documents = legacyDao.getAllDocuments().first()
                .filter { it.isProcessed && it.processingState in READY_PROCESSING_STATES }
            if (documents.isEmpty()) {
                return@withContext RagRetrievalResult.Unavailable("No validated clinical documents are indexed")
            }
            val documentById = documents.associateBy { it.documentId }
            val queryVector = (runCatching { embedder.embedQuery(query) }.getOrNull() as? EmbeddingResult.Success)?.vector
            val candidates = mutableListOf<RetrievalCandidate>()
            var offset = 0
            while (true) {
                val page = legacyDao.getChunksPage(SCAN_PAGE_SIZE, offset)
                if (page.isEmpty()) break
                page.filter { it.documentId in documentById }.forEach { chunk ->
                    candidates += RetrievalCandidate(chunk, chunk.compatibleVector())
                }
                offset += page.size
                require(offset <= MAX_SCANNED_CHUNKS) { "Knowledge-base index exceeds local scan limit." }
                if (page.size < SCAN_PAGE_SIZE) break
            }
            if (candidates.isEmpty()) {
                return@withContext RagRetrievalResult.Unavailable("No validated clinical document chunks are indexed")
            }
            val semanticAvailable = queryVector != null && candidates.any { it.vector != null }
            val mode = if (semanticAvailable) LocalRetrievalMode.SEMANTIC_HYBRID else LocalRetrievalMode.LEXICAL_ONLY
            val ranked = ranker.rank(
                query = query,
                queryVector = queryVector.takeIf { semanticAvailable },
                candidates = candidates,
                semanticLimit = policy.semanticCandidateLimit,
                lexicalLimit = policy.lexicalCandidateLimit,
                resultLimit = minOf(maxResults.coerceAtLeast(1), policy.maxEvidenceChunks),
                maxPerDocument = policy.maxPerDocument,
                maxPerPage = policy.maxPerPage
            )
            val retrievalMethod = if (mode == LocalRetrievalMode.SEMANTIC_HYBRID) {
                RetrievalMethod.SEMANTIC_HYBRID
            } else {
                RetrievalMethod.LEXICAL_ONLY
            }
            if (ranked.isEmpty() || !policy.thresholdPolicy.accepts(RetrievalThresholdContext(query, mode, ranked))) {
                return@withContext RagRetrievalResult.BelowThreshold(
                    bestScore = ranked.firstOrNull()?.fusedScore,
                    method = retrievalMethod
                )
            }
            val citations = ranked.map { scored ->
                val chunk = scored.candidate.chunk
                val document = documentById.getValue(chunk.documentId)
                KnowledgeCitation(
                    citationId = chunk.chunkId,
                    sourceTitle = document.title,
                    section = chunk.sourceLocator ?: "Chunk ${chunk.chunkIndex + 1}",
                    text = chunk.textContent,
                    relevanceScore = scored.fusedScore,
                    sourceFileName = document.sourceFileName ?: chunk.sourceTitle,
                    sourcePage = chunk.sourcePage,
                    sourceLocator = chunk.sourceLocator,
                    contentHash = document.sourceSha256,
                    chunkId = chunk.chunkId,
                    documentId = document.documentId,
                    sourceSha256 = document.sourceSha256
                )
            }
            RagRetrievalResult.Retrieved(
                citations = citations,
                method = retrievalMethod
            )
        }

    private suspend fun retrieveFromEncryptedIndex(dao: KnowledgeBaseDao, query: String, maxResults: Int): RagRetrievalResult {
        runCatching { ranker.requireProductionLexicalBackend() }.onFailure { error ->
            return RagRetrievalResult.Unavailable(
                error.message ?: "Production SQLite FTS5 retrieval is unavailable"
            )
        }
        val generation = dao.activeReadyGeneration()
            ?: return RagRetrievalResult.Unavailable("No READY encrypted knowledge-base generation is available")
        val documents = dao.documentsForGeneration(generation.generationId)
        if (documents.isEmpty()) {
            return RagRetrievalResult.Unavailable("No validated clinical documents are indexed")
        }
        val chunks = dao.chunksForGeneration(generation.generationId)
        if (chunks.isEmpty()) {
            return RagRetrievalResult.Unavailable("No validated encrypted document chunks are indexed")
        }
        val candidates = chunks.map { chunk -> RetrievalCandidate(chunk.asLegacyChunk(), chunk.compatibleVector()) }
        val queryVector = (runCatching { embedder.embedQuery(query) }.getOrNull() as? EmbeddingResult.Success)?.vector
        val matchExpression = Fts5MatchExpression.fromQuery(query)
        if (matchExpression.isBlank()) {
            return RagRetrievalResult.BelowThreshold(
                bestScore = null,
                method = if (queryVector == null) RetrievalMethod.LEXICAL_ONLY else RetrievalMethod.SEMANTIC_HYBRID
            )
        }
        val lexicalRows = runCatching {
            dao.searchReadyGeneration(matchExpression, policy.lexicalCandidateLimit)
        }.getOrElse { error ->
            return RagRetrievalResult.Unavailable("SQLite FTS5 retrieval failed: ${error.message ?: "unknown error"}")
        }
        val candidateById = candidates.associateBy { it.chunk.chunkId }
        val lexicalCandidates = lexicalRows
            .sortedWith(compareBy<com.stressdetector.mobile.data.db.KnowledgeBaseFtsResult> { it.rank }.thenBy { it.chunkId })
            .mapIndexedNotNull { index, row ->
                candidateById[row.chunkId]?.let { it to (1f / (index + 1)) }
            }
        val semanticAvailable = queryVector != null && candidates.any { it.vector != null }
        val mode = if (semanticAvailable) LocalRetrievalMode.SEMANTIC_HYBRID else LocalRetrievalMode.LEXICAL_ONLY
        val ranked = ranker.rank(
            query = query,
            queryVector = queryVector.takeIf { semanticAvailable },
            candidates = candidates,
            semanticLimit = policy.semanticCandidateLimit,
            lexicalLimit = policy.lexicalCandidateLimit,
            resultLimit = minOf(maxResults.coerceAtLeast(1), policy.maxEvidenceChunks),
            maxPerDocument = policy.maxPerDocument,
            maxPerPage = policy.maxPerPage,
            lexicalCandidates = lexicalCandidates
        )
        val retrievalMethod = if (mode == LocalRetrievalMode.SEMANTIC_HYBRID) {
            RetrievalMethod.SEMANTIC_HYBRID
        } else {
            RetrievalMethod.LEXICAL_ONLY
        }
        if (ranked.isEmpty() || !policy.thresholdPolicy.accepts(RetrievalThresholdContext(query, mode, ranked))) {
            return RagRetrievalResult.BelowThreshold(ranked.firstOrNull()?.fusedScore, retrievalMethod)
        }
        val documentById = documents.associateBy(KnowledgeBaseDocumentEntity::documentId)
        val citations = ranked.mapNotNull { scored ->
            val chunk = scored.candidate.chunk
            val document = documentById[chunk.documentId] ?: return@mapNotNull null
            KnowledgeCitation(
                citationId = chunk.chunkId,
                sourceTitle = document.title,
                section = chunk.sourceLocator ?: "Chunk ${chunk.chunkIndex + 1}",
                text = chunk.textContent,
                relevanceScore = scored.fusedScore,
                sourceFileName = document.sourceFileName,
                sourcePage = chunk.sourcePage,
                sourceLocator = chunk.sourceLocator,
                contentHash = document.sourceSha256,
                chunkId = chunk.chunkId,
                documentId = document.documentId,
                generationId = generation.generationId,
                sourceSha256 = document.sourceSha256
            )
        }
        if (citations.isEmpty()) return RagRetrievalResult.Unavailable("Encrypted KB provenance is incomplete")
        return RagRetrievalResult.Retrieved(citations, retrievalMethod)
    }

    private fun DocumentChunkEntity.compatibleVector(): FloatArray? {
        if (embeddingVersion != LocalEmbeddingModel.VERSION) return null
        val bytes = embeddingBlob ?: return null
        if (bytes.size != LocalEmbeddingModel.EMBEDDING_DIMENSIONS * Float.SIZE_BYTES) return null
        return runCatching { LocalEmbeddingModel.byteArrayToFloatArray(bytes) }.getOrNull()
    }

    private fun KnowledgeBaseChunkEntity.asLegacyChunk(): DocumentChunkEntity = DocumentChunkEntity(
        chunkId = chunkId,
        documentId = documentId,
        chunkIndex = chunkIndex,
        textContent = textContent,
        embeddingBlob = embeddingBlob,
        sourceTitle = sourceTitle,
        sourcePage = sourcePage,
        sourceLocator = sourceLocator,
        embeddingVersion = embeddingVersion,
        charStart = charStart,
        charEnd = charEnd,
        tokenStart = tokenStart,
        tokenEnd = tokenEnd,
        chunkPolicyVersion = chunkPolicyVersion
    )

    private fun KnowledgeBaseChunkEntity.compatibleVector(): FloatArray? {
        if (embeddingVersion != LocalEmbeddingModel.VERSION) return null
        val bytes = embeddingBlob ?: return null
        if (bytes.size != LocalEmbeddingModel.EMBEDDING_DIMENSIONS * Float.SIZE_BYTES) return null
        return runCatching { LocalEmbeddingModel.byteArrayToFloatArray(bytes) }.getOrNull()
    }

    private companion object {
        val READY_PROCESSING_STATES = setOf("READY_SEMANTIC", "READY_LEXICAL")
        const val SCAN_PAGE_SIZE = 128
        const val MAX_SCANNED_CHUNKS = 100_000
    }
}

private object Fts5MatchExpression {
    private val term = Regex("[\\p{L}\\p{N}]+")

    fun fromQuery(query: String): String = term.findAll(query)
        .map { it.value.replace("\"", "\"\"") }
        .distinct()
        .joinToString(" AND ") { "\"$it\"" }
}

data class LocalRagRetrievalPolicy(
    val semanticCandidateLimit: Int = 30,
    val lexicalCandidateLimit: Int = 30,
    val maxEvidenceChunks: Int = 4,
    val maxPerDocument: Int = 3,
    val maxPerPage: Int = 2,
    val thresholdPolicy: RetrievalThresholdPolicy = RetrievalThresholdPolicy.UNCALIBRATED,
    /**
     * Test/development escape hatch only. Production must provide a SQLite FTS5 source and leave
     * this false; in-memory BM25 is never a release backend.
     */
    val allowInMemoryBm25ForDevelopment: Boolean = false
) {
    init {
        require(semanticCandidateLimit > 0 && lexicalCandidateLimit > 0)
        require(maxEvidenceChunks in 1..4)
        require(maxPerDocument in 1..maxEvidenceChunks)
        require(maxPerPage in 1..maxEvidenceChunks)
    }
}
