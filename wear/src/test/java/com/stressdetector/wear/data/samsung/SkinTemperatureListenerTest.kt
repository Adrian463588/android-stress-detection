package com.stressdetector.wear.data.samsung

import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.stressdetector.shared.SkinTemperatureReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinTemperatureListenerTest {

    @Test
    fun selectsContinuousSkinTemperatureOnlyNeverOnDemand() {
        assertEquals(
            HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS,
            SamsungSkinTemperatureExtractor.selectSupportedTrackerType(
                listOf(
                    HealthTrackerType.PPG_GREEN,
                    HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND,
                    HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS
                )
            )
        )
        assertNull(
            SamsungSkinTemperatureExtractor.selectSupportedTrackerType(
                listOf(
                    HealthTrackerType.PPG_GREEN,
                    HealthTrackerType.SKIN_TEMPERATURE,
                    HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND
                )
            )
        )
    }

    @Test
    fun parsesValidAndInvalidSdkStatusPreservingAuditRow() {
        val baseEpoch = 1_760_000_000_000L
        val parsed = SamsungSkinTemperatureExtractor.parseReadings(
            readings = listOf(
                SkinTemperatureRawReading(
                    timestampMillis = baseEpoch + 12_000L,
                    skinTemperatureC = 33.45f,
                    ambientTemperatureC = 25.10f,
                    status = SkinTemperatureReading.STATUS_SUCCESS
                ),
                SkinTemperatureRawReading(
                    timestampMillis = baseEpoch + 17_000L,
                    skinTemperatureC = 0.0f,
                    ambientTemperatureC = 25.05f,
                    status = -3
                )
            ),
            sessionStartEpochMillis = baseEpoch
        )

        assertEquals(2, parsed.size)
        assertTrue(parsed[0].isValid)
        assertEquals(12_000L, parsed[0].elapsedMillis)
        assertEquals(33.45f, parsed[0].skinTemperatureC!!, 0.001f)
        assertEquals(25.10f, parsed[0].ambientTemperatureC!!, 0.001f)

        assertFalse(parsed[1].isValid)
        assertEquals(-3, parsed[1].status)
        assertEquals(17_000L, parsed[1].elapsedMillis)
    }

    @Test
    fun ignoresInvalidNegativeImplausibleTemperatureAsInvalid() {
        val baseEpoch = 1_760_000_000_000L
        val parsed = SamsungSkinTemperatureExtractor.parseReadings(
            readings = listOf(
                SkinTemperatureRawReading(
                    timestampMillis = baseEpoch + 5_000L,
                    skinTemperatureC = Float.NaN,
                    ambientTemperatureC = 26.0f,
                    status = SkinTemperatureReading.STATUS_SUCCESS
                )
            ),
            sessionStartEpochMillis = baseEpoch
        )

        assertEquals(1, parsed.size)
        assertFalse(parsed[0].isValid)
        assertNull(parsed[0].skinTemperatureC)
        assertEquals(26.0f, parsed[0].ambientTemperatureC!!, 0.001f)
    }
}
