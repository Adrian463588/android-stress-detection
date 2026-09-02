package com.stressdetector.mobile.ui.home

import com.stressdetector.mobile.MobileUiState
import com.stressdetector.mobile.data.WatchConnectionStatus
import com.stressdetector.mobile.data.WearConnectionState
import com.stressdetector.mobile.data.WearReachabilityPhase
import com.stressdetector.mobile.domain.PredictionAvailability
import com.stressdetector.shared.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiStateTest {
    private val granted = PermissionStatus(
        allGranted = true,
        missingPermissions = emptyList(),
        shouldShowRationale = false
    )

    @Test
    fun activeRecordingIsNotRenderedAsDisconnected() {
        val state = HomeUiState.from(
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
            granted
        )

        assertEquals(HomeSessionStatus.RECORDING, state.status)
        assertTrue(state.transport.hasAppEvidence)
        assertEquals("Device Connected & Streaming", state.transport.statusText)
    }

    @Test
    fun finalizationNoticeIsNotError() {
        val state = HomeUiState.from(
            MobileUiState(sessionNotice = "Finalizing stress session…"),
            granted
        )

        assertEquals(HomeSessionStatus.FINALIZING, state.status)
        assertEquals(null, state.error)
    }

    @Test
    fun transportTelemetryShowsCumulativeSamplesInsteadOfRollingBufferCapacity() {
        val state = HomeUiState.from(
            MobileUiState(
                greenSamples = 4_500,
                irSamples = 4_500,
                redDiagnosticSamples = 4_500,
                totalGreenSamples = 7_600,
                totalIrSamples = 7_601,
                totalRedDiagnosticSamples = 7_599
            ),
            granted
        )

        assertEquals(7_600, state.greenSamples)
        assertEquals(7_601, state.irSamples)
        assertEquals(7_600, state.transport.greenSamples)
        assertEquals(7_601, state.transport.irSamples)
        assertEquals(7_599, state.transport.redDiagnosticSamples)
    }

    @Test
    fun `both quality rejections expose insufficient signal state`() {
        val state = HomeUiState.from(
            MobileUiState(
                primaryPredictionState = PredictionAvailability.Rejected(
                    reason = "quality gate",
                    sampleCount = 12
                ),
                irPredictionState = PredictionAvailability.Rejected(
                    reason = "quality gate",
                    sampleCount = 24
                )
            ),
            PermissionStatus(true, emptyList(), false)
        )

        assertEquals(HomeSessionStatus.INSUFFICIENT_SIGNAL, state.status)
    }

    @Test
    fun disconnectedWatchExposesBlockingPrerequisiteInsteadOfReady() {
        val state = HomeUiState.from(
            MobileUiState(connectionState = WearConnectionState()),
            granted
        )

        assertEquals(HomeSessionStatus.PREREQUISITE_REQUIRED, state.status)
        assertTrue(!state.canStartSession)
    }

    @Test
    fun freshReachableWatchAllowsHomeStart() {
        val state = HomeUiState.from(
            MobileUiState(
                connectionState = WearConnectionState(
                    status = WatchConnectionStatus.APP_REACHABLE,
                    reachabilityPhase = WearReachabilityPhase.APP_REACHABLE,
                    lastPongMillis = System.currentTimeMillis(),
                    lastPongNodeId = "watch",
                    lastNodeIds = listOf("watch")
                )
            ),
            granted
        )

        assertEquals(HomeSessionStatus.READY, state.status)
        assertTrue(state.canStartSession)
    }
}
