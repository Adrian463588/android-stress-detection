package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stressdetector.mobile.domain.reminder.AiReminderStatus
import com.stressdetector.mobile.ui.viewmodels.AiReminderUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiReminderSettingsSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactLayoutAndLargeFontKeepReminderControlsReachable() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    AiReminderSettingsSection(
                        state = AiReminderUiState(status = AiReminderStatus.NoEligibleSession),
                        actions = noOpActions()
                    )
                }
            }
        }

        composeRule.onNodeWithText("AI+ Reminder").assertIsDisplayed()
        composeRule.onNodeWithText("Nama panggilan (opsional)").assertIsDisplayed()
        composeRule.onNodeWithText("Status reminder").assertIsDisplayed()
    }

    @Test
    fun expandedLayoutRendersPermissionStateWithoutOverflowingControls() {
        composeRule.setContent {
            Box(
                modifier = Modifier
                    .width(840.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
            ) {
                AiReminderSettingsSection(
                    state = AiReminderUiState(status = AiReminderStatus.NeedsNotificationPermission),
                    actions = noOpActions()
                )
            }
        }

        composeRule.onNodeWithText("Izinkan notifikasi").assertIsDisplayed()
        composeRule.onNodeWithText("Izin notifikasi diperlukan agar reminder dapat dikirim.")
            .assertIsDisplayed()
    }

    private fun noOpActions() = AiReminderSettingsActions(
        onDisplayNameSaved = {},
        onReminderConsentChanged = {},
        onEnabledChanged = {},
        onRequestNotificationPermission = {},
        onPause = {},
        onRetry = {}
    )
}
