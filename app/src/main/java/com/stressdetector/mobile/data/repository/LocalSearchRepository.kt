package com.stressdetector.mobile.data.repository

import com.stressdetector.mobile.data.db.SearchHistoryDao
import com.stressdetector.mobile.data.db.SearchHistoryEntity
import com.stressdetector.mobile.domain.search.CatalogSearchEngine
import com.stressdetector.mobile.domain.search.CatalogSearchItem
import com.stressdetector.mobile.domain.search.SearchCategory
import com.stressdetector.mobile.domain.search.SearchHistoryEntry
import com.stressdetector.mobile.domain.search.SearchRepository
import com.stressdetector.mobile.domain.search.SearchResult
import com.stressdetector.mobile.domain.search.SearchSuggestion
import com.stressdetector.mobile.domain.search.toCatalogSearchItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LocalSearchRepository(
    private val assessmentRepository: AssessmentRepository,
    private val searchHistoryDao: SearchHistoryDao,
    private val engine: CatalogSearchEngine = CatalogSearchEngine()
) : SearchRepository {

    override suspend fun search(query: String, category: SearchCategory): List<SearchResult> = withContext(Dispatchers.Default) {
        val items = catalogItems(category)
        val recentIds = searchHistoryDao.getRecent(category.name).map { it.canonicalItemId }
        engine.rankMatches(query, items, recentIds)
    }

    override suspend fun getSuggestions(query: String, category: SearchCategory): List<SearchSuggestion> =
        withContext(Dispatchers.Default) {
            val items = catalogItems(category)
            val recentIds = searchHistoryDao.getRecent(category.name).map { it.canonicalItemId }
            engine.buildSuggestions(query, items, recentIds)
        }

    override fun observeRecentSearches(category: SearchCategory): Flow<List<SearchHistoryEntry>> =
        searchHistoryDao.observeRecent(category.name).map { entities ->
            entities.map { entity ->
                SearchHistoryEntry(
                    canonicalItemId = entity.canonicalItemId,
                    canonicalTitle = entity.canonicalTitle,
                    category = SearchCategory.valueOf(entity.category),
                    lastSearchedAt = entity.lastSearchedAt
                )
            }
        }

    override suspend fun saveSearch(entry: SearchHistoryEntry) {
        withContext(Dispatchers.IO) {
            searchHistoryDao.upsert(
                SearchHistoryEntity(
                    canonicalItemId = entry.canonicalItemId,
                    canonicalTitle = entry.canonicalTitle,
                    category = entry.category.name,
                    lastSearchedAt = entry.lastSearchedAt
                )
            )
        }
    }

    override suspend fun removeSearch(canonicalItemId: String, category: SearchCategory) {
        withContext(Dispatchers.IO) {
            searchHistoryDao.delete(canonicalItemId, category.name)
        }
    }

    override suspend fun clearHistory(category: SearchCategory) {
        withContext(Dispatchers.IO) {
            searchHistoryDao.clearCategory(category.name)
        }
    }

    private suspend fun catalogItems(category: SearchCategory): List<CatalogSearchItem> = when (category) {
        SearchCategory.ASSESSMENT -> assessmentRepository.instruments().map { it.toCatalogSearchItem() }
        SearchCategory.COUNSELING_MATERIAL -> assessmentRepository.getCounselingModules().map { it.toCatalogSearchItem() }
    }
}
