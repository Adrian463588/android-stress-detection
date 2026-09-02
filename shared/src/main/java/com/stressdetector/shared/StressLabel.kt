package com.stressdetector.shared

/** Canonical wire values shared by inference, aggregation, and persistence. */
enum class StressLabel(val value: String, val displayName: String) {
    STRESS("STRESS", "Stress"),
    RELAX("RELAX", "Normal"),
    INVALID("INVALID", "Tidak valid")
    ;

    companion object {
        /**
         * Classifies using the threshold frozen in the selected model manifest.
         * There is intentionally no global 0.5 fallback for session decisions.
         */
        fun fromProbability(probability: Float, probabilityThreshold: Float): StressLabel = when {
            !probability.isFinite() || probability !in 0f..1f -> INVALID
            !probabilityThreshold.isFinite() || probabilityThreshold !in 0f..1f -> INVALID
            probability >= probabilityThreshold -> STRESS
            else -> RELAX
        }

        fun canonical(raw: String): String = when (raw.trim().uppercase()) {
            STRESS.value -> STRESS.value
            RELAX.value, "NORMAL", "RELAXATION" -> RELAX.value
            else -> INVALID.value
        }

        fun displayName(raw: String): String = values().firstOrNull { it.value == canonical(raw) }
            ?.displayName
            ?: INVALID.displayName
    }
}
