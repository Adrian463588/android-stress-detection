package com.stressdetector.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class PpgDetectorQualityTest {
    @Test
    fun morphologySupportUsesNearestSortedTroughs() {
        assertEquals(
            2,
            morphologySupport(
                peaks = intArrayOf(-1, 10, 20, 60),
                troughs = intArrayOf(0, 15, 25)
            )
        )
    }

    @Test
    fun morphologySupportDoesNotBridgeMissingTroughs() {
        assertEquals(
            0,
            morphologySupport(
                peaks = intArrayOf(10, 30),
                troughs = intArrayOf(0)
            )
        )
    }
}
