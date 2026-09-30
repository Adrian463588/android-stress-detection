package com.stressdetector.inference

import com.google.gson.Gson
import java.security.MessageDigest

/** Validates every release contract before native runtime initialization. */
object ModelArtifactLoader {
    fun load(
        manifestJson: String,
        modelBytes: ByteArray,
        preflightVector: ModelPreflightVector = ModelPreflightVector(FloatArray(0), Float.NaN),
        preflightVectors: List<ModelPreflightVector> = emptyList(),
        detectorContract: PpgDetectorContract? = null
    ): ModelResources = loadValidated(
        ModelLoadRequest(
            manifestJson = manifestJson,
            modelBytes = modelBytes,
            preflightVector = preflightVector,
            preflightVectors = preflightVectors,
            validateManifest = ModelManifest::requireValid,
            detectorContract = detectorContract
        )
    )

    /** Instrumented-test only loader; candidate artifacts remain invisible to production. */
    internal fun loadCandidateForInstrumentedTest(
        manifestJson: String,
        modelBytes: ByteArray,
        preflightVectors: List<ModelPreflightVector>
    ): ModelResources = loadValidated(
        ModelLoadRequest(
            manifestJson = manifestJson,
            modelBytes = modelBytes,
            preflightVector = firstVector(preflightVectors),
            preflightVectors = preflightVectors,
            validateManifest = ModelManifest::requireCandidateValid,
            detectorContract = null
        )
    )

    internal fun loadResearch(
        manifestJson: String,
        modelBytes: ByteArray,
        preflightVectors: List<ModelPreflightVector>,
        detectorContract: PpgDetectorContract? = null
    ): ModelResources = loadValidated(
        ModelLoadRequest(
            manifestJson = manifestJson,
            modelBytes = modelBytes,
            preflightVector = firstVector(preflightVectors),
            preflightVectors = preflightVectors,
            validateManifest = ModelManifest::requireResearchValid,
            detectorContract = detectorContract
        )
    )

    internal fun loadInternal(
        manifestJson: String,
        modelBytes: ByteArray,
        preflightVectors: List<ModelPreflightVector>,
        detectorContract: PpgDetectorContract? = null
    ): ModelResources = loadValidated(
        ModelLoadRequest(
            manifestJson = manifestJson,
            modelBytes = modelBytes,
            preflightVector = firstVector(preflightVectors),
            preflightVectors = preflightVectors,
            validateManifest = ModelManifest::requireInternalValid,
            detectorContract = detectorContract
        )
    )

    private fun loadValidated(request: ModelLoadRequest): ModelResources {
        val manifestJson = request.manifestJson
        val modelBytes = request.modelBytes
        val preflightVector = request.preflightVector
        val preflightVectors = request.preflightVectors
        require(modelBytes.isNotEmpty()) { "model_unavailable" }
        val manifest =
            Gson().fromJson(manifestJson, ModelManifest::class.java)
                ?: error("manifest_unavailable")
        request.validateManifest(manifest)
        request.detectorContract?.requireCompatible(
            runId = null,
            manifest = manifest,
            modelBytes = modelBytes
        )
        require(sha256(modelBytes) == manifest.onnx_sha256) { "model_hash_mismatch" }
        require(manifest.model_family.lowercase() in CLASSICAL_ONNX_FAMILIES) {
            "unsupported_model_family"
        }
        if (preflightVector.input.isNotEmpty()) {
            require(preflightVector.input.size == manifest.feature_order.size) { "preflight_vector_shape_mismatch" }
            require(preflightVector.input.all(Float::isFinite) && preflightVector.expectedProbability.isFinite()) {
                "preflight_vector_non_finite"
            }
        }
        preflightVectors.forEach { vector ->
            require(vector.input.size == manifest.feature_order.size) { "preflight_vector_shape_mismatch" }
            require(vector.input.all(Float::isFinite) && vector.expectedProbability.isFinite()) {
                "preflight_vector_non_finite"
            }
        }
        return ModelResources(
            manifest = manifest,
            modelBytes = modelBytes.copyOf(),
            preflightVector = preflightVector,
            preflightVectors =
            preflightVectors.ifEmpty {
                if (preflightVector.input.isNotEmpty()) listOf(preflightVector) else emptyList()
            },
            detectorContract = request.detectorContract
        )
    }

    private fun firstVector(vectors: List<ModelPreflightVector>): ModelPreflightVector =
        vectors.firstOrNull() ?: ModelPreflightVector(FloatArray(0), Float.NaN)

    private data class ModelLoadRequest(
        val manifestJson: String,
        val modelBytes: ByteArray,
        val preflightVector: ModelPreflightVector,
        val preflightVectors: List<ModelPreflightVector>,
        val validateManifest: (ModelManifest) -> Unit,
        val detectorContract: PpgDetectorContract?
    )

    fun sha256(values: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(values)
        .joinToString("") { byte -> "%02x".format(byte) }

    private val CLASSICAL_ONNX_FAMILIES =
        setOf(
            "classical",
            "logistic_regression",
            "rbf_svm",
            "extra_trees",
            "lightgbm",
            "xgboost"
        )
}
