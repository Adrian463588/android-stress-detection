package com.stressdetector.mobile.domain

import com.stressdetector.shared.CanonicalPredictionConfidenceCalculator
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.PredictionWindowConfig
import com.stressdetector.shared.SignalQualitySummary
import com.stressdetector.shared.StressLabel
import com.stressdetector.shared.StressPrediction

data class WindowAggregationResult(
    val prediction: StressPrediction?,
    val attemptedWindowCount: Int,
    val validWindowCount: Int,
    val expectedWindowCount: Int,
    val rejectionReason: String? = null
)

/**
 * Aggregates predictions from the seven 180-second windows in the 240-second core.
 * Applies median probability aggregation and canonical confidence rules.
 */
class StressAggregator(
    private val windowConfig: PredictionWindowConfig = PredictionWindowConfig(),
    private val minValidWindowRatio: Float = 0.5f // At least 50% of windows must be usable
) {
    private val predictions = linkedMapOf<PredictionKey, StressPrediction>()

    fun addPrediction(prediction: StressPrediction) {
        predictions[PredictionKey(prediction.sensor, prediction.generatedAtMillis)] = prediction
    }

    fun clear() {
        predictions.clear()
    }

    fun aggregate(primarySensor: PpgType, attemptedWindowCount: Int = -1): WindowAggregationResult {
        val expectedWindows = windowConfig.expectedModelWindows
        val sensorPredictions = predictions.values
            .filter { it.sensor == primarySensor }
            .sortedBy { it.generatedAtMillis }
        val attempted = if (attemptedWindowCount >= 0) {
            attemptedWindowCount
        } else {
            sensorPredictions.size
        }
        if (sensorPredictions.isEmpty()) {
            return WindowAggregationResult(
                prediction = null,
                attemptedWindowCount = attempted,
                validWindowCount = 0,
                expectedWindowCount = expectedWindows,
                rejectionReason = "no_valid_${primarySensor.assetSuffix}_windows"
            )
        }

        val validPredictions = sensorPredictions.filter {
            it.quality.isUsable && it.stressProbability.isFinite() && it.stressProbability in 0f..1f
        }
        // `toInt()` floors 3.5 to 3, which is less than half of seven windows.
        // Round up so the 50% contract is actually enforced (4/7).
        val minValidWindows = kotlin.math.ceil(expectedWindows * minValidWindowRatio).toInt()

        if (validPredictions.size < minValidWindows) {
            return WindowAggregationResult(
                prediction = null,
                attemptedWindowCount = attempted,
                validWindowCount = validPredictions.size,
                expectedWindowCount = expectedWindows,
                rejectionReason = "insufficient_valid_windows:${validPredictions.size}/$expectedWindows"
            )
        }

        val policies = validPredictions.map(StressPrediction::decisionPolicy).distinct()
        if (policies.size != 1) {
            return WindowAggregationResult(
                prediction = null,
                attemptedWindowCount = attempted,
                validWindowCount = validPredictions.size,
                expectedWindowCount = expectedWindows,
                rejectionReason = "inconsistent_model_decision_policy"
            )
        }
        val decisionPolicy = policies.single()

        val medianProb = median(validPredictions.map { it.stressProbability })
        val medianRawProbability = validPredictions
            .mapNotNull { it.rawStressProbability }
            .takeIf { it.isNotEmpty() }
            ?.let(::median)

        val finalLabel = decisionPolicy.labelFor(medianProb).value
        val qualityScore = validPredictions.map { it.quality.qualityScore }.average().toFloat()
        val temporalAgreement = validPredictions.count {
            StressLabel.canonical(it.label) == finalLabel
        }.toFloat() / validPredictions.size.toFloat()
        val confidence = CanonicalPredictionConfidenceCalculator.calculate(
            stressProbability = medianProb,
            qualityScore = qualityScore,
            temporalAgreement = temporalAgreement
        )

        val aggregate = validPredictions.last().copy(
            label = finalLabel,
            stressProbability = medianProb,
            confidence = confidence.value,
            decisionPolicy = decisionPolicy,
            rawStressProbability = medianRawProbability,
            quality = SignalQualitySummary(
                isUsable = true,
                tier = "good",
                reason = "Aggregated from ${validPredictions.size} valid windows",
                sampleCount = sensorPredictions.sumOf { it.quality.sampleCount },
                missingRatio = validPredictions.map { it.quality.missingRatio }.average(),
                flatlineRatio = validPredictions.map { it.quality.flatlineRatio }.average(),
                timestampGapCount = sensorPredictions.sumOf { it.quality.timestampGapCount }
            )
        )
        return WindowAggregationResult(
            prediction = aggregate,
            attemptedWindowCount = attempted,
            validWindowCount = validPredictions.size,
            expectedWindowCount = expectedWindows
        )
    }

    /** Compatibility projection for non-Home consumers. */
    fun getAggregatedPrediction(primarySensor: PpgType): StressPrediction? = aggregate(primarySensor).prediction

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        } else {
            sorted[sorted.size / 2]
        }
    }

    private data class PredictionKey(val sensor: PpgType, val windowTimestampMillis: Long)
}
