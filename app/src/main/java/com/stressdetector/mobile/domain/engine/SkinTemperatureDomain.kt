package com.stressdetector.mobile.domain.engine

enum class ThermalDirection {
    INCREASE,
    DECREASE,
    RECOVERY
}

enum class ThermalSignalStatus {
    UNAVAILABLE,
    DISCONNECTED,
    WARMING_UP,
    ACTIVE,
    INVALID_STATUS,
    AMBIENT_CONFOUND,
    STALE,
    PAUSED
}

data class ThermalPolicyConfig(
    val warmupDurationMs: Long = DEFAULT_WARMUP_MS,
    val analysisWindowMs: Long = DEFAULT_ANALYSIS_WINDOW_MS,
    val persistenceMs: Long = DEFAULT_PERSISTENCE_MS,
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    val chartWindowMs: Long = DEFAULT_CHART_WINDOW_MS,
    val staleTimeoutMs: Long = DEFAULT_STALE_TIMEOUT_MS,
    val contactLossResetMs: Long = DEFAULT_CONTACT_LOSS_RESET_MS,
    val deltaThresholdC: Float = DEFAULT_DELTA_THRESHOLD_C,
    val hysteresisC: Float = DEFAULT_HYSTERESIS_C,
    val maxAmbientSwingC: Float = DEFAULT_MAX_AMBIENT_SWING_C,
    val minWarmupValidSamples: Int = DEFAULT_MIN_WARMUP_SAMPLES,
    val minAnalysisValidSamples: Int = DEFAULT_MIN_ANALYSIS_SAMPLES,
    val autoMarkerEnabled: Boolean = false,
    val algorithmVersion: String = DEFAULT_ALGORITHM_VERSION
) {
    init {
        require(warmupDurationMs >= 0L) { "warmupDurationMs must be non-negative" }
        require(analysisWindowMs > 0L) { "analysisWindowMs must be positive" }
        require(persistenceMs >= 0L) { "persistenceMs must be non-negative" }
        require(cooldownMs >= 0L) { "cooldownMs must be non-negative" }
        require(chartWindowMs > 0L) { "chartWindowMs must be positive" }
        require(staleTimeoutMs > 0L) { "staleTimeoutMs must be positive" }
        require(deltaThresholdC > 0f) { "deltaThresholdC must be positive" }
        require(hysteresisC >= 0f && hysteresisC < deltaThresholdC) {
            "hysteresisC must be in [0, deltaThresholdC)"
        }
        require(maxAmbientSwingC > 0f) { "maxAmbientSwingC must be positive" }
        require(algorithmVersion.isNotBlank()) { "algorithmVersion must not be blank" }
    }

    companion object {
        const val DEFAULT_WARMUP_MS = 180_000L
        const val DEFAULT_ANALYSIS_WINDOW_MS = 60_000L
        const val DEFAULT_PERSISTENCE_MS = 30_000L
        const val DEFAULT_COOLDOWN_MS = 60_000L
        const val DEFAULT_CHART_WINDOW_MS = 120_000L
        const val DEFAULT_STALE_TIMEOUT_MS = 90_000L
        const val DEFAULT_CONTACT_LOSS_RESET_MS = 30_000L
        const val DEFAULT_DELTA_THRESHOLD_C = 0.40f
        const val DEFAULT_HYSTERESIS_C = 0.15f
        const val DEFAULT_MAX_AMBIENT_SWING_C = 1.50f
        const val DEFAULT_MIN_WARMUP_SAMPLES = 5
        const val DEFAULT_MIN_ANALYSIS_SAMPLES = 2
        const val DEFAULT_ALGORITHM_VERSION = "thermal-median-v1"
        const val MARKER_TYPE_THERMAL_DIVERGENCE = "THERMAL_DIVERGENCE"

        val CANDIDATE_OBSERVE_ONLY = ThermalPolicyConfig(autoMarkerEnabled = false)
        val VALIDATED_AUTO_MARKER = ThermalPolicyConfig(autoMarkerEnabled = true)
    }
}

data class SkinTemperatureObservation(
    val sampleId: String,
    val sessionId: String,
    val generation: Long,
    val sequenceNumber: Long,
    val sensorTimestampMs: Long,
    val receivedTimestampMs: Long,
    val elapsedMillis: Long,
    val clockUncertaintyMs: Long?,
    val skinTemperatureC: Float?,
    val ambientTemperatureC: Float?,
    val measurementStatus: Int,
    val isValid: Boolean,
    val wearerRole: String,
    val source: String,
    val isPaused: Boolean = false
)

data class LiveTemperatureSnapshot(
    val status: ThermalSignalStatus = ThermalSignalStatus.UNAVAILABLE,
    val currentSkinTempC: Float? = null,
    val baselineSkinTempC: Float? = null,
    val deltaC: Float? = null,
    val ambientTempC: Float? = null,
    val ambientDeltaC: Float? = null,
    val slopeCPerMin: Float? = null,
    val minSkinTempC: Float? = null,
    val maxSkinTempC: Float? = null,
    val meanSkinTempC: Float? = null,
    val medianSkinTempC: Float? = null,
    val validSampleCount: Int = 0,
    val totalSampleCount: Int = 0,
    val measurementStatusCode: Int? = null,
    val lastUpdatedElapsedMillis: Long? = null,
    val warmupRemainingSeconds: Int = 0,
    val autoMarkerEnabled: Boolean = false,
    val algorithmVersion: String = ThermalPolicyConfig.DEFAULT_ALGORITHM_VERSION,
    val statusReason: String? = null,
    val recentPoints: List<SkinTemperatureObservation> = emptyList(),
    val activeEpisodeId: String? = null,
    val activeDirection: ThermalDirection? = null
)

data class ThermalStatisticsSummary(
    val validCount: Int,
    val totalCount: Int,
    val minC: Float?,
    val maxC: Float?,
    val meanC: Float?,
    val medianC: Float?,
    val latestValidC: Float?,
    val baselineC: Float?,
    val deltaC: Float?,
    val latestAmbientC: Float?,
    val slopeCPerMin: Float?,
    val coverageRatio: Float
)

fun computeSkinTemperatureStatistics(
    observations: List<SkinTemperatureObservation>,
    baselineC: Float? = null,
    slopeCPerMin: Float? = null
): ThermalStatisticsSummary {
    val valid = observations.filter { it.isValid && it.skinTemperatureC != null && it.skinTemperatureC.isFinite() }
        .sortedBy { it.elapsedMillis }
    val values = valid.mapNotNull { it.skinTemperatureC }
    val minC = values.minOrNull()
    val maxC = values.maxOrNull()
    val meanC = if (values.isNotEmpty()) (values.sumOf { it.toDouble() } / values.size).toFloat() else null
    val medianC = medianFloat(values)
    val latestValidC = valid.lastOrNull()?.skinTemperatureC
    val resolvedBaseline = baselineC ?: medianC
    val deltaC = if (latestValidC != null && resolvedBaseline != null) latestValidC - resolvedBaseline else null
    val latestAmbientC = observations
        .asSequence()
        .filter { it.ambientTemperatureC != null && it.ambientTemperatureC.isFinite() }
        .maxByOrNull { it.elapsedMillis }
        ?.ambientTemperatureC
    val coverage = if (observations.isNotEmpty()) valid.size.toFloat() / observations.size.toFloat() else 0f
    return ThermalStatisticsSummary(
        validCount = valid.size,
        totalCount = observations.size,
        minC = minC,
        maxC = maxC,
        meanC = meanC,
        medianC = medianC,
        latestValidC = latestValidC,
        baselineC = resolvedBaseline,
        deltaC = deltaC,
        latestAmbientC = latestAmbientC,
        slopeCPerMin = slopeCPerMin,
        coverageRatio = coverage
    )
}

internal fun medianFloat(values: List<Float>): Float? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[mid]
    } else {
        (sorted[mid - 1] + sorted[mid]) / 2f
    }
}
