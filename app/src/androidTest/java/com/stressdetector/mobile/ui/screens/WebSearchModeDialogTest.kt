package com.stressdetector.mobile.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stressdetector.mobile.domain.search.WebSearchMode
import com.stressdetector.mobile.ui.components.WebSearchModeDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebSearchModeDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun automaticPermissionIsExplicitAndReachableWithLargeFont() {
        var selected: WebSearchMode? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                WebSearchModeDialog(WebSearchMode.Disabled, { selected = it }, {})
            }
        }
        composeRule.onNodeWithText("Izinkan web otomatis").performScrollTo().performClick()
        assertEquals(WebSearchMode.Persistent, selected)
        composeRule.onNodeWithText("Selesai").assertIsDisplayed()
    }
}
