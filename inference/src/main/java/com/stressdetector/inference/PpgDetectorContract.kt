package com.stressdetector.inference

import com.google.gson.Gson
import com.stressdetector.shared.PpgSamplingPolicy
import kotlin.math.abs
import kotlin.math.round

private const val MAX_MIN_DISTANCE_SECONDS = 2.0
private const val MAX_PROMINENCE_MAD_MULTIPLIER = 5.0
private const val MIN_CONFIGURED_PEAK_COUNT = 3
private const val MIN_CONFIGURED_ADJACENT_IBI_COUNT = 1
private const val MIN_CONFIGURED_MORPHOLOGY_SUPPORT = 1
private const val DEFAULT_GREEN_DETECTOR_RATE_HZ = 25.0
private const val INTEGER_RATE_RELATIVE_TOLERANCE = 0.01
private const val DEFAULT_MIN_DISTANCE_SECONDS = 0.30
private const val DEFAULT_PROMINENCE_MAD_MULTIPLIER = 0.40
private const val MILLIS_PER_SECOND = 1_000.0
private const val MIN_IBI_MILLIS = 300.0
private const val MAX_IBI_MILLIS = 1_500.0
private const val FNV_OFFSET_BASIS = -3750763034362895579L
private const val FNV_PRIME = 1099511628211L
private const val HASH_BYTE_MASK = 0xffL
private const val HASH_BYTES = 4
private const val HASH_HEX_WIDTH = 16
private const val SHA256_HEX_LENGTH = 64
private const val BYTE_SHIFT_BITS = 8
private const val HASH_RADIX = 16

/** Run-scoped detector and signal-quality contract emitted by the Python run. */
@Suppress("ConstructorParameterNaming")
data class PpgDetectorContract(
    val sensor_channel: String,
    val model_version: String,
    val detector_method: String,
    val implementation_version: String,
    val source_rate_hz: Double,
    val model_rate_hz: Double,
    val detector_sampling_rate_hz: Double,
    val min_distance_seconds: Double,
    val prominence_mad_multiplier: Double,
    val width_min_samples: Int? = null,
    val width_max_samples: Int? = null,
    val trough_method: String,
    val min_peak_count: Int = 15,
    val min_physiological_ibi_ratio: Double = 0.50,
    val min_adjacent_ibi_count: Int = 3,
    val min_morphology_support: Int = 3,
    val filter_contract_fingerprint: String,
    val detector_config_fingerprint: String,
    val candidate_artifact_fingerprint: String,
    val onnx_sha256: String
) {
    fun requireValid() {
        require(sensor_channel in setOf("ppg_green", "ppg_ir")) { "unsupported_detector_channel" }
        require(model_version.isNotBlank()) { "missing_detector_model_version" }
        require(detector_method in SUPPORTED_METHODS) { "unsupported_detector_method" }
        require(implementation_version.isNotBlank()) { "missing_detector_implementation" }
        require(source_rate_hz.isFinite() && source_rate_hz > 0.0) { "invalid_detector_source_rate" }
        require(model_rate_hz.isFinite() && model_rate_hz > 0.0) { "invalid_detector_model_rate" }
        require(detector_sampling_rate_hz.isFinite() && detector_sampling_rate_hz > 0.0) {
            "invalid_detector_sampling_rate"
        }
        require(min_distance_seconds > 0.0 && min_distance_seconds <= MAX_MIN_DISTANCE_SECONDS) {
            "invalid_detector_distance"
        }
        require(prominence_mad_multiplier >= 0.0 && prominence_mad_multiplier <= MAX_PROMINENCE_MAD_MULTIPLIER) {
            "invalid_detector_prominence"
        }
        require(width_min_samples == null || width_min_samples >= 0) { "invalid_detector_width_min" }
        require(width_max_samples == null || width_max_samples > 0) { "invalid_detector_width_max" }
        require(width_min_samples == null || width_max_samples == null || width_min_samples <= width_max_samples) {
            "invalid_detector_width_range"
        }
        require(min_peak_count >= MIN_CONFIGURED_PEAK_COUNT) { "invalid_detector_peak_count_gate" }
        require(min_physiological_ibi_ratio in 0.0..1.0) { "invalid_detector_ibi_ratio_gate" }
        require(min_adjacent_ibi_count >= MIN_CONFIGURED_ADJACENT_IBI_COUNT) {
            "invalid_detector_adjacent_ibi_gate"
        }
        require(min_morphology_support >= MIN_CONFIGURED_MORPHOLOGY_SUPPORT) {
            "invalid_detector_morphology_gate"
        }
        require(filter_contract_fingerprint.matches(SHA256)) { "invalid_detector_filter_fingerprint" }
        require(detector_config_fingerprint.matches(SHA256)) { "invalid_detector_config_fingerprint" }
        require(candidate_artifact_fingerprint.matches(SHA256)) { "invalid_detector_artifact_fingerprint" }
        require(onnx_sha256.matches(SHA256)) { "invalid_detector_model_checksum" }
    }

    fun requireCompatible(runId: String?, manifest: ModelManifest, modelBytes: ByteArray) {
        requireValid()
        require(runId == null || runId.matches(RUN_ID)) { "ppg_detector_contract_run_mismatch" }
        require(sensor_channel == manifest.sensor_channel) { "ppg_detector_contract_channel_mismatch" }
        require(model_version == manifest.model_version) { "ppg_detector_contract_model_mismatch" }
        require(onnx_sha256 == manifest.onnx_sha256) { "ppg_detector_contract_model_checksum_mismatch" }
        require(ModelArtifactLoader.sha256(modelBytes) == onnx_sha256) {
            "ppg_detector_contract_model_bytes_checksum_mismatch"
        }
        require(candidate_artifact_fingerprint == manifest.candidate_bundle_sha256) {
            "ppg_detector_contract_artifact_fingerprint_mismatch"
        }
        require(filter_contract_fingerprint == manifest.filter_contract_fingerprint) {
            "ppg_detector_contract_filter_mismatch"
        }
        require(detectorValuesCloseEnough(source_rate_hz, manifest.source_rate_hz)) {
            "ppg_detector_contract_source_rate_mismatch"
        }
        require(detectorValuesCloseEnough(model_rate_hz, manifest.model_rate_hz)) {
            "ppg_detector_contract_model_rate_mismatch"
        }
    }

    companion object {
        private val RUN_ID = Regex("\\d{8}T\\d{6}Z")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val SUPPORTED_METHODS = setOf(
            "neurokit2_ppg_charlton_msptdfast",
            "scipy_find_peaks_adaptive"
        )

        fun defaultFor(sensorChannel: String, samplingRateHz: Double): PpgDetectorContract {
            val green = sensorChannel == "ppg_green"
            return PpgDetectorContract(
                sensor_channel = sensorChannel,
                model_version = "unit-test-contract",
                detector_method = if (green) {
                    "neurokit2_ppg_charlton_msptdfast"
                } else {
                    "scipy_find_peaks_adaptive"
                },
                implementation_version = "local-contract-v1",
                source_rate_hz = samplingRateHz,
                model_rate_hz = samplingRateHz,
                detector_sampling_rate_hz = if (green) DEFAULT_GREEN_DETECTOR_RATE_HZ else samplingRateHz,
                min_distance_seconds = DEFAULT_MIN_DISTANCE_SECONDS,
                prominence_mad_multiplier = DEFAULT_PROMINENCE_MAD_MULTIPLIER,
                trough_method = "scipy_find_peaks_adaptive_negative_signal",
                filter_contract_fingerprint = "0".repeat(SHA256_HEX_LENGTH),
                detector_config_fingerprint = "0".repeat(SHA256_HEX_LENGTH),
                candidate_artifact_fingerprint = "0".repeat(SHA256_HEX_LENGTH),
                onnx_sha256 = "0".repeat(SHA256_HEX_LENGTH)
            )
        }
    }
}

@Suppress("ConstructorParameterNaming")
data class PpgDetectorContractBundle(
    val schema_version: String,
    val run_id: String,
    val feature_formula_version: String,
    val feature_area_integration: String,
    val ibi_policy: String,
    val gap_policy: String,
    val contracts: List<PpgDetectorContract> = emptyList()
) {
    fun requireValid() {
        require(schema_version == "ppg-detector-contract-v1") { "unsupported_ppg_detector_contract" }
        require(run_id.matches(RUN_ID)) { "ppg_detector_bundle_run_mismatch" }
        require(feature_formula_version == "v4.0.0_robust_channel_independent") {
            "ppg_detector_feature_formula_mismatch"
        }
        require(feature_area_integration == "trapezoidal") { "ppg_detector_area_formula_mismatch" }
        require(ibi_policy == "adjacent_observed_only") { "ppg_detector_ibi_policy_mismatch" }
        require(gap_policy == "finite_gap_free_segments_no_bridging") {
            "ppg_detector_gap_policy_mismatch"
        }
        require(contracts.map { it.sensor_channel }.toSet() == setOf("ppg_green", "ppg_ir")) {
            "ppg_detector_bundle_channels_invalid"
        }
        contracts.forEach(PpgDetectorContract::requireValid)
    }

    fun forChannel(sensorChannel: String): PpgDetectorContract = contracts
        .singleOrNull { it.sensor_channel == sensorChannel }
        ?: error("ppg_detector_contract_missing_for_$sensorChannel")

    companion object {
        private val RUN_ID = Regex("\\d{8}T\\d{6}Z")

        fun parse(json: String): PpgDetectorContractBundle = Gson().fromJson(
            json,
            PpgDetectorContractBundle::class.java
        ) ?: error("ppg_detector_contract_unreadable")
    }
}

/** Output of the exact detector contract, retained for trace and quality gates. */
data class PpgPeakDetection(
    val peaks: IntArray,
    val troughs: IntArray,
    val detectorMethod: String,
    val detectorFingerprint: String,
    val physiologicalIbiRatio: Double,
    val adjacentIbiCount: Int,
    val morphologySupport: Int
)

/** Detector semantics shared by the Android implementation of the Python contract. */
object BackendPpgPeakDetector {
    fun detect(values: DoubleArray, samplingRateHz: Double, contract: PpgDetectorContract): PpgPeakDetection {
        contract.requireValid()
        require(values.isNotEmpty() && values.all(Double::isFinite)) { "non_finite_detector_input" }
        require(samplingRateHz.isFinite() && samplingRateHz > 0.0) { "invalid_detector_input_rate" }
        // The frozen rate describes the training cohort. Model-bound Gold uses
        // (sample count - 1) / observed endpoint span for each feature window.
        require(PpgSamplingPolicy.isCompatible(samplingRateHz, contract.model_rate_hz)) {
            "detector_input_rate_mismatch"
        }

        val (peaks, troughs) = when (contract.detector_method) {
            "neurokit2_ppg_charlton_msptdfast" -> {
                // Match peak_detection.py: round only the integer-only detector
                // parameter; intervals and morphology retain the feature rate.
                val detectorRate = round(samplingRateHz)
                require(
                    detectorRate > 0.0 &&
                        abs(samplingRateHz - detectorRate) <= INTEGER_RATE_RELATIVE_TOLERANCE * detectorRate
                ) { "detector_integer_sampling_rate_mismatch" }
                val msptdPeaks = msptdFast(values, detectorRate).first
                // Green uses MSPTDfast peaks and the adaptive SciPy negative-signal trough semantics.
                val scipyTroughs = scipyAdaptive(values, samplingRateHz, contract).second
                msptdPeaks to scipyTroughs
            }
            "scipy_find_peaks_adaptive" -> scipyAdaptive(values, samplingRateHz, contract)
            else -> error("unsupported_detector_method")
        }
        require(peaks.size >= contract.min_peak_count) { "insufficient_peaks:${peaks.size}" }
        val allIbi = DoubleArray(peaks.size - 1) { index ->
            (peaks[index + 1] - peaks[index]) * MILLIS_PER_SECOND / samplingRateHz
        }
        val validMask = BooleanArray(allIbi.size) { index ->
            allIbi[index] in MIN_IBI_MILLIS..MAX_IBI_MILLIS
        }
        val validCount = validMask.count { it }
        val validRatio = validCount.toDouble() / allIbi.size.coerceAtLeast(1)
        require(validRatio >= contract.min_physiological_ibi_ratio) {
            "insufficient_physiological_ibi_ratio:$validRatio"
        }
        val adjacentCount = (0 until allIbi.lastIndex).count {
            validMask[it] && validMask[it + 1]
        }
        require(adjacentCount >= contract.min_adjacent_ibi_count) {
            "insufficient_adjacent_physiological_ibi:$adjacentCount"
        }
        val morphology = morphologySupport(peaks, troughs)
        require(morphology >= contract.min_morphology_support) {
            "insufficient_pulse_morphology_support:$morphology"
        }
        return PpgPeakDetection(
            peaks = peaks,
            troughs = troughs,
            detectorMethod = contract.detector_method,
            detectorFingerprint = contract.detector_config_fingerprint,
            physiologicalIbiRatio = validRatio,
            adjacentIbiCount = adjacentCount,
            morphologySupport = morphology
        )
    }
}

object PpgDetectorTraceChecksum {
    fun indices(values: IntArray): String {
        var hash = FNV_OFFSET_BASIS
        values.forEach { value ->
            var current = value.toLong()
            repeat(HASH_BYTES) {
                hash = hash xor (current and HASH_BYTE_MASK)
                hash *= FNV_PRIME
                current = current shr BYTE_SHIFT_BITS
            }
        }
        return java.lang.Long.toUnsignedString(hash, HASH_RADIX).padStart(HASH_HEX_WIDTH, '0')
    }
}
