package com.stressdetector.mobile.ml.rag

import com.stressdetector.mobile.data.db.DocumentChunkEntity
import kotlin.math.ln

enum class LocalRetrievalMode { SEMANTIC_HYBRID, LEXICAL_ONLY }

enum class LexicalBackendKind {
    SQLITE_FTS5_BM25,
    IN_MEMORY_BM25
}

data class LexicalBackendStatus(val kind: LexicalBackendKind, val productionReady: Boolean, val reason: String)

data class RetrievalCandidate(val chunk: DocumentChunkEntity, val vector: FloatArray?)

data class RankedRetrievalCandidate(
    val candidate: RetrievalCandidate,
    val fusedScore: Float,
    val semanticScore: Float?,
    val lexicalScore: Float?
)

data class RetrievalThresholdContext(val query: String, val mode: LocalRetrievalMode, val candidates: List<RankedRetrievalCandidate>)

fun interface RetrievalThresholdPolicy {
    fun accepts(context: RetrievalThresholdContext): Boolean

    companion object {
        /** Default until a versioned benchmark calibration is supplied. */
        val UNCALIBRATED = RetrievalThresholdPolicy { false }
    }
}

/** Values must come from a named retrieval benchmark; construction rejects anonymous magic gates. */
data class CalibratedRetrievalThresholdPolicy(
    val calibrationVersion: String,
    val semanticHybridMinimumRrf: Float,
    val lexicalOnlyMinimumRrf: Float
) : RetrievalThresholdPolicy {
    init {
        require(calibrationVersion.isNotBlank() && calibrationVersion != "default")
        require(
            semanticHybridMinimumRrf in 0f..1f &&
                lexicalOnlyMinimumRrf in 0f..1f &&
                semanticHybridMinimumRrf > 0f &&
                lexicalOnlyMinimumRrf > 0f
        )
    }

    override fun accepts(context: RetrievalThresholdContext): Boolean {
        val top = context.candidates.firstOrNull() ?: return false
        val minimum = when (context.mode) {
            LocalRetrievalMode.SEMANTIC_HYBRID -> semanticHybridMinimumRrf
            LocalRetrievalMode.LEXICAL_ONLY -> lexicalOnlyMinimumRrf
        }
        return top.fusedScore >= minimum
    }
}

/**
 * Lexical retrieval boundary. A backend must explicitly declare whether it is production-ready.
 * In-memory BM25 remains a fixture-only implementation; the app path supplies ranked rows from
 * the encrypted Room/SQLite FTS5 index through [SqliteFts5Bm25CandidateSource].
 */
interface LexicalCandidateSource {
    fun rank(query: String, candidates: List<RetrievalCandidate>, limit: Int): List<Pair<RetrievalCandidate, Float>>

    val status: LexicalBackendStatus
        get() = LexicalBackendStatus(
            kind = LexicalBackendKind.IN_MEMORY_BM25,
            productionReady = false,
            reason = "No production lexical backend is registered; in-memory BM25 remains development-only."
        )
}

class InMemoryBm25CandidateSource : LexicalCandidateSource {
    override val status = LexicalBackendStatus(
        kind = LexicalBackendKind.IN_MEMORY_BM25,
        productionReady = false,
        reason = "In-memory BM25 is a fixture/development backend and cannot be used for production retrieval."
    )

    override fun rank(query: String, candidates: List<RetrievalCandidate>, limit: Int): List<Pair<RetrievalCandidate, Float>> {
        val queryTerms = terms(query).distinct()
        if (queryTerms.isEmpty() || candidates.isEmpty()) return emptyList()
        val documentTerms = candidates.map { terms(it.chunk.textContent) }
        val averageLength = documentTerms.map(List<String>::size).average().coerceAtLeast(1.0)
        val documentFrequency = queryTerms.associateWith { term -> documentTerms.count { term in it } }
        return candidates.mapIndexedNotNull { index, candidate ->
            val words = documentTerms[index]
            // Explicit counting avoids a Kotlin/Windows incremental compiler defect
            // that can emit an unreadable synthetic groupingBy class.
            val frequencies = HashMap<String, Int>()
            words.forEach { word -> frequencies[word] = (frequencies[word] ?: 0) + 1 }
            var score = 0.0
            queryTerms.forEach { term ->
                val frequency = frequencies[term]?.toDouble() ?: 0.0
                if (frequency == 0.0) return@forEach
                val df = documentFrequency.getValue(term).toDouble()
                val idf = ln(1.0 + (candidates.size - df + 0.5) / (df + 0.5))
                val denominator = frequency + K1 * (1.0 - B + B * words.size / averageLength)
                score += idf * (frequency * (K1 + 1.0) / denominator)
            }
            score.toFloat().takeIf { it > 0f }?.let { candidate to it }
        }.sortedWith(
            compareByDescending<Pair<RetrievalCandidate, Float>> { it.second }
                .thenBy { it.first.chunk.documentId }
                .thenBy { it.first.chunk.chunkIndex }
        )
            .take(limit)
    }

    private fun terms(value: String): List<String> = TERM.findAll(value.lowercase()).map { it.value }.toList()

    private companion object {
        const val K1 = 1.2
        const val B = 0.75
        val TERM = Regex("[\\p{L}\\p{N}]+")
    }
}

/**
 * Production lexical backend marker for the encrypted Room FTS5 path.
 *
 * SQLite executes the BM25 query in [com.stressdetector.mobile.data.db.KnowledgeBaseDao]. The
 * ranker receives those rows through `lexicalCandidates`; invoking [rank] without DAO results is
 * an error instead of silently falling back to an in-memory search.
 */
class SqliteFts5Bm25CandidateSource : LexicalCandidateSource {
    override val status = LexicalBackendStatus(
        kind = LexicalBackendKind.SQLITE_FTS5_BM25,
        productionReady = true,
        reason = "Ranked lexical candidates are supplied by the encrypted SQLite FTS5/BM25 index."
    )

    @Suppress("UNUSED_PARAMETER")
    override fun rank(query: String, candidates: List<RetrievalCandidate>, limit: Int): List<Pair<RetrievalCandidate, Float>> = error(
        "SQLite FTS5 lexical candidates must be supplied by KnowledgeBaseDao; in-memory fallback is disabled."
    )
}

class HybridRetrievalRanker(
    private val lexicalSource: LexicalCandidateSource = InMemoryBm25CandidateSource(),
    private val rrfK: Int = 60,
    private val mmrLambda: Float = 0.75f
) {
    init {
        require(rrfK > 0)
        require(mmrLambda in 0f..1f)
    }

    val lexicalBackendStatus: LexicalBackendStatus
        get() = lexicalSource.status

    fun requireProductionLexicalBackend() {
        check(lexicalBackendStatus.productionReady) {
            "RAG retrieval is production-blocked: ${lexicalBackendStatus.reason}"
        }
    }

    fun rank(
        query: String,
        queryVector: FloatArray?,
        candidates: List<RetrievalCandidate>,
        semanticLimit: Int = 30,
        lexicalLimit: Int = 30,
        resultLimit: Int = 4,
        maxPerDocument: Int = 3,
        maxPerPage: Int = 2,
        lexicalCandidates: List<Pair<RetrievalCandidate, Float>>? = null
    ): List<RankedRetrievalCandidate> {
        val semantic = if (queryVector == null) {
            emptyList()
        } else {
            candidates
                .mapNotNull { candidate ->
                    candidate.vector?.takeIf { it.size == queryVector.size }?.let { vector ->
                        candidate to LocalEmbeddingModel.cosineSimilarity(queryVector, vector)
                    }
                }
                .sortedWith(
                    compareByDescending<Pair<RetrievalCandidate, Float>> { it.second }
                        .thenBy { it.first.chunk.documentId }
                        .thenBy { it.first.chunk.chunkIndex }
                )
                .take(semanticLimit)
        }
        val lexical = lexicalCandidates ?: lexicalSource.rank(query, candidates, lexicalLimit)
        val semanticRanks = semantic.mapIndexed { index, value -> value.first.chunk.chunkId to index + 1 }.toMap()
        val lexicalRanks = lexical.mapIndexed { index, value -> value.first.chunk.chunkId to index + 1 }.toMap()
        val semanticScores = semantic.associate { it.first.chunk.chunkId to it.second }
        val lexicalScores = lexical.associate { it.first.chunk.chunkId to it.second }
        val byId = candidates.associateBy { it.chunk.chunkId }
        val fused = (semanticRanks.keys + lexicalRanks.keys).map { chunkId ->
            val score = listOfNotNull(semanticRanks[chunkId], lexicalRanks[chunkId])
                .sumOf { rank -> 1.0 / (rrfK + rank) }
                .toFloat()
            RankedRetrievalCandidate(
                candidate = byId.getValue(chunkId),
                fusedScore = score,
                semanticScore = semanticScores[chunkId],
                lexicalScore = lexicalScores[chunkId]
            )
        }.sortedWith(
            compareByDescending<RankedRetrievalCandidate>(RankedRetrievalCandidate::fusedScore)
                .thenBy { it.candidate.chunk.documentId }
                .thenBy { it.candidate.chunk.chunkIndex }
        )
        return mmrSelect(
            deduplicateAdjacentOverlap(fused),
            resultLimit,
            maxPerDocument,
            maxPerPage
        )
    }

    private fun deduplicateAdjacentOverlap(ranked: List<RankedRetrievalCandidate>): List<RankedRetrievalCandidate> =
        ranked.filter { current ->
            ranked.takeWhile { it !== current }.none { higher ->
                val first = higher.candidate.chunk
                val second = current.candidate.chunk
                first.documentId == second.documentId &&
                    first.sourcePage == second.sourcePage &&
                    kotlin.math.abs(first.chunkIndex - second.chunkIndex) <= 1 &&
                    tokenJaccard(first.textContent, second.textContent) >= ADJACENT_OVERLAP_THRESHOLD
            }
        }

    private fun mmrSelect(
        candidates: List<RankedRetrievalCandidate>,
        limit: Int,
        maxPerDocument: Int,
        maxPerPage: Int
    ): List<RankedRetrievalCandidate> {
        val selected = mutableListOf<RankedRetrievalCandidate>()
        val remaining = candidates.toMutableList()
        while (selected.size < limit && remaining.isNotEmpty()) {
            val eligible = remaining.filter { candidate ->
                val chunk = candidate.candidate.chunk
                selected.count { it.candidate.chunk.documentId == chunk.documentId } < maxPerDocument &&
                    selected.count {
                        it.candidate.chunk.documentId == chunk.documentId &&
                            it.candidate.chunk.sourcePage == chunk.sourcePage
                    } < maxPerPage
            }
            if (eligible.isEmpty()) break
            val next = eligible.maxWithOrNull(
                compareBy<RankedRetrievalCandidate> { candidate ->
                    val redundancy = selected.maxOfOrNull { similarity(candidate, it) } ?: 0f
                    mmrLambda * candidate.fusedScore - (1f - mmrLambda) * redundancy
                }.thenByDescending { it.candidate.chunk.chunkId }
            ) ?: break
            selected += next
            remaining.remove(next)
        }
        return selected
    }

    private fun similarity(first: RankedRetrievalCandidate, second: RankedRetrievalCandidate): Float {
        val firstVector = first.candidate.vector
        val secondVector = second.candidate.vector
        return if (firstVector != null && secondVector != null && firstVector.size == secondVector.size) {
            LocalEmbeddingModel.cosineSimilarity(firstVector, secondVector).coerceIn(0f, 1f)
        } else {
            tokenJaccard(first.candidate.chunk.textContent, second.candidate.chunk.textContent)
        }
    }

    private fun tokenJaccard(first: String, second: String): Float {
        val left = TERM.findAll(first.lowercase()).map { it.value }.toSet()
        val right = TERM.findAll(second.lowercase()).map { it.value }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0f
        return left.intersect(right).size.toFloat() / left.union(right).size
    }

    private companion object {
        const val ADJACENT_OVERLAP_THRESHOLD = 0.6f
        val TERM = Regex("[\\p{L}\\p{N}]+")
    }
}
