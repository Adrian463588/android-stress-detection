package com.stressdetector.mobile.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stressdetector.mobile.ui.StressDetectorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionOnboardingSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeActionOpensSheetInsteadOfOpeningSettings() {
        var settingsOpened = false

        composeRule.setContent {
            StressDetectorTheme {
                var sheetVisible by remember { mutableStateOf(false) }
                PermissionGateCard(onOpenPermissionSheet = { sheetVisible = true })
                if (sheetVisible) {
                    PermissionOnboardingSheet(
                        state = permissionSheetState(33, false, false, false),
                        onRequestAppPermissions = {},
                        onOpenSettings = { settingsOpened = true },
                        onDismiss = { sheetVisible = false }
                    )
                }
            }
        }

        composeRule.onNodeWithText("Izinkan aplikasi").performClick()
        composeRule.onNodeWithText("Izin aplikasi").assertIsDisplayed()
        composeRule.onNodeWithText("Buka pengaturan aplikasi").assertDoesNotExist()
        assertEquals(false, settingsOpened)
    }

    @Test
    fun sheetListsAllCategoriesAndRequestsApplicableAccessOnAction() {
        var requests = 0
        var settingsOpened = false
        composeRule.setContent {
            StressDetectorTheme {
                PermissionOnboardingSheet(
                    state = permissionSheetState(33, false, false, true),
                    onRequestAppPermissions = { requests++ },
                    onOpenSettings = { settingsOpened = true },
                    onDismiss = {}
                )
            }
        }

        listOf("Lokasi", "Mikrofon", "Bluetooth", "Perangkat sekitar", "Notifikasi", "Penyimpanan")
            .forEach { composeRule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        composeRule.onNodeWithText("Izinkan akses yang diperlukan").performClick()
        assertEquals(1, requests)
        assertEquals(false, settingsOpened)
    }

    @Test
    fun appSettingsAreAvailableOnlyForARecognizedPermissionRecoveryState() {
        var settingsOpened = false
        composeRule.setContent {
            StressDetectorTheme {
                PermissionOnboardingSheet(
                    state = permissionSheetState(33, false, false, true),
                    onRequestAppPermissions = {},
                    onOpenSettings = { settingsOpened = true },
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithText("Buka pengaturan aplikasi").performScrollTo().performClick()
        assertEquals(true, settingsOpened)
    }

    @Test
    fun watchGrantDoesNotMarkSheetCompleteWhileOtherRuntimePermissionsAreMissing() {
        var requests = 0
        composeRule.setContent {
            StressDetectorTheme {
                PermissionOnboardingSheet(
                    state = permissionSheetState(33, true, false, false),
                    onRequestAppPermissions = { requests++ },
                    onOpenSettings = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithText("Izinkan akses yang diperlukan").assertIsDisplayed().performClick()
        assertEquals(1, requests)
        composeRule.onNodeWithText("Tutup").assertDoesNotExist()
    }

    @Test
    fun allApplicableRuntimePermissionsShowCompletedState() {
        composeRule.setContent {
            StressDetectorTheme {
                PermissionOnboardingSheet(
                    state = permissionSheetState(33, true, true, false),
                    onRequestAppPermissions = {},
                    onOpenSettings = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithText("Tidak ada izin runtime lain yang perlu diminta.").assertIsDisplayed()
        composeRule.onNodeWithText("Tutup").assertIsDisplayed()
        composeRule.onNodeWithText("Izinkan akses yang diperlukan").assertDoesNotExist()
    }

    private fun permissionSheetState(apiLevel: Int, watchGranted: Boolean, runtimeGranted: Boolean, showSettings: Boolean) =
        PermissionOnboardingState(
            entries = permissionChecklistEntries(apiLevel, watchGranted, false, false),
            runtimePermissionsGranted = runtimeGranted,
            showSettingsRecovery = showSettings
        )
}
