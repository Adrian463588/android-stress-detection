package com.stressdetector.mobile.domain

import com.stressdetector.inference.InferenceResult
import com.stressdetector.inference.InferenceStatus
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.ml.CanonicalPpgRuntime
import com.stressdetector.mobile.signal.PpgPreprocessResult
import com.stressdetector.mobile.signal.PreparedPpgWindow
import com.stressdetector.mobile.signal.SignalQualityGate
import com.stressdetector.mobile.signal.testLiveMonitorConfig
import com.stressdetector.shared.ModelDecisionPolicy
import com.stressdetector.shared.PpgType
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePpgProcessingCoordinatorTest {
    @Test
    fun chartAndInferenceReuseOneProcessedWindow() {
        val runtime = FakeRuntime(readyWindow())
        val coordinator = HomePpgProcessingCoordinator(
            runtime = runtime,
            qualityGate = SignalQualityGate(testLiveMonitorConfig())
        )
        val raw = rawWindow()

        val first = coordinator.process(raw)
        val second = coordinator.process(raw)

        assertSame(first, second)
        assertEquals(1, runtime.prepareCalls)
        assertEquals(1, runtime.inferCalls)
        assertEquals(PpgChartMode.CLEANED_PARTIAL, first.chart.mode)
        assertEquals(1, first.chart.interpolatedMask.count { it })
        assertEquals(first.preparedWindow!!.filteredValues.last().toFloat(), first.chart.values.last())
        assertTrue(first.availability is PredictionAvailability.Ready)
    }

    @Test
    fun preprocessingRejectionKeepsOnlyActualRawDiagnosticChart() {
        val runtime = FakeRuntime(
            prepared = null,
            rejected = "ppg_gap_exceeds_interpolation_policy"
        )
        val coordinator = HomePpgProcessingCoordinator(
            runtime = runtime,
            qualityGate = SignalQualityGate(testLiveMonitorConfig())
        )

        val result = coordinator.process(rawWindow())

        assertEquals(PpgChartMode.RAW_DIAGNOSTIC, result.chart.mode)
        assertEquals("ppg_gap_exceeds_interpolation_policy", result.rejectionReason)
        assertEquals(0, runtime.inferCalls)
        assertTrue(result.chart.values.isNotEmpty())
    }

    @Test
    fun unusablePreparedSignalIsRejectedBeforeModelInference() {
        val flatline = PreparedPpgWindow(
            type = PpgType.GREEN,
            timestampsMillis = LongArray(100) { it * 40L },
            filteredValues = DoubleArray(100) { 1.0 },
            rawSampleCount = 100,
            interpolatedSampleCount = 0
        )
        val runtime = FakeRuntime(flatline)
        val coordinator = HomePpgProcessingCoordinator(
            runtime = runtime,
            qualityGate = SignalQualityGate(testLiveMonitorConfig())
        )

        val result = coordinator.processPrepared(flatline)

        assertTrue(result.availability is PredictionAvailability.Rejected)
        assertEquals(0, runtime.inferCalls)
    }

    @Test
    fun coordinatorUsesTheManifestDecisionPolicyForGreen() {
        val runtime = FakeRuntime(
            prepared = readyWindow(),
            probability = 0.49f,
            threshold = 0.485f
        )
        val coordinator = HomePpgProcessingCoordinator(
            runtime = runtime,
            qualityGate = SignalQualityGate(testLiveMonitorConfig())
        )

        val result = coordinator.process(rawWindow())
        val prediction = (result.availability as PredictionAvailability.Ready).prediction

        assertEquals("STRESS", prediction.label)
        assertEquals(0.485f, prediction.decisionPolicy.probabilityThreshold)
    }

    private fun rawWindow(): WindowSnapshot {
        val values = IntArray(100) { index -> 100_000 + index }
        return WindowSnapshot(
            type = PpgType.GREEN,
            timestampsMillis = LongArray(values.size) { it * 40L },
            rawValues = values
        )
    }

    private fun readyWindow(): PreparedPpgWindow {
        val values = DoubleArray(100) { index -> sin(index / 4.0) }
        val mask = BooleanArray(values.size)
        mask[40] = true
        return PreparedPpgWindow(
            type = PpgType.GREEN,
            timestampsMillis = LongArray(values.size) { it * 40L },
            filteredValues = values,
            rawSampleCount = values.size,
            interpolatedSampleCount = 1,
            interpolatedMask = mask
        )
    }

    private class FakeRuntime(
        private val prepared: PreparedPpgWindow?,
        private val rejected: String? = null,
        private val probability: Float = 0.7f,
        private val threshold: Float = 0.5f
    ) : CanonicalPpgRuntime {
        var prepareCalls = 0
        var inferCalls = 0

        override fun prepare(window: WindowSnapshot): PpgPreprocessResult {
            prepareCalls += 1
            return when {
                prepared != null -> PpgPreprocessResult.Ready(prepared)
                else -> PpgPreprocessResult.Rejected(requireNotNull(rejected))
            }
        }

        override fun prepareSession(window: WindowSnapshot): PpgPreprocessResult = prepare(window)

        override fun infer(preparedWindow: PreparedPpgWindow, qualityTier: String): InferenceResult {
            inferCalls += 1
            val policy = ModelDecisionPolicy(
                modelVersion = "test",
                probabilityThreshold = threshold,
                calibrationVersion = "test-calibration"
            )
            return InferenceResult(
                label = if (policy.labelFor(probability).value == "STRESS") 1 else 0,
                stressProbability = probability,
                calibratedStressProbability = probability,
                calibrationVersion = policy.calibrationVersion,
                decisionPolicy = policy,
                qualityTier = qualityTier,
                latencyMs = 0.1,
                modelVersion = "test",
                status = InferenceStatus.VALID
            )
        }
    }
}
