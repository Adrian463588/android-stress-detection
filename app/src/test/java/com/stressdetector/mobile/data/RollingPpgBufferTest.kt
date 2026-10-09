package com.stressdetector.mobile.data

import com.stressdetector.shared.PpgBatch
import com.stressdetector.shared.PpgType
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RollingPpgBufferTest {
    @Test
    fun irregularIrTimestampsKeepActualElapsedGaps() {
        val buffer = RollingPpgBuffer(PpgType.IR)
        buffer.append(
            PpgBatch(
                type = PpgType.IR,
                timestampsMillis = longArrayOf(1_000L, 1_010L, 1_060L, 1_070L),
                rawValues = intArrayOf(1, 2, 3, 4),
                sessionId = "session",
                elapsedStartMillis = 2_000L,
                elapsedEndMillis = 2_070L
            )
        )

        assertEquals(
            longArrayOf(2_000L, 2_010L, 2_060L, 2_070L).toList(),
            buffer.snapshot().elapsedTimestampsMillis.toList()
        )
    }

    @Test
    fun timestampRegressionIsRejectedInsteadOfSilentlyDroppingTheBatch() {
        val buffer = RollingPpgBuffer(PpgType.GREEN)
        buffer.append(
            PpgBatch(
                type = PpgType.GREEN,
                timestampsMillis = longArrayOf(1_000L, 1_040L),
                rawValues = intArrayOf(1, 2),
                sessionId = "session",
                elapsedMillis = 40L,
                elapsedStartMillis = 0L,
                elapsedEndMillis = 40L
            )
        )

        try {
            buffer.append(
                PpgBatch(
                    type = PpgType.GREEN,
                    timestampsMillis = longArrayOf(960L, 1_000L),
                    rawValues = intArrayOf(3, 4),
                    sessionId = "session",
                    elapsedMillis = 80L,
                    elapsedStartMillis = 40L,
                    elapsedEndMillis = 80L
                )
            )
            fail("A regressed batch must not be accepted")
        } catch (_: IllegalArgumentException) {
            // Expected: the Watch batch must remain retryable instead of being ACKed.
        }
        assertEquals(2, buffer.size)
    }

    @Test
    fun trimsToThreeMinuteWindow() {
        val buffer = RollingPpgBuffer(PpgType.GREEN)
        val count = buffer.requiredSamples + 30
        buffer.append(
            PpgBatch(
                PpgType.GREEN,
                LongArray(count) { it * 40L },
                IntArray(count) { it },
                "test-session"
            )
        )

        val snapshot = buffer.snapshot()
        assertEquals(true, buffer.isReady)
        assertEquals(true, snapshot.timestampsMillis.first() > 0L)
        assertEquals(true, snapshot.timestampsMillis.last() - snapshot.timestampsMillis.first() <= 180_500L)
    }

    @Test
    fun irRawChartUsesHundredHzSourceTimeline() {
        val buffer = RollingPpgBuffer(PpgType.IR)
        val count = PpgType.IR.sourceRateHz * 10
        buffer.append(
            PpgBatch(
                PpgType.IR,
                LongArray(count) { it * 40L },
                IntArray(count) { it },
                "test-session"
            )
        )

        val chart = buffer.recentChartData(5f)

        assertEquals(126, chart.rawValues.size)
        assertEquals(5_000L, chart.timestampsMillis.last() - chart.timestampsMillis.first())
        assertEquals(5f, chart.durationSec)
    }

    @Test
    fun elapsedBoundsRemainAnchoredWhenFirstBatchStartsAtZero() {
        val buffer = RollingPpgBuffer(PpgType.GREEN)
        buffer.append(
            PpgBatch(
                type = PpgType.GREEN,
                timestampsMillis = longArrayOf(0L, 40L),
                rawValues = intArrayOf(1, 2),
                sessionId = "test-session",
                elapsedStartMillis = 0L,
                elapsedEndMillis = 40L
            )
        )
        buffer.append(
            PpgBatch(
                type = PpgType.GREEN,
                timestampsMillis = longArrayOf(1_000L, 1_040L),
                rawValues = intArrayOf(3, 4),
                sessionId = "test-session",
                elapsedStartMillis = 1_000L,
                elapsedEndMillis = 1_040L
            )
        )

        val snapshot = buffer.snapshot()

        assertEquals(0L, snapshot.sessionElapsedStartMillis)
        assertEquals(1_040L, snapshot.sessionElapsedEndMillis)
    }
}
