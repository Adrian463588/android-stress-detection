package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.domain.PredictionAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class SignalReadinessStateTest {
    @Test
    fun warmingUpKeepsSensorCollectionSeparateFromModelWindow() {
        val readiness = LiveChannelState(
            channel = com.stressdetector.shared.PpgType.GREEN,
            availability = PredictionAvailability.WarmingUp(currentSamples = 475, requiredSamples = 4_500)
        ).readiness

        assertEquals(AcquisitionReadiness.CONNECTING, readiness.acquisition)
        assertEquals(SensorQualityReadiness.COLLECTING, readiness.sensorQuality)
        assertEquals(BpmReadiness.WARMING_UP, readiness.bpm)
        assertEquals(StressWindowReadiness.WARMING_UP, readiness.stressModel)
    }

    @Test
    fun readyStateDoesNotReuseWarmingUpAsSignalQuality() {
        val prediction = com.stressdetector.shared.StressPrediction(
            sensor = com.stressdetector.shared.PpgType.GREEN,
            label = "RELAX",
            stressProbability = 0.2f,
            confidence = 0.8f,
            quality = com.stressdetector.shared.SignalQualitySummary(
                isUsable = true,
                tier = "good",
                reason = "ok",
                sampleCount = 18_000,
                missingRatio = 0.0,
                flatlineRatio = 0.0,
                timestampGapCount = 0
            ),
            generatedAtMillis = 1L,
            decisionPolicy = com.stressdetector.shared.ModelDecisionPolicy(
                modelVersion = "model",
                calibrationVersion = "calibration",
                probabilityThreshold = 0.5f
            )
        )
        val readiness = LiveChannelState(
            channel = com.stressdetector.shared.PpgType.GREEN,
            availability = PredictionAvailability.Ready(prediction),
            qualityScore = 1f
        ).readiness

        assertEquals(AcquisitionReadiness.STREAMING, readiness.acquisition)
        assertEquals(SensorQualityReadiness.GOOD, readiness.sensorQuality)
        assertEquals(BpmReadiness.READY, readiness.bpm)
        assertEquals(StressWindowReadiness.READY, readiness.stressModel)
    }

    @Test
    fun transportGapMakesBpmInsufficientWithoutChangingModelLifecycle() {
        val readiness = LiveChannelState(
            channel = com.stressdetector.shared.PpgType.IR,
            availability = PredictionAvailability.WarmingUp(currentSamples = 1_000, requiredSamples = 18_000),
            qualityScore = 0.8f,
            rejectionReason = "transport_gap:42ms"
        ).readiness

        assertEquals(SensorQualityReadiness.GAP, readiness.sensorQuality)
        assertEquals(BpmReadiness.INSUFFICIENT_SIGNAL, readiness.bpm)
        assertEquals(StressWindowReadiness.WARMING_UP, readiness.stressModel)
    }
}
