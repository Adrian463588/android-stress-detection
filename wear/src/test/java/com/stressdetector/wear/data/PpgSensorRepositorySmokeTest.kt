package com.stressdetector.wear.data

import org.junit.Assert.assertTrue
import org.junit.Test

class PpgSensorRepositorySmokeTest {
    @Test
    fun uiStateStartsIdle() {
        val state = WearTrackingState.state.value
        assertTrue(!state.isTracking)
    }
}
