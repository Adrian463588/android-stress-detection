package com.stressdetector.inference

import android.content.res.AssetManager
import com.google.gson.Gson

private const val REQUIRED_VECTOR_COUNT = 100

/** Runtime boundary for the canonical, versioned model bundle packaged by Gradle. */
interface ModelArtifactRepository {
    fun load(sensorChannel: String): ModelArtifactResult
}

sealed interface ModelArtifactResult {
    data class Ready(val resources: ModelResources) : ModelArtifactResult

    data class Unavailable(val reason: String) : ModelArtifactResult

    data class Invalid(val reason: String) : ModelArtifactResult
}

internal data class CanonicalModelIndex(
    val schema_version: String,
    val release_status: String,
    val models: List<CanonicalModelEntry> = emptyList()
)

internal data class CanonicalModelEntry(
    val model_version: String,
    val sensor_channel: String,
    val calibration_version: String = "",
    val artifact_status: String = "",
    val evaluation_scope: String = "",
    val release_status: String,
    val balanced_accuracy_holdout: Double = Double.NaN,
    val f1_holdout: Double = Double.NaN,
    val roc_auc_holdout: Double = Double.NaN,
    val balanced_accuracy_development: Double = Double.NaN,
    val f1_development: Double = Double.NaN,
    val roc_auc_development: Double = Double.NaN,
    val balanced_accuracy_conservative: Double = Double.NaN,
    val f1_conservative: Double = Double.NaN,
    val roc_auc_conservative: Double = Double.NaN,
    val android_ready: Boolean = false,
    val performance_policy_version: String = "",
    val performance_policy_sha256: String = "",
    val performance_tier: String = "",
    val deployment_scope: String = "",
    val research_disclaimer: String = "",
    val disclaimer_required: Boolean = false,
    val analysis_seconds: Int = 0,
    val window_seconds: Int = 0,
    val stride_seconds: Int = 0,
    val expected_windows_per_session: Int = 0,
    val source_rate_hz: Double = 0.0,
    val model_rate_hz: Double = 0.0,
    val files: Map<String, CanonicalFileDescriptor> = emptyMap()
)

internal data class CanonicalFileDescriptor(val path: String, val sha256: String)

/**
 * Loads only artifacts emitted by :inference:syncInferenceAssets. Legacy flat assets are
 * intentionally invisible so an empty canonical index remains a typed, fail-closed state.
 */
class AndroidAssetModelArtifactRepository(private val assets: AssetManager) : ModelArtifactRepository {
    override fun load(sensorChannel: String): ModelArtifactResult = runCatching {
        val researchMode = BuildConfig.RESEARCH_MODEL_MODE
        val indexJson = assets.open(INDEX_PATH).bufferedReader().use { it.readText() }
        val index =
            Gson().fromJson(indexJson, CanonicalModelIndex::class.java)
                ?: return ModelArtifactResult.Invalid("model_index_unreadable")
        if (index.schema_version != "1.0.0") {
            return ModelArtifactResult.Invalid("unsupported_model_index_schema")
        }
        if (index.models.isEmpty()) {
            return ModelArtifactResult.Unavailable(
                index.release_status
                    .takeUnless { it == "blocked_android_evidence_pending" }
                    ?: "physical_device_parity_pending"
            )
        }
        val expectedIndexStatus = if (researchMode) "research_candidate" else "accepted"
        if (index.release_status != expectedIndexStatus) {
            return ModelArtifactResult.Unavailable(index.release_status)
        }
        val policy = PackagedPerformancePolicyLoader.load(assets)
        val indexedSensors = index.models.map { it.sensor_channel }.toSet()
        if (
            index.models.isEmpty() ||
            index.models.size > ACCEPTED_SENSORS.size ||
            index.models.size != indexedSensors.size ||
            !ACCEPTED_SENSORS.containsAll(indexedSensors)
        ) {
            return ModelArtifactResult.Invalid("canonical_model_index_channels_invalid")
        }

        val candidates = index.models.filter { it.sensor_channel == sensorChannel }
        if (candidates.size != 1) {
            return if (candidates.isEmpty()) {
                ModelArtifactResult.Unavailable("model_unavailable_for_$sensorChannel")
            } else {
                ModelArtifactResult.Invalid("ambiguous_model_for_$sensorChannel")
            }
        }
        val candidate = candidates.single()
        val candidateGatePassed = if (researchMode) {
            candidate.artifact_status == "research_candidate" &&
                candidate.evaluation_scope == "development_only" &&
                candidate.release_status == "blocked_existing_holdout_contract_mismatch" &&
                !candidate.android_ready &&
                candidate.deployment_scope == "research"
        } else {
            candidate.release_status == "accepted" && candidate.android_ready
        }
        if (
            !candidateGatePassed ||
            candidate.sensor_channel !in ACCEPTED_SENSORS ||
            !candidate.source_rate_hz.isFinite() ||
            candidate.source_rate_hz <= 0.0 ||
            !candidate.model_rate_hz.isFinite() ||
            candidate.model_rate_hz <= 0.0
        ) {
            return ModelArtifactResult.Invalid("deployment_gate_failed")
        }
        if (!SAFE_ID.matches(candidate.model_version) || !SAFE_ID.matches(candidate.sensor_channel)) {
            return ModelArtifactResult.Invalid("unsafe_model_identity")
        }

        val modelDescriptor =
            candidate.files["model"]
                ?: return ModelArtifactResult.Invalid("model_descriptor_missing")
        val manifestDescriptor =
            candidate.files["manifest"]
                ?: return ModelArtifactResult.Invalid("manifest_descriptor_missing")
        val parityDescriptor =
            candidate.files["parity"]
                ?: return ModelArtifactResult.Invalid("parity_descriptor_missing")
        val vectorDescriptor =
            candidate.files["test_vectors"]
                ?: return ModelArtifactResult.Invalid("test_vectors_descriptor_missing")
        val receiptDescriptor = candidate.files["device_parity_receipt"]
        if (!researchMode && receiptDescriptor == null) {
            return ModelArtifactResult.Invalid("device_parity_receipt_descriptor_missing")
        }
        val assetRoot = "models/${candidate.model_version}/${candidate.sensor_channel}"
        val modelBytes = readVerified(assetRoot, modelDescriptor)
        val manifestBytes = readVerified(assetRoot, manifestDescriptor)
        val parityBytes = readVerified(assetRoot, parityDescriptor)
        val vectorBytes = readVerified(assetRoot, vectorDescriptor)
        val receiptBytes = receiptDescriptor?.let { readVerified(assetRoot, it) }
        requireParityContract(parityBytes)
        val indexedManifest =
            Gson().fromJson(
                manifestBytes.decodeToString(),
                ModelManifest::class.java
            ) ?: return ModelArtifactResult.Invalid("model_manifest_unreadable")
        if (researchMode) {
            policy.requireDevelopmentEntryCompatibility(candidate, indexedManifest)
        } else {
            policy.requireEntryCompatibility(candidate, indexedManifest)
            requireVectorContract(vectorBytes, indexedManifest)
        }
        val preflightVectors =
            parsePreflightVectors(
                vectorBytes,
                indexedManifest.feature_order.size
            )
        val resources = if (researchMode) {
            ModelArtifactLoader.loadResearch(
                manifestJson = manifestBytes.decodeToString(),
                modelBytes = modelBytes,
                preflightVectors = preflightVectors
            )
        } else {
            ModelArtifactLoader.load(
                manifestJson = manifestBytes.decodeToString(),
                modelBytes = modelBytes,
                preflightVector = preflightVectors.first(),
                preflightVectors = preflightVectors
            )
        }
        if (!researchMode) {
            val receipt =
                Gson().fromJson(
                    receiptBytes!!.decodeToString(),
                    DeviceParityReceiptWire::class.java
                ) ?: return ModelArtifactResult.Invalid("device_parity_receipt_unreadable")
            requireDeviceReceipt(receipt, indexedManifest)
        }
        if (resources.manifest.sensor_channel != sensorChannel ||
            resources.manifest.model_version != candidate.model_version ||
            resources.manifest.analysis_seconds != candidate.analysis_seconds ||
            resources.manifest.window_seconds != candidate.window_seconds ||
            resources.manifest.stride_seconds != candidate.stride_seconds ||
            resources.manifest.expected_windows_per_session != candidate.expected_windows_per_session ||
            resources.manifest.source_rate_hz != candidate.source_rate_hz ||
            resources.manifest.model_rate_hz != candidate.model_rate_hz ||
            (
                candidate.calibration_version.isNotBlank() &&
                    resources.manifest.probability_calibration.version != candidate.calibration_version
                )
        ) {
            return ModelArtifactResult.Invalid("index_manifest_identity_mismatch")
        }
        ModelArtifactResult.Ready(resources)
    }.getOrElse { error ->
        val reason = error.message ?: "model_artifact_load_failed"
        if (reason.contains("model_index", ignoreCase = true)) {
            ModelArtifactResult.Unavailable(reason)
        } else {
            ModelArtifactResult.Invalid(reason)
        }
    }

    private fun readVerified(root: String, descriptor: CanonicalFileDescriptor): ByteArray {
        require(descriptor.sha256.matches(SHA256)) { "invalid_index_checksum" }
        val fileName = descriptor.path.replace('\\', '/').substringAfterLast('/')
        require(fileName.isNotBlank() && SAFE_FILE.matches(fileName)) { "unsafe_artifact_path" }
        val bytes = assets.open("$root/$fileName").use { it.readBytes() }
        require(ModelArtifactLoader.sha256(bytes) == descriptor.sha256) { "artifact_checksum_mismatch" }
        return bytes
    }

    private fun requireParityContract(bytes: ByteArray) {
        val parity =
            Gson().fromJson(bytes.decodeToString(), ParityWire::class.java)
                ?: error("onnx_parity_unreadable")
        require(parity.passed) { "onnx_parity_not_passed" }
        require(parity.onnx_checker_passed && parity.real_batch1_inference_passed) {
            "onnx_parity_runtime_checks_failed"
        }
        require(parity.checked_samples >= REQUIRED_VECTOR_COUNT) { "onnx_parity_vector_count_invalid" }
        require(parity.max_abs_diff.isFinite() && parity.max_abs_diff < PARITY_TOLERANCE) {
            "onnx_parity_tolerance_failed"
        }
    }

    private fun requireVectorContract(bytes: ByteArray, manifest: ModelManifest) {
        val vectors =
            Gson().fromJson(bytes.decodeToString(), VectorContractWire::class.java)
                ?: error("test_vectors_unreadable")
        require(vectors.vectorSchemaVersion == "2.0.0") { "test_vectors_schema_mismatch" }
        require(vectors.sourceRateHz == manifest.source_rate_hz) {
            "test_vectors_source_rate_mismatch"
        }
        require(vectors.modelRateHz == manifest.model_rate_hz) {
            "test_vectors_model_rate_mismatch"
        }
        require(vectors.filterContractFingerprint == manifest.filter_contract_fingerprint) {
            "test_vectors_filter_contract_mismatch"
        }
        vectors.cases.filter { it.kind == "valid_raw_window" }.forEach { vector ->
            require(vector.sourceRateHz == manifest.source_rate_hz) {
                "test_vector_source_rate_mismatch"
            }
            require(vector.modelRateHz == manifest.model_rate_hz) {
                "test_vector_model_rate_mismatch"
            }
            require(vector.samplingRateHz == manifest.model_rate_hz) {
                "test_vector_sampling_rate_mismatch"
            }
            require(vector.filterContractFingerprint == manifest.filter_contract_fingerprint) {
                "test_vector_filter_contract_mismatch"
            }
        }
    }

    private fun requireDeviceReceipt(receipt: DeviceParityReceiptWire, manifest: ModelManifest) {
        require(receipt.receipt_sha256.matches(SHA256)) { "device_parity_receipt_hash_invalid" }
        require(receipt.receipt_sha256 == manifest.device_parity_receipt_sha256) {
            "device_parity_receipt_manifest_mismatch"
        }
        require(receipt.device_fingerprint.isNotBlank() && receipt.apk_sha256.matches(SHA256)) {
            "device_parity_identity_invalid"
        }
        require(receipt.model_version == manifest.model_version && receipt.sensor_channel == manifest.sensor_channel) {
            "device_parity_model_identity_mismatch"
        }
        require(receipt.onnx_sha256 == manifest.onnx_sha256) { "device_parity_onnx_mismatch" }
        require(receipt.vector_count == REQUIRED_VECTOR_COUNT) { "device_parity_vector_count_invalid" }
        require(receipt.max_abs_diff.isFinite() && receipt.max_abs_diff < PARITY_TOLERANCE) {
            "device_parity_tolerance_failed"
        }
    }

    private data class ParityWire(
        val checked_samples: Int = 0,
        val max_abs_diff: Double = Double.NaN,
        val onnx_checker_passed: Boolean = false,
        val real_batch1_inference_passed: Boolean = false,
        val passed: Boolean = false
    )

    @Suppress("ConstructorParameterNaming")
    private data class VectorContractWire(
        val vector_schema_version: String = "",
        val source_rate_hz: Double = Double.NaN,
        val model_rate_hz: Double = Double.NaN,
        val filter_contract: VectorFilterWire? = null,
        val cases: List<VectorCaseContractWire> = emptyList()
    ) {
        val vectorSchemaVersion: String get() = vector_schema_version
        val sourceRateHz: Double get() = source_rate_hz
        val modelRateHz: Double get() = model_rate_hz
        val filterContractFingerprint: String get() = filter_contract?.fingerprint.orEmpty()
    }

    private data class VectorFilterWire(val fingerprint: String = "")

    @Suppress("ConstructorParameterNaming")
    private data class VectorCaseContractWire(
        val kind: String? = null,
        val source_rate_hz: Double = Double.NaN,
        val model_rate_hz: Double = Double.NaN,
        val sampling_rate_hz: Double = Double.NaN,
        val filter_contract: VectorFilterWire? = null
    ) {
        val sourceRateHz: Double get() = source_rate_hz
        val modelRateHz: Double get() = model_rate_hz
        val samplingRateHz: Double get() = sampling_rate_hz
        val filterContractFingerprint: String get() = filter_contract?.fingerprint.orEmpty()
    }

    private data class DeviceParityReceiptWire(
        val device_fingerprint: String = "",
        val apk_sha256: String = "",
        val onnx_sha256: String = "",
        val model_version: String = "",
        val sensor_channel: String = "",
        val vector_count: Int = 0,
        val max_abs_diff: Double = Double.NaN,
        val actual_sensor_acquisition: Boolean = false,
        val receipt_sha256: String = ""
    )

    private companion object {
        const val INDEX_PATH = "model_index.json"
        val ACCEPTED_SENSORS = setOf("ppg_green", "ppg_ir")
        val SAFE_ID = Regex("[A-Za-z0-9._-]+")
        val SAFE_FILE = Regex("[A-Za-z0-9._-]+")
        val SHA256 = Regex("[0-9a-f]{64}")
        const val PARITY_TOLERANCE = 1.0e-5
    }
}

/**
 * Parses the complete deterministic vector contract while keeping rejection cases out of
 * native ONNX preflight. Rejection vectors prove that bad quality/schema/timestamps produce no
 * prediction; they do not have an ONNX probability to execute.
 */
internal fun parsePreflightVectors(bytes: ByteArray, expectedFeatureCount: Int): List<ModelPreflightVector> {
    require(expectedFeatureCount > 0) { "invalid_manifest_feature_count" }
    val vectors =
        Gson().fromJson(bytes.decodeToString(), TestVectorsWire::class.java)
            ?: error("test_vectors_unreadable")
    require(vectors.cases.size == REQUIRED_VECTOR_COUNT) { "test_vectors_count_invalid" }

    val validVectors =
        vectors.cases.mapNotNull { vector ->
            if (vector.kind in REJECTION_KINDS) {
                require(!vector.expectedStatus.isNullOrBlank()) { "rejection_vector_status_missing" }
                require(vector.input.isEmpty() && vector.expectedFeatures.isNullOrEmpty()) {
                    "rejection_vector_has_input"
                }
                require(
                    vector.expectedProbability == null &&
                        vector.onnxProbability == null &&
                        vector.expectedStressProbability == null
                ) {
                    "rejection_vector_has_probability"
                }
                return@mapNotNull null
            }

            val input = vector.input.ifEmpty { vector.expectedFeatures.orEmpty() }
            val probability = vector.onnxProbability ?: vector.expectedStressProbability
            require(input.size == expectedFeatureCount) { "test_vector_input_shape_invalid" }
            require(input.all { it.isFinite() }) { "test_vector_input_non_finite" }
            require(probability != null && probability.isFinite()) {
                "test_vector_probability_non_finite"
            }
            ModelPreflightVector(input.toFloatArray(), probability)
        }
    require(validVectors.isNotEmpty()) { "test_vectors_have_no_valid_preflight_cases" }
    return validVectors
}

private data class TestVectorsWire(val cases: List<TestVectorCaseWire> = emptyList())

private data class TestVectorCaseWire(
    val kind: String? = null,
    val expected_status: String? = null,
    val expected_probability: Float? = null,
    val input: List<Float> = emptyList(),
    val onnx_probability: Float? = null,
    val expected_features: List<Float>? = null,
    val expected_stress_probability: Float? = null
) {
    val expectedStatus: String?
        get() = expected_status
    val expectedProbability: Float?
        get() = expected_probability
    val onnxProbability: Float?
        get() = onnx_probability
    val expectedFeatures: List<Float>?
        get() = expected_features
    val expectedStressProbability: Float?
        get() = expected_stress_probability
}

private val REJECTION_KINDS =
    setOf(
        "quality_rejection",
        "timestamp_rejection",
        "schema_rejection",
        "model_rejection"
    )
