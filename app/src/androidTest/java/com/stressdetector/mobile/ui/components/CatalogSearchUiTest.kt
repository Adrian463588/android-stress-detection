package com.stressdetector.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.search.SearchCategory
import com.stressdetector.mobile.domain.search.SearchHistoryEntry
import com.stressdetector.mobile.domain.search.SearchMatchType
import com.stressdetector.mobile.domain.search.SearchSuggestion
import com.stressdetector.mobile.ui.StressDetectorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CatalogSearchUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun staticRecommendationSectionIsAbsentAndHistoryShowsOnlyWhenFocusedAndEmpty() {
        compose.setContent {
            StressDetectorTheme {
                var query by remember { mutableStateOf("") }
                var focused by remember { mutableStateOf(false) }
                CatalogSearchSection(
                    query = query,
                    placeholder = "Cari psikotes...",
                    categoryDisplayName = "Bank Psikotes",
                    isFocused = focused,
                    recentSearches = listOf(
                        SearchHistoryEntry("wartegg", "Wartegg", SearchCategory.ASSESSMENT, 200L),
                        SearchHistoryEntry("mmpi", "MMPI", SearchCategory.ASSESSMENT, 100L)
                    ),
                    suggestions = emptyList(),
                    showSuggestions = false,
                    showClearHistoryConfirmation = false,
                    onQueryChange = { query = it },
                    onFocusChange = { focused = it },
                    onCommitSearch = {},
                    onClearQuery = { query = "" },
                    onSelectSuggestion = {},
                    onSelectHistoryChip = {},
                    onRemoveHistoryChip = {},
                    onRequestClearHistory = {},
                    onDismissClearHistory = {},
                    onConfirmClearHistory = {}
                )
            }
        }

        compose.onNodeWithText("Rekomendasi:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Pencarian Terakhir").assertDoesNotExist()

        compose.onNodeWithText("Cari psikotes...").performClick()
        compose.onNodeWithText("Pencarian Terakhir").assertIsDisplayed()
        compose.onNodeWithText("Wartegg").assertIsDisplayed()
        compose.onNodeWithText("MMPI").assertIsDisplayed()
    }

    @Test
    fun typingOpensDropdownAndSelectingSuggestionAutofillsAndCloses() {
        var selected: SearchSuggestion? = null
        compose.setContent {
            StressDetectorTheme {
                var query by remember { mutableStateOf("") }
                var focused by remember { mutableStateOf(false) }
                var showSuggestions by remember { mutableStateOf(false) }
                val suggestions = if (query.contains("wart", ignoreCase = true)) {
                    listOf(
                        SearchSuggestion(
                            canonicalItemId = "wartegg",
                            canonicalTitle = "Wartegg",
                            categoryLabel = "Proyektif",
                            category = SearchCategory.ASSESSMENT,
                            matchType = SearchMatchType.PREFIX,
                            score = 405_000
                        )
                    )
                } else {
                    emptyList()
                }
                CatalogSearchSection(
                    query = query,
                    placeholder = "Cari psikotes...",
                    categoryDisplayName = "Bank Psikotes",
                    isFocused = focused,
                    recentSearches = listOf(
                        SearchHistoryEntry("mmpi", "MMPI", SearchCategory.ASSESSMENT, 100L)
                    ),
                    suggestions = suggestions,
                    showSuggestions = showSuggestions,
                    showClearHistoryConfirmation = false,
                    onQueryChange = {
                        query = it
                        showSuggestions = it.isNotBlank()
                    },
                    onFocusChange = { focused = it },
                    onCommitSearch = {},
                    onClearQuery = {
                        query = ""
                        showSuggestions = false
                    },
                    onSelectSuggestion = {
                        selected = it
                        query = it.canonicalTitle
                        showSuggestions = false
                        focused = false
                    },
                    onSelectHistoryChip = {},
                    onRemoveHistoryChip = {},
                    onRequestClearHistory = {},
                    onDismissClearHistory = {},
                    onConfirmClearHistory = {}
                )
            }
        }

        val input = compose.onNodeWithText("Cari psikotes...")
        input.performClick()
        input.performTextInput("wart")

        // History chips are suppressed while suggestions are active
        compose.onNodeWithText("Pencarian Terakhir").assertDoesNotExist()
        compose.onNodeWithContentDescription("Saran Wartegg, Proyektif. Ketuk untuk mengisi.")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals("wartegg", selected?.canonicalItemId)
        compose.onNodeWithText("Wartegg").assertIsDisplayed()
        compose.onNodeWithContentDescription("Saran Wartegg, Proyektif. Ketuk untuk mengisi.")
            .assertDoesNotExist()
    }

    @Test
    fun removeChipClearDialogAndCompactFontScaleRespectTouchTargetsWithoutClipping() {
        var removedId: String? = null
        var cleared = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                StressDetectorTheme {
                    var confirmVisible by remember { mutableStateOf(false) }
                    Box(Modifier.width(320.dp)) {
                        CatalogSearchSection(
                            query = "",
                            placeholder = "Cari psikotes...",
                            categoryDisplayName = "Bank Psikotes",
                            isFocused = true,
                            recentSearches = listOf(
                                SearchHistoryEntry("wartegg", "Wartegg", SearchCategory.ASSESSMENT, 300L),
                                SearchHistoryEntry("mmpi", "MMPI", SearchCategory.ASSESSMENT, 200L),
                                SearchHistoryEntry("tiu_5", "TIU 5", SearchCategory.ASSESSMENT, 100L)
                            ),
                            suggestions = emptyList(),
                            showSuggestions = false,
                            showClearHistoryConfirmation = confirmVisible,
                            onQueryChange = {},
                            onFocusChange = {},
                            onCommitSearch = {},
                            onClearQuery = {},
                            onSelectSuggestion = {},
                            onSelectHistoryChip = {},
                            onRemoveHistoryChip = { removedId = it.canonicalItemId },
                            onRequestClearHistory = { confirmVisible = true },
                            onDismissClearHistory = { confirmVisible = false },
                            onConfirmClearHistory = {
                                confirmVisible = false
                                cleared = true
                            }
                        )
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("Hapus riwayat Wartegg")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        assertEquals("wartegg", removedId)

        compose.onNodeWithText("Hapus Riwayat")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertFalse(cleared)

        compose.onNodeWithText("Hapus Riwayat Pencarian?").assertIsDisplayed()
        compose.onNodeWithText("Batal").assertIsDisplayed()
        compose.onAllNodesWithText("Hapus Riwayat").onLast().performClick()
        assertTrue(cleared)
    }
}
