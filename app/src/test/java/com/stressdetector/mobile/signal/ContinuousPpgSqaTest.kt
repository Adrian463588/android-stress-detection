package com.stressdetector.mobile.signal

import com.stressdetector.mobile.data.FakePpgBatchFactory
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.shared.PpgFilterContract
import com.stressdetector.shared.PpgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousPpgSqaTest {
    private val preprocessor = WearablePpgWindowPreprocessor(
        PpgPreprocessingContract(filterContract = PpgFilterContract.forChannel("ppg_ir"))
    )

    @Test
    fun ir100HzSourceWindowHasNoRateConversionPenalty() {
        val batch = FakePpgBatchFactory.sessionBatch(PpgType.IR, "ir-sqa", seconds = 181)
        val prepared = (
            preprocessor.prepare(
                WindowSnapshot(batch.type, batch.timestampsMillis, batch.rawValues)
            ) as PpgPreprocessResult.Ready
            ).window

        assertEquals(18_100, prepared.rawSampleCount)
        assertEquals(18_000, prepared.size)
        assertEquals(0, prepared.interpolatedSampleCount)
        assertEquals(0, prepared.sourceGapInterpolatedSampleCount)
        val quality = SignalQualityGate(testLiveMonitorConfig()).evaluate(prepared)
        assertTrue(quality.isUsable)
        assertEquals(0.0, quality.missingRatio, 1.0e-9)
    }

    @Test
    fun sourceDropoutIsCountedSeparatelyFromRateConversion() {
        val batch = FakePpgBatchFactory.sessionBatch(PpgType.IR, "ir-gap", seconds = 181)
        val timestamps = batch.timestampsMillis.copyOf()
        for (index in 2_000 until timestamps.size) timestamps[index] += 80L

        val prepared = (
            preprocessor.prepare(
                WindowSnapshot(batch.type, timestamps, batch.rawValues)
            ) as PpgPreprocessResult.Ready
            ).window

        assertTrue(prepared.sourceGapInterpolatedSampleCount > 0)
        assertTrue(SignalQualityGate(testLiveMonitorConfig()).evaluate(prepared).missingRatio > 0.0)
    }
}
