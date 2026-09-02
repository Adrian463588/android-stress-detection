package com.stressdetector.shared

/**
 * Shared Home stress-session timing contract.
 *
 * Acquisition remains five minutes. Inference consumes only the four-minute
 * interior core, from elapsed 30 s (inclusive) through 270 s (exclusive).
 */
data class PredictionWindowConfig(
    val sessionSeconds: Int = 300,
    val edgeTrimSeconds: Int = 30,
    val analysisSeconds: Int = 240,
    val modelWindowSeconds: Int = 180,
    val strideSeconds: Int = 10
) {
    init {
        require(sessionSeconds > 0) { "sessionSeconds must be positive" }
        require(edgeTrimSeconds >= 0) { "edgeTrimSeconds must be non-negative" }
        require(analysisSeconds > 0) { "analysisSeconds must be positive" }
        require(modelWindowSeconds > 0) { "modelWindowSeconds must be positive" }
        require(strideSeconds > 0) { "strideSeconds must be positive" }
        require(edgeTrimSeconds * 2 + analysisSeconds == sessionSeconds) {
            "edge trim and analysis duration must cover session duration"
        }
        require(modelWindowSeconds <= analysisSeconds) {
            "modelWindowSeconds must not exceed analysisSeconds"
        }
        require((analysisSeconds - modelWindowSeconds) % strideSeconds == 0) {
            "analysis duration must produce integral model windows"
        }
    }

    val sessionDurationMillis: Long get() = sessionSeconds * MILLIS_PER_SECOND
    val coreStartMillis: Long get() = edgeTrimSeconds * MILLIS_PER_SECOND
    val coreEndMillis: Long get() = (edgeTrimSeconds + analysisSeconds) * MILLIS_PER_SECOND
    val analysisDurationMillis: Long get() = analysisSeconds * MILLIS_PER_SECOND
    val modelWindowMillis: Long get() = modelWindowSeconds * MILLIS_PER_SECOND
    val strideMillis: Long get() = strideSeconds * MILLIS_PER_SECOND
    val expectedModelWindows: Int get() = ((analysisSeconds - modelWindowSeconds) / strideSeconds) + 1

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
