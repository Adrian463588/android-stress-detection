package com.stressdetector.wear.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgServiceStopLifecycleTest {
    @Test
    fun repeatedStopIsHarmlessAndDisconnectRemainsIntentional() {
        val lifecycle = PpgServiceStopLifecycle()

        assertTrue(lifecycle.beginStop())
        assertFalse(lifecycle.beginStop())
        assertTrue(lifecycle.ignoresConnectionEnded)
        assertTrue(lifecycle.complete())
        assertFalse(lifecycle.complete())
        assertTrue(lifecycle.ignoresConnectionEnded)
    }
}
