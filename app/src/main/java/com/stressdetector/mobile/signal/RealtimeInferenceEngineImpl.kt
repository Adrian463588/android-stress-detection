package com.stressdetector.mobile.signal

import android.content.Context
import android.util.Log
import com.stressdetector.inference.InferenceResult
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.domain.PredictionAvailability
import com.stressdetector.mobile.domain.PredictionAvailabilityMapper
import com.stressdetector.mobile.domain.engine.DivergenceEpisodeTracker
import com.stressdetector.mobile.domain.engine.DivergenceEvent
import com.stressdetector.mobile.domain.engine.LiveChannelState
import com.stressdetector.mobile.domain.engine.LiveMonitorStartResult
import com.stressdetector.mobile.domain.engine.LiveMonitorStartResultProvider
import com.stressdetector.mobile.domain.engine.LiveMonitorState
import com.stressdetector.mobile.domain.engine.LiveMonitorStopSummary
import com.stressdetector.mobile.domain.engine.PhysiologicalMarkerType
import com.stressdetector.mobile.domain.engine.RealtimeInferenceEngine
import com.stressdetector.mobile.ml.StressInferenceRuntime
import com.stressdetector.shared.CanonicalPredictionConfidenceCalculator
import com.stressdetector.shared.PpgFilterContract
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.StressPrediction
import java.util.UUID
import kotlin.math.ceil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "RealtimeInference"

class RealtimeInferenceEngineImpl(
    private val context: Context,
    private val inferenceRuntimeProvider: (() -> StressInferenceRuntime)? = null,
    private val closeRuntimeOnStop: Boolean = true,
    private val contractProvider: () -> LiveMonitorRuntimeContract = {
        AssetLiveMonitorRuntimeContract(context).load()
    }
) : RealtimeInferenceEngine,
    LiveMonitorStartResultProvider {
    private val mutableState = MutableStateFlow(LiveMonitorState())
    override val monitorState: StateFlow<LiveMonitorState> = mutableState.asStateFlow()

    private var activeSessionId: String? = null
    private var startedAtMillis: Long = 0L
    private var runtimeContract: LiveMonitorRuntimeContract? = null
    private var activeInferenceRuntime: StressInferenceRuntime? = null
    private var acceptedWindows = 0
    private var rejectedWindows = 0
    private var probabilitySum = 0.0
    private var divergenceCount = 0
    private val lastProcessedWindowTimestampMillis = mutableMapOf<PpgType, Long>()
    private val lastProcessedWindowEndElapsedMillis = mutableMapOf<PpgType, Long>()
    private val lastProcessedBpmTimestampMillis = mutableMapOf<PpgType, Long>()
    private val baselineProbabilitiesByChannel = mutableMapOf<PpgType, ArrayDeque<Float>>()
    private val latestProbabilitiesByChannel = mutableMapOf<PpgType, Float>()
    private val latestBaselinesByChannel = mutableMapOf<PpgType, Float>()
    private val arousalDivergenceDetectorsByChannel = mutableMapOf<PpgType, PhysiologicalArousalDivergenceDetector>()
    private val bpmPreprocessorsByChannel = mutableMapOf<PpgType, WearablePpgWindowPreprocessor>()
    private val bpmMarkerDetectorsByChannel = mutableMapOf<PpgType, BpmPhysiologicalMarkerDetector>()
    private val bpmSummaryAccumulatorsByChannel = mutableMapOf<PpgType, BpmSummaryAccumulator>()
    private var bpmEstimator = PpgBpmEstimator(windowSeconds = 30)
    private var divergenceEpisodeTracker = DivergenceEpisodeTracker(10_000L)

    override fun startMonitoring(sessionId: String): Boolean = startMonitoringResult(sessionId) is LiveMonitorStartResult.Started

    /** Typed start path used by the Live Monitor facade; the legacy Boolean interface remains a bridge. */
    override fun startMonitoringResult(sessionId: String): LiveMonitorStartResult {
        if (sessionId.isBlank()) {
            return failedStart("Session id must not be blank")
        }
        if (activeSessionId != null || mutableState.value.isMonitoring) {
            return failedStart("Live Monitor is already started")
        }

        val contract = try {
            contractProvider()
        } catch (error: Exception) {
            return unavailableStart(
                reason = error.message ?: "Live Monitor model contract is unavailable",
                cause = error
            )
        }
        // PPG arousal is a separate raw-signal capability. A model-unready
        // channel must not prevent BPM snapshots/markers from running.
        val inferenceRuntime = if (contract.modelReadyChannels.isEmpty()) {
            null
        } else {
            val candidate = runCatching {
                inferenceRuntimeProvider?.invoke() ?: StressInferenceRuntime(context.applicationContext)
            }.getOrNull()
            if (candidate == null) {
                null
            } else if (runCatching { candidate.requireConfigured(contract.modelReadyChannels) }.isSuccess) {
                candidate
            } else {
                if (closeRuntimeOnStop) runCatching { candidate.close() }
                null
            }
        }

        runtimeContract = contract
        activeInferenceRuntime = inferenceRuntime
        activeSessionId = sessionId
        startedAtMillis = System.currentTimeMillis()
        acceptedWindows = 0
        rejectedWindows = 0
        probabilitySum = 0.0
        divergenceCount = 0
        baselineProbabilitiesByChannel.clear()
        latestProbabilitiesByChannel.clear()
        latestBaselinesByChannel.clear()
        arousalDivergenceDetectorsByChannel.clear()
        bpmPreprocessorsByChannel.clear()
        bpmMarkerDetectorsByChannel.clear()
        bpmSummaryAccumulatorsByChannel.clear()
        bpmEstimator = PpgBpmEstimator(contract.config.bpmWindowSeconds)
        lastProcessedWindowTimestampMillis.clear()
        lastProcessedWindowEndElapsedMillis.clear()
        lastProcessedBpmTimestampMillis.clear()
        divergenceEpisodeTracker = DivergenceEpisodeTracker(contract.config.markerMergeGapSeconds * 1_000L)
        Log.i(
            TAG,
            "Monitoring started for session $sessionId with primary=${contract.primaryChannel.assetSuffix}, fallback=${contract.fallbackChannel.assetSuffix}"
        )
        mutableState.value = LiveMonitorState(
            isMonitoring = true,
            currentQualityScore = 0f,
            isWindowValid = false,
            requiredChannels = contract.requiredChannels,
            missingChannels = contract.requiredChannels,
            rejectionReason = "Waiting for complete manifest-declared window",
            channelStates = contract.requiredChannels.associateWith { channel ->
                LiveChannelState(
                    channel = channel,
                    availability = if (channel in contract.modelReadyChannels) {
                        val modelContract = requireNotNull(contract.modelContracts[channel])
                        PredictionAvailability.WarmingUp(
                            currentSamples = 0,
                            requiredSamples = ceil(modelContract.sourceRateHz * modelContract.windowSeconds).toInt()
                        )
                    } else {
                        PredictionAvailability.Unavailable("model_not_ready_android")
                    }
                )
            },
            predictionAvailability = if (contract.primaryChannel in contract.modelReadyChannels) {
                val modelContract = requireNotNull(contract.modelContracts[contract.primaryChannel])
                PredictionAvailability.WarmingUp(
                    currentSamples = 0,
                    requiredSamples = ceil(modelContract.sourceRateHz * modelContract.windowSeconds).toInt()
                )
            } else {
                PredictionAvailability.Unavailable("model_not_ready_android")
            }
        )
        return LiveMonitorStartResult.Started
    }

    override fun processPpgWindows(windows: Map<PpgType, WindowSnapshot>) {
        val sessionId = activeSessionId ?: return
        val contract = runtimeContract ?: return
        val inferenceRuntime = activeInferenceRuntime
        if (!mutableState.value.isMonitoring) return

        val channelStates = mutableState.value.channelStates.toMutableMap()
        val activeDivergences = mutableState.value.activeDivergences.toMutableList()
        var latestPrediction: StressPrediction? = mutableState.value.latestPrediction
        var latestTimestamp: Long? = null
        var latestElapsedMillis = 0L
        var latestQuality = mutableState.value.currentQualityScore
        val latestBpmByChannel = mutableState.value.latestBpmByChannel.toMutableMap()
        val bpmSummaryByChannel = mutableState.value.bpmSummaryByChannel.toMutableMap()
        var lastAvailability: PredictionAvailability = mutableState.value.predictionAvailability
        var lastRejection: String? = null
        val acceptedPredictions = mutableMapOf<PpgType, AcceptedLivePrediction>()

        fun appendMarker(candidate: DivergenceEvent) {
            val updated = divergenceEpisodeTracker.add(activeDivergences, candidate)
            if (updated.size > activeDivergences.size) divergenceCount++
            activeDivergences.clear()
            activeDivergences.addAll(updated)
        }

        contract.requiredChannels.sortedBy { it.ordinal }.forEach { channel ->
            val bpmResult = processBpmObservation(
                sessionId = sessionId,
                channel = channel,
                snapshot = windows[channel],
                contract = contract
            )
            bpmResult?.let { result ->
                latestBpmByChannel[channel] = result.estimate.bpm
                val accumulator = bpmSummaryAccumulatorsByChannel.getOrPut(channel) {
                    BpmSummaryAccumulator()
                }
                accumulator.add(result.estimate)
                accumulator.summary()?.let { summary -> bpmSummaryByChannel[channel] = summary }
            }
            bpmResult?.markers.orEmpty().forEach { event ->
                appendMarker(event)
            }

            if (inferenceRuntime == null || channel !in contract.modelReadyChannels) {
                val reason = "model_not_ready_android"
                channelStates[channel] = LiveChannelState(
                    channel = channel,
                    availability = PredictionAvailability.Unavailable(reason),
                    rejectionReason = reason
                )
                lastRejection = reason
                lastAvailability = PredictionAvailability.Unavailable(reason)
                return@forEach
            }

            val preparedResult = windows[channel]?.let(inferenceRuntime::prepare)
            val warmingUp = preparedResult as? PpgPreprocessResult.WarmingUp
            val prepared = (preparedResult as? PpgPreprocessResult.Ready)?.window
            if (prepared == null) {
                val reason = (preparedResult as? PpgPreprocessResult.Rejected)?.reason
                    ?: "Missing or incomplete ${channel.assetSuffix} window"
                val availability = warmingUp?.let {
                    PredictionAvailability.WarmingUp(
                        currentSamples = it.currentSamples,
                        requiredSamples = it.requiredSamples
                    )
                } ?: PredictionAvailability.Rejected(reason, windows[channel]?.size ?: 0)
                channelStates[channel] = LiveChannelState(channel, availability, rejectionReason = reason)
                lastRejection = reason
                lastAvailability = availability
                return@forEach
            }

            val quality = SignalQualityGate(contract.config).evaluate(prepared)
            val qualityScore = quality.qualityScore
            val windowTimestamp = prepared.timestampsMillis.lastOrNull()
            val windowElapsedMillis = prepared.sessionElapsedEndMillis.takeIf { it > 0L }
                ?: (elapsedSeconds(windowTimestamp) * 1_000L)
            latestTimestamp = maxOf(latestTimestamp ?: Long.MIN_VALUE, windowTimestamp ?: Long.MIN_VALUE)
            latestElapsedMillis = maxOf(latestElapsedMillis, windowElapsedMillis)
            latestQuality = qualityScore
            if (!quality.isUsable || qualityScore < contract.config.minimumQualityScore) {
                val reason = quality.reason
                channelStates[channel] = LiveChannelState(
                    channel = channel,
                    availability = PredictionAvailability.Rejected(reason, prepared.size),
                    qualityScore = qualityScore,
                    lastWindowEndElapsedMillis = windowElapsedMillis,
                    rejectionReason = reason
                )
                rejectedWindows++
                lastRejection = reason
                lastAvailability = PredictionAvailability.Rejected(reason, prepared.size)
                return@forEach
            }
            if (windowTimestamp != null && windowTimestamp <= (lastProcessedWindowTimestampMillis[channel] ?: Long.MIN_VALUE)) {
                return@forEach
            }
            val modelContract = requireNotNull(contract.modelContracts[channel])
            val previousWindowEnd = lastProcessedWindowEndElapsedMillis[channel]
            if (previousWindowEnd != null &&
                windowElapsedMillis - previousWindowEnd < modelContract.strideSeconds * 1_000L
            ) {
                return@forEach
            }
            if (windowTimestamp != null) lastProcessedWindowTimestampMillis[channel] = windowTimestamp
            lastProcessedWindowEndElapsedMillis[channel] = windowElapsedMillis

            val result = inferenceRuntime.infer(prepared, quality.tier)
            if (result.status != com.stressdetector.inference.InferenceStatus.VALID || result.stressProbability == null) {
                rejectedWindows++
                val reason = result.rejectionReason ?: result.status.name
                channelStates[channel] = LiveChannelState(
                    channel = channel,
                    availability = PredictionAvailabilityMapper.from(result, prepared.size),
                    qualityScore = qualityScore,
                    lastWindowEndElapsedMillis = windowElapsedMillis,
                    rejectionReason = reason
                )
                lastRejection = reason
                lastAvailability = PredictionAvailabilityMapper.from(result, prepared.size)
                return@forEach
            }

            val probability = validatedLiveStressProbability(result)
            if (probability == null) {
                val reason = "onnx_probability_invalid"
                rejectedWindows++
                channelStates[channel] = LiveChannelState(
                    channel = channel,
                    availability = PredictionAvailability.InferenceError(reason),
                    qualityScore = qualityScore,
                    lastWindowEndElapsedMillis = windowElapsedMillis,
                    rejectionReason = reason
                )
                lastRejection = reason
                lastAvailability = PredictionAvailability.InferenceError(reason)
                return@forEach
            }
            val decisionPolicy = result.manifestDecisionPolicyOrNull()
            if (decisionPolicy == null) {
                val reason = "model_decision_policy_unavailable"
                rejectedWindows++
                channelStates[channel] = LiveChannelState(
                    channel = channel,
                    availability = PredictionAvailability.InferenceError(reason),
                    qualityScore = qualityScore,
                    lastWindowEndElapsedMillis = windowElapsedMillis,
                    rejectionReason = reason
                )
                lastRejection = reason
                lastAvailability = PredictionAvailability.InferenceError(reason)
                return@forEach
            }
            val confidence = CanonicalPredictionConfidenceCalculator.calculate(probability, qualityScore)
            val baselineBuffer = baselineProbabilitiesByChannel.getOrPut(channel) { ArrayDeque() }
            val baseline = baselineBuffer.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: probability
            val detector = arousalDivergenceDetectorsByChannel.getOrPut(channel) {
                PhysiologicalArousalDivergenceDetector(contract.config)
            }
            baselineBuffer.addLast(probability)
            while (baselineBuffer.size > contract.config.baselineWindowCount) baselineBuffer.removeFirst()
            latestProbabilitiesByChannel[channel] = probability
            latestBaselinesByChannel[channel] = baseline

            val prediction = StressPrediction(
                sensor = channel,
                label = decisionPolicy.labelFor(probability).value,
                stressProbability = probability,
                confidence = confidence.value,
                quality = quality,
                generatedAtMillis = windowTimestamp ?: System.currentTimeMillis(),
                decisionPolicy = decisionPolicy,
                modelVersion = result.modelVersion,
                rawStressProbability = result.rawStressProbability,
                performanceTier = result.performanceTier,
                researchDisclaimer = result.researchDisclaimer,
                inferenceTrace = result.trace
            )
            latestPrediction = prediction
            acceptedWindows++
            probabilitySum += probability
            val elapsed = windowElapsedMillis
            if (detector.observe(probability, baseline, elapsed)) {
                val candidate = divergenceEpisodeTracker.candidate(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    channel = channel,
                    markerElapsedMillis = elapsed,
                    contextStartElapsedMillis = prepared.sessionElapsedStartMillis.takeIf { it > 0L }
                        ?: (elapsed - modelContract.windowSeconds * 1_000L)
                            .coerceAtLeast(0L),
                    contextEndElapsedMillis = elapsed,
                    currentProbability = probability,
                    baselineProbability = baseline,
                    confidence = confidence.value,
                    signalQuality = qualityScore,
                    markerType = PhysiologicalMarkerType.TEMPORAL_BASELINE_AROUSAL,
                    markerConfigVersion = contract.config.markerConfigVersion
                )
                appendMarker(candidate)
            }
            acceptedPredictions[channel] = AcceptedLivePrediction(
                prediction = prediction,
                timestampMillis = windowTimestamp ?: System.currentTimeMillis(),
                windowStartElapsedMillis = prepared.sessionElapsedStartMillis,
                windowEndElapsedMillis = windowElapsedMillis,
                qualityScore = qualityScore
            )
            channelStates[channel] = LiveChannelState(
                channel = channel,
                availability = PredictionAvailability.Ready(prediction),
                prediction = prediction,
                qualityScore = qualityScore,
                lastWindowEndElapsedMillis = windowElapsedMillis
            )
            lastAvailability = PredictionAvailability.Ready(prediction)
            lastRejection = null
        }

        appendChannelAgreementMarker(
            sessionId = sessionId,
            contract = contract,
            acceptedPredictions = acceptedPredictions,
            appendMarker = ::appendMarker
        )

        val selectedPrediction = acceptedPredictions[contract.primaryChannel]
            ?: acceptedPredictions[contract.fallbackChannel]
        if (selectedPrediction != null) {
            latestPrediction = selectedPrediction.prediction
        } else if (acceptedPredictions.isNotEmpty()) {
            latestPrediction = null
        }

        val missingChannels = contract.requiredChannels.filterTo(mutableSetOf()) { channel ->
            channelStates[channel]?.prediction == null
        }
        val isAnyValid = channelStates.values.any { it.prediction != null }
        val selectedBpm = latestBpmByChannel[contract.primaryChannel]
            ?: latestBpmByChannel[contract.fallbackChannel]
        mutableState.update {
            it.copy(
                elapsedSeconds = latestElapsedMillis / 1_000L,
                currentQualityScore = latestQuality,
                isWindowValid = isAnyValid,
                latestPrediction = latestPrediction,
                latestBpm = selectedBpm,
                latestBpmByChannel = latestBpmByChannel,
                bpmSummaryByChannel = bpmSummaryByChannel,
                activeDivergences = activeDivergences,
                missingChannels = missingChannels,
                rejectionReason = if (isAnyValid) null else lastRejection,
                latestWindowTimestampMs = latestTimestamp,
                predictionAvailability = lastAvailability,
                channelStates = channelStates
            )
        }
    }

    private fun appendChannelAgreementMarker(
        sessionId: String,
        contract: LiveMonitorRuntimeContract,
        acceptedPredictions: Map<PpgType, AcceptedLivePrediction>,
        appendMarker: (DivergenceEvent) -> Unit
    ) {
        val primary = acceptedPredictions[contract.primaryChannel] ?: return
        val fallback = acceptedPredictions[contract.fallbackChannel] ?: return
        val timestampDelta = kotlin.math.abs(primary.timestampMillis - fallback.timestampMillis)
        if (timestampDelta > contract.config.inferenceStepSeconds * 1_000L) return
        val probabilityDelta = kotlin.math.abs(
            primary.prediction.stressProbability - fallback.prediction.stressProbability
        )
        if (probabilityDelta < contract.config.channelAgreementDeltaMin) return

        val high = if (primary.prediction.stressProbability >= fallback.prediction.stressProbability) {
            primary
        } else {
            fallback
        }
        val low = if (primary.prediction.stressProbability <= fallback.prediction.stressProbability) {
            primary
        } else {
            fallback
        }
        val markerElapsedMillis = channelAgreementMarkerElapsedMillis(
            primary.windowEndElapsedMillis,
            fallback.windowEndElapsedMillis
        )
        appendMarker(
            divergenceEpisodeTracker.candidate(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                channel = contract.primaryChannel,
                markerElapsedMillis = markerElapsedMillis,
                contextStartElapsedMillis = minOf(
                    primary.windowStartElapsedMillis,
                    fallback.windowStartElapsedMillis
                ),
                contextEndElapsedMillis = markerElapsedMillis,
                currentProbability = high.prediction.stressProbability,
                baselineProbability = low.prediction.stressProbability,
                confidence = minOf(primary.prediction.confidence, fallback.prediction.confidence),
                signalQuality = minOf(primary.qualityScore, fallback.qualityScore),
                markerType = PhysiologicalMarkerType.CHANNEL_AGREEMENT,
                markerConfigVersion = contract.config.markerConfigVersion,
                isElevatedArousal = false,
                sourceChannels = setOf(contract.primaryChannel, contract.fallbackChannel),
                copyText = "Perbedaan probabilitas PPG Green/IR sebesar ${(probabilityDelta * 100).toInt()}%; model tetap independen"
            )
        )
    }

    override fun stopMonitoring(): LiveMonitorStopSummary? {
        val sessionId = activeSessionId ?: return null
        val summary = LiveMonitorStopSummary(
            sessionId = sessionId,
            startedAtMillis = startedAtMillis,
            endedAtMillis = System.currentTimeMillis(),
            acceptedWindowCount = acceptedWindows,
            rejectedWindowCount = rejectedWindows,
            divergenceCount = divergenceCount,
            averageStressProbability = if (acceptedWindows == 0) null else (probabilitySum / acceptedWindows).toFloat(),
            bpmSummaryByChannel = bpmSummaryAccumulatorsByChannel.mapNotNull { (channel, accumulator) ->
                accumulator.summary()?.let { channel to it }
            }.toMap()
        )
        val currentRuntime = activeInferenceRuntime
        activeInferenceRuntime = null
        activeSessionId = null
        runtimeContract = null
        lastProcessedWindowTimestampMillis.clear()
        lastProcessedWindowEndElapsedMillis.clear()
        lastProcessedBpmTimestampMillis.clear()
        latestProbabilitiesByChannel.clear()
        latestBaselinesByChannel.clear()
        arousalDivergenceDetectorsByChannel.clear()
        bpmPreprocessorsByChannel.clear()
        bpmMarkerDetectorsByChannel.clear()
        bpmSummaryAccumulatorsByChannel.clear()
        if (closeRuntimeOnStop) currentRuntime?.let { runCatching { it.close() } }
        mutableState.update { it.copy(isMonitoring = false) }
        return summary
    }

    private fun unavailableStart(reason: String, cause: Throwable? = null): LiveMonitorStartResult.Unavailable {
        runCatching { Log.w(TAG, "Live Monitor unavailable: $reason", cause) }
        clearFailedStartState(reason)
        return LiveMonitorStartResult.Unavailable(reason, cause)
    }

    private fun failedStart(reason: String, cause: Throwable? = null): LiveMonitorStartResult.Failed {
        runCatching { Log.e(TAG, "Live Monitor failed to start: $reason", cause) }
        clearFailedStartState(reason)
        return LiveMonitorStartResult.Failed(reason, cause)
    }

    private fun clearFailedStartState(reason: String) {
        if (closeRuntimeOnStop) activeInferenceRuntime?.let { runCatching { it.close() } }
        activeInferenceRuntime = null
        activeSessionId = null
        runtimeContract = null
        arousalDivergenceDetectorsByChannel.clear()
        bpmPreprocessorsByChannel.clear()
        bpmMarkerDetectorsByChannel.clear()
        lastProcessedBpmTimestampMillis.clear()
        mutableState.value = LiveMonitorState(
            isMonitoring = false,
            isWindowValid = false,
            errorMessage = reason,
            rejectionReason = "Runtime contract unavailable",
            predictionAvailability = PredictionAvailability.Unavailable(reason)
        )
    }

    private fun elapsedSeconds(sensorTimestampMs: Long?): Long {
        if (sensorTimestampMs == null || startedAtMillis <= 0L) return 0L
        return ((sensorTimestampMs - startedAtMillis).coerceAtLeast(0L) / 1000L)
    }

    /**
     * BPM uses an independent canonical 30-second window. It never waits for
     * the 180-second ONNX model window, but still filters/resamples once and
     * applies the same SQA gate before producing a marker.
     */
    @Suppress("LongMethod", "ReturnCount")
    private fun processBpmObservation(
        sessionId: String,
        channel: PpgType,
        snapshot: WindowSnapshot?,
        contract: LiveMonitorRuntimeContract
    ): BpmProcessingResult? {
        val detector = bpmMarkerDetectorsByChannel.getOrPut(channel) {
            BpmPhysiologicalMarkerDetector(contract.config)
        }
        fun unavailable(): BpmProcessingResult? {
            // A missing/invalid observation is a transport or SQA boundary;
            // never carry a rise episode across that boundary.
            detector.reset()
            return null
        }

        val raw = snapshot ?: return unavailable()
        val preprocessor = bpmPreprocessorsByChannel.getOrPut(channel) {
            WearablePpgWindowPreprocessor(
                contract = PpgPreprocessingContract(
                    windowSeconds = contract.config.bpmWindowSeconds,
                    sourceRateHz = channel.sourceRateHz.toDouble(),
                    modelRateHz = channel.modelRateHz.toDouble(),
                    filterContract = PpgFilterContract.forChannel(channel.assetSuffix)
                )
            )
        }
        val prepared = (preprocessor.prepare(raw) as? PpgPreprocessResult.Ready)?.window ?: return unavailable()
        val quality = SignalQualityGate(contract.config).evaluate(prepared)
        if (!quality.isUsable || quality.qualityScore < contract.config.minimumQualityScore) return unavailable()

        val timestamp = prepared.timestampsMillis.lastOrNull() ?: return unavailable()
        if (timestamp <= (lastProcessedBpmTimestampMillis[channel] ?: Long.MIN_VALUE)) return null
        val previousTimestamp = lastProcessedBpmTimestampMillis[channel]
        if (previousTimestamp != null &&
            timestamp - previousTimestamp >
            contract.config.inferenceStepSeconds * 1_000L + contract.config.maxTimestampGapDeltaMs.toLong()
        ) {
            lastProcessedBpmTimestampMillis[channel] = timestamp
            return unavailable()
        }
        lastProcessedBpmTimestampMillis[channel] = timestamp
        val estimate = bpmEstimator.estimate(prepared) ?: return unavailable()
        val probability = latestProbabilitiesByChannel[channel]
        val baselineProbability = latestBaselinesByChannel[channel]
        val markers = detector.observe(estimate).map { marker ->
            val markerType = when (marker.trend) {
                BpmMarkerTrend.RISING, BpmMarkerTrend.RECOVERING -> PhysiologicalMarkerType.BPM_TRAJECTORY
            }
            divergenceEpisodeTracker.candidate(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                channel = channel,
                markerElapsedMillis = marker.windowEndElapsedMillis,
                contextStartElapsedMillis = marker.windowStartElapsedMillis,
                contextEndElapsedMillis = marker.windowEndElapsedMillis,
                currentProbability = probability,
                baselineProbability = baselineProbability,
                confidence = quality.qualityScore,
                signalQuality = quality.qualityScore,
                markerType = markerType,
                episodeStartElapsedMillis = marker.episodeStartElapsedMillis,
                episodeEndElapsedMillis = marker.windowEndElapsedMillis,
                bpm = marker.bpm,
                baselineBpm = marker.baselineBpm,
                peakBpm = marker.peakBpm,
                bpmDelta = marker.deltaBpm,
                bpmTrend = marker.trend.name,
                markerConfigVersion = contract.config.markerConfigVersion,
                isElevatedArousal = false
            )
        }
        return BpmProcessingResult(estimate = estimate, markers = markers)
    }

    private data class BpmProcessingResult(val estimate: BpmEstimate, val markers: List<DivergenceEvent>)

    private data class AcceptedLivePrediction(
        val prediction: StressPrediction,
        val timestampMillis: Long,
        val windowStartElapsedMillis: Long,
        val windowEndElapsedMillis: Long,
        val qualityScore: Float
    )
}

internal fun channelAgreementMarkerElapsedMillis(primaryWindowEndElapsedMillis: Long, fallbackWindowEndElapsedMillis: Long): Long =
    maxOf(primaryWindowEndElapsedMillis, fallbackWindowEndElapsedMillis)

internal fun validatedLiveStressProbability(result: InferenceResult): Float? {
    val probability = result.calibratedStressProbability ?: result.stressProbability
    return probability?.takeIf { it.isFinite() && it in 0f..1f }
}
