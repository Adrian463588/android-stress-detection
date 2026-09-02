package com.stressdetector.mobile.ui

import com.stressdetector.mobile.data.WatchConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MonitoringConnectionPhaseTest {
    @Test
    fun compatibilityStatusIsProjectedToHonestReachabilityPhase() {
        assertEquals("NODE_DISCOVERED", honestConnectionPhase(WatchConnectionStatus.NODE_ONLY, "NODE_ONLY"))
        assertEquals("APP_REACHABLE", honestConnectionPhase(WatchConnectionStatus.APP_REACHABLE, "APP_REACHABLE"))
        assertEquals("STREAMING", honestConnectionPhase(WatchConnectionStatus.RECORDING, "RECORDING"))
        assertEquals("OFFLINE", honestConnectionPhase(WatchConnectionStatus.DISCONNECTED, "DISCONNECTED"))
        assertEquals("PROTOCOL_ERROR", honestConnectionPhase(WatchConnectionStatus.ERROR, "ERROR"))
    }

    @Test
    fun explicitDrainingPhaseIsPreserved() {
        assertEquals("DRAINING", honestConnectionPhase(WatchConnectionStatus.RECORDING, "DRAINING"))
    }
}
