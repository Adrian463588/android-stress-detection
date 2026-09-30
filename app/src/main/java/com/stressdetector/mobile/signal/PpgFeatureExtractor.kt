package com.stressdetector.mobile.signal

import com.stressdetector.inference.PpgDetectorContract
import com.stressdetector.inference.PpgDetectorTraceChecksum
import com.stressdetector.inference.PpgFeatureExtractionInput
import com.stressdetector.inference.V3PpgFeatureExtractor

data class ExtractedFeatures(
    val values: Map<String, Float>,
    val peakCount: Int = 0,
    val meanHrBpm: Float = 0f,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null,
    val troughCount: Int = 0,
    val validIbiCount: Int = 0,
    val adjacentIbiCount: Int = 0,
    val physiologicalIbiRatio: Double? = null,
    val morphologySupport: Int = 0,
    val detectorMethod: String? = null,
    val detectorFingerprint: String? = null,
    val peakChecksum: String? = null,
    val troughChecksum: String? = null,
    val qualityStatus: String = "rejected"
)

/**
 * Delegating feature extractor wrapper for mobile.
 * Delegates feature extraction to [V3PpgFeatureExtractor] from `:inference`
 * as the single source of truth.
 */
class PpgFeatureExtractor {

    fun extract(window: PreparedPpgWindow, detectorContract: PpgDetectorContract? = null): ExtractedFeatures = try {
        val filtered = window.filteredValues
        val fs = window.modelRateHz
        require(fs.isFinite() && fs > 0.0) { "invalid_model_sampling_rate" }
        require(filtered.isNotEmpty() && filtered.all(Double::isFinite)) { "invalid_preprocessed_signal" }
        require(filtered.minOrNull() != filtered.maxOrNull()) { "flatline_signal" }
        val effectiveContract = detectorContract
            ?: PpgDetectorContract.defaultFor(window.type.assetSuffix, fs)
        require(effectiveContract.sensor_channel == window.type.assetSuffix) {
            "ppg_detector_contract_channel_mismatch"
        }

        val canonical = V3PpgFeatureExtractor.extract(
            PpgFeatureExtractionInput(
                values = filtered,
                samplingRateHz = fs,
                timestampsMillis = window.timestampsMillis,
                missingRatio = window.sourceGapInterpolatedSampleCount.toDouble() /
                    window.size.coerceAtLeast(1),
                detectorContract = effectiveContract
            )
        )
        val features = canonical.values
        val meanHr = features["heart_rate"] ?: 0f

        ExtractedFeatures(
            values = features,
            peakCount = canonical.peakIndices.size,
            troughCount = canonical.troughIndices.size,
            validIbiCount = canonical.validIbiCount,
            adjacentIbiCount = canonical.adjacentIbiCount,
            physiologicalIbiRatio = canonical.physiologicalIbiRatio,
            morphologySupport = canonical.morphologySupport,
            detectorMethod = canonical.detectorMethod,
            detectorFingerprint = canonical.detectorFingerprint,
            peakChecksum = PpgDetectorTraceChecksum.indices(canonical.peakIndices),
            troughChecksum = PpgDetectorTraceChecksum.indices(canonical.troughIndices),
            qualityStatus = canonical.qualityStatus,
            meanHrBpm = meanHr,
            isSuccess = true
        )
    } catch (e: Exception) {
        ExtractedFeatures(
            values = emptyMap(),
            peakCount = 0,
            meanHrBpm = 0f,
            isSuccess = false,
            errorMessage = e.message ?: "Feature extraction failed"
        )
    }
}
