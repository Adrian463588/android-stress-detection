package com.stressdetector.inference

import com.stressdetector.shared.PpgSamplingPolicy
import com.stressdetector.shared.StressInferenceTrace
import com.stressdetector.shared.StressLabel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.system.measureNanoTime

@Suppress("TooManyFunctions")
class StressInferenceEngine(
    private val resources: ModelResources?,
    private val classifierFactory: (ModelResources) -> StressClassifier = {
        OnnxStressClassifier(it)
    }
) : AutoCloseable {
    private data class InferenceInput(
        val features: FloatArray,
        val qualityTier: String,
        val sampleCount: Int,
        val traceContext: InferenceTraceContext?
    )

    private data class InferenceContext(
        val manifest: ModelManifest,
        val classifier: StressClassifier,
        val calibrator: ProbabilityCalibrator
    )

    private val initialization = initialize()

    val readiness: StressModelReadiness
        get() = initialization.readiness

    val preflightReceipt: ModelPreflightReceipt?
        get() = initialization.preflightReceipt

    fun inferFeatures(
        features: FloatArray,
        qualityTier: String,
        sampleCount: Int = features.size,
        traceContext: InferenceTraceContext? = null
    ): InferenceResult {
        val manifest = resources?.manifest
        val classifier = initialization.classifier
        val calibrator = initialization.calibrator
        return when {
            qualityTier == "poor" || qualityTier == "invalid" ->
                rejected(InferenceStatus.INVALID_QUALITY, "invalid_quality", qualityTier)
            manifest == null ->
                rejected(InferenceStatus.MODEL_ARTIFACT_INVALID, "model_resources_unavailable", qualityTier)
            classifier == null || calibrator == null -> notReady(qualityTier)
            features.size != manifest.feature_order.size ->
                rejected(InferenceStatus.SCHEMA_MISMATCH, "schema_mismatch", qualityTier)
            else -> inferValidated(
                InferenceInput(features, qualityTier, sampleCount, traceContext),
                InferenceContext(manifest, classifier, calibrator)
            )
        }
    }

    private fun inferValidated(input: InferenceInput, context: InferenceContext): InferenceResult {
        val scaledFeatures = scaleFeatures(input.features, context.manifest)
        var probability = Float.NaN
        val elapsed = measureNanoTime {
            probability = context.classifier.predict(scaledFeatures)
        } / 1_000_000.0
        return when {
            !probability.isFinite() ->
                rejected(InferenceStatus.INVALID_QUALITY, "onnx_inference_nan", input.qualityTier)
            probability !in 0f..1f ->
                rejected(InferenceStatus.INVALID_QUALITY, "onnx_probability_out_of_range", input.qualityTier)
            else -> validInference(input, context, probability, elapsed)
        }
    }

    private fun scaleFeatures(features: FloatArray, manifest: ModelManifest): FloatArray =
        if (manifest.scaler_mean != null && manifest.scaler_scale != null) {
            FloatArray(features.size) { index ->
                val mean = manifest.scaler_mean[index].toFloat()
                val scale = manifest.scaler_scale[index].toFloat()
                if (scale > 0f) (features[index] - mean) / scale else features[index]
            }
        } else {
            features
        }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun validInference(input: InferenceInput, context: InferenceContext, probability: Float, elapsed: Double): InferenceResult {
        val calibratedProbability = context.calibrator.calibrate(probability)
        val decisionPolicy = context.manifest.decisionPolicy()
        return InferenceResult(
            label = if (decisionPolicy.labelFor(calibratedProbability) == StressLabel.STRESS) 1 else 0,
            stressProbability = calibratedProbability,
            rawStressProbability = probability,
            calibratedStressProbability = calibratedProbability,
            calibrationVersion = context.calibrator.version,
            decisionPolicy = decisionPolicy,
            qualityTier = input.qualityTier,
            latencyMs = elapsed,
            modelVersion = context.manifest.model_version,
            performanceTier = context.manifest.performance_tier,
            researchDisclaimer = context.manifest.research_disclaimer.takeIf { context.manifest.disclaimer_required },
            trace = StressInferenceTrace(
                manifestFingerprint = context.manifest.candidate_bundle_sha256,
                sensorChannel = context.manifest.sensor_channel,
                featureOrder = context.manifest.feature_order,
                featureCount = input.features.size,
                featureValues = input.features.toList(),
                sampleCount = input.sampleCount.coerceAtLeast(0),
                sourceRateHz = input.traceContext?.sourceRateHz ?: context.manifest.source_rate_hz,
                modelRateHz = input.traceContext?.modelRateHz ?: context.manifest.model_rate_hz,
                samplingRatePolicy = input.traceContext?.samplingRatePolicy
                    ?: PpgSamplingPolicy.MANIFEST_FIXED_V1,
                rawProbability = probability,
                calibratedProbability = calibratedProbability,
                calibrationVersion = context.calibrator.version,
                threshold = decisionPolicy.probabilityThreshold,
                labelOrder = decisionPolicy.labelOrder,
                finalLabel = decisionPolicy.labelFor(calibratedProbability).value,
                qualityTier = input.qualityTier,
                sessionId = input.traceContext?.sessionId,
                windowId = input.traceContext?.windowId,
                trackerContract = input.traceContext?.trackerContract,
                acceptedSampleCount = input.traceContext?.acceptedSampleCount ?: input.sampleCount,
                rejectedSampleCount = input.traceContext?.rejectedSampleCount ?: 0,
                duplicateSampleCount = input.traceContext?.duplicateSampleCount ?: 0,
                sequenceLossCount = input.traceContext?.sequenceLossCount ?: 0L,
                observedSampleCount = input.traceContext?.observedSampleCount ?: input.sampleCount,
                repairedShortGapSampleCount = input.traceContext?.repairedShortGapSampleCount ?: 0,
                trueGapCount = input.traceContext?.trueGapCount ?: 0,
                unresolvedSampleCount = input.traceContext?.unresolvedSampleCount ?: 0,
                gridResampledSampleCount = input.traceContext?.gridResampledSampleCount ?: 0,
                observedStartMillis = input.traceContext?.observedStartMillis,
                observedEndMillis = input.traceContext?.observedEndMillis,
                coverageMillis = input.traceContext?.coverageMillis ?: 0L,
                observedCadenceHz = input.traceContext?.observedCadenceHz,
                maxGapMillis = input.traceContext?.maxGapMillis ?: 0L,
                preprocessingVersion = input.traceContext?.preprocessingVersion ?: context.manifest.preprocessing_version,
                featureVersion = input.traceContext?.featureVersion ?: context.manifest.feature_version,
                featureChecksum = input.traceContext?.featureChecksum ?: checksum(input.features),
                featureMinimum = input.features.minOrNull(),
                featureMaximum = input.features.maxOrNull(),
                scalerChecksum = scalerChecksum(context.manifest),
                filterAlgorithm = input.traceContext?.filterAlgorithm
                    ?: context.manifest.filter_algorithm,
                filterOrder = input.traceContext?.filterOrder
                    ?: context.manifest.filter_order,
                filterLowCutHz = input.traceContext?.filterLowCutHz
                    ?: context.manifest.filter_low_cut_hz,
                filterHighCutHz = input.traceContext?.filterHighCutHz
                    ?: context.manifest.filter_high_cut_hz,
                filterContractFingerprint = input.traceContext?.filterContractFingerprint
                    ?: context.manifest.filter_contract_fingerprint,
                detectorMethod = input.traceContext?.detectorMethod,
                detectorFingerprint = input.traceContext?.detectorFingerprint,
                peakChecksum = input.traceContext?.peakChecksum,
                troughChecksum = input.traceContext?.troughChecksum,
                peakCount = input.traceContext?.peakCount ?: 0,
                validIbiCount = input.traceContext?.validIbiCount ?: 0,
                adjacentIbiCount = input.traceContext?.adjacentIbiCount ?: 0,
                physiologicalIbiRatio = input.traceContext?.physiologicalIbiRatio,
                morphologySupport = input.traceContext?.morphologySupport ?: 0,
                qualityStatus = input.traceContext?.qualityStatus,
                rejectionReason = input.traceContext?.rejectionReason
            ),
            status = InferenceStatus.VALID
        )
    }

    fun validateRawWindow(window: RawPpgWindow): InferenceResult? {
        if (window.timestampDeltasMs.size != window.rawValues.size) {
            return rejected(InferenceStatus.SCHEMA_MISMATCH, "shape_mismatch", "invalid")
        }
        if (
            window.timestampDeltasMs.isEmpty() ||
            window.timestampDeltasMs.first() != 0.0 ||
            window.timestampDeltasMs.drop(1).any { !it.isFinite() || it <= 0.0 }
        ) {
            return rejected(InferenceStatus.INVALID_TIMESTAMP, "invalid_timestamp", "invalid")
        }
        if (window.rawValues.any { !it.isFinite() }) {
            return rejected(InferenceStatus.INVALID_QUALITY, "non_finite_raw", "invalid")
        }
        return null
    }

    private fun rejected(status: InferenceStatus, reason: String, qualityTier: String) = InferenceResult(
        label = null,
        stressProbability = null,
        rawStressProbability = null,
        calibratedStressProbability = null,
        calibrationVersion = null,
        confidence = null,
        qualityTier = qualityTier,
        latencyMs = 0.0,
        modelVersion = resources?.manifest?.model_version ?: "unavailable",
        status = status,
        rejectionReason = reason
    )

    private fun initialize(): EngineInitialization {
        val currentResources =
            resources ?: return EngineInitialization(
                classifier = null,
                calibrator = null,
                preflightReceipt = null,
                readiness = StressModelReadiness.ArtifactInvalid("model_resources_unavailable")
            )
        val createdCalibrator =
            try {
                ProbabilityCalibrator(currentResources.manifest.probability_calibration)
            } catch (_: IllegalArgumentException) {
                return EngineInitialization(
                    classifier = null,
                    calibrator = null,
                    preflightReceipt = null,
                    readiness = StressModelReadiness.ArtifactInvalid("invalid_probability_calibration")
                )
            }
        val createdClassifier =
            try {
                classifierFactory(currentResources)
            } catch (_: Throwable) {
                return EngineInitialization(
                    classifier = null,
                    calibrator = null,
                    preflightReceipt = null,
                    readiness = StressModelReadiness.RuntimeUnavailable("onnx_session_init_failed")
                )
            }
        val preflightVectors = currentResources.preflightVectors
        if (preflightVectors.isEmpty()) {
            createdClassifier.close()
            return EngineInitialization(
                classifier = null,
                calibrator = null,
                preflightReceipt = null,
                readiness = StressModelReadiness.PreflightFailed("onnx_preflight_vectors_missing")
            )
        }
        val maxAbsDiff =
            try {
                preflightVectors.maxOf { preflight ->
                    val actual = createdClassifier.predict(preflight.input)
                    if (!actual.isFinite()) error("onnx_preflight_output_non_finite")
                    kotlin.math.abs(actual - preflight.expectedProbability)
                }
            } catch (error: Throwable) {
                createdClassifier.close()
                val reason =
                    error.message?.takeIf { it == "onnx_preflight_output_non_finite" }
                        ?: "onnx_preflight_execution_failed"
                return EngineInitialization(
                    classifier = null,
                    calibrator = null,
                    preflightReceipt = null,
                    readiness = StressModelReadiness.PreflightFailed(reason)
                )
            }
        val receipt = ModelPreflightReceipt(preflightVectors.size, maxAbsDiff)
        if (maxAbsDiff >= PREFLIGHT_TOLERANCE) {
            createdClassifier.close()
            return EngineInitialization(
                classifier = null,
                calibrator = null,
                preflightReceipt = receipt,
                readiness = StressModelReadiness.PreflightFailed("onnx_preflight_parity_failed")
            )
        }
        return EngineInitialization(
            classifier = createdClassifier,
            calibrator = createdCalibrator,
            preflightReceipt = receipt,
            readiness = StressModelReadiness.Ready(currentResources.manifest.model_version)
        )
    }

    private fun notReady(qualityTier: String): InferenceResult = when (val state = readiness) {
        is StressModelReadiness.ArtifactInvalid ->
            rejected(
                InferenceStatus.MODEL_ARTIFACT_INVALID,
                state.code,
                qualityTier
            )
        is StressModelReadiness.RuntimeUnavailable ->
            rejected(
                InferenceStatus.RUNTIME_UNAVAILABLE,
                state.code,
                qualityTier
            )
        is StressModelReadiness.PreflightFailed ->
            rejected(
                InferenceStatus.PREFLIGHT_FAILED,
                state.code,
                qualityTier
            )
        is StressModelReadiness.Ready ->
            rejected(
                InferenceStatus.RUNTIME_UNAVAILABLE,
                "onnx_classifier_unavailable",
                qualityTier
            )
    }

    override fun close() {
        initialization.classifier?.close()
    }

    private fun checksum(features: FloatArray): String = sha256(
        ByteBuffer.allocate(features.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { features.forEach(::putFloat) }
            .array()
    )

    @Suppress("ReturnCount")
    private fun scalerChecksum(manifest: ModelManifest): String? {
        val mean = manifest.scaler_mean ?: return null
        val scale = manifest.scaler_scale ?: return null
        return sha256((mean + scale).joinToString(",").toByteArray(Charsets.UTF_8))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class EngineInitialization(
        val classifier: StressClassifier?,
        val calibrator: ProbabilityCalibrator?,
        val preflightReceipt: ModelPreflightReceipt?,
        val readiness: StressModelReadiness
    )

    private companion object {
        const val PREFLIGHT_TOLERANCE = 1.0e-5f
    }
}
