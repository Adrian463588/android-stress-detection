package com.stressdetector.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgSamplingPolicyTest {
    @Test
    fun featureRateUsesObservedEndpointCadence() {
        assertEquals(25.0, PpgSamplingPolicy.observedRateHz(longArrayOf(100, 140, 180)), 0.0)
        assertEquals(20.0, PpgSamplingPolicy.observedRateHz(longArrayOf(100, 145, 200)), 0.0)
        assertTrue(PpgSamplingPolicy.isCompatible(25.0, 24.91929703690945))
        assertFalse(PpgSamplingPolicy.isCompatible(12.5, 24.91929703690945))
        assertFalse(PpgSamplingPolicy.isCompatible(Double.NaN, 25.0))
        assertEquals("recording_filter_observed_window_v1", PpgSamplingPolicy.RECORDING_OBSERVED_V1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateTimestampCannotDefineObservedRate() {
        PpgSamplingPolicy.observedRateHz(longArrayOf(100, 100, 140))
    }
}
