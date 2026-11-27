package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stressdetector.mobile.domain.engine.CounselingInteractionMode
import com.stressdetector.mobile.domain.engine.FollowUpReminderKind
import com.stressdetector.mobile.domain.engine.ResponseProfile
import com.stressdetector.mobile.domain.reminder.AiReminderStatus
import com.stressdetector.mobile.ui.StressDetectorTheme
import com.stressdetector.mobile.ui.viewmodels.AiReminderUiState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            StressDetectorTheme {
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
        }

        composeRule.onNodeWithText("AI+ Reminder").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Nama panggilan (opsional)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Jadwalkan Tindak Lanjut").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Status reminder").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun expandedLayoutRendersPermissionStateWithoutOverflowingControls() {
        composeRule.setContent {
            StressDetectorTheme {
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
        }

        composeRule.onNodeWithText("Izinkan notifikasi").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Izin notifikasi diperlukan agar reminder dapat dikirim.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun tappingSwitchRowLabelAndSchedulingSheetWorkReliablyInProfile() {
        var consentState by mutableStateOf(false)
        var scheduledKind: FollowUpReminderKind? = null
        var scheduledHours: Long? = null
        var scheduledNote: String? = null
        var cancelled = false
        var selectedMode = CounselingInteractionMode.GENERAL_COUNSELING
        var selectedProfile = ResponseProfile.STANDARD
        var behavioralContext by mutableStateOf(true)

        composeRule.setContent {
            StressDetectorTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    AiCounselorPreferencesSection(
                        mode = selectedMode,
                        responseProfile = selectedProfile,
                        includeBehavioralContext = behavioralContext,
                        onSelectMode = { selectedMode = it },
                        onSelectProfile = { selectedProfile = it },
                        onToggleBehavioralContext = { behavioralContext = it }
                    )
                    AiReminderSettingsSection(
                        state = AiReminderUiState(
                            reminderConsentGranted = consentState,
                            status = AiReminderStatus.Scheduled(
                                Instant.parse("2026-11-17T03:00:00Z"),
                                "profile-manual"
                            ),
                            isManualFollowUp = true,
                            activeFollowUpKind = FollowUpReminderKind.HOMEWORK_PRACTICE,
                            activeFollowUpDelayHours = 24L
                        ),
                        actions = AiReminderSettingsActions(
                            onDisplayNameSaved = {},
                            onReminderConsentChanged = { consentState = it },
                            onEnabledChanged = {},
                            onRequestNotificationPermission = {},
                            onPause = {},
                            onRetry = {},
                            onScheduleFollowUp = { kind, hours, note ->
                                scheduledKind = kind
                                scheduledHours = hours
                                scheduledNote = note
                            },
                            onCancelPending = { cancelled = true }
                        )
                    )
                }
            }
        }

        // Whole-row toggleable responds to tapping label text directly
        composeRule.onNodeWithText("Integrasi Asesmen & Verbatim+").performScrollTo().performClick()
        assertEquals(false, behavioralContext)

        composeRule.onNodeWithText("Rencana").performScrollTo().performClick()
        assertEquals(CounselingInteractionMode.COUNSELOR_PLANNING, selectedMode)

        composeRule.onNodeWithText("Persetujuan AI+ Reminder").performScrollTo().performClick()
        assertTrue(consentState)

        composeRule.onNodeWithText("Ubah Pengingat").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Sesi Konseling").performScrollTo().performClick()
        composeRule.onNodeWithText("48 jam").performScrollTo().performClick()
        composeRule.onNodeWithText("Catatan singkat non-klinis (opsional)").performScrollTo().performTextInput("Evaluasi mingguan")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Simpan Perubahan").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(FollowUpReminderKind.COUNSELING_SESSION, scheduledKind)
        assertEquals(48L, scheduledHours)
        assertEquals("Evaluasi mingguan", scheduledNote)

        composeRule.onNodeWithText("Batalkan").performScrollTo().performClick()
        assertTrue(cancelled)
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
