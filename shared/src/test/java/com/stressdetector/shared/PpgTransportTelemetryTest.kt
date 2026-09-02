package com.stressdetector.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class PpgTransportTelemetryTest {
    @Test
    fun greenCallbackToSendBudgetIsOneSecond() {
        assertEquals(1_000L, PpgTransportTiming.GREEN_MAX_CALLBACK_TO_SEND_MILLIS)
        assertEquals(1_000L, PpgTransportTiming.MAX_BATCH_AGE_MILLIS)
    }

    @Test
    fun telemetryIsImmutableAndStartsEmpty() {
        val telemetry = PpgChannelTelemetry()

        assertEquals(0L, telemetry.callbackCount)
        assertEquals(0L, telemetry.acceptedSampleCount)
        assertEquals(null, telemetry.lastBatchLatencyMillis)
    }

    @Test
    fun greenFlushesOnCallbackWhileOtherChannelsUseBatchOrAge() {
        assertEquals(true, PpgBatchPolicy.shouldFlush(PpgType.GREEN, 1, 0L))
        assertEquals(false, PpgBatchPolicy.shouldFlush(PpgType.IR, 1, 0L))
        assertEquals(true, PpgBatchPolicy.shouldFlush(PpgType.IR, 1, 1_000L))
    }

    @Test
    fun greenCallbackFlushesImmediatelyWithoutCreatingSamples() {
        assertEquals(
            true,
            PpgBatchPolicy.shouldFlush(
                type = PpgType.GREEN,
                sampleCount = 2,
                callbackAgeMillis = 0L,
                sampleSpanMillis = 1_000L
            )
        )
        assertEquals(
            true,
            PpgBatchPolicy.shouldFlush(
                type = PpgType.GREEN,
                sampleCount = 2,
                callbackAgeMillis = 200L,
                sampleSpanMillis = 200L
            )
        )
    }
}
