package com.stressdetector.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchAccelerometerCollectorTest {
    @Test
    fun sensorTimestampUsesSessionMonotonicClock() {
        val sample = observedMotionSample(1_000_000_000L, 1_040_000_000L, floatArrayOf(1f, 9.8f, 2f))
        assertEquals(40L, sample?.elapsedMillis)
        assertEquals(1_040_000_000L, sample?.sensorTimestampNanos)
    }

    @Test
    fun staleAndNonFiniteSensorEventsAreNotFabricated() {
        assertNull(observedMotionSample(1_000L, 999L, floatArrayOf(1f, 2f, 3f)))
        assertNull(observedMotionSample(1_000L, 2_000L, floatArrayOf(Float.NaN, 2f, 3f)))
        assertNull(observedMotionSample(1_000L, 2_000L, floatArrayOf(1f, 2f)))
    }
}
