package com.stressdetector.mobile.domain

import com.stressdetector.shared.ModelDecisionPolicy
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.PredictionWindowConfig
import com.stressdetector.shared.SignalQualitySummary
import com.stressdetector.shared.StressPrediction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StressAggregatorTest {
    @Test
    fun duplicateWindowTimestampCountsOnce() {
        val aggregator = StressAggregator()
        val timestamp = 1_000L

        aggregator.addPrediction(prediction(timestamp, probability = 0.2f))
        aggregator.addPrediction(prediction(timestamp, probability = 0.8f))
        repeat(14) { index ->
            aggregator.addPrediction(prediction(timestamp + (index + 1) * 10_000L, probability = 0.8f))
        }

        val aggregated = aggregator.getAggregatedPrediction(PpgType.GREEN)

        assertNotNull(aggregated)
        assertEquals(0.8f, aggregated!!.stressProbability, 0.0001f)
        assertEquals(0.8f, aggregated.confidence, 0.0001f)
    }

    @Test
    fun sevenCoreWindowsProduceSessionPrediction() {
        val aggregator = StressAggregator()
        repeat(7) { index ->
            aggregator.addPrediction(
                prediction(
                    timestamp = 1_000L + index * 10_000L,
                    probability = if (index % 2 == 0) 0.7f else 0.6f
                )
            )
        }

        val aggregated = aggregator.getAggregatedPrediction(PpgType.GREEN)

        assertNotNull(aggregated)
        assertEquals("STRESS", aggregated!!.label)
        assertEquals(0.7f, aggregated.stressProbability, 0.0001f)
        assertEquals(0.7f, aggregated.confidence, 0.0001f)
    }

    @Test
    fun normalUiLabelIsCanonicalizedBeforeTemporalAgreement() {
        val aggregator = StressAggregator()
        repeat(7) { index ->
            aggregator.addPrediction(
                prediction(
                    timestamp = 1_000L + index * 10_000L,
                    probability = 0.2f
                ).copy(label = "Normal")
            )
        }

        val aggregated = aggregator.getAggregatedPrediction(PpgType.GREEN)

        assertNotNull(aggregated)
        assertEquals("RELAX", aggregated!!.label)
        assertEquals(0.2f, aggregated.stressProbability, 0.0001f)
    }

    @Test
    fun aggregationReportsWindowCoverageInsteadOfReturningOpaqueNull() {
        val aggregator = StressAggregator()
        repeat(2) { index ->
            aggregator.addPrediction(
                prediction(
                    timestamp = 1_000L + index * 10_000L,
                    probability = 0.7f
                )
            )
        }

        val result = aggregator.aggregate(PpgType.GREEN, attemptedWindowCount = 7)

        assertEquals(null, result.prediction)
        assertEquals(7, result.attemptedWindowCount)
        assertEquals(2, result.validWindowCount)
        assertEquals(7, result.expectedWindowCount)
        assertEquals("insufficient_valid_windows:2/7", result.rejectionReason)
    }

    @Test
    fun defaultCoreContractRequiresAtLeastFourOfSevenValidWindows() {
        val insufficient = StressAggregator()
        repeat(3) { index ->
            insufficient.addPrediction(prediction(1_000L + index * 10_000L, 0.7f))
        }

        val insufficientResult = insufficient.aggregate(PpgType.GREEN, attemptedWindowCount = 7)

        assertEquals(null, insufficientResult.prediction)
        assertEquals("insufficient_valid_windows:3/7", insufficientResult.rejectionReason)

        val accepted = StressAggregator()
        repeat(4) { index ->
            accepted.addPrediction(prediction(1_000L + index * 10_000L, 0.7f))
        }

        val acceptedResult = accepted.aggregate(PpgType.GREEN, attemptedWindowCount = 7)

        assertNotNull(acceptedResult.prediction)
        assertEquals(4, acceptedResult.validWindowCount)
    }

    @Test
    fun aggregatePreservesChannelAndRawProbabilityProvenance() {
        val aggregator = StressAggregator()
        repeat(7) { index ->
            aggregator.addPrediction(
                prediction(1_000L + index * 10_000L, 0.6f).copy(
                    modelVersion = "green-canonical",
                    rawStressProbability = 0.7f
                )
            )
        }

        val result = aggregator.aggregate(PpgType.GREEN, attemptedWindowCount = 7)

        assertEquals(PpgType.GREEN, result.prediction?.sensor)
        assertEquals("green-canonical", result.prediction?.modelVersion)
        assertEquals(0.7f, result.prediction?.rawStressProbability)
        assertEquals(7, result.validWindowCount)
    }

    @Test
    fun aggregationUsesManifestDerivedWindowCount() {
        val aggregator = StressAggregator(
            PredictionWindowConfig(
                sessionSeconds = 300,
                edgeTrimSeconds = 30,
                analysisSeconds = 240,
                modelWindowSeconds = 120,
                strideSeconds = 30
            )
        )
        repeat(3) { index ->
            aggregator.addPrediction(prediction(1_000L + index * 30_000L, 0.7f))
        }

        val result = aggregator.aggregate(PpgType.GREEN, attemptedWindowCount = 5)

        assertNotNull(result.prediction)
        assertEquals(5, result.expectedWindowCount)
        assertEquals(3, result.validWindowCount)
    }

    @Test
    fun aggregationUsesTheManifestThresholdInsteadOfGlobalHalf() {
        val policy = ModelDecisionPolicy(
            modelVersion = "green-canonical",
            probabilityThreshold = 0.485f,
            calibrationVersion = "calibration-green"
        )
        val aggregator = StressAggregator()
        repeat(7) { index ->
            aggregator.addPrediction(
                prediction(1_000L + index * 10_000L, 0.49f, policy)
            )
        }

        val result = aggregator.aggregate(PpgType.GREEN, attemptedWindowCount = 7)

        assertEquals("STRESS", result.prediction?.label)
        assertEquals(0.485f, result.prediction?.decisionPolicy?.probabilityThreshold)
    }

    @Test
    fun aggregationRejectsWindowsFromDifferentDecisionPolicies() {
        val aggregator = StressAggregator()
        repeat(4) { index ->
            aggregator.addPrediction(prediction(1_000L + index * 10_000L, 0.49f, defaultPolicy))
        }
        repeat(3) { index ->
            aggregator.addPrediction(
                prediction(
                    50_000L + index * 10_000L,
                    0.49f,
                    defaultPolicy.copy(probabilityThreshold = 0.485f)
                )
            )
        }

        val result = aggregator.aggregate(PpgType.GREEN, attemptedWindowCount = 7)

        assertEquals(null, result.prediction)
        assertEquals("inconsistent_model_decision_policy", result.rejectionReason)
    }

    private fun prediction(timestamp: Long, probability: Float, decisionPolicy: ModelDecisionPolicy = defaultPolicy) = StressPrediction(
        sensor = PpgType.GREEN,
        label = decisionPolicy.labelFor(probability).value,
        stressProbability = probability,
        confidence = 0.9f,
        quality = SignalQualitySummary(
            isUsable = true,
            tier = "good",
            reason = "test",
            sampleCount = PpgType.GREEN.samplingRateHz * 180,
            missingRatio = 0.0,
            flatlineRatio = 0.0,
            timestampGapCount = 0
        ),
        generatedAtMillis = timestamp,
        decisionPolicy = decisionPolicy
    )

    private companion object {
        val defaultPolicy = ModelDecisionPolicy(
            modelVersion = "test-model",
            probabilityThreshold = 0.5f,
            calibrationVersion = "test-calibration"
        )
    }
}
