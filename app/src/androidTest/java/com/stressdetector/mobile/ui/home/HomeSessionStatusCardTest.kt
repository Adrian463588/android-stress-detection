package com.stressdetector.mobile.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stressdetector.mobile.MobileUiState
import com.stressdetector.mobile.data.ReachabilityProbeStatus
import com.stressdetector.mobile.data.WatchConnectionStatus
import com.stressdetector.mobile.data.WearConnectionState
import com.stressdetector.mobile.data.WearReachabilityPhase
import com.stressdetector.mobile.domain.ModelGateState
import com.stressdetector.mobile.domain.ModelGateStatus
import com.stressdetector.mobile.domain.PredictionAvailability
import com.stressdetector.shared.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeSessionStatusCardTest {
    private val readyModelGate = ModelGateState(ModelGateStatus.READY)

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recordingConnectionStateRendersStreamingInsteadOfDisconnected() {
        val permissions = PermissionStatus(true, emptyList(), false)
        val recording = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
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
        composeRule.onNodeWithText(evidenceScopeLabel()).assertIsDisplayed()
        composeRule.onNodeWithText("Merekam").assertIsDisplayed()
    }

    @Test
    fun qualityRejectionRendersInsufficientSignal() {
        val permissions = PermissionStatus(true, emptyList(), false)
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
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

        composeRule.onAllNodesWithText("Sinyal tidak cukup").assertCountEquals(3)
    }

    @Test
    fun resultStateUsesCompactChannelRowsAndKeepsTechnicalMetricsHidden() {
        val permissions = PermissionStatus(true, emptyList(), false)
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
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

        composeRule.onNodeWithText("Hasil sesi").assertIsDisplayed()
        composeRule.onNodeWithText("Green").assertIsDisplayed()
        composeRule.onNodeWithText("IR").assertIsDisplayed()
        composeRule.onNodeWithText("Lihat detail teknis").performClick()
        composeRule.onNodeWithText("Detail teknis").assertIsDisplayed()
    }

    @Test
    fun recordingStopActionUsesLocalizedAccessibleSemantics() {
        val permissions = PermissionStatus(true, emptyList(), false)
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                isRecording = true,
                connectionState = WearConnectionState(
                    status = WatchConnectionStatus.RECORDING,
                    reachabilityPhase = WearReachabilityPhase.STREAMING,
                    nodeCount = 1,
                    lastNodeIds = listOf("watch")
                )
            ),
            permissions
        )

        composeRule.setContent {
            MaterialTheme {
                HomeSessionStatusCard(state = state, onEvent = {})
            }
        }

        composeRule.onNodeWithContentDescription("Hentikan sesi Watch").assertIsDisplayed()
    }

    @Test
    fun disconnectedWatchShowsConnectionRecoveryAction() {
        val permissions = PermissionStatus(true, emptyList(), false)
        val state = HomeUiState.from(
            MobileUiState(modelGate = readyModelGate, connectionState = WearConnectionState()),
            permissions
        )

        composeRule.setContent {
            MaterialTheme {
                HomeSessionStatusCard(state = state, onEvent = {})
            }
        }

        composeRule.onNodeWithText("Watch tidak tersedia").assertIsDisplayed()
        composeRule.onNodeWithText("Coba koneksi").assertIsDisplayed()
    }

    @Test
    fun unavailableModelShowsProfileRecoveryAction() {
        val permissions = PermissionStatus(true, emptyList(), false)
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = ModelGateState(ModelGateStatus.UNAVAILABLE, "invalid_manifest"),
                connectionState = WearConnectionState(
                    status = WatchConnectionStatus.APP_REACHABLE,
                    reachabilityPhase = WearReachabilityPhase.APP_REACHABLE,
                    lastPongMillis = System.currentTimeMillis(),
                    lastPongNodeId = "watch",
                    lastNodeIds = listOf("watch")
                )
            ),
            permissions
        )
        var event: HomeEvent? = null

        composeRule.setContent {
            MaterialTheme {
                HomeSessionStatusCard(state = state, onEvent = { event = it })
            }
        }

        composeRule.onNodeWithText("Model belum siap").assertIsDisplayed()
        composeRule.onNodeWithText("Buka Profile").performClick()
        assertEquals(HomeEvent.OpenProfile, event)
    }

    @Test
    fun checkingWatchDisablesTheRecoveryAction() {
        val permissions = PermissionStatus(true, emptyList(), false)
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                connectionState = WearConnectionState(
                    status = WatchConnectionStatus.NODE_ONLY,
                    reachabilityPhase = WearReachabilityPhase.NODE_DISCOVERED,
                    probeStatus = ReachabilityProbeStatus.CHECKING,
                    nodeCount = 1,
                    lastNodeIds = listOf("watch")
                )
            ),
            permissions
        )

        composeRule.setContent {
            MaterialTheme {
                HomeSessionStatusCard(state = state, onEvent = {})
            }
        }

        composeRule.onNodeWithContentDescription("Memeriksa koneksi Watch")
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }
}
