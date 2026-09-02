package com.stressdetector.mobile.ml.rag

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
}
