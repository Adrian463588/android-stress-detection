package com.stressdetector.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.engine.SpeechSegment
import com.stressdetector.mobile.domain.engine.VerbatimSessionDetail
import com.stressdetector.mobile.domain.engine.VerbatimSessionSummary
import com.stressdetector.mobile.ui.screens.VerbatimArchiveActions
import com.stressdetector.mobile.ui.screens.VerbatimCaptureControls
import com.stressdetector.mobile.ui.screens.VerbatimMarkerFilters
import com.stressdetector.mobile.ui.viewmodels.SessionCommandState
import com.stressdetector.mobile.ui.viewmodels.UnifiedSessionUiState
import com.stressdetector.mobile.ui.viewmodels.VerbatimMarkerCounts
import com.stressdetector.mobile.ui.viewmodels.VerbatimMarkerFilter
import com.stressdetector.mobile.ui.viewmodels.VerbatimWorkspace
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VerbatimWorkspaceControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun stopAndPauseWrapAndRemainReachableAt320dpAndLargeFont() {
        var stopped = 0
        var paused = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        VerbatimCaptureControls(
                            VerbatimWorkspace(
                                UnifiedSessionUiState(
                                    isSessionActive = true,
                                    sessionCommandState = SessionCommandState.Recording
                                )
                            ),
                            "Merekam",
                            { stopped++ },
                            { paused++ }
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Hentikan & simpan").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Jeda").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, stopped)
        assertEquals(1, paused)
    }

    @Test fun activeCommandErrorStillOffersStopWithoutPause() {
        compose.setContent {
            MaterialTheme {
                VerbatimCaptureControls(
                    VerbatimWorkspace(
                        UnifiedSessionUiState(
                            isSessionActive = true,
                            sessionCommandState = SessionCommandState.Error("test command failure")
                        )
                    ),
                    "Gagal",
                    {},
                    {}
                )
            }
        }
        compose.onNodeWithText("Hentikan & simpan").assertIsDisplayed()
        compose.onNodeWithText("Jeda").assertIsNotEnabled()
    }

    @Test fun archiveKeepsDestructiveAndZipActionsBehindDisclosure() {
        var exported = 0
        compose.setContent { MaterialTheme { VerbatimArchiveActions(VerbatimWorkspace(archive()), { exported++ }, {}, {}) } }
        compose.onNodeWithText("Hapus sesi").assertDoesNotExist()
        compose.onNodeWithText("Unduh transkrip TXT").performClick()
        compose.onNodeWithText("Opsi sesi lainnya").performClick()
        compose.onNodeWithText("Hapus sesi").assertIsDisplayed()
        compose.onNodeWithText("Ekspor arsip ZIP").assertIsNotEnabled()
        assertEquals(1, exported)
    }

    @Test fun sourceFilterHasSelectionSemanticsAndRealCounts() {
        var selected: VerbatimMarkerFilter? = null
        compose.setContent {
            MaterialTheme {
                VerbatimMarkerFilters(VerbatimMarkerCounts(archive()), VerbatimMarkerFilter.TEXT, { selected = it })
            }
        }
        compose.onNodeWithText("Teks · 1").assertIsSelected()
        compose.onNodeWithText("PPG · 0").performClick()
        assertEquals(VerbatimMarkerFilter.PPG, selected)
    }

    private fun archive(): UnifiedSessionUiState {
        val segment = SpeechSegment("test", "Unknown", 0, 1_000, 0, 1_000, "mungkin besok", 0.8f)
        return UnifiedSessionUiState(
            selectedSessionId = "test-session",
            transcriptSegments = listOf(segment),
            selectedSession = VerbatimSessionDetail(
                VerbatimSessionSummary(
                    "test-session", "Test", 0, 1_000, 1,
                    "audio.wav", "", "COMPLETED", "UNAVAILABLE"
                ),
                listOf(segment),
                emptyList()
            )
        )
    }
}
