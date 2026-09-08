package com.stressdetector.wear.data

import com.stressdetector.shared.PpgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgCallbackBatchTest {
    @Test
    fun copiesWholeCallbackBeforeBackgroundIngress() {
        val callback = mutableListOf(
            PpgRawSample(PpgType.GREEN, 1_000L, 10),
            PpgRawSample(PpgType.IR, 1_000L, 20),
            PpgRawSample(PpgType.RED, 1_000L, 30)
        )

        val batch = PpgCallbackBatch.copyOf(callback)
        callback.clear()

        assertEquals(3, batch.samples.size)
        assertTrue(batch.samples.map(PpgRawSample::type).containsAll(PpgType.acquisitionChannels))
    }
}
