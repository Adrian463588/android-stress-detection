package com.stressdetector.inference

import kotlin.math.exp
import kotlin.math.ln

/** Pure Platt calibration; the ONNX output is never modified in the model. */
class ProbabilityCalibrator(private val calibration: ProbabilityCalibration) {
    init {
        calibration.requireValid()
    }

    fun calibrate(rawProbability: Float): Float {
        require(rawProbability.isFinite() && rawProbability in 0f..1f) {
            "raw_probability_out_of_range"
        }
        val bounded = rawProbability.toDouble().coerceIn(EPSILON, 1.0 - EPSILON)
        val logit = ln(bounded / (1.0 - bounded))
        val calibratedLogit = calibration.slope * logit + calibration.intercept
        val calibrated = 1.0 / (1.0 + exp(-calibratedLogit))
        return calibrated.toFloat().coerceIn(0f, 1f)
    }

    val version: String
        get() = calibration.version

    private companion object {
        const val EPSILON = 1.0e-7
    }
}
