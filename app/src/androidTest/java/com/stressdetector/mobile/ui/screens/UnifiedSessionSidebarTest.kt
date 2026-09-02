package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stressdetector.mobile.domain.engine.VerbatimSessionSummary
import com.stressdetector.mobile.ui.StressDetectorTheme
import com.stressdetector.mobile.ui.viewmodels.SessionCommandState
import com.stressdetector.mobile.ui.viewmodels.UnifiedSessionUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnifiedSessionSidebarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactDrawer_keepsRecoveryAndLongSessionRowUsable() {
        val longName = "Sesi konseling dengan nama yang sangat panjang untuk menguji batas layout drawer"
        val session = VerbatimSessionSummary(
            sessionId = "session-1",
            sessionName = longName,
            startedAtMillis = 1L,
            endedAtMillis = 2L,
            durationSeconds = 120L,
            audioFilePath = "/data/audio.wav",
            artifactFolderPath = "",
            finalizationStatus = "COMPLETED",
            summaryStatus = "UNAVAILABLE"
        )
        var selectedSessionId by mutableStateOf<String?>(null)
        var deletedSessionId by mutableStateOf<String?>(null)

        composeRule.setContent {
            StressDetectorTheme {
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .height(720.dp)
                        .testTag("verbatim-drawer")
                ) {
                    SessionSidebar(
                        uiState = UnifiedSessionUiState(
                            sessionHistory = listOf(session),
                            isSessionHistoryLoading = false
                        ),
                        onNewRecording = {},
                        onImportAudio = {},
                        onSelectSession = { selectedSessionId = it },
                        onDeleteSession = { deletedSessionId = it },
                        onDeleteAll = {},
                        onOpenProfile = {},
                        onRefreshReadiness = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText("Sesi Verbatim+").assertIsDisplayed()
        composeRule.onNodeWithText("Folder belum terhubung", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Buka Profile").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("verbatim-drawer").performTouchInput { swipeUp() }
        composeRule.onNodeWithText("Tersimpan").assertIsDisplayed()

        composeRule.onNodeWithText(longName, substring = true).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("session-1", selectedSessionId) }

        composeRule
            .onNodeWithContentDescription("Hapus sesi $longName")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals("session-1", deletedSessionId) }
    }

    @Test
    fun busyDrawer_exposesSessionSelectionAsDisabled() {
        val session = VerbatimSessionSummary(
            sessionId = "session-2",
            sessionName = "Sesi aktif",
            startedAtMillis = 1L,
            endedAtMillis = 2L,
            durationSeconds = 30L,
            audioFilePath = "/data/audio.wav",
            artifactFolderPath = "",
            finalizationStatus = "COMPLETED",
            summaryStatus = "UNAVAILABLE"
        )

        composeRule.setContent {
            StressDetectorTheme {
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .height(720.dp)
                ) {
                    SessionSidebar(
                        uiState = UnifiedSessionUiState(
                            sessionHistory = listOf(session),
                            isSessionHistoryLoading = false,
                            sessionCommandState = SessionCommandState.Starting
                        ),
                        onNewRecording = {},
                        onImportAudio = {},
                        onSelectSession = {},
                        onDeleteSession = {},
                        onDeleteAll = {},
                        onOpenProfile = {},
                        onRefreshReadiness = {}
                    )
                }
            }
        }

        composeRule
            .onNodeWithText("Sesi aktif", substring = true)
            .performScrollTo()
            .assertIsNotEnabled()
    }
}
