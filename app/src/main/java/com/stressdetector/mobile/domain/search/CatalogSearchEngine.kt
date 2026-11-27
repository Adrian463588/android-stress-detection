package com.stressdetector.mobile.domain.search

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class CatalogSearchEngine {

    fun buildSuggestions(
        query: String,
        items: List<CatalogSearchItem>,
        recentItemIds: List<String> = emptyList(),
        limit: Int = DEFAULT_SUGGESTION_LIMIT
    ): List<SearchSuggestion> {
        if (normalize(query).isEmpty()) return emptyList()
        return rankMatches(query, items, recentItemIds)
            .take(limit)
            .map { result ->
                SearchSuggestion(
                    canonicalItemId = result.item.id,
                    canonicalTitle = result.item.title,
                    categoryLabel = result.item.categoryLabel,
                    category = result.item.category,
                    matchType = result.matchType,
                    score = result.score
                )
            }
    }

    fun rankMatches(query: String, items: List<CatalogSearchItem>, recentItemIds: List<String> = emptyList()): List<SearchResult> {
        val normQuery = normalize(query)
        if (normQuery.isEmpty()) {
            return items.map { SearchResult(it, SearchMatchType.EXACT_TITLE, 0) }
        }
        val compactQuery = normQuery.replace(" ", "")
        val recentRankById = recentItemIds.withIndex().associate { it.value to (MAX_RECENCY_BONUS - it.index.coerceAtMost(99)) }

        return items.mapNotNull { item ->
            evaluateItem(item, normQuery, compactQuery, recentRankById[item.id] ?: 0)
        }.sortedWith(
            compareByDescending<SearchResult> { it.score }
                .thenBy { it.item.title.lowercase(Locale.ROOT) }
        )
    }

    private fun evaluateItem(item: CatalogSearchItem, normQuery: String, compactQuery: String, recencyBonus: Int): SearchResult? {
        val normTitle = normalize(item.title)
        val compactTitle = normTitle.replace(" ", "")
        val normAliases = item.aliases.map(::normalize).filter { it.isNotEmpty() }
        val compactAliases = normAliases.map { it.replace(" ", "") }

        val match = matchTier(normQuery, compactQuery, normTitle, compactTitle, normAliases, compactAliases, item)
            ?: return null

        return SearchResult(
            item = item,
            matchType = match.first,
            score = match.second + recencyBonus
        )
    }

    @Suppress("ReturnCount")
    private fun matchTier(
        normQuery: String,
        compactQuery: String,
        normTitle: String,
        compactTitle: String,
        normAliases: List<String>,
        compactAliases: List<String>,
        item: CatalogSearchItem
    ): Pair<SearchMatchType, Int>? {
        if (normTitle == normQuery || compactTitle == compactQuery) {
            return SearchMatchType.EXACT_TITLE to TIER_EXACT_TITLE
        }
        if (normAliases.any { it == normQuery } || compactAliases.any { it == compactQuery }) {
            return SearchMatchType.EXACT_ALIAS to TIER_EXACT_ALIAS
        }
        val titleWords = normTitle.split(' ').filter { it.isNotEmpty() }
        if (normTitle.startsWith(normQuery) || compactTitle.startsWith(compactQuery)) {
            return SearchMatchType.PREFIX to (TIER_PREFIX + SUB_DIRECT_PREFIX)
        }
        if (titleWords.any { it.startsWith(normQuery) }) {
            return SearchMatchType.PREFIX to (TIER_PREFIX + SUB_WORD_PREFIX)
        }
        if (normAliases.any { it.startsWith(normQuery) } || compactAliases.any { it.startsWith(compactQuery) }) {
            return SearchMatchType.ALIAS_PREFIX to TIER_ALIAS_PREFIX
        }
        val normSearchText = normalize(item.searchText)
        if (normTitle.contains(normQuery) || compactTitle.contains(compactQuery)) {
            return SearchMatchType.SUBSTRING to (TIER_SUBSTRING + SUB_TITLE_CONTAINS)
        }
        if (normAliases.any { it.contains(normQuery) } || compactAliases.any { it.contains(compactQuery) }) {
            return SearchMatchType.SUBSTRING to (TIER_SUBSTRING + SUB_ALIAS_CONTAINS)
        }
        if (normSearchText.contains(normQuery)) {
            return SearchMatchType.SUBSTRING to (TIER_SUBSTRING + SUB_BODY_CONTAINS)
        }
        return evaluateFuzzy(normQuery, compactQuery, normTitle, compactTitle, normAliases, normSearchText)
    }

    private fun evaluateFuzzy(
        normQuery: String,
        compactQuery: String,
        normTitle: String,
        compactTitle: String,
        normAliases: List<String>,
        normSearchText: String
    ): Pair<SearchMatchType, Int>? {
        if (normQuery.length < MIN_FUZZY_QUERY_LENGTH) return null
        val maxDistance = if (normQuery.length <= SHORT_FUZZY_MAX_LEN) 1 else 2

        val primaryTargets = (
            listOf(normTitle, compactTitle) +
                normTitle.split(' ') +
                normAliases +
                normAliases.flatMap { it.split(' ') }
            )
            .filter { it.length >= MIN_FUZZY_QUERY_LENGTH }
            .distinct()

        val bestPrimary = bestFuzzyScore(normQuery, compactQuery, primaryTargets, maxDistance, PRIMARY_FUZZY_BONUS)
        if (bestPrimary != null) {
            return SearchMatchType.FUZZY to (TIER_FUZZY + bestPrimary)
        }

        val secondaryTargets = normSearchText.split(' ')
            .filter { it.length >= MIN_FUZZY_QUERY_LENGTH }
            .distinct()
        val bestSecondary = bestFuzzyScore(normQuery, compactQuery, secondaryTargets, maxDistance, 0)
        return bestSecondary?.let { SearchMatchType.FUZZY to (TIER_FUZZY + it) }
    }

    private fun bestFuzzyScore(normQuery: String, compactQuery: String, targets: List<String>, maxDistance: Int, weightBonus: Int): Int? {
        var bestScore: Int? = null
        for (target in targets) {
            val candidateQuery = if (' ' in target) normQuery else compactQuery
            if (candidateQuery.length == MIN_FUZZY_QUERY_LENGTH && candidateQuery[0] != target[0]) continue
            if (kotlin.math.abs(candidateQuery.length - target.length) > maxDistance) continue
            val distance = damerauLevenshtein(candidateQuery, target)
            if (distance > maxDistance) continue
            val similarity = 1.0 - distance.toDouble() / max(candidateQuery.length, target.length)
            if (similarity < MIN_FUZZY_SIMILARITY) continue
            val score = weightBonus + (similarity * SIMILARITY_SCALE).toInt() - distance * DISTANCE_PENALTY
            if (bestScore == null || score > bestScore) {
                bestScore = score
            }
        }
        return bestScore
    }

    companion object {
        private const val DEFAULT_SUGGESTION_LIMIT = 5
        private const val MIN_FUZZY_QUERY_LENGTH = 4
        private const val SHORT_FUZZY_MAX_LEN = 6
        private const val MIN_FUZZY_SIMILARITY = 0.70
        private const val MAX_RECENCY_BONUS = 100

        private const val TIER_EXACT_TITLE = 600_000
        private const val TIER_EXACT_ALIAS = 500_000
        private const val TIER_PREFIX = 400_000
        private const val TIER_ALIAS_PREFIX = 300_000
        private const val TIER_SUBSTRING = 200_000
        private const val TIER_FUZZY = 100_000

        private const val SUB_DIRECT_PREFIX = 5_000
        private const val SUB_WORD_PREFIX = 3_000
        private const val SUB_TITLE_CONTAINS = 5_000
        private const val SUB_ALIAS_CONTAINS = 3_000
        private const val SUB_BODY_CONTAINS = 1_000
        private const val PRIMARY_FUZZY_BONUS = 2_000
        private const val SIMILARITY_SCALE = 1_000
        private const val DISTANCE_PENALTY = 250

        private val nonWordRegex = Regex("[^\\p{L}\\p{N}]+")

        fun normalize(text: String): String {
            val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
            return nfkc.lowercase(Locale.ROOT)
                .replace(nonWordRegex, " ")
                .trim()
        }

        internal fun damerauLevenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            val rows = a.length + 1
            val cols = b.length + 1
            val dp = Array(rows) { IntArray(cols) }
            for (i in 0 until rows) dp[i][0] = i
            for (j in 0 until cols) dp[0][j] = j

            for (i in 1 until rows) {
                for (j in 1 until cols) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var best = min(
                        min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                    )
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        best = min(best, dp[i - 2][j - 2] + cost)
                    }
                    dp[i][j] = best
                }
            }
            return dp[a.length][b.length]
        }
    }
}
