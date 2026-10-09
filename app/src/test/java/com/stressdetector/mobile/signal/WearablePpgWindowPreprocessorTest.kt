package com.stressdetector.mobile.signal

import com.stressdetector.mobile.data.FakePpgBatchFactory
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.shared.AccelerometerSample
import com.stressdetector.shared.PpgFilterContract
import com.stressdetector.shared.PpgType
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearablePpgWindowPreprocessorTest {
    @Test
    fun observedMotionAnnotatesWindowWithoutChangingPreparedPpg() {
        val batch = FakePpgBatchFactory.inferenceWindowBatch(PpgType.GREEN)
        val base = WindowSnapshot(batch.type, batch.timestampsMillis, batch.rawValues)
        val samples = (0 until batch.rawValues.size).map { index ->
            val elapsed = index * 40L
            AccelerometerSample(1_000_000_000L + elapsed * 1_000_000L, elapsed, 0f, 9.8f, 0f)
        }
        val withMotionResult = preprocessor(PpgType.GREEN).prepare(base.copy(accelerometerSamples = samples))
        val withMotion = (withMotionResult as PpgPreprocessResult.Ready).window
        val withoutMotion = (preprocessor(PpgType.GREEN).prepare(base) as PpgPreprocessResult.Ready).window

        assertEquals("time_windowed_rolling_variance_robust_mad", withMotion.motionAnnotation.motionMethod)
        assertEquals(samples.size, withMotion.motionAnnotation.sampleCount)
        assertEquals(withoutMotion.filteredValuesSha256, withMotion.filteredValuesSha256)
        assertEquals(withoutMotion.rawValuesSha256, withMotion.rawValuesSha256)
    }

    private fun preprocessor(type: PpgType): WearablePpgWindowPreprocessor = WearablePpgWindowPreprocessor(
        PpgPreprocessingContract(
            filterContract = PpgFilterContract.forChannel(type.assetSuffix)
        )
    )

    @Test
    fun canonicalGreenWindowIsPreparedOnceAtNominalRate() {
        val batch = FakePpgBatchFactory.inferenceWindowBatch(PpgType.GREEN)

        val result = preprocessor(batch.type).prepare(WindowSnapshot(batch.type, batch.timestampsMillis, batch.rawValues))

        val ready = result as PpgPreprocessResult.Ready
        assertEquals(PpgType.GREEN.samplingRateHz * 180, ready.window.size)
        assertEquals(0, ready.window.interpolatedSampleCount)
        assertEquals(0, ready.window.interpolatedMask.count { it })
        assertTrue(ready.window.filteredValues.all(Double::isFinite))
        assertEquals("unavailable", ready.window.motionAnnotation.motionAnnotation)
        assertEquals("annotation_only_no_accelerometer", ready.window.motionAnnotation.motionMethod)
    }

    @Test
    fun mismatchedManifestRatesRejectInsteadOfCreatingGridSamples() {
        val fractionalPreprocessor = WearablePpgWindowPreprocessor(
            PpgPreprocessingContract(
                sourceRateHz = 25.0,
                modelRateHz = 24.916666666666668,
                filterContract = PpgFilterContract.forChannel("ppg_green")
            )
        )
        val batch = FakePpgBatchFactory.inferenceWindowBatch(PpgType.GREEN)

        val result = fractionalPreprocessor.prepare(
            WindowSnapshot(batch.type, batch.timestampsMillis, batch.rawValues)
        )

        assertEquals(
            "ppg_rate_resampling_not_permitted",
            (result as PpgPreprocessResult.Rejected).reason
        )
    }

    @Test
    fun canonicalIrSourceWindowUsesHundredHzSourceWithoutMissingPenalty() {
        // One-second retention margin mirrors RollingPpgBuffer before a 180 s slice.
        val batch = FakePpgBatchFactory.sessionBatch(PpgType.IR, "fixture-ir", seconds = 181)

        val result = preprocessor(batch.type).prepare(WindowSnapshot(batch.type, batch.timestampsMillis, batch.rawValues))

        val ready = result as PpgPreprocessResult.Ready
        assertEquals(PpgType.IR.modelRateHz * 180 + 1, ready.window.size)
        assertEquals(ready.window.size, ready.window.rawSampleCount)
        assertEquals(0, ready.window.interpolatedSampleCount)
        assertEquals(0, ready.window.sourceGapInterpolatedSampleCount)
        assertEquals(0, ready.window.gridResampledSampleCount)
        assertEquals(ready.window.size, ready.window.observedSampleCount)
        val quality = SignalQualityGate(testLiveMonitorConfig()).evaluate(ready.window)
        assertTrue("native 100Hz source must remain usable", quality.isUsable)
        assertEquals(0.0, quality.missingRatio, 1.0e-9)
    }

    @Test
    fun duplicateTimestampKeepsFirstRawSampleDeterministically() {
        val batch = FakePpgBatchFactory.inferenceWindowBatch(PpgType.GREEN)
        val timestamps = batch.timestampsMillis.copyOf(batch.timestampsMillis.size + 1)
        val values = batch.rawValues.copyOf(batch.rawValues.size + 1)
        timestamps[100] = timestamps[99]
        values[100] = Int.MIN_VALUE
        timestamps[timestamps.lastIndex] = batch.timestampsMillis.last() + 40L
        values[values.lastIndex] = batch.rawValues.last()

        val result = preprocessor(PpgType.GREEN).prepare(WindowSnapshot(PpgType.GREEN, timestamps, values))

        assertTrue(result is PpgPreprocessResult.Ready)
    }

    @Test
    fun longDropoutIsRejectedInsteadOfSynthesized() {
        val batch = FakePpgBatchFactory.inferenceWindowBatch(PpgType.GREEN)
        val timestamps = batch.timestampsMillis.copyOf()
        for (index in 2_000 until timestamps.size) timestamps[index] += 1_000L

        val result = preprocessor(PpgType.GREEN).prepare(WindowSnapshot(PpgType.GREEN, timestamps, batch.rawValues))

        assertEquals("ppg_true_gap_detected", (result as PpgPreprocessResult.Rejected).reason)
    }

    @Test
    fun irSourceGapOver500MillisecondsIsRejected() {
        val batch = FakePpgBatchFactory.sessionBatch(PpgType.IR, "fixture-ir", seconds = 181)
        val timestamps = batch.timestampsMillis.copyOf()
        for (index in 2_000 until timestamps.size) timestamps[index] += 1_000L

        val result = preprocessor(PpgType.IR).prepare(WindowSnapshot(PpgType.IR, timestamps, batch.rawValues))

        assertEquals("ppg_true_gap_detected", (result as PpgPreprocessResult.Rejected).reason)
    }

    @Test
    fun irWarmupReportsNativeSourceSamplesAndRequiredRawSamples() {
        val batch = FakePpgBatchFactory.sessionBatch(PpgType.IR, "fixture-ir", seconds = 30)

        val result = preprocessor(batch.type).prepare(WindowSnapshot(batch.type, batch.timestampsMillis, batch.rawValues))

        val warmingUp = result as PpgPreprocessResult.WarmingUp
        assertEquals(30 * PpgType.IR.sourceRateHz, warmingUp.currentSamples)
        assertEquals(180 * PpgType.IR.sourceRateHz, warmingUp.requiredSamples)
    }

    @Test
    fun outOfOrderSamplesAreRejectedExplicitly() {
        val batch = FakePpgBatchFactory.inferenceWindowBatch(PpgType.IR)
        val timestamps = batch.timestampsMillis.copyOf()
        timestamps[800] = timestamps[799] - 1L

        val result = preprocessor(PpgType.IR).prepare(WindowSnapshot(PpgType.IR, timestamps, batch.rawValues))

        assertEquals("ppg_timestamp_out_of_order", (result as PpgPreprocessResult.Rejected).reason)
    }

    @Test
    fun insufficientDurationIsWarmingUp() {
        val batch = FakePpgBatchFactory.sessionBatch(PpgType.GREEN, "test", seconds = 30)

        val result = preprocessor(PpgType.GREEN).prepare(WindowSnapshot(PpgType.GREEN, batch.timestampsMillis, batch.rawValues))

        val warmingUp = result as PpgPreprocessResult.WarmingUp
        assertEquals(30 * PpgType.GREEN.samplingRateHz, warmingUp.currentSamples)
        assertEquals(180 * PpgType.GREEN.samplingRateHz, warmingUp.requiredSamples)
    }

    @Test
    fun lowerObservedCadenceIsRejectedInsteadOfResampled() {
        val timestamps = LongArray(2_251) { it * 80L }
        val values = IntArray(timestamps.size) { index ->
            (100_000.0 + 1_000.0 * sin(index / 12.0)).toInt()
        }

        val result = preprocessor(PpgType.GREEN).prepare(WindowSnapshot(PpgType.GREEN, timestamps, values))

        assertEquals(
            "ppg_observed_cadence_out_of_contract",
            (result as PpgPreprocessResult.Rejected).reason
        )
    }

    @Test
    fun callbackJitterAtUsableAverageCadenceDoesNotInvalidateWatchWindow() {
        val timestamps = LongArray(4_501)
        for (index in 1 until timestamps.size) {
            // Same 25 Hz average as Galaxy Watch callbacks, but each pair has
            // 20/60 ms delivery jitter that used to fail per-interval gating.
            timestamps[index] = timestamps[index - 1] + if (index % 2 == 0) 20L else 60L
        }
        val values = IntArray(timestamps.size) { index ->
            (100_000.0 + 1_000.0 * sin(index / 12.0)).toInt()
        }

        val result = preprocessor(PpgType.GREEN).prepare(WindowSnapshot(PpgType.GREEN, timestamps, values))

        val ready = result as PpgPreprocessResult.Ready
        assertEquals(0, ready.window.gridResampledSampleCount)
        assertEquals(0, ready.window.interpolatedSampleCount)
        assertEquals(ready.window.size, ready.window.observedSampleCount)
        assertEquals(ready.window.size, ready.window.observedMask.count { it })
        assertEquals(0, ready.window.gridResampledMask.count { it })
    }

    @Test
    fun sessionPreparationUsesAvailableActualSpanForCoreSlicing() {
        val batch = FakePpgBatchFactory.sessionBatch(PpgType.GREEN, "test", seconds = 288)
        val snapshot = WindowSnapshot(
            type = batch.type,
            timestampsMillis = batch.timestampsMillis,
            rawValues = batch.rawValues,
            sessionElapsedStartMillis = 0L,
            sessionElapsedEndMillis = 288_000L,
            elapsedTimestampsMillis = batch.timestampsMillis
        )

        val result = preprocessor(PpgType.GREEN).prepareSession(snapshot)

        val ready = result as PpgPreprocessResult.Ready
        assertTrue(ready.window.size >= 7_200)
        assertEquals(0L, ready.window.elapsedTimestampsMillis.first())
        assertTrue(ready.window.elapsedTimestampsMillis.last() <= 288_000L)
        assertTrue(ready.window.filteredValues.all(Double::isFinite))
    }
}
