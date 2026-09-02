package com.stressdetector.mobile.domain.search

import java.util.Locale

/** Small deterministic bilingual lexical ranker shared by local RAG and approved web sources. */
object LocalTextRanker {
    data class Passage(val text: String, val score: Float)

    fun score(query: String, candidate: String): Float {
        val orderedQueryTokens = tokens(query)
        val queryTokens = orderedQueryTokens.toSet()
        val candidateTokens = tokens(candidate)
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) return 0f

        val matchedTokens = queryTokens.count { it in candidateTokens }
        if (matchedTokens == 0) return 0f

        val coverage = matchedTokens.toFloat() / queryTokens.size
        val density = (candidateTokens.count { it in queryTokens }.toFloat() / candidateTokens.size)
            .times(DENSITY_SCALE)
            .coerceAtMost(1f)
        val normalizedQuery = orderedQueryTokens.joinToString(" ")
        val phraseBonus = if (normalizedQuery.length >= MIN_PHRASE_LENGTH &&
            candidate.lowercase(Locale.ROOT).contains(normalizedQuery)
        ) {
            PHRASE_BONUS
        } else {
            0f
        }
        return (coverage * COVERAGE_WEIGHT + density * DENSITY_WEIGHT + phraseBonus).coerceAtMost(1f)
    }

    fun bestPassage(query: String, text: String, maxCharacters: Int): Passage? {
        if (maxCharacters <= 0 || text.isBlank()) return null
        val paragraphs = text
            .split(PARAGRAPH_SEPARATOR)
            .flatMap { paragraph -> paragraph.chunked(maxCharacters) }
            .map(String::trim)
            .filter(String::isNotBlank)
        return paragraphs
            .map { candidate -> Passage(candidate, score(query, candidate)) }
            .maxByOrNull(Passage::score)
    }

    fun tokens(value: String): List<String> = TOKEN_REGEX
        .findAll(value.lowercase(Locale.ROOT))
        .map { it.value }
        .filterNot(STOP_WORDS::contains)
        .toList()

    private const val COVERAGE_WEIGHT = 0.75f
    private const val DENSITY_WEIGHT = 0.20f
    private const val PHRASE_BONUS = 0.05f
    private const val DENSITY_SCALE = 8f
    private const val MIN_PHRASE_LENGTH = 8
    private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]{2,}")
    private val PARAGRAPH_SEPARATOR = Regex("(?:\\r?\\n){2,}")
    private val STOP_WORDS = setOf(
        "dan", "atau", "yang", "untuk", "dengan", "dari", "pada", "ini", "itu", "saya", "anda",
        "the", "and", "or", "with", "from", "that", "this", "about", "what", "how", "are", "was"
    )
}
