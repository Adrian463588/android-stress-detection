package com.stressdetector.wear.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgSensorSampleTest {
    @Test
    fun normalStatusIsAcceptedWithExplicitMeaning() {
        val sample = PpgSensorSample(timestamp = 1L, value = 42, statusCode = 0)

        assertTrue(sample.accepted)
        assertEquals(0, sample.statusCode)
        assertEquals("normal", sample.statusMeaning)
    }

    @Test
    fun missingStatusIsRejectedInsteadOfDefaultingToNormal() {
        val sample = PpgSensorSample(timestamp = 5L, value = 1237)

        assertNull(sample.statusCode)
        assertEquals("status_unavailable", sample.statusMeaning)
        assertFalse(sample.accepted)
    }

    @Test
    fun sensorConflictIsRejectedWithoutChangingTheRawValue() {
        val sample = PpgSensorSample(timestamp = 2L, value = 1234, statusCode = -1)

        assertFalse(sample.accepted)
        assertEquals(1234, sample.value)
        assertEquals("sensor_error", sample.statusMeaning)
    }

    @Test
    fun documentedIndividualTrackerStatusesRemainRejectedAndAuditable() {
        val priority = PpgSensorSample(timestamp = 3L, value = 1235, statusCode = -999)
        val unsupported = PpgSensorSample(timestamp = 4L, value = 1236, statusCode = 500)

        assertFalse(priority.accepted)
        assertEquals("higher_priority_sensor", priority.statusMeaning)
        assertFalse(unsupported.accepted)
        assertEquals("status_unsupported_by_watch_software", unsupported.statusMeaning)
    }
}
