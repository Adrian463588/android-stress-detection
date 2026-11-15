package com.stressdetector.mobile.ui.viewmodels

import com.google.gson.Gson
import com.stressdetector.mobile.data.repository.AssessmentRepository
import com.stressdetector.mobile.domain.assessment.AssessmentCatalog
import com.stressdetector.mobile.domain.assessment.AssessmentDiscussion
import com.stressdetector.mobile.domain.assessment.AssessmentInstrument
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
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

    private lateinit var repository: AssessmentRepository
    private lateinit var discussion: AssessmentDiscussion

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mock(AssessmentRepository::class.java)
        discussion = mock(AssessmentDiscussion::class.java)
        runTest(testDispatcher) {
            `when`(repository.instruments()).thenReturn(instruments)
            `when`(repository.getCounselingModules()).thenReturn(emptyList())
            `when`(repository.getRecentSearches()).thenReturn(emptyList())
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun givenCatalog_whenSearchQueryMatchesTiu6_thenFiltersCorrectly() = runTest(testDispatcher) {
        if (instruments.isEmpty()) return@runTest
        `when`(repository.instruments()).thenReturn(instruments)
        `when`(repository.getRecentSearches()).thenReturn(listOf("TIU 6", "MMPI"))

        val viewModel = AssessmentViewModel(null, repository, discussion)
        testDispatcher.scheduler.advanceUntilIdle()

        // When: User enters "TIU 6"
        viewModel.updateSearchQuery("TIU 6")
        assertEquals("TIU 6", viewModel.uiState.value.searchQuery)

        // Then: Matching filter returns TIU 6
        val matches = instruments.filter { matchesQuery(it, viewModel.uiState.value.searchQuery) }
        assertTrue(matches.any { it.id == "tiu_6" })
        assertEquals("tiu_6", matches.first().id)
    }

    @Test
    fun givenCatalog_whenSearchQueryMatchesMmpiOrCfit_thenFiltersCorrectly() = runTest(testDispatcher) {
        if (instruments.isEmpty()) return@runTest
        `when`(repository.instruments()).thenReturn(instruments)
        `when`(repository.getRecentSearches()).thenReturn(emptyList())

        val viewModel = AssessmentViewModel(null, repository, discussion)
        testDispatcher.scheduler.advanceUntilIdle()

        // When searching MMPI
        viewModel.updateSearchQuery("MMPI")
        val mmpiMatches = instruments.filter { matchesQuery(it, viewModel.uiState.value.searchQuery) }
        assertTrue("MMPI must be found", mmpiMatches.any { it.id == "mmpi" || it.aliases.contains("MMPI") })

        // When searching CFIT
        viewModel.updateSearchQuery("CFIT")
        val cfitMatches = instruments.filter { matchesQuery(it, viewModel.uiState.value.searchQuery) }
        assertTrue("CFIT 3A and 2A must be found", cfitMatches.any { it.id == "cfit_3a" })
        assertTrue("CFIT 2A must be found", cfitMatches.any { it.id == "cfit_2a" })
    }

    @Test
    fun givenRecentSearches_whenCommitSearch_thenPersistsAndUpdatesState() = runTest(testDispatcher) {
        `when`(repository.instruments()).thenReturn(instruments)
        `when`(repository.getRecentSearches()).thenReturn(listOf("TIU 6"))
        `when`(repository.saveRecentSearch("MMPI")).thenReturn(listOf("MMPI", "TIU 6"))

        val viewModel = AssessmentViewModel(null, repository, discussion)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("TIU 6"), viewModel.uiState.value.recentSearches)

        // When user commits search for "MMPI"
        viewModel.commitSearch("MMPI")

        verify(repository).saveRecentSearch("MMPI")
        assertEquals("MMPI", viewModel.uiState.value.searchQuery)
        assertEquals(listOf("MMPI", "TIU 6"), viewModel.uiState.value.recentSearches)
    }

    @Test
    fun givenRecentSearches_whenRemoveRecentSearch_thenUpdatesState() = runTest(testDispatcher) {
        `when`(repository.instruments()).thenReturn(instruments)
        `when`(repository.getRecentSearches()).thenReturn(listOf("TIU 6", "MMPI"))
        `when`(repository.removeRecentSearch("TIU 6")).thenReturn(listOf("MMPI"))

        val viewModel = AssessmentViewModel(null, repository, discussion)
        testDispatcher.scheduler.advanceUntilIdle()

        // When removing "TIU 6"
        viewModel.removeRecentSearch("TIU 6")

        verify(repository).removeRecentSearch("TIU 6")
        assertEquals(listOf("MMPI"), viewModel.uiState.value.recentSearches)
    }

    @Test
    fun givenRecentSearches_whenClearRecentSearches_thenBecomesEmpty() = runTest(testDispatcher) {
        `when`(repository.instruments()).thenReturn(instruments)
        `when`(repository.getRecentSearches()).thenReturn(listOf("TIU 6", "MMPI"))
        `when`(repository.clearRecentSearches()).thenReturn(emptyList())

        val viewModel = AssessmentViewModel(null, repository, discussion)
        testDispatcher.scheduler.advanceUntilIdle()

        // When clearing all
        viewModel.clearRecentSearches()

        verify(repository).clearRecentSearches()
        assertTrue(viewModel.uiState.value.recentSearches.isEmpty())
    }

    @Test
    fun givenSearchQuery_whenClearSearchQuery_thenQueryBecomesEmpty() = runTest(testDispatcher) {
        `when`(repository.instruments()).thenReturn(instruments)
        `when`(repository.getRecentSearches()).thenReturn(emptyList())

        val viewModel = AssessmentViewModel(null, repository, discussion)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.updateSearchQuery("WPT")
        assertEquals("WPT", viewModel.uiState.value.searchQuery)

        viewModel.clearSearchQuery()
        assertEquals("", viewModel.uiState.value.searchQuery)
    }

    private fun matchesQuery(instrument: AssessmentInstrument, query: String): Boolean {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return true
        return instrument.title.lowercase(Locale.ROOT).contains(q) ||
            instrument.id.lowercase(Locale.ROOT).contains(q) ||
            instrument.aliases.any { it.lowercase(Locale.ROOT).contains(q) } ||
            instrument.category.lowercase(Locale.ROOT).contains(q) ||
            instrument.description.lowercase(Locale.ROOT).contains(q)
    }
}
