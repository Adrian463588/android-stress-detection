package com.stressdetector.shared

data class StressPrediction(
    val sensor: PpgType,
    val label: String,
    val stressProbability: Float,
    val confidence: Float,
    val quality: SignalQualitySummary,
    val generatedAtMillis: Long,
    /** Manifest-derived threshold and calibration provenance used for this prediction. */
    val decisionPolicy: ModelDecisionPolicy,
    val modelVersion: String? = null,
    val rawStressProbability: Float? = null,
    val performanceTier: String? = null,
    val researchDisclaimer: String? = null,
    /** Model-boundary evidence used to diagnose parity and label mapping. */
    val inferenceTrace: StressInferenceTrace? = null
) {
    val normalProbability: Float
        get() = (1f - stressProbability).coerceIn(0f, 1f)

    val confidenceTier: ConfidenceTier
        get() = if (confidence >= PredictionConfidence.HIGH_CONFIDENCE_THRESHOLD) {
            ConfidenceTier.HIGH
        } else {
            ConfidenceTier.REVIEW_REQUIRED
        }
}
