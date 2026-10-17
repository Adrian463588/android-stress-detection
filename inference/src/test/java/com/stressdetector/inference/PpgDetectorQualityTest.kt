package com.stressdetector.inference

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PpgDetectorQualityTest {
    @Test
    fun greenDetectorRoundsFeatureRateWithoutChangingFrozenContract() {
        val featureRate = 24.01
        val values = DoubleArray(4_322) { sin(2.0 * PI * 1.2 * it / featureRate) }
        val contract = PpgDetectorContract.defaultFor("ppg_green", 24.91929703690945)

        val detection = BackendPpgPeakDetector.detect(values, featureRate, contract)

        assertArrayEquals(msptdFast(values, 24.0).first, detection.peaks)
        assertEquals(25.0, contract.detector_sampling_rate_hz, 0.0)
        assertEquals(24.91929703690945, contract.model_rate_hz, 0.0)
    }

    @Test
    fun greenDetectorRejectsFeatureRateOutsideOnePercentOfInteger() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            BackendPpgPeakDetector.detect(
                values = doubleArrayOf(0.0, 1.0, 0.0),
                samplingRateHz = 24.74,
                contract = PpgDetectorContract.defaultFor("ppg_green", 24.91929703690945)
            )
        }

        assertEquals("detector_integer_sampling_rate_mismatch", error.message)
    }

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
