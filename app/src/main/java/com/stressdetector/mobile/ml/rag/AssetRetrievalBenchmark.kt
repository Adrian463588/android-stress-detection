package com.stressdetector.mobile.ml.rag

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.Strictness
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException

/** Optional packaged evidence. Absence never supplies a threshold or a benchmark result. */
class AssetRetrievalBenchmark(
    private val runtimeIdentity: RetrievalRuntimeIdentity? = null,
    private val openAsset: (String) -> InputStream
) {
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()

    fun load(): RetrievalPolicyBinding = try {
        val raw = openAsset(ASSET_PATH).use { input ->
            input.readBounded(MAX_ARTIFACT_BYTES, "benchmark_artifact_size_limit_exceeded")
                .decodeToString(throwOnInvalidSequence = true)
        }
        val artifact = parse(raw)
        val binding = VersionedRetrievalPolicyResolver.resolve(artifact)
        when {
            binding !is RetrievalPolicyBinding.Calibrated -> binding
            runtimeIdentity == null -> RetrievalPolicyBinding.Uncalibrated("benchmark_runtime_identity_unavailable")
            !runtimeIdentity.matches(artifact) -> RetrievalPolicyBinding.Uncalibrated("benchmark_runtime_identity_mismatch")
            else -> binding
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: FileNotFoundException) {
        VersionedRetrievalPolicyResolver.resolve(null)
    } catch (_: IOException) {
        RetrievalPolicyBinding.Uncalibrated("benchmark_artifact_unreadable")
    } catch (_: Exception) {
        RetrievalPolicyBinding.Uncalibrated("benchmark_artifact_invalid")
    }

    private fun parse(raw: String): RetrievalBenchmarkArtifact {
        val root = requireNotNull(gson.fromJson(raw, JsonObject::class.java))
        require(root.keySet().all { it in ROOT_FIELDS })
        STRING_FIELDS.forEach { root.requireString(it) }
        root.requireNumber("createdAtEpochMillis")
        root["createdAtEpochMillis"].asBigDecimal.longValueExact()
        val queries = requireNotNull(root.getAsJsonArray("queries"))
        queries.forEach { entry ->
            val query = entry.asJsonObject
            require(query.keySet() == QUERY_FIELDS)
            query.requireString("queryId")
            query.requireString("language")
            require(query["answerable"].asJsonPrimitive.isBoolean)
            require(query["expectedChunkIds"].asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString })
        }
        root.getAsJsonObject("metrics")?.let { metrics ->
            require(metrics.keySet() == METRIC_FIELDS)
            METRIC_FIELDS.forEach { metrics.requireNumber(it) }
        }
        root.getAsJsonObject("thresholdCalibration")?.let { calibration ->
            require(calibration.keySet() == CALIBRATION_FIELDS)
            calibration.requireString("calibrationVersion")
            calibration.requireNumber("semanticHybridMinimumRrf")
            calibration.requireNumber("lexicalOnlyMinimumRrf")
        }
        val artifact = gson.fromJson(root, RetrievalBenchmarkArtifact::class.java)
        artifact.queries.forEach { requireNotNull(it.language) }
        // Gson reflection bypasses constructors; recheck their finite/range invariants.
        return artifact.copy(
            metrics = artifact.metrics?.copy(),
            thresholdCalibration = artifact.thresholdCalibration?.copy()
        )
    }

    private fun JsonObject.requireString(name: String) {
        require(get(name)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true)
    }

    private fun JsonObject.requireNumber(name: String) {
        require(get(name)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } == true)
    }

    companion object {
        const val ASSET_PATH = "ai/retrieval_benchmark.json"
        private const val MAX_ARTIFACT_BYTES = 1024 * 1024
        private val STRING_FIELDS = setOf(
            "schemaVersion",
            "artifactVersion",
            "querySetSha256",
            "corpusSha256",
            "embeddingModelSha256",
            "tokenizerSha256",
            "chunkPolicyVersion",
            "implementationVersion"
        )
        private val ROOT_FIELDS = STRING_FIELDS + setOf("createdAtEpochMillis", "queries", "metrics", "thresholdCalibration")
        private val QUERY_FIELDS = setOf("queryId", "language", "answerable", "expectedChunkIds")
        private val METRIC_FIELDS = setOf("recallAt5", "mrrAt5", "noAnswerPrecision", "citationLocatorAccuracy")
        private val CALIBRATION_FIELDS = setOf("calibrationVersion", "semanticHybridMinimumRrf", "lexicalOnlyMinimumRrf")
    }
}

/** Caller must provide actual active corpus/runtime fingerprints, never the benchmark's own fields. */
data class RetrievalRuntimeIdentity(
    val corpusSha256: String,
    val embeddingModelSha256: String,
    val tokenizerSha256: String,
    val chunkPolicyVersion: String,
    val implementationVersion: String
) {
    fun matches(artifact: RetrievalBenchmarkArtifact): Boolean = corpusSha256 == artifact.corpusSha256 &&
        embeddingModelSha256 == artifact.embeddingModelSha256 &&
        tokenizerSha256 == artifact.tokenizerSha256 &&
        chunkPolicyVersion == artifact.chunkPolicyVersion &&
        implementationVersion == artifact.implementationVersion
}
