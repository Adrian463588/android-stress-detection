@file:Suppress("ConstructorParameterNaming")

package com.stressdetector.mobile.signal

import android.content.Context
import com.google.gson.Gson
import com.stressdetector.inference.ModelSessionContract
import com.stressdetector.inference.PackagedPerformancePolicy
import com.stressdetector.inference.PackagedPerformancePolicyLoader
import com.stressdetector.mobile.BuildConfig
import com.stressdetector.shared.PpgType

private fun String.toLivePpgType(): PpgType = when (this) {
    "ppg_green" -> PpgType.GREEN
    "ppg_ir" -> PpgType.IR
    else -> error("Unsupported live monitor channel: $this")
}

data class LiveMonitorConfig(
    val adrReference: String,
    val inferenceStepSeconds: Int,
    val minimumQualityScore: Float,
    val maxTimestampGapDeltaMs: Double,
    val maxTimestampGapCountPerSecond: Int,
    val maxFlatlineRatioUsable: Double,
    val maxFlatlineRatioExcellent: Double,
    val maxMissingRatioUsable: Double = 0.05,
    val maxClippingRatioUsable: Double = 0.01,
    val maxArtifactRatioUsable: Double = 0.10,
    val maxSamplingJitterUsable: Double = 0.20,
    val divergenceDeltaMin: Float,
    val divergenceProbabilityMin: Float,
    val baselineWindowCount: Int,
    val divergencePersistenceObservations: Int = 2,
    val divergenceWarmupObservations: Int = 2,
    val divergenceRecoveryHysteresis: Float = 0.05f,
    val divergenceCooldownSeconds: Int = 20,
    val markerConfigVersion: String = "physiological-markers-v1",
    val bpmWindowSeconds: Int = 30,
    val bpmBaselineWindowSeconds: Int = 60,
    val bpmRiseAbsoluteThreshold: Float = 10f,
    val bpmRiseRelativeThreshold: Float = 0.15f,
    val bpmRiseSustainSeconds: Int = 10,
    val bpmRecoveryAbsoluteThreshold: Float = 5f,
    val bpmRecoveryRelativeThreshold: Float = 0.05f,
    val bpmRecoveryWithinSeconds: Int = 60,
    val markerMergeGapSeconds: Int = 10,
    val channelAgreementDeltaMin: Float = 0.20f,
    val primaryChannel: PpgType = PpgType.GREEN,
    val fallbackChannel: PpgType = PpgType.IR
) {
    init {
        require(adrReference == "ADR-012") { "Live Monitor thresholds must be backed by ADR-012" }
        require(inferenceStepSeconds > 0)
        require(minimumQualityScore in 0f..1f)
        require(maxTimestampGapDeltaMs >= 0.0)
        require(maxTimestampGapCountPerSecond >= 0)
        require(maxFlatlineRatioExcellent in 0.0..maxFlatlineRatioUsable)
        require(maxFlatlineRatioUsable in 0.0..1.0)
        require(maxMissingRatioUsable in 0.0..1.0)
        require(maxClippingRatioUsable in 0.0..1.0)
        require(maxArtifactRatioUsable in 0.0..1.0)
        require(maxSamplingJitterUsable in 0.0..1.0)
        require(divergenceDeltaMin in 0f..1f)
        require(divergenceProbabilityMin in 0f..1f)
        require(baselineWindowCount > 0)
        require(divergencePersistenceObservations > 0)
        require(divergenceWarmupObservations > 0)
        require(divergenceRecoveryHysteresis in 0f..divergenceDeltaMin)
        require(divergenceCooldownSeconds >= 0)
        require(markerConfigVersion.isNotBlank())
        require(bpmWindowSeconds == 30) { "BPM marker window is fixed at 30 seconds" }
        require(bpmBaselineWindowSeconds == 60) { "BPM baseline window is fixed at 60 seconds" }
        require(bpmRiseAbsoluteThreshold > 0f)
        require(bpmRiseRelativeThreshold > 0f)
        require(bpmRiseSustainSeconds == 10) { "BPM rise persistence is fixed at 10 seconds" }
        require(bpmRecoveryAbsoluteThreshold > 0f)
        require(bpmRecoveryRelativeThreshold > 0f)
        require(bpmRecoveryWithinSeconds == 60) { "BPM recovery window is fixed at 60 seconds" }
        require(markerMergeGapSeconds > 0)
        require(channelAgreementDeltaMin in 0f..1f)
        require(primaryChannel != fallbackChannel) { "Live Monitor primary and fallback channels must differ" }
        require(setOf(primaryChannel, fallbackChannel) == setOf(PpgType.GREEN, PpgType.IR)) {
            "Live Monitor supports only ppg_green primary and ppg_ir fallback"
        }
    }
}

data class LiveMonitorRuntimeContract(
    val config: LiveMonitorConfig,
    val requiredChannels: Set<PpgType>,
    /** Channels whose canonical ONNX bundle passed Android readiness gates. */
    val modelReadyChannels: Set<PpgType> = requiredChannels,
    val modelContracts: Map<PpgType, LiveModelContract> = emptyMap(),
    val primaryChannel: PpgType = config.primaryChannel,
    val fallbackChannel: PpgType = config.fallbackChannel
)

data class LiveModelContract(
    val modelVersion: String,
    val analysisSeconds: Int,
    val windowSeconds: Int,
    val strideSeconds: Int,
    val expectedWindowsPerSession: Int,
    val sourceRateHz: Double = 0.0,
    val modelRateHz: Double = 0.0
) {
    fun requireValid() {
        require(modelVersion.isNotBlank()) { "missing_live_model_version" }
        require(sourceRateHz.isFinite() && sourceRateHz > 0.0) { "invalid_live_source_rate" }
        require(modelRateHz.isFinite() && modelRateHz > 0.0) { "invalid_live_model_rate" }
        require(analysisSeconds == 240) { "live_analysis_core_must_be_240_seconds" }
        require(windowSeconds in 1..analysisSeconds) { "invalid_live_model_window" }
        require(strideSeconds in 1..windowSeconds) { "invalid_live_model_stride" }
        require((analysisSeconds - windowSeconds) % strideSeconds == 0) {
            "non_integral_live_window_contract"
        }
        require(expectedWindowsPerSession == ((analysisSeconds - windowSeconds) / strideSeconds) + 1) {
            "live_expected_window_count_mismatch"
        }
    }
}

class AssetLiveMonitorRuntimeContract(private val context: Context) {
    fun loadConfig(): LiveMonitorConfig {
        val configJson = context.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
        return parseConfigJson(configJson)
    }

    fun load(): LiveMonitorRuntimeContract {
        val config = loadConfig()
        val researchMode = BuildConfig.RESEARCH_MODEL_MODE
        val indexJson = context.assets.open(MODEL_INDEX_ASSET).bufferedReader().use { it.readText() }
        val index = Gson().fromJson(indexJson, CanonicalIndexWire::class.java)
        require(index.schema_version == "1.0.0") { "unsupported_model_index_schema" }
        if (index.models.isEmpty()) {
            require(index.release_status.startsWith("blocked_")) {
                "empty_model_index_must_be_explicitly_blocked"
            }
            return buildContract(config, emptyMap())
        }
        val expectedIndexStatus = if (researchMode) "research_candidate" else "accepted"
        require(index.release_status == expectedIndexStatus) { "canonical_model_index_not_accepted" }
        val policy = PackagedPerformancePolicyLoader.load(context.assets)
        val accepted = index.models.filter {
            if (researchMode) {
                it.artifact_status == "research_candidate" &&
                    it.evaluation_scope == "development_only" &&
                    it.release_status == "blocked_existing_holdout_contract_mismatch" &&
                    !it.android_ready
            } else {
                it.release_status == "accepted" && it.android_ready
            }
        }
        val acceptedTypes = accepted.map(CanonicalModelWire::toPpgType).toSet()
        require(acceptedTypes.isNotEmpty()) { "no_android_ready_live_model" }
        require(acceptedTypes.size == accepted.size) { "duplicate_live_model_channel" }
        require(acceptedTypes.all(PpgType.productionChannels::contains)) {
            "Live Monitor accepts only independent ppg_green and ppg_ir models"
        }
        val modelContracts = accepted.associate { model ->
            model.requireAndroidRateContract()
            model.requirePerformancePolicy(policy, researchMode)
            model.toPpgType() to model.toLiveModelContract().also(LiveModelContract::requireValid)
        }
        return buildContract(config, modelContracts)
    }

    /** Uses the same validated repository-backed manifests as Home. */
    fun load(manifestContracts: Map<PpgType, ModelSessionContract>): LiveMonitorRuntimeContract {
        val config = loadConfig()
        val contracts = manifestContracts
            .filterKeys(PpgType.productionChannels::contains)
            .mapValues { (_, contract) ->
                LiveModelContract(
                    modelVersion = contract.modelVersion,
                    analysisSeconds = contract.analysisSeconds,
                    windowSeconds = contract.windowSeconds,
                    strideSeconds = contract.strideSeconds,
                    expectedWindowsPerSession = contract.expectedWindowsPerSession,
                    sourceRateHz = contract.sourceRateHz,
                    modelRateHz = contract.modelRateHz
                ).also(LiveModelContract::requireValid)
            }
        return buildContract(config, contracts)
    }

    private fun buildContract(config: LiveMonitorConfig, modelContracts: Map<PpgType, LiveModelContract>): LiveMonitorRuntimeContract {
        val primary = PpgType.GREEN.takeIf(modelContracts::containsKey)
            ?: PpgType.IR.takeIf(modelContracts::containsKey)
            ?: config.primaryChannel
        val fallback = if (primary == PpgType.GREEN) PpgType.IR else PpgType.GREEN
        return LiveMonitorRuntimeContract(
            config = config,
            requiredChannels = PpgType.productionChannels,
            modelReadyChannels = modelContracts.keys,
            modelContracts = modelContracts,
            primaryChannel = primary,
            fallbackChannel = fallback
        )
    }

    private data class ConfigWire(
        val adr_reference: String,
        val inference_step_seconds: Int,
        val minimum_quality_score: Float,
        val max_timestamp_gap_delta_ms: Double,
        val max_timestamp_gap_count_per_second: Int,
        val max_flatline_ratio_usable: Double,
        val max_flatline_ratio_excellent: Double,
        val max_missing_ratio_usable: Double? = null,
        val max_clipping_ratio_usable: Double? = null,
        val max_artifact_ratio_usable: Double? = null,
        val max_sampling_jitter_usable: Double? = null,
        val divergence_delta_min: Float,
        val divergence_probability_min: Float,
        val baseline_window_count: Int,
        val divergence_persistence_observations: Int? = null,
        val divergence_warmup_observations: Int? = null,
        val divergence_recovery_hysteresis: Float? = null,
        val divergence_cooldown_seconds: Int? = null,
        val marker_config_version: String? = null,
        val bpm_window_seconds: Int = 30,
        val bpm_baseline_window_seconds: Int = 60,
        val bpm_rise_absolute_threshold: Float = 10f,
        val bpm_rise_relative_threshold: Float = 0.15f,
        val bpm_rise_sustain_seconds: Int = 10,
        val bpm_recovery_absolute_threshold: Float = 5f,
        val bpm_recovery_relative_threshold: Float = 0.05f,
        val bpm_recovery_within_seconds: Int = 60,
        val marker_merge_gap_seconds: Int = 10,
        val channel_agreement_delta_min: Float = 0.20f,
        val primary_channel: String = "ppg_green",
        val fallback_channel: String = "ppg_ir"
    ) {
        fun toDomain() = LiveMonitorConfig(
            adrReference = adr_reference,
            inferenceStepSeconds = inference_step_seconds,
            minimumQualityScore = minimum_quality_score,
            maxTimestampGapDeltaMs = max_timestamp_gap_delta_ms,
            maxTimestampGapCountPerSecond = max_timestamp_gap_count_per_second,
            maxFlatlineRatioUsable = max_flatline_ratio_usable,
            maxFlatlineRatioExcellent = max_flatline_ratio_excellent,
            maxMissingRatioUsable = max_missing_ratio_usable ?: 0.05,
            maxClippingRatioUsable = max_clipping_ratio_usable ?: 0.01,
            maxArtifactRatioUsable = max_artifact_ratio_usable ?: 0.10,
            maxSamplingJitterUsable = max_sampling_jitter_usable ?: 0.20,
            divergenceDeltaMin = divergence_delta_min,
            divergenceProbabilityMin = divergence_probability_min,
            baselineWindowCount = baseline_window_count,
            divergencePersistenceObservations = divergence_persistence_observations ?: 2,
            divergenceWarmupObservations = divergence_warmup_observations ?: 2,
            divergenceRecoveryHysteresis = divergence_recovery_hysteresis ?: 0.05f,
            divergenceCooldownSeconds = divergence_cooldown_seconds ?: 20,
            markerConfigVersion = marker_config_version ?: "physiological-markers-v1",
            bpmWindowSeconds = bpm_window_seconds,
            bpmBaselineWindowSeconds = bpm_baseline_window_seconds,
            bpmRiseAbsoluteThreshold = bpm_rise_absolute_threshold,
            bpmRiseRelativeThreshold = bpm_rise_relative_threshold,
            bpmRiseSustainSeconds = bpm_rise_sustain_seconds,
            bpmRecoveryAbsoluteThreshold = bpm_recovery_absolute_threshold,
            bpmRecoveryRelativeThreshold = bpm_recovery_relative_threshold,
            bpmRecoveryWithinSeconds = bpm_recovery_within_seconds,
            markerMergeGapSeconds = marker_merge_gap_seconds,
            channelAgreementDeltaMin = channel_agreement_delta_min,
            primaryChannel = primary_channel.toLivePpgType(),
            fallbackChannel = fallback_channel.toLivePpgType()
        )
    }

    private data class CanonicalIndexWire(
        val schema_version: String,
        val release_status: String,
        val models: List<CanonicalModelWire> = emptyList()
    )

    private data class CanonicalModelWire(
        val model_version: String = "",
        val sensor_channel: String,
        val artifact_status: String = "",
        val evaluation_scope: String = "",
        val release_status: String,
        val android_ready: Boolean = false,
        val source_rate_hz: Double = 0.0,
        val model_rate_hz: Double = 0.0,
        val balanced_accuracy_holdout: Double = Double.NaN,
        val f1_holdout: Double = Double.NaN,
        val roc_auc_holdout: Double = Double.NaN,
        val balanced_accuracy_development: Double = Double.NaN,
        val f1_development: Double = Double.NaN,
        val roc_auc_development: Double = Double.NaN,
        val performance_policy_version: String = "",
        val performance_policy_sha256: String = "",
        val performance_tier: String = "",
        val deployment_scope: String = "",
        val research_disclaimer: String = "",
        val disclaimer_required: Boolean = false,
        val analysis_seconds: Int = 0,
        val window_seconds: Int = 0,
        val stride_seconds: Int = 0,
        val expected_windows_per_session: Int = 0
    ) {
        fun toPpgType(): PpgType = when (sensor_channel) {
            "ppg_green" -> PpgType.GREEN
            "ppg_ir" -> PpgType.IR
            "ppg_red", "fusion" -> error("Red and fusion are prohibited for Android inference")
            else -> error("Canonical index declares unsupported live channel $sensor_channel")
        }

        fun requireAndroidRateContract() {
            require(source_rate_hz.isFinite() && source_rate_hz > 0.0) {
                "invalid_source_rate_contract"
            }
            require(model_rate_hz.isFinite() && model_rate_hz > 0.0) {
                "invalid_model_rate_contract"
            }
        }

        fun requirePerformancePolicy(policy: PackagedPerformancePolicy, researchMode: Boolean) {
            require(performance_policy_version == policy.performance_policy_version) {
                "live_policy_version_mismatch"
            }
            require(performance_policy_sha256 == policy.performance_policy_sha256) {
                "live_policy_checksum_mismatch"
            }
            val expected = if (researchMode) {
                require(
                    policy.minimum_research.accepts(
                        balanced_accuracy_development,
                        f1_development,
                        roc_auc_development
                    )
                ) { "live_development_metrics_below_minimum" }
                policy.declaredTier(
                    balanced_accuracy_development,
                    f1_development,
                    roc_auc_development
                )
            } else {
                policy.declaredTier(
                    balanced_accuracy_holdout,
                    f1_holdout,
                    roc_auc_holdout
                )
            }
            require(performance_tier == expected.wireName) { "live_performance_tier_mismatch" }
            require(deployment_scope == if (researchMode) "research" else expected.deploymentScope) {
                "live_deployment_scope_mismatch"
            }
            if (researchMode || expected.wireName == "research_usable") {
                require(disclaimer_required && research_disclaimer == policy.research_disclaimer) {
                    "live_research_disclaimer_mismatch"
                }
            }
        }

        fun toLiveModelContract(): LiveModelContract = LiveModelContract(
            modelVersion = model_version,
            analysisSeconds = analysis_seconds,
            windowSeconds = window_seconds,
            strideSeconds = stride_seconds,
            expectedWindowsPerSession = expected_windows_per_session,
            sourceRateHz = source_rate_hz,
            modelRateHz = model_rate_hz
        )
    }

    internal companion object {
        internal fun parseConfigJson(configJson: String): LiveMonitorConfig = Gson().fromJson(configJson, ConfigWire::class.java).toDomain()

        const val CONFIG_ASSET = "live_monitor_config.json"
        const val MODEL_INDEX_ASSET = "model_index.json"
    }
}
