package com.stressdetector.mobile.ml.rag

/**
 * The only supported bridge from a benchmark artifact to a production
 * retrieval threshold. Absent or invalid artifacts deliberately keep RAG in
 * abstaining mode; no score threshold is invented here.
 */
sealed interface RetrievalPolicyBinding {
    val thresholdPolicy: RetrievalThresholdPolicy
    val benchmarkArtifactVersion: String?
    val validationErrors: List<String>

    data class Calibrated(override val thresholdPolicy: CalibratedRetrievalThresholdPolicy, override val benchmarkArtifactVersion: String) :
        RetrievalPolicyBinding {
        override val validationErrors: List<String> = emptyList()
    }

    data class Uncalibrated(val reason: String, override val validationErrors: List<String> = emptyList()) : RetrievalPolicyBinding {
        override val thresholdPolicy: RetrievalThresholdPolicy = RetrievalThresholdPolicy.UNCALIBRATED
        override val benchmarkArtifactVersion: String? = null
    }
}

object VersionedRetrievalPolicyResolver {
    fun resolve(artifact: RetrievalBenchmarkArtifact?): RetrievalPolicyBinding {
        if (artifact == null) return RetrievalPolicyBinding.Uncalibrated("benchmark_artifact_missing")
        val validation = artifact.validate()
        if (!validation.thresholdReady) {
            return RetrievalPolicyBinding.Uncalibrated(
                reason = "benchmark_artifact_not_calibrated",
                validationErrors = validation.errors
            )
        }
        val thresholdPolicy = artifact.toThresholdPolicy()
        return if (thresholdPolicy is CalibratedRetrievalThresholdPolicy) {
            RetrievalPolicyBinding.Calibrated(thresholdPolicy, artifact.artifactVersion)
        } else {
            RetrievalPolicyBinding.Uncalibrated("benchmark_artifact_policy_rejected")
        }
    }
}

fun LocalRagRetrievalPolicy.withBenchmarkBinding(binding: RetrievalPolicyBinding): LocalRagRetrievalPolicy =
    copy(thresholdPolicy = binding.thresholdPolicy)
