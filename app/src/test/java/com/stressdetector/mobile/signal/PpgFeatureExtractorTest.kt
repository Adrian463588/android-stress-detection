package com.stressdetector.signal

import com.stressdetector.mobile.data.FakePpgBatchFactory
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.signal.PpgFeatureExtractor
import com.stressdetector.mobile.signal.PpgPreprocessResult
import com.stressdetector.mobile.signal.PpgPreprocessingContract
import com.stressdetector.mobile.signal.PreparedPpgWindow
import com.stressdetector.mobile.signal.WearablePpgWindowPreprocessor
import com.stressdetector.shared.PpgFilterContract
import com.stressdetector.shared.PpgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgFeatureExtractorTest {
    @Test
    fun emitsCanonicalPredictorFeaturesForHrv() {
        val batch = FakePpgBatchFactory.inferenceWindowBatch(PpgType.GREEN)
        val window = requireNotNull(
            (
                WearablePpgWindowPreprocessor(
                    PpgPreprocessingContract(filterContract = PpgFilterContract.forChannel("ppg_green"))
                ).prepare(
                    WindowSnapshot(batch.type, batch.timestampsMillis, batch.rawValues)
                ) as? PpgPreprocessResult.Ready
                )?.window
        )

        val result = PpgFeatureExtractor().extract(window)
        val canonicalPredictors = setOf(
            "median_hr_bpm",
            "ppi_median_ms",
            "ppi_iqr_ms",
            "ppi_cv",
            "rmssd_ms",
            "sdnn_ms",
            "pnn50_observed_adjacent",
            "pulse_amplitude_cv",
            "normalized_pulse_amplitude_median",
            "pulse_width_half_height_median_ms",
            "rise_time_median_ms",
            "decay_time_median_ms",
            "normalized_pulse_area_median",
            "normalized_rise_slope_median",
            "normalized_decay_slope_median"
        )

        assertTrue("Canonical extraction failed: ${result.errorMessage}", result.isSuccess)
        assertTrue(result.values.keys.containsAll(canonicalPredictors))
        assertEquals(canonicalPredictors.size, result.values.filterKeys(canonicalPredictors::contains).size)
        assertTrue(result.values.values.all(Float::isFinite))
    }

    /**
     * Edge case: very short IR source window (~3 s, 75 raw samples at 25 Hz).
     * With only a handful of detected peaks the entropy / fractal functions
     * previously produced NaN or Infinity for katzFD, sampleEntropy, etc.
     */
    @Test
    fun irWindowMinimalPeaks_allFeaturesFinite() {
        val batch = FakePpgBatchFactory.sessionBatch(PpgType.IR, "test", seconds = 3)
        val window = prepared(batch.type, batch.timestampsMillis, batch.rawValues)

        val result = PpgFeatureExtractor().extract(window)
        val features = result.values

        val nonFinite = features.filter { (_, v) -> !v.isFinite() }
        assertTrue(
            "Non-finite feature values for IR minimal window: $nonFinite",
            nonFinite.isEmpty()
        )
        assertFalse(result.isSuccess)
    }

    /**
     * Edge case: flat signal (constant ADC value) → stddev = 0.
     * fuzzyEntropy previously computed exp(-d^n / 0) = NaN when sdnn = 0.
     */
    @Test
    fun irWindowFlatSignal_allFeaturesFinite() {
        val count = PpgType.IR.samplingRateHz * 180
        val timestamps = LongArray(count) { System.currentTimeMillis() - (count - it) * 10L }
        val values = IntArray(count) { 100_000 } // perfectly flat
        val window = prepared(PpgType.IR, timestamps, values)

        val result = PpgFeatureExtractor().extract(window)
        val features = result.values

        val nonFinite = features.filter { (_, v) -> !v.isFinite() }
        assertTrue(
            "Non-finite feature values for IR flat-signal window: $nonFinite",
            nonFinite.isEmpty()
        )
        assertFalse(result.isSuccess)
    }

    /**
     * Edge case: Green minimal window — same guards should hold for GREEN channel.
     */
    @Test
    fun greenWindowMinimalPeaks_allFeaturesFinite() {
        val batch = FakePpgBatchFactory.sessionBatch(PpgType.GREEN, "test", seconds = 12)
        val window = prepared(batch.type, batch.timestampsMillis, batch.rawValues)

        val result = PpgFeatureExtractor().extract(window)
        val features = result.values

        val nonFinite = features.filter { (_, v) -> !v.isFinite() }
        assertTrue(
            "Non-finite feature values for GREEN minimal window: $nonFinite",
            nonFinite.isEmpty()
        )
        assertFalse(result.isSuccess)
    }

    private fun prepared(type: PpgType, timestamps: LongArray, values: IntArray) = PreparedPpgWindow(
        type = type,
        timestampsMillis = timestamps,
        filteredValues = DoubleArray(values.size) { values[it].toDouble() },
        rawSampleCount = values.size,
        interpolatedSampleCount = 0
    )
}
