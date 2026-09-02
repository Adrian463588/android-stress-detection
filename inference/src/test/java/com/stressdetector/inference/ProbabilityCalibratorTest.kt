package com.stressdetector.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbabilityCalibratorTest {
    @Test
    fun identityPlattCalibrationPreservesRawProbability() {
        val calibrator = ProbabilityCalibrator(testCalibration(slope = 1.0, intercept = 0.0))

        assertEquals(0.10f, calibrator.calibrate(0.10f), 0.0001f)
        assertEquals(0.90f, calibrator.calibrate(0.90f), 0.0001f)
    }

    @Test
    fun calibrationNeverRaisesProbabilityToConfidenceThreshold() {
        val calibrator = ProbabilityCalibrator(testCalibration(slope = 0.5, intercept = 0.0))

        assertTrue(calibrator.calibrate(0.10f) < 0.80f)
    }

    private fun testCalibration(slope: Double, intercept: Double) = ProbabilityCalibration(
        method = "platt",
        version = "test-calibration",
        slope = slope,
        intercept = intercept,
        fitted_cohort = (1..28).map { "ID-%02d".format(it) },
        fitted_on = "participant_grouped_development_oof",
        calibration_status = "validated_development_oof",
        brier_score = 0.2,
        log_loss = 0.5,
        ece = 0.1
    )
}
