package com.stressdetector.mobile.domain

import com.stressdetector.inference.InferenceResult
import com.stressdetector.inference.InferenceStatus
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.ml.CanonicalPpgRuntime
import com.stressdetector.mobile.signal.PpgPreprocessResult
import com.stressdetector.mobile.signal.PreparedPpgWindow
import com.stressdetector.mobile.signal.SignalQualityGate
import com.stressdetector.mobile.signal.testLiveMonitorConfig
import com.stressdetector.shared.PpgType
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePpgProcessingCoordinatorSourceRateTest {
    @Test
    fun rawIrChartUsesTimestampTailNotModelRate() {
        val sampleCount = 1_000
        val window = WindowSnapshot(
            type = PpgType.IR,
            timestampsMillis = LongArray(sampleCount) { it * 40L },
            rawValues = IntArray(sampleCount) { 100_000 + it }
        )
        val coordinator = HomePpgProcessingCoordinator(
            runtime = RejectingRuntime(),
            qualityGate = SignalQualityGate(testLiveMonitorConfig())
        )

        val chart = coordinator.preview(window)

        // Rendering follows timestamp bounds, not nominal model rate. The
        // fixture cadence is 25 Hz, so its ten-second tail contains 251 points.
        assertEquals(251, chart.values.size)
        assertEquals(10_000L, chart.timestampsMillis.last() - chart.timestampsMillis.first())
    }

    private class RejectingRuntime : CanonicalPpgRuntime {
        override fun prepare(window: WindowSnapshot): PpgPreprocessResult = PpgPreprocessResult.Rejected("test_rejection")

        override fun prepareSession(window: WindowSnapshot): PpgPreprocessResult = prepare(window)

        override fun infer(preparedWindow: PreparedPpgWindow, qualityTier: String): InferenceResult = InferenceResult(
            label = null,
            stressProbability = null,
            qualityTier = qualityTier,
            latencyMs = 0.0,
            modelVersion = "test",
            status = InferenceStatus.MODEL_ARTIFACT_INVALID,
            rejectionReason = "test_rejection"
        )
    }
}
