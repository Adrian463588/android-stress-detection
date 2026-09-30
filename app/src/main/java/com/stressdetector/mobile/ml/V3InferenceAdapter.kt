package com.stressdetector.mobile.ml

import android.content.Context
import com.stressdetector.inference.AndroidAssetModelArtifactRepository
import com.stressdetector.inference.InferenceResult
import com.stressdetector.inference.InferenceStatus
import com.stressdetector.inference.InferenceTraceContext
import com.stressdetector.inference.ModelArtifactRepository
import com.stressdetector.inference.ModelArtifactResult
import com.stressdetector.inference.ModelContractStatus
import com.stressdetector.inference.ModelSessionContract
import com.stressdetector.inference.StressInferenceEngine
import com.stressdetector.inference.StressModelReadiness
import com.stressdetector.mobile.signal.ExtractedFeatures
import com.stressdetector.shared.PpgType

/**
 * Per-channel adapter that keeps artifact loading independent from native
 * session creation. The session is created only on the background inference
 * path, where its concrete readiness can be reported safely.
 */
class V3InferenceAdapter(private val artifactRepository: ModelArtifactRepository, private val type: PpgType) : AutoCloseable {
    constructor(context: Context, type: PpgType) : this(
        AndroidAssetModelArtifactRepository(context.assets),
        type
    )

    private val artifactResult = artifactRepository.load(type.assetSuffix)
    private val engineLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        (artifactResult as? ModelArtifactResult.Ready)?.resources?.let(::StressInferenceEngine)
    }
    private val engine: StressInferenceEngine? get() = engineLazy.value

    val artifactReadiness: StressModelReadiness
        get() = when (val result = artifactResult) {
            is ModelArtifactResult.Ready -> StressModelReadiness.Ready(result.resources.manifest.model_version)
            is ModelArtifactResult.Unavailable -> StressModelReadiness.ArtifactInvalid(result.reason)
            is ModelArtifactResult.Invalid -> StressModelReadiness.ArtifactInvalid(result.reason)
        }

    val readiness: StressModelReadiness
        get() = engine?.readiness ?: artifactReadiness

    val sessionContractStatus: ModelContractStatus
        get() = when (val result = artifactResult) {
            is ModelArtifactResult.Ready -> result.resources.manifest.sessionContractStatus(
                result.resources.detectorContract
            )
            is ModelArtifactResult.Unavailable -> ModelContractStatus.Unavailable(result.reason)
            is ModelArtifactResult.Invalid -> ModelContractStatus.Unavailable(result.reason)
        }

    val sessionContract: ModelSessionContract?
        get() = (sessionContractStatus as? ModelContractStatus.Ready)?.contract

    @Suppress("ReturnCount")
    fun infer(
        extracted: ExtractedFeatures,
        qualityTier: String,
        sampleCount: Int = 0,
        traceContext: InferenceTraceContext? = null
    ): InferenceResult {
        val currentEngine = engine ?: return rejectedForReadiness(qualityTier)
        if (!extracted.isSuccess || extracted.values.isEmpty()) {
            return rejected(
                qualityTier = qualityTier,
                status = InferenceStatus.INVALID_QUALITY,
                reason = extracted.errorMessage ?: "feature_extraction_failed"
            )
        }

        val resources = (artifactResult as? ModelArtifactResult.Ready)?.resources
            ?: return rejected(
                qualityTier = qualityTier,
                status = InferenceStatus.MODEL_ARTIFACT_INVALID,
                reason = "model_resources_unavailable"
            )
        val featureOrder = resources.manifest.feature_order
        if (featureOrder.isEmpty()) {
            return rejected(
                qualityTier = qualityTier,
                status = InferenceStatus.SCHEMA_MISMATCH,
                reason = "canonical_feature_order_missing"
            )
        }
        val resolved = featureOrder.associateWith { feature ->
            if (feature == "pnn50" && resources.manifest.feature_version.startsWith("v4.")) {
                extracted.values["pnn50_observed_adjacent"]
            } else {
                extracted.values[feature]
            }
        }
        val missingFeatures = resolved.filterValues { it == null }.keys
        if (missingFeatures.isNotEmpty()) {
            return rejected(
                qualityTier = qualityTier,
                status = InferenceStatus.SCHEMA_MISMATCH,
                reason = "canonical_feature_schema_mismatch"
            )
        }
        val ordered = featureOrder.map { feature -> requireNotNull(resolved[feature]) }
        if (ordered.any { !it.isFinite() }) {
            return rejected(
                qualityTier = qualityTier,
                status = InferenceStatus.SCHEMA_MISMATCH,
                reason = "canonical_feature_non_finite"
            )
        }
        return currentEngine.inferFeatures(
            features = ordered.toFloatArray(),
            qualityTier = qualityTier,
            sampleCount = sampleCount,
            traceContext = traceContext
        )
    }

    override fun close() {
        if (engineLazy.isInitialized()) engineLazy.value?.close()
    }

    private fun rejectedForReadiness(qualityTier: String): InferenceResult = when (val state = readiness) {
        is StressModelReadiness.ArtifactInvalid -> rejected(
            qualityTier = qualityTier,
            status = InferenceStatus.MODEL_ARTIFACT_INVALID,
            reason = state.code
        )
        is StressModelReadiness.RuntimeUnavailable -> rejected(
            qualityTier = qualityTier,
            status = InferenceStatus.RUNTIME_UNAVAILABLE,
            reason = state.code
        )
        is StressModelReadiness.PreflightFailed -> rejected(
            qualityTier = qualityTier,
            status = InferenceStatus.PREFLIGHT_FAILED,
            reason = state.code
        )
        is StressModelReadiness.Ready -> rejected(
            qualityTier = qualityTier,
            status = InferenceStatus.RUNTIME_UNAVAILABLE,
            reason = "onnx_classifier_unavailable"
        )
    }

    private fun rejected(qualityTier: String, status: InferenceStatus, reason: String): InferenceResult = InferenceResult(
        label = null,
        stressProbability = null,
        qualityTier = qualityTier,
        latencyMs = 0.0,
        modelVersion = (artifactResult as? ModelArtifactResult.Ready)?.resources?.manifest?.model_version
            ?: "unavailable",
        status = status,
        rejectionReason = reason
    )
}
