package com.stressdetector.mobile.domain.search

import com.stressdetector.mobile.domain.assessment.AssessmentInstrument
import com.stressdetector.mobile.domain.assessment.CounselingKnowledgeModule
import kotlinx.coroutines.flow.Flow

enum class SearchCategory {
    ASSESSMENT,
    COUNSELING_MATERIAL
}

data class SearchHistoryEntry(
    val canonicalItemId: String,
    val canonicalTitle: String,
    val category: SearchCategory,
    val lastSearchedAt: Long
)

enum class SearchMatchType {
    EXACT_TITLE,
    EXACT_ALIAS,
    PREFIX,
    ALIAS_PREFIX,
    SUBSTRING,
    FUZZY
}

data class CatalogSearchItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val categoryLabel: String,
    val aliases: List<String>,
    val searchText: String,
    val category: SearchCategory
)

data class SearchSuggestion(
    val canonicalItemId: String,
    val canonicalTitle: String,
    val categoryLabel: String,
    val category: SearchCategory,
    val matchType: SearchMatchType,
    val score: Int
)

data class SearchResult(val item: CatalogSearchItem, val matchType: SearchMatchType, val score: Int)

interface SearchRepository {
    suspend fun search(query: String, category: SearchCategory): List<SearchResult>
    suspend fun getSuggestions(query: String, category: SearchCategory): List<SearchSuggestion>
    fun observeRecentSearches(category: SearchCategory): Flow<List<SearchHistoryEntry>>
    suspend fun saveSearch(entry: SearchHistoryEntry)
    suspend fun removeSearch(canonicalItemId: String, category: SearchCategory)
    suspend fun clearHistory(category: SearchCategory)
}

fun AssessmentInstrument.toCatalogSearchItem(): CatalogSearchItem = CatalogSearchItem(
    id = id,
    title = title,
    subtitle = description,
    categoryLabel = category,
    aliases = (aliases + listOfNotNull(form?.title)).distinct(),
    searchText = listOfNotNull(category, description, form?.title).joinToString(" "),
    category = SearchCategory.ASSESSMENT
)

fun CounselingKnowledgeModule.toCatalogSearchItem(): CatalogSearchItem = CatalogSearchItem(
    id = id,
    title = title,
    subtitle = summary,
    categoryLabel = "$pillar · $category",
    aliases = (listOf(pillar, category) + keyTopics).distinct(),
    searchText = (listOf(pillar, category, summary) + keyTopics).joinToString(" "),
    category = SearchCategory.COUNSELING_MATERIAL
)
