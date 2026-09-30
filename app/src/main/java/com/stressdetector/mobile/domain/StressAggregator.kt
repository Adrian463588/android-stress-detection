package com.stressdetector.mobile.domain

import com.stressdetector.shared.CanonicalPredictionConfidenceCalculator
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.PredictionWindowConfig
import com.stressdetector.shared.SignalQualitySummary
import com.stressdetector.shared.StressInferenceTrace
import com.stressdetector.shared.StressLabel
import com.stressdetector.shared.StressPrediction
import kotlin.math.abs

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
    // At least 50% of windows must be usable.
    private val minValidWindowRatio: Float = 0.5f
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

        val representative = validPredictions.minWithOrNull(
            compareBy<StressPrediction> { abs(it.stressProbability - medianProb) }
                .thenBy(StressPrediction::generatedAtMillis)
        ) ?: return WindowAggregationResult(
            prediction = null,
            attemptedWindowCount = attempted,
            validWindowCount = 0,
            expectedWindowCount = expectedWindows,
            rejectionReason = "no_valid_${primarySensor.assetSuffix}_windows"
        )
        val aggregateTrace = aggregateTrace(
            representative = representative,
            predictions = validPredictions,
            medianProbability = medianProb,
            finalLabel = finalLabel
        )
        val aggregate = representative.copy(
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
            ),
            inferenceTrace = aggregateTrace
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

    private fun aggregateTrace(
        representative: StressPrediction,
        predictions: List<StressPrediction>,
        medianProbability: Float,
        finalLabel: String
    ): StressInferenceTrace? {
        val traces = predictions.mapNotNull { it.inferenceTrace }
        val representativeTrace = representative.inferenceTrace ?: return null
        val rawProbabilities = traces.mapNotNull { it.rawProbability }
        val detectorMethods = traces.mapNotNull { it.detectorMethod }.distinct()
        val detectorFingerprints = traces.mapNotNull { it.detectorFingerprint }.distinct()
        val observedStartMillis = traces.mapNotNull { it.observedStartMillis }.minOrNull()
        val observedEndMillis = traces.mapNotNull { it.observedEndMillis }.maxOrNull()
        return representativeTrace.copy(
            windowId = "aggregate",
            featureValues = emptyList(),
            rawProbability = rawProbabilities.takeIf { it.isNotEmpty() }?.let(::median),
            calibratedProbability = medianProbability,
            finalLabel = finalLabel,
            qualityTier = "good",
            featureChecksum = null,
            sampleCount = traces.map { it.sampleCount }.average().toInt(),
            sourceRateHz = traces.map { it.sourceRateHz }.average(),
            modelRateHz = traces.map { it.modelRateHz }.average(),
            observedStartMillis = observedStartMillis,
            observedEndMillis = observedEndMillis,
            coverageMillis = if (observedStartMillis != null && observedEndMillis != null) {
                (observedEndMillis - observedStartMillis).coerceAtLeast(0L)
            } else {
                0L
            },
            featureMinimum = traces.mapNotNull { it.featureMinimum }.minOrNull(),
            featureMaximum = traces.mapNotNull { it.featureMaximum }.maxOrNull(),
            detectorMethod = detectorMethods.singleOrNull(),
            detectorFingerprint = detectorFingerprints.singleOrNull(),
            peakChecksum = null,
            troughChecksum = null,
            peakCount = traces.map { it.peakCount }.average().toInt(),
            validIbiCount = traces.map { it.validIbiCount }.average().toInt(),
            adjacentIbiCount = traces.map { it.adjacentIbiCount }.average().toInt(),
            observedSampleCount = traces.map { it.observedSampleCount }.average().toInt(),
            repairedShortGapSampleCount = traces.map { it.repairedShortGapSampleCount }.average().toInt(),
            trueGapCount = traces.map { it.trueGapCount }.average().toInt(),
            unresolvedSampleCount = traces.map { it.unresolvedSampleCount }.average().toInt(),
            gridResampledSampleCount = traces.map { it.gridResampledSampleCount }.average().toInt(),
            physiologicalIbiRatio = traces.mapNotNull { it.physiologicalIbiRatio }.average().takeIf { it.isFinite() },
            morphologySupport = traces.map { it.morphologySupport }.average().toInt(),
            qualityStatus = "aggregated",
            rejectionReason = null
        )
    }

    private data class PredictionKey(val sensor: PpgType, val windowTimestampMillis: Long)
}
