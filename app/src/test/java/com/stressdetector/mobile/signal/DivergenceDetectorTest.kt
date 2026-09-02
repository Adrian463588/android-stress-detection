package com.stressdetector.mobile.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DivergenceDetectorTest {

    @Test
    fun testPhysiologicalArousalDivergenceCalculation() {
        val baselineProbabilities = listOf(0.30f, 0.32f, 0.28f, 0.31f)
        val baseline = baselineProbabilities.average().toFloat()

        val currentProb = 0.72f
        val delta = currentProb - baseline

        val isElevatedArousal = PhysiologicalArousalDivergenceDetector(testLiveMonitorConfig())
            .isDivergent(currentProb, baseline)

        assertTrue("Elevated arousal divergence should be detected", isElevatedArousal)
        assertEquals(0.42f, delta, 0.01f)
    }
}
