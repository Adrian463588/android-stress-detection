package com.stressdetector.inference

import com.stressdetector.shared.ModelDecisionPolicy
import com.stressdetector.shared.PpgFilterContract
import com.stressdetector.shared.PredictionConfidence
import com.stressdetector.shared.StressInferenceTrace

private const val DEVELOPMENT_PARTICIPANT_FIRST = 1
private const val DEVELOPMENT_PARTICIPANT_LAST = 28
private const val MIN_RESEARCH_VALIDATION_COHORT = 26
private val DEVELOPMENT_CALIBRATION_FIT_SOURCES =
    setOf("participant_grouped_development_oof", "nested_outer_development_oof")
private val DEVELOPMENT_CALIBRATION_STATUSES =
    setOf("validated_development_oof", "validated_nested_outer_oof")

data class RawPpgWindow(
    val sensorChannel: String,
    val samplingRateHz: Double,
    val timestampDeltasMs: DoubleArray,
    val rawValues: DoubleArray
)

enum class InferenceStatus {
    VALID,
    INVALID_QUALITY,
    INVALID_TIMESTAMP,
    SCHEMA_MISMATCH,
    MODEL_HASH_MISMATCH,
    MODEL_UNAVAILABLE,
    MODEL_ARTIFACT_INVALID,
    RUNTIME_UNAVAILABLE,
    PREFLIGHT_FAILED
}

data class InferenceResult(
    val label: Int?,
    val stressProbability: Float?,
    val rawStressProbability: Float? = null,
    val calibratedStressProbability: Float? = stressProbability,
    val calibrationVersion: String? = null,
    /** Present only for valid predictions emitted by an accepted manifest. */
    val decisionPolicy: ModelDecisionPolicy? = null,
    val confidence: PredictionConfidence? = null,
    val qualityTier: String,
    val latencyMs: Double,
    val modelVersion: String,
    val performanceTier: String? = null,
    val researchDisclaimer: String? = null,
    /** Immutable provenance for diagnosing label/threshold and parity issues. */
    val trace: StressInferenceTrace? = null,
    val status: InferenceStatus,
    val rejectionReason: String? = null
) {
    init {
        require(status == InferenceStatus.VALID || label == null)
        require(status == InferenceStatus.VALID || stressProbability == null)
        require(status == InferenceStatus.VALID || rawStressProbability == null)
        require(status == InferenceStatus.VALID || calibratedStressProbability == null)
    }

    /** Guards consumers against mixing an inference result with a different manifest policy. */
    fun manifestDecisionPolicyOrNull(): ModelDecisionPolicy? = decisionPolicy?.takeIf {
        it.modelVersion == modelVersion && it.calibrationVersion == calibrationVersion
    }
}

/** Metadata supplied by the acquisition/preprocessing boundary for trace-only diagnostics. */
data class InferenceTraceContext(
    val sourceRateHz: Double? = null,
    val modelRateHz: Double? = null,
    val samplingRatePolicy: String? = null,
    val sessionId: String? = null,
    val windowId: String? = null,
    val trackerContract: String? = null,
    val acceptedSampleCount: Int = 0,
    val rejectedSampleCount: Int = 0,
    val duplicateSampleCount: Int = 0,
    val sequenceLossCount: Long = 0L,
    val observedSampleCount: Int = acceptedSampleCount,
    val repairedShortGapSampleCount: Int = 0,
    val trueGapCount: Int = 0,
    val unresolvedSampleCount: Int = 0,
    val gridResampledSampleCount: Int = 0,
    val observedStartMillis: Long? = null,
    val observedEndMillis: Long? = null,
    val coverageMillis: Long = 0L,
    val observedCadenceHz: Double? = null,
    val maxGapMillis: Long = 0L,
    val preprocessingVersion: String? = null,
    val featureVersion: String? = null,
    val featureChecksum: String? = null,
    val featureMinimum: Float? = null,
    val featureMaximum: Float? = null,
    val scalerChecksum: String? = null,
    val filterAlgorithm: String? = null,
    val filterOrder: Int? = null,
    val filterLowCutHz: Double? = null,
    val filterHighCutHz: Double? = null,
    val filterContractFingerprint: String? = null,
    val detectorMethod: String? = null,
    val detectorFingerprint: String? = null,
    val peakChecksum: String? = null,
    val troughChecksum: String? = null,
    val peakCount: Int = 0,
    val validIbiCount: Int = 0,
    val adjacentIbiCount: Int = 0,
    val physiologicalIbiRatio: Double? = null,
    val morphologySupport: Int = 0,
    val qualityStatus: String? = null,
    val rejectionReason: String? = null
)

data class TensorContract(val name: String, val dtype: String, val shape: List<Int>) {
    fun requireInput() {
        require(name.isNotBlank() && dtype == "float32") { "invalid_input_contract" }
        require(shape.isNotEmpty() && shape.first() == 1 && shape.all { it > 0 }) {
            "input_must_be_static_batch_1"
        }
    }
}

/**
 * Model manifest contract. Gson does not apply Kotlin default parameters when
 * a JSON key is absent. Candidate manifests intentionally omit the physical-
 * device receipt until promotion requires a content-addressed evidence file.
 */
@Suppress("ConstructorParameterNaming")
data class ModelManifest(
    val schema_version: String,
    val artifact_status: String = "",
    val evaluation_scope: String = "",
    val model_family: String,
    val model_version: String,
    val preprocessing_version: String,
    val feature_version: String,
    val sensor_channel: String,
    val feature_order: List<String> = emptyList(),
    val label_order: List<String> = listOf("relax", "task"),
    val probability_threshold: Float,
    val onnx_sha256: String,
    val inputs: List<TensorContract>,
    val output: TensorContract,
    val release_status: String,
    val gate_passed: Boolean,
    val research_gate_passed: Boolean = false,
    val balanced_accuracy_gate: Double,
    val balanced_accuracy_holdout: Double,
    val f1_gate: Double = 0.0,
    val f1_holdout: Double = 0.0,
    val roc_auc_gate: Double = 0.0,
    val roc_auc_holdout: Double = 0.0,
    val balanced_accuracy_development: Double = Double.NaN,
    val f1_development: Double = Double.NaN,
    val roc_auc_development: Double = Double.NaN,
    val performance_policy_version: String = "",
    val performance_policy_sha256: String = "",
    val performance_tier: String = "",
    val deployment_scope: String = "",
    val research_disclaimer: String = "",
    val disclaimer_required: Boolean = false,
    val validation_cohort_ids: List<String>,
    val cohort_access_count: Int,
    val threshold_source: String,
    val candidate_bundle_sha256: String,
    val probability_calibration: ProbabilityCalibration,
    val scaler_mean: List<Double>? = null,
    val scaler_scale: List<Double>? = null,
    /** Physical acquisition rate declared by the model-producing pipeline. */
    val source_rate_hz: Double = 0.0,
    /** Canonical rate represented by the feature tensor. */
    val model_rate_hz: Double = 0.0,
    /** Manifest-owned Butterworth/SOS preprocessing contract. */
    val filter_algorithm: String = "",
    val filter_order: Int = 0,
    val filter_low_cut_hz: Double = 0.0,
    val filter_high_cut_hz: Double = 0.0,
    val filter_zero_phase: Boolean = false,
    val filter_pad_policy: String = "",
    val filter_design_version: String = "",
    val filter_contract_fingerprint: String = "",
    /** Retained analysis core declared by the selected channel model. */
    val analysis_seconds: Int = 0,
    /** One model window selected on development participants only. */
    val window_seconds: Int = 0,
    val stride_seconds: Int = 0,
    val expected_windows_per_session: Int = 0,
    /** Explicit promotion gate; absent legacy manifests fail closed. */
    val android_ready: Boolean = false,
    val device_parity_receipt_sha256: String? = null,
    val holdout_evaluated: Boolean = false,
    val clinical_validation_status: String = "",
    /** Scoped status fields for the internal-32 Android contract. */
    val validation_scope: String = "",
    val claims_policy: String = "",
    val hrv_prv_status: String = "",
    val training_admission_status: String = "",
    val development_model_status: String = "",
    val internal_test_status: String = "",
    val onnx_export_status: String = "",
    val android_device_parity_status: String = "",
    val internal_android_status: String = "",
    val balanced_accuracy_internal_test: Double? = null,
    val f1_internal_test: Double? = null,
    val roc_auc_internal_test: Double? = null,
    val internal_test_participants: List<String> = emptyList()
) {
    fun decisionPolicy(): ModelDecisionPolicy = ModelDecisionPolicy(
        modelVersion = model_version,
        probabilityThreshold = probability_threshold,
        calibrationVersion = probability_calibration.version,
        labelOrder = label_order
    )

    fun requireValid() {
        requireCommonContract(requireHoldoutEvidence = true)
        require(schema_version == "1.0.0") { "unsupported_manifest_schema" }
        require(release_status == "accepted" && gate_passed) { "deployment_gate_failed" }
        require(android_ready) { "android_release_gate_failed" }
        require(device_parity_receipt_sha256?.matches(SHA256) == true) {
            "device_parity_receipt_missing_or_invalid"
        }
    }

    /** Candidate manifests are executable only from instrumented-test assets. */
    internal fun requireCandidateValid() {
        requireCommonContract(requireHoldoutEvidence = true)
        require(schema_version == "1.0.0") { "unsupported_manifest_schema" }
        require(release_status == "release_candidate" && gate_passed) { "candidate_gate_failed" }
        require(!android_ready) { "candidate_must_not_be_android_ready" }
        require(device_parity_receipt_sha256.isNullOrBlank()) {
            "candidate_must_not_contain_device_receipt"
        }
    }

    internal fun requireResearchValid() {
        requireCommonContract(requireHoldoutEvidence = false)
        require(schema_version == "onnx-research-v1") { "unsupported_research_manifest_schema" }
        require(artifact_status == "research_candidate") { "research_artifact_status_invalid" }
        require(evaluation_scope == "development_only") { "research_scope_invalid" }
        require(release_status == "blocked_existing_holdout_contract_mismatch") {
            "research_release_blocker_missing"
        }
        require(gate_passed && research_gate_passed) { "research_gate_failed" }
        require(!android_ready && !holdout_evaluated) { "research_must_not_be_release_ready" }
        require(clinical_validation_status == "external_validation_required") {
            "research_clinical_status_invalid"
        }
        require(device_parity_receipt_sha256.isNullOrBlank()) {
            "research_must_not_contain_device_receipt"
        }
    }

    /** Internal 32-participant bundle; device parity is verified separately. */
    internal fun requireInternalValid() {
        requireCommonContract(
            requireHoldoutEvidence = false,
            allowInternalTestAccess = true
        )
        require(schema_version == "internal-android-v2") {
            "unsupported_internal_manifest_schema"
        }
        require(artifact_status == "internal_candidate") { "internal_artifact_status_invalid" }
        require(evaluation_scope == "internal_32_participants") { "internal_scope_invalid" }
        require(validation_scope == "internal_32_participants") { "internal_validation_scope_invalid" }
        require(claims_policy == "decision_support_only") { "internal_claims_policy_invalid" }
        require(release_status == "internal_candidate" && gate_passed) {
            "internal_deployment_gate_failed"
        }
        require(
            listOf(
                hrv_prv_status,
                training_admission_status,
                development_model_status,
                internal_test_status,
                onnx_export_status
            ).all { it == "GO" }
        ) { "internal_gate_status_invalid" }
        require(android_device_parity_status in setOf("PENDING", "GO")) {
            "internal_device_parity_status_invalid"
        }
        require(internal_android_status in setOf("PENDING_DEVICE_PARITY", "GO")) {
            "internal_android_status_invalid"
        }
        require(
            internal_test_participants == listOf("ID-29", "ID-30", "ID-31", "ID-32")
        ) { "internal_test_cohort_invalid" }
        require(
            validation_cohort_ids ==
                (DEVELOPMENT_PARTICIPANT_FIRST..DEVELOPMENT_PARTICIPANT_LAST)
                    .map { "ID-%02d".format(it) }
        ) { "internal_development_cohort_invalid" }
        require(cohort_access_count == 1) { "internal_test_not_evaluated_once" }
        require(
            listOf(
                balanced_accuracy_internal_test,
                f1_internal_test,
                roc_auc_internal_test
            ).all { value -> value?.isFinite() == true }
        ) { "internal_test_metrics_non_finite" }
        require(android_ready != true && clinical_validation_status.isNullOrBlank()) {
            "internal_manifest_contains_legacy_status"
        }
    }

    /**
     * Returns the runtime contract only after validating the access policy for
     * the manifest kind. Internal Android bundles are already validated by
     * [requireInternalValid] at the artifact boundary and may use their
     * internal cohort receipt. Research and release manifests keep their
     * stricter holdout rules.
     */
    fun sessionContract(detectorContract: PpgDetectorContract? = null): ModelSessionContract {
        when (schema_version) {
            "internal-android-v2" -> requireInternalValid()
            "onnx-research-v1" -> requireResearchValid()
            else -> requireCommonContract(requireHoldoutEvidence = true)
        }
        return ModelSessionContract(
            modelVersion = model_version,
            sensorChannel = sensor_channel,
            analysisSeconds = analysis_seconds,
            windowSeconds = window_seconds,
            strideSeconds = stride_seconds,
            expectedWindowsPerSession = expected_windows_per_session,
            featureOrder = feature_order,
            sourceRateHz = source_rate_hz,
            modelRateHz = model_rate_hz,
            filterContract = ppgFilterContract(),
            preprocessingVersion = preprocessing_version,
            featureVersion = feature_version,
            manifestFingerprint = candidate_bundle_sha256,
            performanceTier = performance_tier,
            deploymentScope = deployment_scope,
            researchDisclaimer = research_disclaimer.takeIf { disclaimer_required },
            detectorContract = detectorContract
        )
    }

    /** UI/runtime-safe projection of the executable model contract. */
    fun sessionContractStatus(detectorContract: PpgDetectorContract? = null): ModelContractStatus = runCatching {
        ModelContractStatus.Ready(sessionContract(detectorContract))
    }.getOrElse { error ->
        ModelContractStatus.Unavailable(
            reason = error.message?.takeIf(String::isNotBlank) ?: "model_contract_invalid"
        )
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun requireCommonContract(requireHoldoutEvidence: Boolean, allowInternalTestAccess: Boolean = false) {
        require(model_version.isNotBlank()) { "missing_model_version" }
        require(preprocessing_version.isNotBlank()) { "bad_preprocessing_version" }
        require(sensor_channel in setOf("ppg_green", "ppg_ir", "ppg_red")) { "unsupported_model_sensor" }
        require(label_order == listOf("relax", "task")) { "unsupported_label_order" }
        require(source_rate_hz.isFinite() && source_rate_hz > 0.0) { "missing_source_rate_contract" }
        require(model_rate_hz.isFinite() && model_rate_hz > 0.0) { "missing_model_rate_contract" }
        ppgFilterContract().requireCompatibleWith(model_rate_hz)
        ModelSessionContract(
            modelVersion = model_version,
            sensorChannel = sensor_channel,
            analysisSeconds = analysis_seconds,
            windowSeconds = window_seconds,
            strideSeconds = stride_seconds,
            expectedWindowsPerSession = expected_windows_per_session,
            featureOrder = feature_order,
            sourceRateHz = source_rate_hz,
            modelRateHz = model_rate_hz,
            filterContract = ppgFilterContract(),
            preprocessingVersion = preprocessing_version,
            featureVersion = feature_version,
            manifestFingerprint = candidate_bundle_sha256
        ).requireValid()
        require(balanced_accuracy_gate.isFinite() && balanced_accuracy_gate in 0.0..1.0) {
            "invalid_balanced_accuracy_gate"
        }
        require(f1_gate.isFinite() && f1_gate in 0.0..1.0) { "invalid_f1_gate" }
        require(roc_auc_gate.isFinite() && roc_auc_gate in 0.0..1.0) { "invalid_roc_auc_gate" }
        if (requireHoldoutEvidence) {
            require(balanced_accuracy_holdout.isFinite() && f1_holdout.isFinite() && roc_auc_holdout.isFinite()) {
                "non_finite_holdout_metrics"
            }
        } else {
            require(
                balanced_accuracy_development.isFinite() &&
                    f1_development.isFinite() &&
                    roc_auc_development.isFinite()
            ) { "non_finite_development_metrics" }
        }
        require(performance_policy_version.isNotBlank()) { "missing_performance_policy_version" }
        require(performance_policy_sha256.matches(SHA256)) { "invalid_performance_policy_checksum" }
        require(performance_tier in setOf("below_minimum", "research_usable", "preferred")) {
            "invalid_performance_tier"
        }
        require(deployment_scope in setOf("none", "research", "standard", "internal")) {
            "invalid_deployment_scope"
        }
        if (disclaimer_required) {
            require(research_disclaimer.isNotBlank()) { "missing_research_disclaimer" }
        }
        if (requireHoldoutEvidence) {
            require(validation_cohort_ids.sorted() == listOf("ID-29", "ID-30", "ID-31", "ID-32")) {
                "bad_validation_cohort"
            }
            require(cohort_access_count == 1) { "cohort_not_one_time" }
        } else {
            val development =
                (DEVELOPMENT_PARTICIPANT_FIRST..DEVELOPMENT_PARTICIPANT_LAST)
                    .map { "ID-%02d".format(it) }
                    .toSet()
            require(
                validation_cohort_ids.size >= MIN_RESEARCH_VALIDATION_COHORT &&
                    validation_cohort_ids.toSet().size == validation_cohort_ids.size
            ) {
                "research_development_cohort_too_small_or_duplicated"
            }
            require(validation_cohort_ids.all(development::contains)) { "bad_research_validation_cohort" }
            if (!allowInternalTestAccess) {
                require(cohort_access_count == 0) { "research_holdout_access_forbidden" }
            }
        }
        require(threshold_source == "participant_grouped_development_validation") { "unsafe_threshold_source" }
        require(probability_threshold in 0f..1f) { "bad_threshold" }
        probability_calibration.requireValid()
        require(onnx_sha256.matches(SHA256)) { "bad_model_checksum" }
        require(candidate_bundle_sha256.matches(SHA256)) { "bad_candidate_checksum" }
        require(inputs.isNotEmpty() && inputs.map { it.name }.distinct().size == inputs.size) { "bad_inputs" }
        inputs.forEach(TensorContract::requireInput)
        require(inputs.size == 1 && inputs.single().shape.size == 2 && inputs.single().shape[0] == 1) {
            "input_shape_must_be_static_feature_vector"
        }
        require(output.name == "stress_probability" && output.dtype == "float32" && output.shape == listOf(1)) { "bad_output_contract" }
        if (model_family == "classical") {
            require(feature_order.isNotEmpty() && feature_order.distinct().size == feature_order.size) {
                "bad_feature_order"
            }
            require(feature_order.size == inputs.single().shape[1]) {
                "feature_order_must_match_input_shape"
            }
        }
    }

    fun ppgFilterContract(): PpgFilterContract = PpgFilterContract(
        algorithm = filter_algorithm,
        order = filter_order,
        lowCutHz = filter_low_cut_hz,
        highCutHz = filter_high_cut_hz,
        zeroPhase = filter_zero_phase,
        padPolicy = filter_pad_policy,
        designVersion = filter_design_version,
        fingerprint = filter_contract_fingerprint
    )

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

sealed interface ModelContractStatus {
    data class Ready(val contract: ModelSessionContract) : ModelContractStatus

    data class Unavailable(val reason: String) : ModelContractStatus
}

data class ModelSessionContract(
    val modelVersion: String,
    val sensorChannel: String,
    val analysisSeconds: Int,
    val windowSeconds: Int,
    val strideSeconds: Int,
    val expectedWindowsPerSession: Int,
    val featureOrder: List<String>,
    val sourceRateHz: Double = 0.0,
    val modelRateHz: Double = 0.0,
    val filterContract: PpgFilterContract,
    val preprocessingVersion: String = "",
    val featureVersion: String = "",
    val manifestFingerprint: String = "",
    val performanceTier: String = "",
    val deploymentScope: String = "",
    val researchDisclaimer: String? = null,
    val detectorContract: PpgDetectorContract? = null
) {
    fun requireValid() {
        require(modelVersion.isNotBlank()) { "missing_model_version" }
        require(sensorChannel in setOf("ppg_green", "ppg_ir", "ppg_red")) {
            "unsupported_model_sensor"
        }
        require(sourceRateHz.isFinite() && sourceRateHz > 0.0) { "invalid_source_rate_contract" }
        require(modelRateHz.isFinite() && modelRateHz > 0.0) { "invalid_model_rate_contract" }
        filterContract.requireCompatibleWith(modelRateHz)
        require(preprocessingVersion.isNotBlank()) { "missing_preprocessing_version" }
        require(featureVersion.isNotBlank()) { "missing_feature_version" }
        require(analysisSeconds == 240) { "analysis_core_must_be_240_seconds" }
        require(windowSeconds in 1..analysisSeconds) { "invalid_model_window_seconds" }
        require(strideSeconds in 1..windowSeconds) { "invalid_model_stride_seconds" }
        require((analysisSeconds - windowSeconds) % strideSeconds == 0) {
            "non_integral_model_window_contract"
        }
        val derived = ((analysisSeconds - windowSeconds) / strideSeconds) + 1
        require(expectedWindowsPerSession == derived) { "expected_window_count_mismatch" }
        require(featureOrder.isNotEmpty() && featureOrder.distinct().size == featureOrder.size) {
            "bad_feature_order"
        }
    }
}

data class ProbabilityCalibration(
    val method: String,
    val version: String,
    val slope: Double,
    val intercept: Double,
    val fitted_cohort: List<String>,
    val fitted_on: String,
    val calibration_status: String,
    val brier_score: Double,
    val log_loss: Double,
    val ece: Double
) {
    fun requireValid() {
        require(method == "platt") { "unsupported_probability_calibration" }
        require(version.isNotBlank()) { "missing_calibration_version" }
        require(slope.isFinite() && slope > 0.0) { "invalid_calibration_slope" }
        require(intercept.isFinite()) { "invalid_calibration_intercept" }
        require(fitted_cohort == (1..28).map { "ID-%02d".format(it) }) {
            "calibration_must_use_development_cohort"
        }
        require(fitted_on in DEVELOPMENT_CALIBRATION_FIT_SOURCES) {
            "invalid_calibration_provenance"
        }
        require(calibration_status in DEVELOPMENT_CALIBRATION_STATUSES) {
            "calibration_not_validated"
        }
        require(brier_score.isFinite() && brier_score in 0.0..1.0) { "invalid_calibration_brier" }
        require(log_loss.isFinite() && log_loss >= 0.0) { "invalid_calibration_log_loss" }
        require(ece.isFinite() && ece in 0.0..1.0) { "invalid_calibration_ece" }
    }
}

/** A deterministic, release-approved vector used to verify the native session. */
data class ModelPreflightVector(val input: FloatArray, val expectedProbability: Float)

data class ModelPreflightReceipt(val vectorCount: Int, val maxAbsDiff: Float)

/**
 * Readiness of one independently deployed stress model.
 *
 * File integrity and native runtime readiness are intentionally separate: a
 * checksum-valid model can still be incompatible with the device runtime.
 */
sealed interface StressModelReadiness {
    data class Ready(val modelVersion: String) : StressModelReadiness

    data class ArtifactInvalid(val code: String) : StressModelReadiness

    data class RuntimeUnavailable(val code: String) : StressModelReadiness

    data class PreflightFailed(val code: String) : StressModelReadiness
}

data class ModelResources(
    val manifest: ModelManifest,
    val modelBytes: ByteArray,
    val preflightVector: ModelPreflightVector,
    val preflightVectors: List<ModelPreflightVector> =
        if (preflightVector.input.isNotEmpty()) listOf(preflightVector) else emptyList(),
    val detectorContract: PpgDetectorContract? = null
)
