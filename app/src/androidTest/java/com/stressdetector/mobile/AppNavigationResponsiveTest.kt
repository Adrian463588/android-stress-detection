package com.stressdetector.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.ui.APP_DESTINATIONS
import com.stressdetector.mobile.ui.AppBottomNavigation
import com.stressdetector.mobile.ui.Screen
import com.stressdetector.mobile.ui.screens.ProfileStatusRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Isolated presentation callbacks only: never opens or clears app storage. */
class AppNavigationResponsiveTest {
    @get:Rule val compose = createComposeRule()

    @Test fun crowdedTabsKeepTheirNamesAndAllActionsAt200PercentFont() {
        val visited = mutableListOf<Screen>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        AppBottomNavigation(Screen.Verbatim, visited::add)
                    }
                }
            }
        }
        compose.onNodeWithText("Verbatim+").assertIsDisplayed()
        compose.onNodeWithText("History").assertDoesNotExist()
        compose.onNodeWithContentDescription("Verbatim+").assertIsSelected()
        APP_DESTINATIONS.forEach { destination ->
            compose.onNodeWithContentDescription(destination.label)
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        }
        assertEquals(APP_DESTINATIONS, visited)
    }

    @Test fun labelsRemainVisibleWhenTheirMeasuredTextFits() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme { Box(Modifier.width(600.dp)) { AppBottomNavigation(Screen.Verbatim, {}) } }
            }
        }
        APP_DESTINATIONS.forEach { compose.onNodeWithText(it.label).assertIsDisplayed() }
    }

    @Test fun readinessLabelAndStatusWrapWithoutOverlapAtLargeFont() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        ProfileStatusRow("Penyimpanan offline", "Belum terhubung", false)
                    }
                }
            }
        }
        val label = compose.onNodeWithText("Penyimpanan offline", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithText("Belum terhubung", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Status must wrap below the label, not overlap it", status.top >= label.bottom)
        compose.onNodeWithContentDescription("Penyimpanan offline: Belum terhubung").assertIsDisplayed()
    }
}
