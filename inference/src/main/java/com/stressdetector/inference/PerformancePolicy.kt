package com.stressdetector.inference

import android.content.res.AssetManager
import com.google.gson.Gson

/**
 * Versioned release policy emitted by the Python training/export contract.
 *
 * This is deliberately separate from a model fingerprint: changing a release
 * policy must not make a reused internal-test cohort eligible for re-evaluation.
 */
data class MetricGate(val balanced_accuracy: Double, val f1: Double, val roc_auc: Double) {
    fun requireValid() {
        listOf(balanced_accuracy, f1, roc_auc).forEach { value ->
            require(value.isFinite() && value in 0.0..1.0) { "invalid_policy_metric_gate" }
        }
    }

    fun accepts(balancedAccuracy: Double, f1Score: Double, rocAuc: Double): Boolean = balancedAccuracy.isFinite() &&
        f1Score.isFinite() &&
        rocAuc.isFinite() &&
        balancedAccuracy >= balanced_accuracy &&
        f1Score >= f1 &&
        rocAuc >= roc_auc
}

data class PackagedPerformancePolicy(
    val schema_version: String,
    val performance_policy_version: String,
    val performance_policy_sha256: String,
    val minimum_research: MetricGate,
    val preferred: MetricGate,
    val research_disclaimer: String,
    val accuracy_is_gate: Boolean = false
) {
    fun requireValid() {
        require(schema_version == "1.0.0") { "unsupported_performance_policy_schema" }
        require(performance_policy_version.isNotBlank()) { "missing_performance_policy_version" }
        require(performance_policy_sha256.matches(SHA256)) { "invalid_performance_policy_checksum" }
        require(!accuracy_is_gate) { "accuracy_must_not_be_a_release_gate" }
        minimum_research.requireValid()
        preferred.requireValid()
        require(preferred.balanced_accuracy >= minimum_research.balanced_accuracy) {
            "preferred_balanced_accuracy_below_minimum"
        }
        require(preferred.f1 >= minimum_research.f1) { "preferred_f1_below_minimum" }
        require(preferred.roc_auc >= minimum_research.roc_auc) { "preferred_roc_auc_below_minimum" }
        require(research_disclaimer.isNotBlank()) { "missing_research_disclaimer" }
    }

    fun declaredTier(balancedAccuracy: Double, f1Score: Double, rocAuc: Double): PerformanceTier = when {
        !minimum_research.accepts(balancedAccuracy, f1Score, rocAuc) -> PerformanceTier.BELOW_MINIMUM
        preferred.accepts(balancedAccuracy, f1Score, rocAuc) -> PerformanceTier.PREFERRED
        else -> PerformanceTier.RESEARCH_USABLE
    }

    internal fun requireEntryCompatibility(entry: CanonicalModelEntry, manifest: ModelManifest) {
        require(entry.performance_policy_version == performance_policy_version) {
            "index_policy_version_mismatch"
        }
        require(entry.performance_policy_sha256 == performance_policy_sha256) {
            "index_policy_checksum_mismatch"
        }
        require(manifest.performance_policy_version == performance_policy_version) {
            "manifest_policy_version_mismatch"
        }
        require(manifest.performance_policy_sha256 == performance_policy_sha256) {
            "manifest_policy_checksum_mismatch"
        }
        require(entry.performance_tier == manifest.performance_tier) { "index_manifest_tier_mismatch" }
        require(entry.deployment_scope == manifest.deployment_scope) {
            "index_manifest_scope_mismatch"
        }
        require(entry.research_disclaimer == manifest.research_disclaimer) {
            "index_manifest_disclaimer_mismatch"
        }

        require(
            minimum_research.accepts(
                entry.balanced_accuracy_holdout,
                entry.f1_holdout,
                entry.roc_auc_holdout
            )
        ) { "model_holdout_below_minimum_policy" }
        val declared = declaredTier(
            entry.balanced_accuracy_conservative,
            entry.f1_conservative,
            entry.roc_auc_conservative
        )
        require(declared != PerformanceTier.BELOW_MINIMUM) {
            "model_conservative_metrics_below_minimum_policy"
        }
        require(entry.performance_tier == declared.wireName) { "model_performance_tier_invalid" }
        require(entry.deployment_scope == declared.deploymentScope) { "model_deployment_scope_invalid" }
        if (declared == PerformanceTier.RESEARCH_USABLE) {
            require(entry.research_disclaimer == research_disclaimer) {
                "research_disclaimer_mismatch"
            }
            require(entry.disclaimer_required && manifest.disclaimer_required) {
                "research_disclaimer_required"
            }
        }
    }

    internal fun requireDevelopmentEntryCompatibility(entry: CanonicalModelEntry, manifest: ModelManifest) {
        require(entry.performance_policy_version == performance_policy_version) {
            "index_policy_version_mismatch"
        }
        require(entry.performance_policy_sha256 == performance_policy_sha256) {
            "index_policy_checksum_mismatch"
        }
        require(manifest.performance_policy_version == performance_policy_version) {
            "manifest_policy_version_mismatch"
        }
        require(manifest.performance_policy_sha256 == performance_policy_sha256) {
            "manifest_policy_checksum_mismatch"
        }
        require(
            minimum_research.accepts(
                entry.balanced_accuracy_development,
                entry.f1_development,
                entry.roc_auc_development
            )
        ) { "model_development_below_minimum_policy" }
        require(entry.performance_tier == manifest.performance_tier) { "index_manifest_tier_mismatch" }
        require(entry.deployment_scope == "research" && manifest.deployment_scope == "research") {
            "research_scope_mismatch"
        }
        require(entry.research_disclaimer == research_disclaimer && manifest.research_disclaimer == research_disclaimer) {
            "research_disclaimer_mismatch"
        }
        require(entry.disclaimer_required && manifest.disclaimer_required) {
            "research_disclaimer_required"
        }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

enum class PerformanceTier(val wireName: String, val deploymentScope: String) {
    BELOW_MINIMUM("below_minimum", "none"),
    RESEARCH_USABLE("research_usable", "research"),
    PREFERRED("preferred", "standard")
}

object PackagedPerformancePolicyLoader {
    private const val ASSET_PATH = "performance_policy.json"

    fun load(assets: AssetManager): PackagedPerformancePolicy {
        val policyJson = assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        return Gson().fromJson(policyJson, PackagedPerformancePolicy::class.java)
            ?.also(PackagedPerformancePolicy::requireValid)
            ?: error("performance_policy_unreadable")
    }
}
