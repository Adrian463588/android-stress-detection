package com.stressdetector.mobile.ml.rag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalBenchmarkTest {
    @Test
    fun `threshold policy abstains when benchmark artifact is absent`() {
        val policy = benchmarkArtifact().toThresholdPolicy()

        val accepted = policy.accepts(
            RetrievalThresholdContext(
                query = "cemas",
                mode = LocalRetrievalMode.LEXICAL_ONLY,
                candidates = emptyList()
            )
        )

        assertFalse(accepted)
        assertFalse(benchmarkArtifact().validate().thresholdReady)
    }

    @Test
    fun `schema rejects incomplete query set`() {
        val invalid = benchmarkArtifact().copy(queries = emptyList()).validate()

        assertFalse(invalid.isValid)
        assertTrue(invalid.errors.any { it.startsWith("query_count_") })
    }

    @Test
    fun `schema accepts the required language and answerability counts without results`() {
        val validation = benchmarkArtifact().validate()

        assertTrue(validation.isValid)
        assertFalse(validation.thresholdReady)
    }

    private fun benchmarkArtifact(): RetrievalBenchmarkArtifact {
        val queries = buildList {
            repeat(20) { add(query("id-$it", RetrievalBenchmarkLanguage.INDONESIAN, true)) }
            repeat(15) { add(query("en-$it", RetrievalBenchmarkLanguage.ENGLISH, true)) }
            repeat(10) { add(query("mix-$it", RetrievalBenchmarkLanguage.MIXED_LANGUAGE, true)) }
            repeat(15) { add(query("none-$it", RetrievalBenchmarkLanguage.INDONESIAN, false)) }
        }
        return RetrievalBenchmarkArtifact(
            schemaVersion = RetrievalBenchmarkArtifact.SCHEMA_VERSION,
            artifactVersion = "schema-test",
            querySetSha256 = "a".repeat(64),
            corpusSha256 = "b".repeat(64),
            embeddingModelSha256 = "c".repeat(64),
            tokenizerSha256 = "d".repeat(64),
            chunkPolicyVersion = TextChunker.POLICY_VERSION,
            implementationVersion = "test-only-schema",
            createdAtEpochMillis = 1L,
            queries = queries
        )
    }

    private fun query(id: String, language: RetrievalBenchmarkLanguage, answerable: Boolean) = RetrievalBenchmarkQuerySpec(
        queryId = id,
        language = language,
        answerable = answerable,
        expectedChunkIds = if (answerable) listOf("evidence-$id") else emptyList()
    )
}
