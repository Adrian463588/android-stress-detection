package com.stressdetector.mobile.ml.rag

/**
 * Language buckets required by the local retrieval benchmark. The benchmark data itself is kept
 * outside the application source; this type only validates a signed, locally produced artifact.
 */
enum class RetrievalBenchmarkLanguage {
    INDONESIAN,
    ENGLISH,
    MIXED_LANGUAGE
}

data class RetrievalBenchmarkQuerySpec(
    val queryId: String,
    val language: RetrievalBenchmarkLanguage,
    val answerable: Boolean,
    val expectedChunkIds: List<String>
)

data class RetrievalBenchmarkMetrics(
    val recallAt5: Float,
    val mrrAt5: Float,
    val noAnswerPrecision: Float,
    val citationLocatorAccuracy: Float
) {
    init {
        listOf(recallAt5, mrrAt5, noAnswerPrecision, citationLocatorAccuracy).forEach {
            require(it in 0f..1f && it.isFinite()) { "Benchmark metrics must be finite probabilities." }
        }
    }
}

data class RetrievalThresholdCalibration(
    val calibrationVersion: String,
    val semanticHybridMinimumRrf: Float,
    val lexicalOnlyMinimumRrf: Float
) {
    init {
        require(calibrationVersion.isNotBlank())
        require(semanticHybridMinimumRrf in 0f..1f && semanticHybridMinimumRrf > 0f)
        require(lexicalOnlyMinimumRrf in 0f..1f && lexicalOnlyMinimumRrf > 0f)
    }
}

data class RetrievalBenchmarkArtifact(
    val schemaVersion: String,
    val artifactVersion: String,
    val querySetSha256: String,
    val corpusSha256: String,
    val embeddingModelSha256: String,
    val tokenizerSha256: String,
    val chunkPolicyVersion: String,
    val implementationVersion: String,
    val createdAtEpochMillis: Long,
    val queries: List<RetrievalBenchmarkQuerySpec>,
    val metrics: RetrievalBenchmarkMetrics? = null,
    val thresholdCalibration: RetrievalThresholdCalibration? = null
) {
    fun validate(): RetrievalBenchmarkValidation {
        val errors = mutableListOf<String>()
        if (schemaVersion != SCHEMA_VERSION) errors += "unsupported_schema_version"
        if (artifactVersion.isBlank()) errors += "missing_artifact_version"
        listOf(
            "querySetSha256" to querySetSha256,
            "corpusSha256" to corpusSha256,
            "embeddingModelSha256" to embeddingModelSha256,
            "tokenizerSha256" to tokenizerSha256
        ).forEach { (name, value) ->
            if (!SHA256.matches(value.lowercase())) errors += "invalid_$name"
        }
        if (chunkPolicyVersion.isBlank()) errors += "missing_chunk_policy_version"
        if (implementationVersion.isBlank()) errors += "missing_implementation_version"
        if (createdAtEpochMillis <= 0L) errors += "invalid_created_at"

        val queryIds = queries.map(RetrievalBenchmarkQuerySpec::queryId)
        if (queries.size != TOTAL_QUERIES) errors += "query_count_${queries.size}_expected_$TOTAL_QUERIES"
        if (queryIds.any(String::isBlank) || queryIds.toSet().size != queryIds.size) {
            errors += "query_ids_not_unique_or_blank"
        }
        EXPECTED_LANGUAGE_COUNTS.forEach { (language, expected) ->
            val actual = queries.count { it.language == language && it.answerable }
            if (actual != expected) {
                errors += "${language.name.lowercase()}_answerable_count_${actual}_expected_$expected"
            }
        }
        val expectedNoAnswer = queries.count { !it.answerable }
        if (expectedNoAnswer != NO_ANSWER_QUERIES) {
            errors += "no_answer_count_${expectedNoAnswer}_expected_$NO_ANSWER_QUERIES"
        }
        queries.forEach { query ->
            if (query.answerable && query.expectedChunkIds.isEmpty()) {
                errors += "${query.queryId}_missing_expected_evidence"
            }
            if (!query.answerable && query.expectedChunkIds.isNotEmpty()) {
                errors += "${query.queryId}_no_answer_has_expected_evidence"
            }
            if (query.expectedChunkIds.any(String::isBlank)) {
                errors += "${query.queryId}_blank_evidence_id"
            }
        }

        metrics?.let { metric ->
            if (metric.recallAt5 < MIN_RECALL_AT_5) errors += "recall_at_5_below_gate"
            if (metric.mrrAt5 < MIN_MRR_AT_5) errors += "mrr_at_5_below_gate"
            if (metric.noAnswerPrecision < MIN_NO_ANSWER_PRECISION) {
                errors += "no_answer_precision_below_gate"
            }
            if (metric.citationLocatorAccuracy < REQUIRED_CITATION_LOCATOR_ACCURACY) {
                errors += "citation_locator_below_gate"
            }
        }
        thresholdCalibration?.let { calibration ->
            if (calibration.calibrationVersion.isBlank()) errors += "missing_calibration_version"
            if (metrics == null) errors += "threshold_without_benchmark_metrics"
        }

        val thresholdReady = errors.isEmpty() && metrics != null && thresholdCalibration != null
        return RetrievalBenchmarkValidation(errors = errors, thresholdReady = thresholdReady)
    }

    /** Returns an abstaining policy until a complete benchmark artifact is supplied. */
    fun toThresholdPolicy(): RetrievalThresholdPolicy {
        val validation = validate()
        if (!validation.thresholdReady) return RetrievalThresholdPolicy.UNCALIBRATED
        val calibration = requireNotNull(thresholdCalibration)
        return CalibratedRetrievalThresholdPolicy(
            calibrationVersion = "benchmark:$artifactVersion:${calibration.calibrationVersion}",
            semanticHybridMinimumRrf = calibration.semanticHybridMinimumRrf,
            lexicalOnlyMinimumRrf = calibration.lexicalOnlyMinimumRrf
        )
    }

    companion object {
        const val SCHEMA_VERSION = "rag-retrieval-benchmark-v1"
        const val TOTAL_QUERIES = 60
        const val NO_ANSWER_QUERIES = 15
        const val MIN_RECALL_AT_5 = 0.90f
        const val MIN_MRR_AT_5 = 0.80f
        const val MIN_NO_ANSWER_PRECISION = 0.90f
        const val REQUIRED_CITATION_LOCATOR_ACCURACY = 1.0f
        private val SHA256 = Regex("^[a-f0-9]{64}$")
        private val EXPECTED_LANGUAGE_COUNTS = mapOf(
            RetrievalBenchmarkLanguage.INDONESIAN to 20,
            RetrievalBenchmarkLanguage.ENGLISH to 15,
            RetrievalBenchmarkLanguage.MIXED_LANGUAGE to 10
        )
    }
}

data class RetrievalBenchmarkValidation(val errors: List<String>, val thresholdReady: Boolean) {
    val isValid: Boolean get() = errors.isEmpty()
}

object RetrievalProductionReadiness {
    const val SQLITE_FTS5_REQUIRED = "SQLite FTS5 BM25 backend is required for production retrieval."

    fun requireFts5(status: LexicalBackendStatus) {
        check(status.productionReady && status.kind == LexicalBackendKind.SQLITE_FTS5_BM25) {
            SQLITE_FTS5_REQUIRED
        }
    }
}
