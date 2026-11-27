package com.stressdetector.wear.data.samsung

import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.stressdetector.shared.SessionIdentity
import com.stressdetector.shared.WearHeartRateBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateListenerTest {

    @Test
    fun selectsContinuousHeartRateBeforeDiscreteFallback() {
        val continuous = SamsungHeartRateExtractor.selectSupportedTrackerType(
            listOf(HealthTrackerType.PPG_CONTINUOUS, HealthTrackerType.HEART_RATE, HealthTrackerType.HEART_RATE_CONTINUOUS)
        )
        assertEquals(HealthTrackerType.HEART_RATE_CONTINUOUS, continuous)

        val fallback = SamsungHeartRateExtractor.selectSupportedTrackerType(
            listOf(HealthTrackerType.PPG_GREEN, HealthTrackerType.HEART_RATE)
        )
        assertEquals(HealthTrackerType.HEART_RATE, fallback)

        assertNull(
            SamsungHeartRateExtractor.selectSupportedTrackerType(listOf(HealthTrackerType.PPG_GREEN))
        )
    }

    @Test
    fun filtersImplausibleBpmAndPreservesValidIbiIntervals() {
        val identity = SessionIdentity("session-hr-1", "nonce-1", SessionIdentity.CURRENT_PROTOCOL_VERSION)
        val batches = SamsungHeartRateExtractor.parseReadings(
            readings = listOf(
                HeartRateRawReading(1_760_000_005_000L, bpm = 0, heartRateStatus = 1),
                HeartRateRawReading(1_760_000_006_000L, bpm = 82, heartRateStatus = -1),
                HeartRateRawReading(
                    timestampMillis = 1_760_000_010_000L,
                    bpm = 76,
                    heartRateStatus = 1,
                    ibiMillisList = listOf(785, 792, 120),
                    ibiStatusList = listOf(0, 0, 1)
                )
            ),
            identity = identity,
            generation = 2L,
            sessionStartEpochMillis = 1_760_000_000_000L
        )

        assertEquals(1, batches.size)
        val batch = batches.single()
        assertEquals(10_000L, batch.sampleTimestampElapsedMillis)
        assertEquals(76, batch.bpm)
        assertEquals(WearHeartRateBatch.SOURCE_WATCH_SDK_HR, batch.source)
        assertEquals(listOf(785, 792), batch.validIbiMillis)
    }
}
