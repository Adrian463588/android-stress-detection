package com.stressdetector.wear.sensor

import com.stressdetector.shared.PpgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgTrackerSpecTest {
    @Test
    fun activeContractUsesThreeIndependentPdsatuTrackers() {
        assertEquals(listOf(PpgType.GREEN, PpgType.IR, PpgType.RED), PpgTrackerSpec.individual.map { it.channel })
        assertEquals(25.0, PpgTrackerSpec.green.expectedRateHz, 0.0)
        assertEquals(100.0, PpgTrackerSpec.ir.expectedRateHz, 0.0)
        assertEquals(100.0, PpgTrackerSpec.red.expectedRateHz, 0.0)
        assertTrue(PpgTrackerSpec.individual.all { it.batchSize == 300 })
    }

    @Test
    fun onlyExplicitNormalStatusIsAccepted() {
        assertTrue(PpgStatusPolicy.isAccepted(0))
        assertFalse(PpgStatusPolicy.isAccepted(null))
        assertFalse(PpgStatusPolicy.isAccepted(-999))
        assertFalse(PpgStatusPolicy.isAccepted(500))
        assertEquals("status_unavailable", PpgStatusPolicy.rejectionReason(null))
        assertEquals("higher_priority_sensor", PpgStatusPolicy.rejectionReason(-999))
    }
}
