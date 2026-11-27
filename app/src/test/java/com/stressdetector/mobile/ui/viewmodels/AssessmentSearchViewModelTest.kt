package com.stressdetector.mobile.ui.viewmodels

import com.google.gson.Gson
import com.stressdetector.mobile.data.repository.AssessmentRepository
import com.stressdetector.mobile.domain.assessment.AssessmentDiscussion
import com.stressdetector.mobile.domain.assessment.AssessmentInstrument
import com.stressdetector.mobile.domain.assessment.CounselingKnowledgeModule
import com.stressdetector.mobile.domain.search.CatalogSearchEngine
import com.stressdetector.mobile.domain.search.CatalogSearchItem
import com.stressdetector.mobile.domain.search.SearchCategory
import com.stressdetector.mobile.domain.search.SearchHistoryEntry
import com.stressdetector.mobile.domain.search.SearchRepository
import com.stressdetector.mobile.domain.search.SearchResult
import com.stressdetector.mobile.domain.search.SearchSuggestion
import com.stressdetector.mobile.domain.search.toCatalogSearchItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class AssessmentSearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val catalogFile = File("src/main/canonicalAssets/ai/psychometrics/catalog.json")
    private val instruments: List<AssessmentInstrument> by lazy {
        if (catalogFile.exists()) {
            Gson().fromJson(catalogFile.readText(), Array<AssessmentInstrument>::class.java).toList()
        } else {
            emptyList()
        }
    }
    private val counselingModules = listOf(
        CounselingKnowledgeModule(
            id = "bk-01",
            title = "Dasar, Asas & Kode Etik BK",
            pillar = "Pilar 1",
            category = "Etika & Regulasi",
            summary = "Asas kerahasiaan, 12 asas konseling Prayitno, kode etik ABKIN.",
            assetFileName = "bk_01_dasar_dan_etika.md",
            keyTopics = listOf("Asas Kerahasiaan", "Kode Etik ABKIN")
        ),
        CounselingKnowledgeModule(
            id = "bk-05",
            title = "Keterampilan Mikro Konseling",
            pillar = "Pilar 5",
            category = "Teknik Komunikasi",
            summary = "Keterampilan attending SOLER dan refleksi perasaan konseling.",
            assetFileName = "bk_05_keterampilan_mikro.md",
            keyTopics = listOf("Sikap Duduk SOLER")
        )
    )

    private lateinit var repository: AssessmentRepository
    private lateinit var discussion: AssessmentDiscussion
    private lateinit var searchRepository: FakeSearchRepository
    private var nowMillis = 1_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mock(AssessmentRepository::class.java)
        discussion = mock(AssessmentDiscussion::class.java)
        nowMillis = 1_000L
        searchRepository = FakeSearchRepository(instruments, counselingModules)
        runTest(testDispatcher) {
            `when`(repository.instruments()).thenReturn(instruments)
            `when`(repository.getCounselingModules()).thenReturn(counselingModules)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun typingWartAndWateggProducesDebouncedSuggestionsAndFilteredResults() = runTest(testDispatcher) {
        if (instruments.isEmpty()) return@runTest
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSearchFocusChange(true)
        viewModel.onSearchQueryChange("wart")
        advanceTimeBy(200L)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showSuggestions)
        assertEquals("Wartegg", viewModel.uiState.value.suggestions.first().canonicalTitle)
        assertTrue(viewModel.uiState.value.filteredInstruments.any { it.id == "wartegg" })

        viewModel.onSearchQueryChange("wategg")
        advanceTimeBy(200L)
        advanceUntilIdle()

        assertEquals("Wartegg", viewModel.uiState.value.suggestions.first().canonicalTitle)
        assertTrue(viewModel.uiState.value.filteredInstruments.any { it.id == "wartegg" })
    }

    @Test
    fun selectingSuggestionAutofillsClosesDropdownAndSavesToHistory() = runTest(testDispatcher) {
        if (instruments.isEmpty()) return@runTest
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSearchFocusChange(true)
        viewModel.onSearchQueryChange("wart")
        advanceTimeBy(200L)
        advanceUntilIdle()

        val suggestion = viewModel.uiState.value.suggestions.first { it.canonicalItemId == "wartegg" }
        viewModel.onSelectSuggestion(suggestion)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Wartegg", state.searchQuery)
        assertFalse(state.showSuggestions)
        assertEquals(1, state.recentSearches.size)
        assertEquals("wartegg", state.recentSearches.first().canonicalItemId)
        assertEquals(listOf("wartegg"), state.filteredInstruments.map { it.id })
    }

    @Test
    fun duplicateSearchUpdatesRecencyOrderWithoutDuplicates() = runTest(testDispatcher) {
        if (instruments.isEmpty()) return@runTest
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onCommitSearch("Wartegg")
        advanceUntilIdle()
        nowMillis += 1_000L
        viewModel.onCommitSearch("MMPI")
        advanceUntilIdle()
        nowMillis += 1_000L
        viewModel.onCommitSearch("Wartegg")
        advanceUntilIdle()

        val historyIds = viewModel.uiState.value.recentSearches.map { it.canonicalItemId }
        assertEquals(listOf("wartegg", "mmpi"), historyIds)
    }

    @Test
    fun unknownQueryDoesNotSaveFakeHistoryAndClearQueryKeepsHistory() = runTest(testDispatcher) {
        if (instruments.isEmpty()) return@runTest
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onCommitSearch("MMPI")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.recentSearches.size)

        viewModel.onCommitSearch("unknown_instrument_xyz")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.recentSearches.size)

        viewModel.onClearSearchQuery()
        advanceUntilIdle()
        assertEquals("", viewModel.uiState.value.searchQuery)
        assertEquals(instruments.size, viewModel.uiState.value.filteredInstruments.size)
        assertEquals(1, viewModel.uiState.value.recentSearches.size)
    }

    @Test
    fun removeSingleChipAndConfirmedClearAllWorkPerCategory() = runTest(testDispatcher) {
        if (instruments.isEmpty()) return@runTest
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onCommitSearch("Wartegg")
        advanceUntilIdle()
        nowMillis += 1_000L
        viewModel.onCommitSearch("MMPI")
        advanceUntilIdle()

        viewModel.selectTab(1)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.recentSearches.isEmpty())
        viewModel.onCommitSearch("konsel")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.recentSearches.size)

        viewModel.selectTab(0)
        advanceUntilIdle()
        assertEquals(listOf("mmpi", "wartegg"), viewModel.uiState.value.recentSearches.map { it.canonicalItemId })

        val toRemove = viewModel.uiState.value.recentSearches.first { it.canonicalItemId == "mmpi" }
        viewModel.onRemoveHistoryChip(toRemove)
        advanceUntilIdle()
        assertEquals(listOf("wartegg"), viewModel.uiState.value.recentSearches.map { it.canonicalItemId })

        viewModel.onRequestClearHistory()
        assertTrue(viewModel.uiState.value.showClearHistoryConfirmation)
        viewModel.onConfirmClearHistory()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.showClearHistoryConfirmation)
        assertTrue(viewModel.uiState.value.recentSearches.isEmpty())

        viewModel.selectTab(1)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.recentSearches.size)
    }

    private fun createViewModel(): AssessmentViewModel = AssessmentViewModel(
        id = null,
        repository = repository,
        discussion = discussion,
        searchRepository = searchRepository,
        clock = { nowMillis++ }
    )

    private class FakeSearchRepository(
        private val instruments: List<AssessmentInstrument>,
        private val modules: List<CounselingKnowledgeModule>
    ) : SearchRepository {
        private val engine = CatalogSearchEngine()
        private val history = MutableStateFlow<List<SearchHistoryEntry>>(emptyList())

        override suspend fun search(query: String, category: SearchCategory): List<SearchResult> {
            val recent = history.value.filter { it.category == category }.map { it.canonicalItemId }
            return engine.rankMatches(query, itemsFor(category), recent)
        }

        override suspend fun getSuggestions(query: String, category: SearchCategory): List<SearchSuggestion> {
            val recent = history.value.filter { it.category == category }.map { it.canonicalItemId }
            return engine.buildSuggestions(query, itemsFor(category), recent)
        }

        override fun observeRecentSearches(category: SearchCategory): Flow<List<SearchHistoryEntry>> = history.map { entries ->
            entries.filter { it.category == category }
                .sortedByDescending { it.lastSearchedAt }
                .take(5)
        }

        override suspend fun saveSearch(entry: SearchHistoryEntry) {
            history.value = (
                history.value.filterNot {
                    it.canonicalItemId == entry.canonicalItemId && it.category == entry.category
                } + entry
                ).sortedByDescending { it.lastSearchedAt }
        }

        override suspend fun removeSearch(canonicalItemId: String, category: SearchCategory) {
            history.value = history.value.filterNot {
                it.canonicalItemId == canonicalItemId && it.category == category
            }
        }

        override suspend fun clearHistory(category: SearchCategory) {
            history.value = history.value.filterNot { it.category == category }
        }

        private fun itemsFor(category: SearchCategory): List<CatalogSearchItem> = when (category) {
            SearchCategory.ASSESSMENT -> instruments.map { it.toCatalogSearchItem() }
            SearchCategory.COUNSELING_MATERIAL -> modules.map { it.toCatalogSearchItem() }
        }
    }
}
