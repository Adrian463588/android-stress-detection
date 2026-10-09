package com.stressdetector.mobile.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeResultPercentTest {
    @Test
    fun roundsManifestProbabilityForDisplayWithoutChangingDecisionValue() {
        assertEquals("59%", 0.59f.asPercent())
        assertEquals("47%", 0.47f.asPercent())
        assertEquals("90%", 0.899f.asPercent())
    }
}
