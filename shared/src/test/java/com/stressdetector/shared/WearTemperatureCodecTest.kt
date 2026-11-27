package com.stressdetector.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearTemperatureCodecTest {

    @Test
    fun roundTripPreservesValidAndInvalidReadingsWithNullableValues() {
        val batch = WearTemperatureBatch(
            sessionId = "session-temp-1",
            sessionNonce = "nonce-999",
            generation = 4L,
            sequenceNumber = 7L,
            peerNodeId = "phone-node-1",
            sentAtEpochMillis = 1_760_000_020_000L,
            readings = listOf(
                SkinTemperatureReading(
                    sensorTimestampEpochMillis = 1_760_000_018_500L,
                    elapsedMillis = 18_500L,
                    skinTemperatureC = 33.42f,
                    ambientTemperatureC = 25.81f,
                    status = SkinTemperatureReading.STATUS_SUCCESS,
                    isValid = true,
                    clockUncertaintyMillis = 250L
                ),
                SkinTemperatureReading(
                    sensorTimestampEpochMillis = 1_760_000_019_500L,
                    elapsedMillis = 19_500L,
                    skinTemperatureC = null,
                    ambientTemperatureC = 25.80f,
                    status = -2,
                    isValid = false,
                    clockUncertaintyMillis = null
                )
            )
        )

        val decoded = WearTemperatureCodec.decode(WearTemperatureCodec.encode(batch))
        assertEquals(batch, decoded)
    }

    @Test
    fun skinTemperatureMessagePathUsesPpgPrefixForManifestDelivery() {
        assertTrue(MessagePath.SKIN_TEMPERATURE_LIVE.startsWith("/ppg/"))
        assertEquals("/ppg/skin-temperature", MessagePath.SKIN_TEMPERATURE_LIVE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeRejectsCorruptPayload() {
        WearTemperatureCodec.decode(byteArrayOf(0x01, 0x02, 0x03))
    }

    @Test(expected = IllegalArgumentException::class)
    fun readingRejectsNonFiniteTemperature() {
        SkinTemperatureReading(
            sensorTimestampEpochMillis = 1_760_000_000_000L,
            elapsedMillis = 0L,
            skinTemperatureC = Float.NaN,
            ambientTemperatureC = 25f,
            status = 0,
            isValid = true
        )
    }
}
