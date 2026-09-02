package com.stressdetector.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgTypeRoleTest {
    @Test
    fun redIsAcquiredButNotAnInferenceChannel() {
        assertEquals(setOf(PpgType.GREEN, PpgType.IR, PpgType.RED), PpgType.acquisitionChannels)
        assertEquals(setOf(PpgType.GREEN, PpgType.IR), PpgType.inferenceChannels)
        assertTrue(PpgType.RED.isAcquisitionChannel)
        assertFalse(PpgType.RED.isProductionChannel)
    }
}
