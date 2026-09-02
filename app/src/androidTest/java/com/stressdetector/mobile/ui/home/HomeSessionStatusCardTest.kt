package com.stressdetector.mobile.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stressdetector.mobile.MobileUiState
import com.stressdetector.mobile.data.WatchConnectionStatus
import com.stressdetector.mobile.data.WearConnectionState
import com.stressdetector.mobile.data.WearReachabilityPhase
import com.stressdetector.mobile.domain.PredictionAvailability
import com.stressdetector.shared.PermissionStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeSessionStatusCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recordingConnectionStateRendersStreamingInsteadOfDisconnected() {
        val permissions = PermissionStatus(true, emptyList(), false)
        val recording = HomeUiState.from(
            MobileUiState(
                isRecording = true,
                connectionState = WearConnectionState(
                    status = WatchConnectionStatus.RECORDING,
                    reachabilityPhase = WearReachabilityPhase.STREAMING,
                    nodeCount = 1,
                    lastNodeIds = listOf("watch")
                ),
                appliedBatchCount = 1,
                observedDataElapsedMillis = 1_000L
            ),
            permissions
        )

        composeRule.setContent {
            MaterialTheme {
                HomeSessionStatusCard(state = recording, onEvent = {})
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Device Connected & Streaming").assertIsDisplayed()
        composeRule.onNodeWithText("Merekam").assertIsDisplayed()
    }

    @Test
    fun qualityRejectionRendersInsufficientSignal() {
        val permissions = PermissionStatus(true, emptyList(), false)
        val state = HomeUiState.from(
            MobileUiState(
                primaryPredictionState = PredictionAvailability.Rejected("quality gate", 12),
                irPredictionState = PredictionAvailability.Rejected("quality gate", 24)
            ),
            permissions
        )

        composeRule.setContent {
            MaterialTheme {
                HomeSessionStatusCard(state = state, onEvent = {})
            }
        }

        composeRule.onNodeWithText("Sinyal tidak cukup").assertIsDisplayed()
    }

    @Test
    fun disconnectedWatchShowsConnectionRecoveryAction() {
        val permissions = PermissionStatus(true, emptyList(), false)
        val state = HomeUiState.from(
            MobileUiState(connectionState = WearConnectionState()),
            permissions
        )

        composeRule.setContent {
            MaterialTheme {
                HomeSessionStatusCard(state = state, onEvent = {})
            }
        }

        composeRule.onNodeWithText("Watch belum terhubung").assertIsDisplayed()
        composeRule.onNodeWithText("Coba koneksi").assertIsDisplayed()
    }
}
