package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stressdetector.mobile.domain.engine.CitationSource
import com.stressdetector.mobile.domain.engine.KnowledgeCitation
import com.stressdetector.mobile.ui.viewmodels.UiChatMessage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiCounselorCitationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun answerHasOneCollapsedSourceDisclosure() {
        composeRule.setContent {
            MaterialTheme {
                ChatMessageBubble(
                    UiChatMessage("answer", "assistant", "Dukungan umum, bukan diagnosis.", 0L, webSources),
                    ChatMessageActions(false, false, { _, _ -> }, {}, {}, {})
                )
            }
        }
        composeRule.onAllNodesWithText("Lihat sumber").assertCountEquals(1)
        composeRule.onNodeWithText("Lihat sumber")
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "2 sumber, disembunyikan"))
        composeRule.onNodeWithText("[W1] Web · Stress").assertDoesNotExist()
        composeRule.onNodeWithText("[W2] Web · Semantics").assertDoesNotExist()
    }

    @Test
    fun oneDisclosureShowsAllSourcesAndKeepsExactUrlActionsAtLargeFont() {
        val openedUrls = mutableListOf<String>()
        showSources(fontScale = 2f, onOpen = openedUrls::add)
        composeRule.onNodeWithText("Lihat sumber").performClick()
        composeRule.onAllNodesWithText("Sembunyikan sumber").assertCountEquals(1)
        composeRule.onNodeWithText("Sembunyikan sumber")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "2 sumber, ditampilkan"))
        webSources.forEachIndexed { index, source ->
            composeRule.onNodeWithText("[W${index + 1}] Web · ${source.sourceTitle}")
                .performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(source.sourceUrl!!)
                .performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        }
        assertEquals(webSources.map { it.sourceUrl }, openedUrls)
        composeRule.onNodeWithText("Sembunyikan sumber").performScrollTo().performClick()
        composeRule.onNodeWithText("[W1] Web · Stress").assertDoesNotExist()
        composeRule.onNodeWithText("[W2] Web · Semantics").assertDoesNotExist()
        composeRule.onNodeWithText("Lihat sumber").assertIsDisplayed()
    }

    @Test
    fun localSourcesDoNotShiftWebCitationNumbers() {
        val localSource = KnowledgeCitation(
            citationId = "test-local",
            sourceTitle = "Materi konseling",
            sourceFileName = "materi-konseling.pdf",
            sourcePage = 3,
            section = "",
            text = "",
            relevanceScore = 0f
        )
        showSources(listOf(localSource) + webSources)
        composeRule.onNodeWithText("Lihat sumber").performClick()
        composeRule.onNodeWithText("• Dokumen lokal · materi-konseling.pdf").assertIsDisplayed()
        composeRule.onNodeWithText("Halaman 3").assertIsDisplayed()
        composeRule.onNodeWithText("[W1] Web · Stress").assertIsDisplayed()
        composeRule.onNodeWithText("[W2] Web · Semantics").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("[W3] Web · Semantics").assertDoesNotExist()
    }

    @Test
    fun emptySourcesHaveNoDisclosure() {
        showSources(emptyList())
        composeRule.onNodeWithText("Lihat sumber").assertDoesNotExist()
        composeRule.onNodeWithText("Sembunyikan sumber").assertDoesNotExist()
    }

    @Test
    fun changedSourcesResetDisclosure() {
        val citations = mutableStateOf(webSources)
        composeRule.setContent { MaterialTheme { CitationList(citations.value, {}) } }
        composeRule.onNodeWithText("Lihat sumber").performClick()
        composeRule.runOnIdle { citations.value = webSources.take(1) }
        composeRule.onNodeWithText("Lihat sumber").assertIsDisplayed()
        composeRule.onNodeWithText("[W1] Web · Stress").assertDoesNotExist()
        composeRule.onNodeWithText("Sembunyikan sumber").assertDoesNotExist()
    }

    @Test
    fun recreatedDisclosureStartsCollapsed() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { MaterialTheme { CitationList(webSources, {}) } }
        composeRule.onNodeWithText("Lihat sumber").performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("Lihat sumber").assertIsDisplayed()
        composeRule.onNodeWithText("Sembunyikan sumber").assertDoesNotExist()
        composeRule.onNodeWithText("[W1] Web · Stress").assertDoesNotExist()
    }

    private fun showSources(citations: List<KnowledgeCitation> = webSources, fontScale: Float = 1f, onOpen: (String) -> Unit = {}) {
        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        ChatMessageBubble(
                            UiChatMessage("answer", "assistant", "Dukungan umum, bukan diagnosis.", 0L, citations),
                            ChatMessageActions(false, false, { _, _ -> }, {}, onOpen, {})
                        )
                    }
                }
            }
        }
    }

    // Rendering fixtures only: these do not enter retrieval, generation or production storage.
    private val webSources = listOf(
        webSource("test-web-1", "Stress", "https://www.who.int/news-room/questions-and-answers/item/stress"),
        webSource("test-web-2", "Semantics", "https://developer.android.com/develop/ui/compose/accessibility/semantics")
    )

    private fun webSource(id: String, title: String, url: String) = KnowledgeCitation(
        citationId = id,
        sourceTitle = title,
        section = "",
        text = "",
        relevanceScore = 0f,
        sourceType = CitationSource.WEB,
        sourceUrl = url
    )
}
