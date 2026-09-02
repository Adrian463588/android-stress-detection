package com.stressdetector.mobile.signal

import com.stressdetector.inference.V3PpgFeatureExtractor

data class ExtractedFeatures(
    val values: Map<String, Float>,
    val peakCount: Int = 0,
    val meanHrBpm: Float = 0f,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null
)

/**
 * Delegating feature extractor wrapper for mobile.
 * Delegates feature extraction to [V3PpgFeatureExtractor] from `:inference`
 * as the single source of truth.
 */
class PpgFeatureExtractor {

    fun extract(window: PreparedPpgWindow): ExtractedFeatures = try {
        val filtered = window.filteredValues
        val fs = window.modelRateHz
        require(fs.isFinite() && fs > 0.0) { "invalid_model_sampling_rate" }
        require(filtered.isNotEmpty() && filtered.all(Double::isFinite)) { "invalid_preprocessed_signal" }
        require(filtered.minOrNull() != filtered.maxOrNull()) { "flatline_signal" }

        val canonical = V3PpgFeatureExtractor.extract(
            filtered,
            fs,
            timestampsMillis = window.timestampsMillis,
            missingRatio = window.sourceGapInterpolatedSampleCount.toDouble() /
                window.size.coerceAtLeast(1)
        )
        val features = canonical.values
        val meanHr = features["heart_rate"] ?: 0f

        ExtractedFeatures(
            values = features,
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
