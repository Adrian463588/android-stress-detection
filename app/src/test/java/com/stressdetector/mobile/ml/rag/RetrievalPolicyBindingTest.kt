package com.stressdetector.mobile.ml.rag

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalPolicyBindingTest {
    @Test
    fun `missing benchmark remains explicitly uncalibrated`() {
        val binding = VersionedRetrievalPolicyResolver.resolve(null)

        assertTrue(binding is RetrievalPolicyBinding.Uncalibrated)
        assertEquals("benchmark_artifact_missing", (binding as RetrievalPolicyBinding.Uncalibrated).reason)
    }

    @Test
    fun `complete benchmark injects its versioned calibration`() {
        val binding = VersionedRetrievalPolicyResolver.resolve(completeArtifact())

        assertTrue(binding is RetrievalPolicyBinding.Calibrated)
        assertEquals("benchmark-v1", binding.benchmarkArtifactVersion)
        assertEquals(
            "benchmark:benchmark-v1:calibration-v1",
            (binding as RetrievalPolicyBinding.Calibrated).thresholdPolicy.calibrationVersion
        )
    }

    @Test
    fun `asset loader reads and validates supplied benchmark instead of missing constant`() {
        var openedPath: String? = null
        val bytes = Gson().toJson(completeArtifact()).toByteArray(Charsets.UTF_8)
        val input = object : ByteArrayInputStream(bytes) {
            var closed = false
            override fun close() {
                closed = true
                super.close()
            }
        }
        val binding = AssetRetrievalBenchmark(runtimeIdentity()) { path ->
            openedPath = path
            input
        }.load()

        assertEquals("ai/retrieval_benchmark.json", openedPath)
        assertTrue(input.closed)
        assertEquals(VersionedRetrievalPolicyResolver.resolve(completeArtifact()), binding)
    }

    @Test
    fun `complete artifact cannot calibrate absent or mismatched active runtime`() {
        val raw = Gson().toJson(completeArtifact()).toByteArray()
        val absent = AssetRetrievalBenchmark { ByteArrayInputStream(raw) }.load()
        assertEquals("benchmark_runtime_identity_unavailable", (absent as RetrievalPolicyBinding.Uncalibrated).reason)
        listOf(
            runtimeIdentity().copy(corpusSha256 = "e".repeat(64)),
            runtimeIdentity().copy(embeddingModelSha256 = "e".repeat(64)),
            runtimeIdentity().copy(tokenizerSha256 = "e".repeat(64)),
            runtimeIdentity().copy(chunkPolicyVersion = "other-policy"),
            runtimeIdentity().copy(implementationVersion = "other-implementation")
        ).forEach { identity ->
            val binding = AssetRetrievalBenchmark(identity) { ByteArrayInputStream(raw) }.load()
            assertEquals("benchmark_runtime_identity_mismatch", (binding as RetrievalPolicyBinding.Uncalibrated).reason)
        }
    }

    @Test
    fun `missing unreadable and malformed assets stay uncalibrated`() {
        val missing = AssetRetrievalBenchmark { throw FileNotFoundException("missing") }.load()
        val unreadable = AssetRetrievalBenchmark { throw IOException("cannot read") }.load()
        val malformed = assetBinding("not JSON")

        assertEquals("benchmark_artifact_missing", (missing as RetrievalPolicyBinding.Uncalibrated).reason)
        assertEquals("benchmark_artifact_unreadable", (unreadable as RetrievalPolicyBinding.Uncalibrated).reason)
        assertEquals("benchmark_artifact_invalid", (malformed as RetrievalPolicyBinding.Uncalibrated).reason)
    }

    @Test
    fun `loader rejects invalid scalar types and omitted nested fields`() {
        val mutations = listOf<(JsonObject) -> Unit>(
            { it.getAsJsonObject("metrics").addProperty("recallAt5", 1.1f) },
            { it.getAsJsonObject("metrics").addProperty("recallAt5", "0.91") },
            { it.getAsJsonObject("metrics").remove("mrrAt5") },
            { it.getAsJsonObject("thresholdCalibration").addProperty("lexicalOnlyMinimumRrf", -0.1f) },
            { it.getAsJsonObject("thresholdCalibration").remove("semanticHybridMinimumRrf") },
            { it.getAsJsonArray("queries")[0].asJsonObject.remove("answerable") },
            { it.getAsJsonArray("queries")[0].asJsonObject.addProperty("answerable", "true") },
            { it.getAsJsonArray("queries")[0].asJsonObject.addProperty("language", "UNKNOWN") },
            { it.addProperty("createdAtEpochMillis", 1.5) },
            { it.addProperty("schemaVersion", "wrong-schema") },
            { it.addProperty("unrecognizedField", true) }
        )
        mutations.forEach { mutate ->
            val root = JsonParser.parseString(Gson().toJson(completeArtifact())).asJsonObject
            mutate(root)
            assertTrue("Invalid benchmark admitted", assetBinding(root.toString()) is RetrievalPolicyBinding.Uncalibrated)
        }
    }

    @Test
    fun `missing metrics calibration or valid query inventory cannot enable retrieval`() {
        listOf(
            completeArtifact().copy(metrics = null),
            completeArtifact().copy(thresholdCalibration = null),
            completeArtifact().copy(queries = emptyList()),
            completeArtifact().copy(metrics = RetrievalBenchmarkMetrics(0.1f, 0.1f, 0.1f, 1f))
        ).forEach { artifact ->
            assertTrue(assetBinding(Gson().toJson(artifact)) is RetrievalPolicyBinding.Uncalibrated)
        }
    }

    @Test
    fun `loader rejects trailing data and oversized artifact`() {
        val validJson = Gson().toJson(completeArtifact())
        assertTrue(assetBinding("$validJson {}") is RetrievalPolicyBinding.Uncalibrated)
        assertTrue(assetBinding(" ".repeat(1024 * 1024 + 1)) is RetrievalPolicyBinding.Uncalibrated)
    }

    @Test
    fun `asset cancellation propagates`() {
        val cancellation = CancellationException("asset cancelled")
        val result = runCatching { AssetRetrievalBenchmark { throw cancellation }.load() }
        assertTrue(result.exceptionOrNull() === cancellation)
    }

    private fun assetBinding(raw: String): RetrievalPolicyBinding = AssetRetrievalBenchmark {
        ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8))
    }.load()

    private fun completeArtifact(): RetrievalBenchmarkArtifact = RetrievalBenchmarkArtifact(
        schemaVersion = RetrievalBenchmarkArtifact.SCHEMA_VERSION,
        artifactVersion = "benchmark-v1",
        querySetSha256 = "a".repeat(64),
        corpusSha256 = "b".repeat(64),
        embeddingModelSha256 = "c".repeat(64),
        tokenizerSha256 = "d".repeat(64),
        chunkPolicyVersion = TextChunker.POLICY_VERSION,
        implementationVersion = "retrieval-test-v1",
        createdAtEpochMillis = 1L,
        queries = buildList {
            repeat(20) { add(query("id-$it", RetrievalBenchmarkLanguage.INDONESIAN, true)) }
            repeat(15) { add(query("en-$it", RetrievalBenchmarkLanguage.ENGLISH, true)) }
            repeat(10) { add(query("mix-$it", RetrievalBenchmarkLanguage.MIXED_LANGUAGE, true)) }
            repeat(15) { add(query("none-$it", RetrievalBenchmarkLanguage.INDONESIAN, false)) }
        },
        metrics = RetrievalBenchmarkMetrics(0.91f, 0.81f, 0.91f, 1.0f),
        thresholdCalibration = RetrievalThresholdCalibration("calibration-v1", 0.10f, 0.08f)
    )

    private fun query(id: String, language: RetrievalBenchmarkLanguage, answerable: Boolean): RetrievalBenchmarkQuerySpec =
        RetrievalBenchmarkQuerySpec(
            queryId = id,
            language = language,
            answerable = answerable,
            expectedChunkIds = if (answerable) listOf("evidence-$id") else emptyList()
        )

    private fun runtimeIdentity() = RetrievalRuntimeIdentity(
        corpusSha256 = "b".repeat(64),
        embeddingModelSha256 = "c".repeat(64),
        tokenizerSha256 = "d".repeat(64),
        chunkPolicyVersion = TextChunker.POLICY_VERSION,
        implementationVersion = "retrieval-test-v1"
    )
}
