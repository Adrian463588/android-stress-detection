package com.stressdetector.mobile.signal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysiologicalArousalDivergenceDetectorTest {
    @Test
    fun requiresWarmupAndPersistenceBeforeEmitting() {
        val detector = PhysiologicalArousalDivergenceDetector(testLiveMonitorConfig())

        assertFalse(detector.observe(0.50f, 0.40f, 10_000L))
        assertFalse(detector.observe(0.80f, 0.50f, 20_000L))
        assertTrue(detector.observe(0.82f, 0.50f, 30_000L))
        assertFalse(detector.observe(0.85f, 0.50f, 40_000L))
    }

    @Test
    fun recoveryUsesHysteresisAndCooldown() {
        val detector = PhysiologicalArousalDivergenceDetector(testLiveMonitorConfig())
        detector.observe(0.50f, 0.40f, 10_000L)
        detector.observe(0.80f, 0.50f, 20_000L)
        assertTrue(detector.observe(0.82f, 0.50f, 30_000L))

        // Below the raw threshold but inside hysteresis keeps the episode active.
        assertFalse(detector.observe(0.76f, 0.50f, 34_000L))
        // Recovery below the hysteresis boundary closes the episode.
        assertFalse(detector.observe(0.60f, 0.50f, 35_000L))
        assertFalse(detector.observe(0.80f, 0.50f, 36_000L))
        assertFalse(detector.observe(0.82f, 0.50f, 37_000L))
        // Cooldown is measured from the emitted marker, not recovery time.
        assertTrue(detector.observe(0.82f, 0.50f, 70_000L))
    }
}
