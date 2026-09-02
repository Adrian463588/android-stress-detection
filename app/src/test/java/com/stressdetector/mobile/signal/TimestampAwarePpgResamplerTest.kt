package com.stressdetector.mobile.signal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimestampAwarePpgResamplerTest {
    @Test
    fun resamplesBySensorTimeAndUsesFirstDuplicate() {
        val result = TimestampAwarePpgResampler.resample(
            timestampsMillis = longArrayOf(0L, 0L, 40L, 80L),
            values = doubleArrayOf(1.0, 99.0, 2.0, 3.0),
            targetRateHz = 25
        )

        requireNotNull(result)
        assertArrayEquals(longArrayOf(0L, 40L, 80L), result.timestampsMillis)
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0), result.values, 1.0e-9)
        assertEquals(25.0, result.observedRateHz, 1.0e-9)
    }

    @Test
    fun rejectsOutOfOrderAndLargeGaps() {
        assertNull(
            TimestampAwarePpgResampler.resample(
                longArrayOf(0L, 40L, 20L),
                doubleArrayOf(1.0, 2.0, 3.0),
                targetRateHz = 25
            )
        )
        assertNull(
            TimestampAwarePpgResampler.resample(
                longArrayOf(0L, 40L, 700L),
                doubleArrayOf(1.0, 2.0, 3.0),
                targetRateHz = 25
            )
        )
    }
}
