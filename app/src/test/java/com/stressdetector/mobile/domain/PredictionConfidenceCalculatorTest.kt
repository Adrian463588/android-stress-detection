package com.stressdetector.mobile.domain

import com.stressdetector.shared.CanonicalPredictionConfidenceCalculator
import com.stressdetector.shared.ConfidenceTier
import org.junit.Assert.assertEquals
import org.junit.Test

class PredictionConfidenceCalculatorTest {
    @Test
    fun lowStressProbabilityCanBeHighConfidenceNormalDecision() {
        val confidence = CanonicalPredictionConfidenceCalculator.calculate(
            stressProbability = 0.10f,
            qualityScore = 0.95f,
            temporalAgreement = 0.90f
        )

        assertEquals(0.90f, confidence.value, 0.0001f)
        assertEquals(ConfidenceTier.HIGH, confidence.tier)
    }

    @Test
    fun ambiguousProbabilityRequiresReview() {
        val confidence = CanonicalPredictionConfidenceCalculator.calculate(
            stressProbability = 0.50f,
            qualityScore = 1.0f
        )

        assertEquals(0.50f, confidence.value, 0.0001f)
        assertEquals(ConfidenceTier.REVIEW_REQUIRED, confidence.tier)
    }

    @Test
    fun poorQualityLimitsConfidenceEvenWhenModelIsCertain() {
        val confidence = CanonicalPredictionConfidenceCalculator.calculate(
            stressProbability = 0.90f,
            qualityScore = 0.50f
        )

        assertEquals(0.50f, confidence.value, 0.0001f)
        assertEquals(ConfidenceTier.REVIEW_REQUIRED, confidence.tier)
    }
}
