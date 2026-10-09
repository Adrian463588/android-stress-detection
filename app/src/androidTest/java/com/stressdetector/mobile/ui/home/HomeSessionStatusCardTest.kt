package com.stressdetector.mobile.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.stressdetector.mobile.domain.StressSessionFinalizationState
import com.stressdetector.mobile.ui.MonitoringScreen
import com.stressdetector.shared.PermissionStatus
import com.stressdetector.shared.PpgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun recordingCompanionShowsActualClockAndAcceptedSignalWithoutPrematureResult() {
        val now = System.currentTimeMillis()
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                isRecording = true,
                elapsedMillis = 134_000L,
                progress = 134f / 300f,
                totalGreenSamples = 100,
                totalIrSamples = 80,
                connectionState = WearConnectionState(
                    status = WatchConnectionStatus.RECORDING,
                    reachabilityPhase = WearReachabilityPhase.STREAMING,
                    lastStreamingEvidenceMillis = now
                )
            ),
            PermissionStatus(true, emptyList(), false),
            nowMillis = now
        )

        composeRule.setContent {
            MaterialTheme { HomeSessionStatusCard(state = state, onEvent = {}) }
        }

        composeRule.onNodeWithText("Waktu sesi 02:14 / 05:00").assertIsDisplayed()
        composeRule.onNodeWithText("PPG Green dan IR diterima").assertIsDisplayed()
        composeRule.onAllNodesWithText("Hasil sesi").assertCountEquals(0)
    }

    @Test
    fun monitoringRouteShowsPassiveRecordingCompanion() {
        composeRule.setContent {
            MaterialTheme {
                MonitoringScreen(
                    progress = 0f,
                    elapsedMillis = 0L,
                    recordingSignalLabel = "Menunggu data PPG dari Watch",
                    greenSamples = 0,
                    irSamples = 0,
                    onStop = {}
                )
            }
        }

        composeRule.onNodeWithText("Penjaga sinyal").assertIsDisplayed()
        composeRule.onNodeWithText("Menunggu data PPG dari Watch").assertIsDisplayed()
    }

    @Test
    fun finalizationGameAppearsAfterSnapshotAndHidesOnTerminalOutcome() {
        val evaluating = MobileUiState(
            modelGate = readyModelGate,
            sessionId = "session-a",
            finalizationSnapshotSessionId = "session-a",
            finalizationState = StressSessionFinalizationState.Evaluating(PpgType.GREEN, 2, 7)
        )
        val permissions = PermissionStatus(true, emptyList(), false)
        var state by mutableStateOf(HomeUiState.from(evaluating, permissions))
        val homeEvents = mutableListOf<HomeEvent>()

        composeRule.setContent {
            MaterialTheme { HomeSessionStatusCard(state = state, onEvent = homeEvents::add) }
        }

        composeRule.onNodeWithText("Memeriksa kualitas dan model Green…").assertIsDisplayed()
        composeRule.onNodeWithText("2 dari 7 window diperiksa").assertIsDisplayed()
        composeRule.onAllNodesWithText("Hasil sesi").assertCountEquals(0)
        composeRule.mainClock.advanceTimeBy(2_100L)
        composeRule.onNodeWithText("Main sebentar").assertIsDisplayed()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Main sebentar").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithContentDescription("Ketuk untuk melompati rintangan").performClick()
        assertTrue(homeEvents.isEmpty())

        composeRule.runOnUiThread {
            state = HomeUiState.from(
                evaluating.copy(
                    finalizationState = StressSessionFinalizationState.InsufficientSignal("ppg_true_gap_detected")
                ),
                permissions
            )
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Awan Melayang").assertCountEquals(0)
        composeRule.onNodeWithText("Sinyal tidak cukup untuk menentukan hasil. Periksa detail teknis.").assertIsDisplayed()
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
