package com.stressdetector.mobile.signal

import com.stressdetector.inference.InferenceResult
import com.stressdetector.inference.InferenceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelAgreementTimelineTest {
    @Test
    fun markerUsesSessionElapsedWindowEndInsteadOfSensorEpoch() {
        assertEquals(182_000L, channelAgreementMarkerElapsedMillis(180_000L, 182_000L))
    }

    @Test
    fun invalidLiveProbabilityIsRejectedBeforeMarkerOrPrediction() {
        val result = InferenceResult(
            label = 1,
            stressProbability = Float.NaN,
            calibratedStressProbability = Float.POSITIVE_INFINITY,
            qualityTier = "good",
            latencyMs = 1.0,
            modelVersion = "test",
            status = InferenceStatus.VALID
        )

        assertNull(validatedLiveStressProbability(result))
    }
}
