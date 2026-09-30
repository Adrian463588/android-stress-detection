package com.stressdetector.mobile.domain

import com.stressdetector.inference.InferenceStatus
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.ml.CanonicalPpgRuntime
import com.stressdetector.mobile.signal.PpgPreprocessResult
import com.stressdetector.mobile.signal.PpgRejectionDiagnostics
import com.stressdetector.mobile.signal.PreparedPpgWindow
import com.stressdetector.mobile.signal.SignalQualityGate
import com.stressdetector.shared.CanonicalPredictionConfidenceCalculator
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SignalQualitySummary
import com.stressdetector.shared.StressInferenceTrace
import com.stressdetector.shared.StressPrediction

enum class PpgChartMode {
    FULL_SESSION_PREVIEW,
    CLEANED_CORE,
    CLEANED_PARTIAL,
    RAW_DIAGNOSTIC,
    EMPTY
}

data class PpgChartData(
    val mode: PpgChartMode,
    val timestampsMillis: LongArray,
    val values: FloatArray,
    val interpolatedMask: BooleanArray,
    val min: Float,
    val max: Float,
    val qualityTier: String? = null,
    val statusMessage: String? = null,
    val axisStartMillis: Long? = null,
    val axisEndMillis: Long? = null,
    /** Precomputed off-main so Canvas only draws immutable points. */
    val normalizedValues: FloatArray = FloatArray(0),
    val gapThresholdMillis: Long = 500L,
    /** Number of accepted source samples represented by this projection. */
    val acceptedSampleCount: Int = values.size,
    /** Number of actual source samples retained for rendering. */
    val renderedPointCount: Int = values.size,
    /** Observed range before robust display scaling. */
    val observedMin: Float = min,
    val observedMax: Float = max,
    /** Calculated only from the actual rendered timestamps; never model input. */
    val actualCadenceHz: Float? = null,
    /** Long gaps remain disconnected in the Canvas and are exposed for review. */
    val significantGapCount: Int = 0,
    val largestGapMillis: Long = 0L,
    /** Lets the final chart prove it came from the same prepared inference input. */
    val sourceFingerprint: String? = null
) {
    val isEmpty: Boolean get() = values.isEmpty()

    companion object {
        fun empty(message: String? = null) = PpgChartData(
            mode = PpgChartMode.EMPTY,
            timestampsMillis = LongArray(0),
            values = FloatArray(0),
            interpolatedMask = BooleanArray(0),
            min = 0f,
            max = 0f,
            statusMessage = message
        )
    }
}

data class PpgProcessingSnapshot(
    val channel: PpgType,
    val rawSampleCount: Int,
    val durationMillis: Long,
    val preparedWindow: PreparedPpgWindow?,
    val quality: SignalQualitySummary?,
    val availability: PredictionAvailability,
    val chart: PpgChartData,
    val rejectionReason: String?,
    /** Metadata-only trace is also emitted when preprocessing rejects before ONNX. */
    val inferenceTrace: StressInferenceTrace? = null,
    val processingFingerprint: String
)

/**
 * Single Home boundary for preprocessing, quality, inference, and chart data.
 * Cache key prevents chart and inference callers from processing same window twice.
 */
class HomePpgProcessingCoordinator(
    private val runtime: CanonicalPpgRuntime,
    private val qualityGate: SignalQualityGate,
    private val chartProjector: RealtimePpgChartProjector = RealtimePpgChartProjector()
) {
    private val lock = Any()
    private val cache = mutableMapOf<PpgType, PpgProcessingSnapshot>()

    fun process(window: WindowSnapshot): PpgProcessingSnapshot {
        val fingerprint = fingerprint(window)
        synchronized(lock) {
            cache[window.type]?.takeIf { it.processingFingerprint == fingerprint }?.let { return it }
        }

        val result = when (val preparedResult = runtime.prepare(window)) {
            is PpgPreprocessResult.WarmingUp -> PpgProcessingSnapshot(
                channel = window.type,
                rawSampleCount = window.size,
                durationMillis = durationMillis(window.timestampsMillis),
                preparedWindow = null,
                quality = null,
                availability = PredictionAvailability.WarmingUp(
                    preparedResult.currentSamples,
                    preparedResult.requiredSamples
                ),
                chart = rawChart(window, "RAW / BELUM DIPROSES: window belum lengkap"),
                rejectionReason = "window_incomplete",
                processingFingerprint = fingerprint
            )
            is PpgPreprocessResult.Rejected -> PpgProcessingSnapshot(
                channel = window.type,
                rawSampleCount = window.size,
                durationMillis = durationMillis(window.timestampsMillis),
                preparedWindow = null,
                quality = null,
                availability = PredictionAvailability.Rejected(preparedResult.reason, window.size),
                chart = rawChart(window, "RAW / DIAGNOSTIK: ${preparedResult.reason}"),
                rejectionReason = preparedResult.reason,
                inferenceTrace = rejectionTrace(window, preparedResult.reason, preparedResult.diagnostics),
                processingFingerprint = fingerprint
            )
            is PpgPreprocessResult.Ready -> evaluatePrepared(
                window = window,
                prepared = preparedResult.window,
                fingerprint = fingerprint
            )
        }

        synchronized(lock) {
            cache[window.type] = result
        }
        return result
    }

    /** Renders actual samples for acquisition preview; never used for inference. */
    fun preview(window: WindowSnapshot): PpgChartData = chartProjector.preview(window, "PREVIEW / bukan input model")

    /** Renders the cleaned core without running filtering or inference again. */
    fun chartForPrepared(prepared: PreparedPpgWindow): PpgChartData = chartProjector.cleaned(
        window = prepared,
        message = "CLEANED_CORE / 30–270s",
        mode = PpgChartMode.CLEANED_CORE,
        restrictToCore = true
    )

    /** Evaluates a prepared model window; preprocessing is intentionally skipped. */
    fun processPrepared(prepared: PreparedPpgWindow): PpgProcessingSnapshot {
        val fingerprint = preparedFingerprint(prepared)
        synchronized(lock) {
            cache[prepared.type]?.takeIf { it.processingFingerprint == fingerprint }?.let { return it }
        }
        val result = evaluatePrepared(
            channel = prepared.type,
            rawSampleCount = prepared.rawSampleCount,
            timestampsMillis = prepared.timestampsMillis,
            prepared = prepared,
            fingerprint = fingerprint,
            chartMode = PpgChartMode.CLEANED_PARTIAL
        )
        synchronized(lock) {
            cache[prepared.type] = result
        }
        return result
    }

    fun clear() = synchronized(lock) { cache.clear() }

    private fun evaluatePrepared(window: WindowSnapshot, prepared: PreparedPpgWindow, fingerprint: String): PpgProcessingSnapshot =
        evaluatePrepared(
            channel = window.type,
            rawSampleCount = window.size,
            timestampsMillis = window.timestampsMillis,
            prepared = prepared,
            fingerprint = fingerprint,
            chartMode = PpgChartMode.CLEANED_PARTIAL
        )

    private fun evaluatePrepared(
        channel: PpgType,
        rawSampleCount: Int,
        timestampsMillis: LongArray,
        prepared: PreparedPpgWindow,
        fingerprint: String,
        chartMode: PpgChartMode
    ): PpgProcessingSnapshot {
        val quality = qualityGate.evaluate(prepared)
        if (!quality.isUsable) {
            return PpgProcessingSnapshot(
                channel = channel,
                rawSampleCount = rawSampleCount,
                durationMillis = durationMillis(timestampsMillis),
                preparedWindow = prepared,
                quality = quality,
                availability = PredictionAvailability.Rejected(quality.reason, prepared.size),
                chart = cleanChart(prepared, quality, quality.reason, chartMode),
                rejectionReason = quality.reason,
                processingFingerprint = fingerprint
            )
        }

        val inferenceResult = runtime.infer(prepared, quality.tier)
        val availability = if (
            inferenceResult.status == InferenceStatus.VALID &&
            inferenceResult.stressProbability != null
        ) {
            val probability = inferenceResult.calibratedStressProbability
                ?: inferenceResult.stressProbability
            val decisionPolicy = inferenceResult.manifestDecisionPolicyOrNull()
            if (decisionPolicy == null) {
                PredictionAvailability.InferenceError("model_decision_policy_unavailable")
            } else if (probability != null && probability.isFinite() && probability in 0f..1f) {
                val confidence = CanonicalPredictionConfidenceCalculator.calculate(
                    stressProbability = probability,
                    qualityScore = quality.qualityScore
                )
                PredictionAvailability.Ready(
                    StressPrediction(
                        sensor = channel,
                        label = decisionPolicy.labelFor(probability).value,
                        stressProbability = probability,
                        confidence = confidence.value,
                        quality = quality,
                        generatedAtMillis = prepared.timestampsMillis.lastOrNull()
                            ?: System.currentTimeMillis(),
                        decisionPolicy = decisionPolicy,
                        modelVersion = inferenceResult.modelVersion,
                        rawStressProbability = inferenceResult.rawStressProbability,
                        performanceTier = inferenceResult.performanceTier,
                        researchDisclaimer = inferenceResult.researchDisclaimer,
                        inferenceTrace = inferenceResult.trace
                    )
                )
            } else {
                PredictionAvailability.InferenceError("onnx_probability_invalid")
            }
        } else {
            PredictionAvailabilityMapper.from(inferenceResult, prepared.size)
        }
        val reason = (availability as? PredictionAvailability.Ready)?.let { null }
            ?: reasonOf(availability)
        return PpgProcessingSnapshot(
            channel = channel,
            rawSampleCount = rawSampleCount,
            durationMillis = durationMillis(timestampsMillis),
            preparedWindow = prepared,
            quality = quality,
            availability = availability,
            chart = cleanChart(prepared, quality, reason, chartMode),
            rejectionReason = reason,
            inferenceTrace = inferenceResult.trace,
            processingFingerprint = fingerprint
        )
    }

    private fun rawChart(window: WindowSnapshot, message: String, mode: PpgChartMode = PpgChartMode.RAW_DIAGNOSTIC): PpgChartData =
        chartProjector.preview(window, message, mode)

    private fun cleanChart(
        prepared: PreparedPpgWindow,
        quality: SignalQualitySummary?,
        message: String?,
        mode: PpgChartMode = PpgChartMode.CLEANED_PARTIAL,
        fullWindow: Boolean = false
    ): PpgChartData = chartProjector.cleaned(
        window = prepared,
        message = message ?: "CLEANED / ${quality?.tier ?: "unassessed"}",
        mode = mode,
        restrictToCore = fullWindow
    ).copy(qualityTier = quality?.tier)

    private fun durationMillis(timestampsMillis: LongArray): Long = if (timestampsMillis.size < 2) {
        0L
    } else {
        (timestampsMillis.last() - timestampsMillis.first()).coerceAtLeast(0L)
    }

    private fun fingerprint(window: WindowSnapshot): String = buildString {
        append(window.type.assetSuffix)
        append(':').append(window.size)
        append(':').append(window.timestampsMillis.firstOrNull() ?: 0L)
        append(':').append(window.timestampsMillis.lastOrNull() ?: 0L)
        append(':').append(window.rawValues.contentHashCode())
    }

    private fun preparedFingerprint(window: PreparedPpgWindow): String = buildString {
        append(window.type.assetSuffix)
        append(':').append(window.size)
        append(':').append(window.timestampsMillis.firstOrNull() ?: 0L)
        append(':').append(window.timestampsMillis.lastOrNull() ?: 0L)
        append(':').append(window.filteredValues.contentHashCode())
        append(':').append(window.interpolatedMask.contentHashCode())
        append(':').append(window.observedMask.contentHashCode())
        append(':').append(window.repairedShortGapMask.contentHashCode())
        append(':').append(window.gridResampledMask.contentHashCode())
    }

    private fun reasonOf(availability: PredictionAvailability): String = when (availability) {
        is PredictionAvailability.WarmingUp -> "window_incomplete"
        is PredictionAvailability.Rejected -> availability.reason
        is PredictionAvailability.Unavailable -> availability.reason
        is PredictionAvailability.InferenceError -> availability.reason
        is PredictionAvailability.Ready -> ""
    }

    @Suppress("CyclomaticComplexMethod")
    private fun rejectionTrace(window: WindowSnapshot, reason: String, diagnostics: PpgRejectionDiagnostics?): StressInferenceTrace {
        val contract = runtime.modelContract(window.type)
        return StressInferenceTrace(
            manifestFingerprint = contract?.manifestFingerprint.orEmpty(),
            sensorChannel = window.type.assetSuffix,
            featureOrder = contract?.featureOrder.orEmpty(),
            featureCount = contract?.featureOrder?.size ?: 0,
            sampleCount = window.size,
            sourceRateHz = diagnostics?.sourceRateHz
                ?: contract?.sourceRateHz
                ?: window.type.sourceRateHz.toDouble(),
            modelRateHz = diagnostics?.modelRateHz
                ?: contract?.modelRateHz
                ?: window.type.modelRateHz.toDouble(),
            rawProbability = null,
            calibratedProbability = null,
            calibrationVersion = null,
            threshold = null,
            finalLabel = null,
            qualityTier = "rejected",
            sessionId = diagnostics?.sessionId ?: window.sessionId,
            windowId = diagnostics?.windowId ?: window.windowId,
            trackerContract = "PDSATU_LITERAL_${window.type.name}",
            acceptedSampleCount = diagnostics?.acceptedSampleCount ?: window.size,
            rejectedSampleCount = diagnostics?.rejectedSampleCount ?: 0,
            duplicateSampleCount = diagnostics?.duplicateSampleCount ?: 0,
            sequenceLossCount = diagnostics?.sequenceLossCount ?: 0L,
            observedSampleCount = diagnostics?.observedSampleCount ?: window.size,
            repairedShortGapSampleCount = 0,
            trueGapCount = diagnostics?.trueGapCount ?: 0,
            unresolvedSampleCount = diagnostics?.unresolvedSampleCount ?: 0,
            gridResampledSampleCount = diagnostics?.gridResampledSampleCount ?: 0,
            observedStartMillis = diagnostics?.observedStartMillis,
            observedEndMillis = diagnostics?.observedEndMillis,
            coverageMillis = diagnostics?.coverageMillis ?: 0L,
            observedCadenceHz = diagnostics?.observedCadenceHz,
            maxGapMillis = diagnostics?.maxGapMillis ?: 0L,
            preprocessingVersion = contract?.preprocessingVersion,
            featureVersion = contract?.featureVersion,
            filterAlgorithm = diagnostics?.filterAlgorithm ?: contract?.filterContract?.algorithm,
            filterOrder = diagnostics?.filterOrder ?: contract?.filterContract?.order,
            filterLowCutHz = diagnostics?.filterLowCutHz ?: contract?.filterContract?.lowCutHz,
            filterHighCutHz = diagnostics?.filterHighCutHz ?: contract?.filterContract?.highCutHz,
            filterContractFingerprint = diagnostics?.filterContractFingerprint
                ?: contract?.filterContract?.fingerprint,
            rejectionReason = reason
        )
    }
}

internal fun normalizedChartValues(values: FloatArray, min: Float, max: Float): FloatArray {
    val range = max - min
    if (!range.isFinite() || range <= 0f) return FloatArray(values.size) { 0.5f }
    return FloatArray(values.size) { index -> ((values[index] - min) / range).coerceIn(0f, 1f) }
}

internal fun chartGapThresholdMillis(timestampsMillis: LongArray): Long {
    if (timestampsMillis.size < 2) return 500L
    val deltas = LongArray(timestampsMillis.size - 1)
    var count = 0
    for (index in 1 until timestampsMillis.size) {
        val delta = timestampsMillis[index] - timestampsMillis[index - 1]
        if (delta > 0L) deltas[count++] = delta
    }
    if (count == 0) return 500L
    val sorted = deltas.copyOf(count).apply { sort() }
    return maxOf(500L, sorted[count / 2] * 5L)
}
