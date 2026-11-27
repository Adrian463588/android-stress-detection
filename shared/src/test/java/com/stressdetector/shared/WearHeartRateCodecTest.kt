package com.stressdetector.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearHeartRateCodecTest {

    @Test
    fun roundTripPreservesHeartRateAndIbiPayload() {
        val batch = WearHeartRateBatch(
            sessionId = "session-123",
            sessionNonce = "nonce-456",
            generation = 3L,
            sampleTimestampElapsedMillis = 12_450L,
            sampleTimestampEpochMillis = 1_760_000_012_450L,
            bpm = 78,
            heartRateStatus = 1,
            ibiMillisList = listOf(768, 774, 210),
            ibiStatusList = listOf(0, 0, 1)
        )

        val decoded = WearHeartRateCodec.decode(WearHeartRateCodec.encode(batch))
        assertEquals(batch, decoded)
        assertEquals(listOf(768, 774), decoded.validIbiMillis)
    }

    @Test
    fun heartRateMessagePathUsesPpgPrefixForManifestDelivery() {
        assertTrue(MessagePath.HEART_RATE_LIVE.startsWith("/ppg/"))
        assertEquals("/ppg/heart-rate", MessagePath.HEART_RATE_LIVE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeRejectsCorruptPayload() {
        WearHeartRateCodec.decode(byteArrayOf(1, 2, 3, 4))
    }
}
