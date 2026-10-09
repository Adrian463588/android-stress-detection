package com.stressdetector.mobile.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebQueryConsentDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun blankQueryCannotStartSearch() {
        composeRule.setContent { WebQueryConsentDialog("", {}, {}) }
        composeRule.onNodeWithText("Izinkan dan cari").assertIsNotEnabled()
        composeRule.onNodeWithText("Batal").assertIsDisplayed()
    }

    @Test
    fun largeFontKeepsConfirmationVisibleAndOnlyReturnsEditedQuery() {
        var confirmedQuery: String? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                WebQueryConsentDialog("Apa itu coping?", { confirmedQuery = it }, {})
            }
        }
        composeRule.onNodeWithText("Apa itu coping?").performTextReplacement("Panduan coping umum")
        composeRule.onNodeWithText("Izinkan dan cari").assertIsDisplayed().performClick()
        assertEquals("Panduan coping umum", confirmedQuery)
    }
}
