package com.stressdetector.wear.data

import com.stressdetector.shared.PpgType
import com.stressdetector.shared.WearSessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearTrackingStateTest {
    @Test
    fun sessionStartSourcePreservesPhoneEvidenceAndSensorFailureIsExplicit() {
        WearTrackingState.setPhoneNodeDiscovered(false, "reset")
        WearTrackingState.beginSession(
            sessionId = "session-test",
            mode = WearSessionMode.HOME_STRESS,
            sourceNodeId = "phone-node"
        )

        val started = WearTrackingState.state.value
        assertTrue(started.isPhoneNodeDiscovered)
        assertFalse(started.isPhoneReachable)
        assertEquals(WearConnectionPhase.DISCOVERING, started.connectionPhase)

        WearTrackingState.setSamsungConnected(true)
        assertEquals(WearConnectionPhase.DISCOVERING, WearTrackingState.state.value.connectionPhase)
        assertFalse(WearTrackingState.state.value.greenSensorReady)
        assertFalse(WearTrackingState.state.value.irSensorReady)

        WearTrackingState.setPhoneReachable(true, "correlated ping")
        assertEquals(WearConnectionPhase.PHONE_REACHABLE, WearTrackingState.state.value.connectionPhase)

        WearTrackingState.markChannelReady(PpgType.GREEN)
        assertEquals(WearConnectionPhase.SENSOR_READY, WearTrackingState.state.value.connectionPhase)
        WearTrackingState.markChannelReady(PpgType.IR)
        assertEquals(WearConnectionPhase.SENSOR_READY, WearTrackingState.state.value.connectionPhase)
        assertTrue(WearTrackingState.state.value.greenSensorReady)
        assertTrue(WearTrackingState.state.value.irSensorReady)

        WearTrackingState.setSamsungConnected(false, "Samsung SDK connection ended")
        val failed = WearTrackingState.state.value
        assertFalse(failed.isConnectedToSamsung)
        assertEquals(WearConnectionPhase.PHONE_REACHABLE, failed.connectionPhase)
        assertEquals("Samsung SDK connection ended", failed.sensorFailure)

        WearTrackingState.setTracking(false)
        WearTrackingState.setPhoneNodeDiscovered(false, "reset")
    }

    @Test
    fun streamingStartsWhenOneIndependentInferenceChannelIsDelivered() {
        WearTrackingState.beginSession("dual-channel", WearSessionMode.HOME_STRESS, "phone-node")
        WearTrackingState.setSamsungConnected(true)
        WearTrackingState.markChannelReady(PpgType.GREEN)
        WearTrackingState.markChannelReady(PpgType.IR)

        WearTrackingState.addSent(PpgType.GREEN, 1, 1L)
        assertEquals(WearConnectionPhase.STREAMING, WearTrackingState.state.value.connectionPhase)

        WearTrackingState.addSent(PpgType.IR, 1, 1L)
        assertEquals(WearConnectionPhase.STREAMING, WearTrackingState.state.value.connectionPhase)

        WearTrackingState.setTracking(false)
    }

    @Test
    fun redIsDiagnosticAndDoesNotBlockGreenIrReadiness() {
        WearTrackingState.beginSession("red-diagnostic", WearSessionMode.HOME_STRESS, "phone-node")
        WearTrackingState.setSamsungConnected(true)
        WearTrackingState.setPhoneReachable(true, "correlated ping")
        WearTrackingState.markChannelFailure(PpgType.RED, "red unavailable")
        WearTrackingState.markChannelReady(PpgType.GREEN)
        WearTrackingState.markChannelReady(PpgType.IR)

        val ready = WearTrackingState.state.value
        assertEquals(WearConnectionPhase.SENSOR_READY, ready.connectionPhase)
        assertTrue(ready.greenSensorReady)
        assertTrue(ready.irSensorReady)
        assertFalse(ready.redSensorReady)
        assertEquals("red unavailable", ready.redSensorFailure)
        assertEquals(null, ready.sensorFailure)

        WearTrackingState.setTracking(false)
    }

    @Test
    fun intentionalDisconnectAfterStopDoesNotOverwriteCompletedState() {
        WearTrackingState.beginSession("completed-session", WearSessionMode.HOME_STRESS, "phone-node")
        WearTrackingState.setSamsungConnected(true)
        WearTrackingState.setDraining()
        WearTrackingState.transportComplete("completed normally")

        WearTrackingState.onSamsungConnectionEnded("disconnect callback", intentional = true)

        val completed = WearTrackingState.state.value
        assertEquals(WearConnectionPhase.COMPLETE, completed.connectionPhase)
        assertEquals("completed normally", completed.lastMessage)
        assertEquals(null, completed.sensorFailure)
        WearTrackingState.setTracking(false)
    }

    @Test
    fun channelTelemetryTracksCallbacksSamplesAndBatchLatencyIndependently() {
        WearTrackingState.beginSession("telemetry-session", WearSessionMode.HOME_STRESS, "phone-node")
        WearTrackingState.recordCallback(PpgType.GREEN, 25, 100L)
        WearTrackingState.recordAccepted(PpgType.GREEN)
        WearTrackingState.recordBatchSent(PpgType.GREEN, 42L)
        WearTrackingState.recordTransportGap(PpgType.IR, "temporary_gap")

        val state = WearTrackingState.state.value
        assertEquals(1L, state.channelTelemetry.getValue(PpgType.GREEN).callbackCount)
        assertEquals(25L, state.channelTelemetry.getValue(PpgType.GREEN).callbackSampleCount)
        assertEquals(1L, state.channelTelemetry.getValue(PpgType.GREEN).acceptedSampleCount)
        assertEquals(42L, state.channelTelemetry.getValue(PpgType.GREEN).lastBatchLatencyMillis)
        assertEquals(1L, state.channelTelemetry.getValue(PpgType.IR).transportGapCount)

        WearTrackingState.setTracking(false)
    }

    @Test
    fun nonNormalSamsungStatusIsRetainedAsDiagnosticAndNotAccepted() {
        WearTrackingState.beginSession("status-session", WearSessionMode.HOME_STRESS, "phone-node")
        WearTrackingState.recordRejected(PpgType.IR, "sensor_status_-1:sensor_error_or_conflict", statusCode = -1)

        val telemetry = WearTrackingState.state.value.channelTelemetry.getValue(PpgType.IR)
        assertEquals(1L, telemetry.rejectedSampleCount)
        assertEquals(1L, telemetry.invalidStatusCount)
        assertEquals(-1, telemetry.lastStatusCode)
        assertEquals("sensor_status_-1:sensor_error_or_conflict", telemetry.lastError)

        WearTrackingState.setTracking(false)
    }

    @Test
    fun unexpectedConnectionLossDuringRecordingRemainsExplicitFailure() {
        WearTrackingState.beginSession("lost-session", WearSessionMode.HOME_STRESS, "phone-node")
        WearTrackingState.setSamsungConnected(true)

        WearTrackingState.onSamsungConnectionEnded("Samsung connection lost", intentional = false)

        val failed = WearTrackingState.state.value
        assertEquals(WearConnectionPhase.FAILED, failed.connectionPhase)
        assertFalse(failed.isConnectedToSamsung)
        assertEquals("Samsung connection lost", failed.sensorFailure)
        WearTrackingState.setTracking(false)
    }

    @Test
    fun sdkResolutionIsSeparateFromPhoneReachabilityAndActionable() {
        WearTrackingState.setHealthSdkPermissionRequired()
        assertEquals(HealthSdkState.SDK_PERMISSION_REQUIRED, WearTrackingState.state.value.healthSdkState)

        WearTrackingState.setHealthSdkConnecting()
        assertEquals(HealthSdkState.SDK_CONNECTING, WearTrackingState.state.value.healthSdkState)

        WearTrackingState.setHealthSdkFailure(
            message = "Samsung SDK needs permission",
            resolutionAvailable = true
        )
        val state = WearTrackingState.state.value
        assertEquals(HealthSdkState.SDK_RESOLUTION_REQUIRED, state.healthSdkState)
        assertTrue(state.healthSdkResolutionAvailable)
        assertFalse(state.isConnectedToSamsung)

        WearTrackingState.setHealthSdkReady()
        assertEquals(HealthSdkState.SDK_READY, WearTrackingState.state.value.healthSdkState)
        WearTrackingState.setHealthSdkDisconnected()
        WearTrackingState.setTracking(false)
    }
}
