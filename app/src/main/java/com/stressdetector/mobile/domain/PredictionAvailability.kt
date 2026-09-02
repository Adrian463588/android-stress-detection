package com.stressdetector.mobile.domain

import com.stressdetector.shared.StressPrediction

data class PredictionWindowStats(val attemptedWindowCount: Int = 0, val validWindowCount: Int = 0, val expectedWindowCount: Int = 0)

/** Explicit prediction lifecycle exposed to presentation code. */
sealed interface PredictionAvailability {
    data class WarmingUp(
        val currentSamples: Int,
        val requiredSamples: Int,
        val windowStats: PredictionWindowStats = PredictionWindowStats()
    ) : PredictionAvailability

    data class Ready(val prediction: StressPrediction, val windowStats: PredictionWindowStats = PredictionWindowStats()) :
        PredictionAvailability

    data class Rejected(val reason: String, val sampleCount: Int, val windowStats: PredictionWindowStats = PredictionWindowStats()) :
        PredictionAvailability

    data class Unavailable(val reason: String, val windowStats: PredictionWindowStats = PredictionWindowStats()) : PredictionAvailability

    data class InferenceError(val reason: String, val windowStats: PredictionWindowStats = PredictionWindowStats()) :
        PredictionAvailability
}

fun PredictionAvailability.predictionOrNull(): StressPrediction? = (this as? PredictionAvailability.Ready)?.prediction
