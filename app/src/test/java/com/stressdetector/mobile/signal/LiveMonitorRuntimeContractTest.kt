package com.stressdetector.mobile.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LiveMonitorRuntimeContractTest {
    @Test
    fun absentOptionalQualityThresholdsKeepSafeDefaults() {
        val json = """
            {
              "adr_reference": "ADR-012",
              "inference_step_seconds": 10,
              "minimum_quality_score": 0.40,
              "max_timestamp_gap_delta_ms": 500.0,
              "max_timestamp_gap_count_per_second": 5,
              "max_flatline_ratio_usable": 0.25,
              "max_flatline_ratio_excellent": 0.05,
              "divergence_delta_min": 0.20,
              "divergence_probability_min": 0.65,
              "baseline_window_count": 10,
              "bpm_window_seconds": 30,
              "bpm_baseline_window_seconds": 60,
              "bpm_rise_absolute_threshold": 10.0,
              "bpm_rise_relative_threshold": 0.15,
              "bpm_rise_sustain_seconds": 10,
              "bpm_recovery_absolute_threshold": 5.0,
              "bpm_recovery_relative_threshold": 0.05,
              "bpm_recovery_within_seconds": 60,
              "marker_merge_gap_seconds": 10,
              "channel_agreement_delta_min": 0.20,
              "primary_channel": "ppg_green",
              "fallback_channel": "ppg_ir"
            }
        """.trimIndent()

        val config = AssetLiveMonitorRuntimeContract.parseConfigJson(json)

        assertEquals(0.05, config.maxMissingRatioUsable, 0.0)
        assertEquals(0.01, config.maxClippingRatioUsable, 0.0)
        assertEquals(0.10, config.maxArtifactRatioUsable, 0.0)
        assertEquals(0.20, config.maxSamplingJitterUsable, 0.0)
    }

    @Test
    fun selectedModelWindowContractIsNotHardcodedTo180Seconds() {
        val contract = LiveModelContract(
            modelVersion = "stress-test-ir",
            analysisSeconds = 240,
            windowSeconds = 120,
            strideSeconds = 30,
            expectedWindowsPerSession = 5,
            sourceRateHz = 100.0,
            modelRateHz = 100.0
        )

        contract.requireValid()

        assertEquals(5, contract.expectedWindowsPerSession)
    }

    @Test
    fun inconsistentManifestWindowCountFailsClosed() {
        val contract = LiveModelContract(
            modelVersion = "stress-test-green",
            analysisSeconds = 240,
            windowSeconds = 180,
            strideSeconds = 10,
            expectedWindowsPerSession = 6,
            sourceRateHz = 25.0,
            modelRateHz = 25.0
        )

        assertThrows(IllegalArgumentException::class.java) { contract.requireValid() }
    }
}
