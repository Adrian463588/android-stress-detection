package com.stressdetector.shared

/**
 * Immutable decision contract copied from one accepted model manifest.
 *
 * A session prediction must retain this policy so aggregation cannot silently
 * fall back to a global probability threshold.
 */
data class ModelDecisionPolicy(
    val modelVersion: String,
    val probabilityThreshold: Float,
    val calibrationVersion: String,
    val labelOrder: List<String> = listOf("relax", "task")
) {
    init {
        require(modelVersion.isNotBlank()) { "modelVersion is blank" }
        require(probabilityThreshold.isFinite() && probabilityThreshold in 0f..1f) {
            "probabilityThreshold must be within [0, 1]"
        }
        require(calibrationVersion.isNotBlank()) { "calibrationVersion is blank" }
        require(labelOrder == listOf("relax", "task")) { "unsupported_label_order" }
    }

    fun labelFor(stressProbability: Float): StressLabel = StressLabel.fromProbability(stressProbability, probabilityThreshold)
}
