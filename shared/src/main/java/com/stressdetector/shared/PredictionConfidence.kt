package com.stressdetector.shared

import kotlin.math.max
import kotlin.math.min

enum class ConfidenceTier {
    HIGH,
    REVIEW_REQUIRED
}

data class PredictionConfidence(val value: Float, val modelCertainty: Float, val qualityScore: Float, val temporalAgreement: Float) {
    val tier: ConfidenceTier
        get() = if (value >= HIGH_CONFIDENCE_THRESHOLD) {
            ConfidenceTier.HIGH
        } else {
            ConfidenceTier.REVIEW_REQUIRED
        }

    companion object {
        const val HIGH_CONFIDENCE_THRESHOLD = 0.80f
    }
}

fun interface PredictionConfidenceCalculator {
    fun calculate(stressProbability: Float, qualityScore: Float, temporalAgreement: Float): PredictionConfidence
}

object CanonicalPredictionConfidenceCalculator : PredictionConfidenceCalculator {
    fun calculate(stressProbability: Float, qualityScore: Float): PredictionConfidence =
        calculate(stressProbability, qualityScore, temporalAgreement = 1f)

    override fun calculate(stressProbability: Float, qualityScore: Float, temporalAgreement: Float): PredictionConfidence {
        require(stressProbability.isFinite() && stressProbability in 0f..1f) {
            "stress_probability_out_of_range"
        }
        require(qualityScore.isFinite() && qualityScore in 0f..1f) {
            "quality_score_out_of_range"
        }
        require(temporalAgreement.isFinite() && temporalAgreement in 0f..1f) {
            "temporal_agreement_out_of_range"
        }

        val modelCertainty = max(stressProbability, 1f - stressProbability)
        val confidence = min(modelCertainty, min(qualityScore, temporalAgreement))
        return PredictionConfidence(
            value = confidence,
            modelCertainty = modelCertainty,
            qualityScore = qualityScore,
            temporalAgreement = temporalAgreement
        )
    }
}
