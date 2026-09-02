package com.stressdetector.shared

/**
 * Evidence captured at the model boundary. It contains contract metadata and
 * scalar outputs, never the raw PPG waveform, so it can be persisted or shown
 * in a diagnostic report without duplicating the session payload.
 */
data class StressInferenceTrace(
    val manifestFingerprint: String,
    val sensorChannel: String,
    val featureOrder: List<String>,
    val featureCount: Int,
    val sampleCount: Int,
    val sourceRateHz: Double,
    val modelRateHz: Double,
    val rawProbability: Float?,
    val calibratedProbability: Float?,
    val calibrationVersion: String?,
    val threshold: Float?,
    val labelOrder: List<String> = listOf("relax", "task"),
    val finalLabel: String?,
    val qualityTier: String,
    val sessionId: String? = null,
    val windowId: String? = null,
    val trackerContract: String? = null,
    val acceptedSampleCount: Int = sampleCount,
    val rejectedSampleCount: Int = 0,
    val duplicateSampleCount: Int = 0,
    val sequenceLossCount: Long = 0L,
    val observedStartMillis: Long? = null,
    val observedEndMillis: Long? = null,
    val coverageMillis: Long = 0L,
    val observedCadenceHz: Double? = null,
    val maxGapMillis: Long = 0L,
    val preprocessingVersion: String? = null,
    val featureVersion: String? = null,
    val featureChecksum: String? = null,
    val featureMinimum: Float? = null,
    val featureMaximum: Float? = null,
    val scalerChecksum: String? = null,
    val filterAlgorithm: String? = null,
    val filterOrder: Int? = null,
    val filterLowCutHz: Double? = null,
    val filterHighCutHz: Double? = null,
    val filterContractFingerprint: String? = null,
    val rejectionReason: String? = null
)
