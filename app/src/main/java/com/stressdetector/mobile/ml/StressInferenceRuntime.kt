package com.stressdetector.mobile.ml

import android.content.Context
import com.stressdetector.inference.AndroidAssetModelArtifactRepository
import com.stressdetector.inference.InferenceResult
import com.stressdetector.inference.InferenceStatus
import com.stressdetector.inference.InferenceTraceContext
import com.stressdetector.inference.ModelArtifactRepository
import com.stressdetector.inference.ModelContractStatus
import com.stressdetector.inference.ModelSessionContract
import com.stressdetector.inference.PpgDetectorContract
import com.stressdetector.inference.StressModelReadiness
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.signal.ExtractedFeatures
import com.stressdetector.mobile.signal.PpgFeatureExtractor
import com.stressdetector.mobile.signal.PpgPreprocessResult
import com.stressdetector.mobile.signal.PpgPreprocessingContract
import com.stressdetector.mobile.signal.PreparedPpgWindow
import com.stressdetector.mobile.signal.WearablePpgWindowPreprocessor
import com.stressdetector.shared.PpgType
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared inference boundary for Home and Live Monitor. It owns the only raw
 * wearable preprocessing path and the lifecycle of independently loaded ONNX
 * sessions, so UI layers cannot drift into model-specific behavior.
 */
interface CanonicalPpgRuntime {
    fun prepare(window: WindowSnapshot): PpgPreprocessResult

    /** Prepares the retained session once before core/model slicing. */
    fun prepareSession(window: WindowSnapshot): PpgPreprocessResult

    fun infer(preparedWindow: PreparedPpgWindow, qualityTier: String): InferenceResult

    /** Immutable per-channel window/feature contract from the accepted manifest. */
    fun modelContract(type: PpgType): ModelSessionContract? = null

    /** Typed contract gate for callers that need to explain model unavailability. */
    fun modelContractStatus(type: PpgType): ModelContractStatus = modelContract(type)?.let(ModelContractStatus::Ready)
        ?: ModelContractStatus.Unavailable("model_contract_unavailable")
}

class StressInferenceRuntime(
    context: Context,
    private val artifactRepository: ModelArtifactRepository =
        AndroidAssetModelArtifactRepository(context.applicationContext.assets)
) : CanonicalPpgRuntime,
    AutoCloseable {
    private val extractor = PpgFeatureExtractor()
    private val adapters = ConcurrentHashMap<PpgType, V3InferenceAdapter>()
    private val preprocessors = ConcurrentHashMap<PpgType, WearablePpgWindowPreprocessor>()
    private val preprocessorSignatures = ConcurrentHashMap<PpgType, String>()

    override fun prepare(window: WindowSnapshot): PpgPreprocessResult {
        val contract = modelContract(window.type)
            ?: return PpgPreprocessResult.Rejected("model_contract_unavailable")
        return preprocessorFor(window.type, contract).prepare(window)
    }

    override fun prepareSession(window: WindowSnapshot): PpgPreprocessResult {
        val contract = modelContract(window.type)
            ?: return PpgPreprocessResult.Rejected("model_contract_unavailable")
        return preprocessorFor(window.type, contract).prepareSession(window)
    }

    override fun modelContract(type: PpgType): ModelSessionContract? = adapterFor(type).sessionContract

    override fun modelContractStatus(type: PpgType): ModelContractStatus = adapterFor(type).sessionContractStatus

    fun artifactReadiness(type: PpgType): StressModelReadiness = adapterFor(type).artifactReadiness

    fun readiness(type: PpgType): StressModelReadiness = adapterFor(type).readiness

    fun requireConfigured(types: Set<PpgType>) {
        types.forEach { type ->
            // Configuration is a release gate, not an asset-existence check.
            // Force the adapter to create its ONNX session and run the packaged
            // preflight/parity vector before a live session can start.
            when (val state = readiness(type)) {
                is StressModelReadiness.Ready -> Unit
                is StressModelReadiness.ArtifactInvalid -> error(state.code)
                is StressModelReadiness.RuntimeUnavailable -> error(state.code)
                is StressModelReadiness.PreflightFailed -> error(state.code)
            }
        }
    }

    override fun infer(preparedWindow: PreparedPpgWindow, qualityTier: String): InferenceResult =
        if (!preparedWindow.type.isProductionChannel) {
            invalidInference(qualityTier, "unsupported_channel", "channel_not_allowed_for_home_inference")
        } else {
            val adapter = adapterFor(preparedWindow.type)
            val contract = adapter.sessionContract
            val detectorContract = contract?.detectorContract
            when {
                contract == null -> invalidInference(qualityTier, "unavailable", "PARITY_CONTRACT_MISMATCH")
                detectorContract == null -> {
                    invalidInference(qualityTier, contract.modelVersion, "PARITY_CONTRACT_MISMATCH")
                }
                else -> inferWithContract(preparedWindow, qualityTier, adapter, detectorContract)
            }
        }

    private fun inferWithContract(
        preparedWindow: PreparedPpgWindow,
        qualityTier: String,
        adapter: V3InferenceAdapter,
        detectorContract: PpgDetectorContract
    ): InferenceResult {
        val extracted = extractor.extract(preparedWindow, detectorContract)
        return adapter.infer(
            extracted = extracted,
            qualityTier = qualityTier,
            sampleCount = preparedWindow.rawSampleCount,
            traceContext = traceContext(preparedWindow, extracted)
        )
    }

    private fun traceContext(preparedWindow: PreparedPpgWindow, extracted: ExtractedFeatures): InferenceTraceContext =
        InferenceTraceContext(
            sourceRateHz = preparedWindow.sourceRateHz,
            modelRateHz = preparedWindow.modelRateHz,
            samplingRatePolicy = preparedWindow.samplingRatePolicy,
            sessionId = preparedWindow.sessionId,
            windowId = preparedWindow.windowId,
            trackerContract = "PDSATU_LITERAL_${preparedWindow.type.name}",
            acceptedSampleCount = preparedWindow.transportDiagnostics?.acceptedSampleCount?.toInt()
                ?: preparedWindow.rawSampleCount,
            rejectedSampleCount = preparedWindow.transportDiagnostics?.rejectedSampleCount?.toInt() ?: 0,
            duplicateSampleCount = preparedWindow.duplicateSampleCount,
            sequenceLossCount = preparedWindow.transportDiagnostics?.sequenceLossCount ?: 0L,
            observedSampleCount = preparedWindow.observedSampleCount,
            repairedShortGapSampleCount = preparedWindow.repairedShortGapSampleCount,
            trueGapCount = preparedWindow.trueGapCount,
            unresolvedSampleCount = preparedWindow.unresolvedSampleCount,
            gridResampledSampleCount = preparedWindow.gridResampledSampleCount,
            observedStartMillis = preparedWindow.sessionElapsedStartMillis,
            observedEndMillis = preparedWindow.sessionElapsedEndMillis,
            coverageMillis = (preparedWindow.sessionElapsedEndMillis - preparedWindow.sessionElapsedStartMillis)
                .coerceAtLeast(0L),
            observedCadenceHz = preparedWindow.observedCadenceHz,
            maxGapMillis = preparedWindow.maxObservedGapMillis,
            preprocessingVersion = preparedWindow.preprocessingVersion,
            featureVersion = preparedWindow.featureVersion,
            filterAlgorithm = preparedWindow.filterAlgorithm,
            filterOrder = preparedWindow.filterOrder,
            filterLowCutHz = preparedWindow.filterLowCutHz,
            filterHighCutHz = preparedWindow.filterHighCutHz,
            filterContractFingerprint = preparedWindow.filterContractFingerprint,
            detectorMethod = extracted.detectorMethod,
            detectorFingerprint = extracted.detectorFingerprint,
            peakChecksum = extracted.peakChecksum,
            troughChecksum = extracted.troughChecksum,
            peakCount = extracted.peakCount,
            validIbiCount = extracted.validIbiCount,
            adjacentIbiCount = extracted.adjacentIbiCount,
            physiologicalIbiRatio = extracted.physiologicalIbiRatio,
            morphologySupport = extracted.morphologySupport,
            qualityStatus = extracted.qualityStatus,
            rejectionReason = preparedWindow.rejectionReason
        )

    private fun invalidInference(qualityTier: String, modelVersion: String, reason: String): InferenceResult = InferenceResult(
        label = null,
        stressProbability = null,
        qualityTier = qualityTier,
        latencyMs = 0.0,
        modelVersion = modelVersion,
        status = InferenceStatus.MODEL_ARTIFACT_INVALID,
        rejectionReason = reason
    )

    fun unavailableReason(type: PpgType): String? = when (val state = readiness(type)) {
        is StressModelReadiness.Ready -> null
        is StressModelReadiness.ArtifactInvalid -> state.code
        is StressModelReadiness.RuntimeUnavailable -> state.code
        is StressModelReadiness.PreflightFailed -> state.code
    }

    override fun close() {
        adapters.values.forEach(V3InferenceAdapter::close)
        adapters.clear()
        preprocessors.clear()
        preprocessorSignatures.clear()
    }

    private fun adapterFor(type: PpgType): V3InferenceAdapter = adapters.computeIfAbsent(type) {
        V3InferenceAdapter(artifactRepository, it)
    }

    private fun preprocessorFor(type: PpgType, contract: ModelSessionContract): WearablePpgWindowPreprocessor {
        val signature = listOf(
            contract.modelVersion,
            contract.windowSeconds,
            contract.sourceRateHz,
            contract.modelRateHz,
            contract.preprocessingVersion,
            contract.featureVersion,
            contract.filterContract,
            contract.manifestFingerprint
        ).joinToString("|")
        return preprocessors.compute(type) { _, current ->
            if (current == null || preprocessorSignatures[type] != signature) {
                preprocessorSignatures[type] = signature
                WearablePpgWindowPreprocessor(
                    contract = PpgPreprocessingContract(
                        windowSeconds = contract.windowSeconds,
                        sourceRateHz = contract.sourceRateHz,
                        modelRateHz = contract.modelRateHz,
                        preprocessingVersion = contract.preprocessingVersion,
                        featureVersion = contract.featureVersion,
                        manifestFingerprint = contract.manifestFingerprint,
                        filterContract = contract.filterContract
                    )
                )
            } else {
                current
            }
        }!!
    }
}
