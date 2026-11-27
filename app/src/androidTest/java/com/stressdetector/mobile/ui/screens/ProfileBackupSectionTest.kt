package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stressdetector.mobile.lifecycle.SafUserBackupState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI fixtures only; no user password, SAF folder, or clinical records are accessed. */
@RunWith(AndroidJUnit4::class)
class ProfileBackupSectionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun disconnectedTreeRequiresConnectionAndCannotStartBackup() {
        var connections = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    ProfileBackupSection(SafUserBackupState(), false, false, ProfileBackupActions({ connections++ }, {}, {}))
                }
            }
        }
        compose.onNodeWithText("Cadangkan History, AI+, dan Verbatim+").assertIsNotEnabled()
        compose.onNodeWithText("Pulihkan dari cadangan SAF").assertIsNotEnabled()
        compose.onNodeWithText("Hubungkan folder SAF").performClick()
        compose.runOnIdle { assertEquals(1, connections) }
    }

    @Test
    fun compactLargeTextExposesPendingChangesAndRealActions() {
        var backups = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme {
                    Column(Modifier.widthIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        ProfileBackupSection(
                            SafUserBackupState(enabled = true, pendingChanges = true),
                            true,
                            false,
                            ProfileBackupActions({}, { backups++ }, {})
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Ada perubahan belum tercadangkan. Tunggu cadangan selesai sebelum uninstall.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cadangkan History, AI+, dan Verbatim+").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, backups) }
    }

    @Test
    fun runningBackupDisablesBothActions() {
        compose.setContent {
            MaterialTheme {
                Column {
                    ProfileBackupSection(SafUserBackupState(running = true), true, false, ProfileBackupActions({}, {}, {}))
                }
            }
        }
        compose.onNodeWithText("Memproses cadangan…").assertIsNotEnabled()
        compose.onNodeWithText("Pulihkan dari cadangan SAF").assertIsNotEnabled()
    }

    @Test
    fun oneClickBackupAndRestoreTriggerDirectlyWithoutPasswordPrompt() {
        var backups = 0
        var restores = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    ProfileBackupSection(
                        SafUserBackupState(),
                        true,
                        false,
                        ProfileBackupActions({}, { backups++ }, { restores++ })
                    )
                }
            }
        }
        compose.onNodeWithText("Cadangkan History, AI+, dan Verbatim+").performClick()
        compose.onNodeWithText("Pulihkan dari cadangan SAF").performClick()
        compose.onNodeWithText("Kata sandi pemulihan").assertDoesNotExist()
        compose.onNodeWithText("Pulihkan data").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, backups)
            assertEquals(1, restores)
        }
    }
}
