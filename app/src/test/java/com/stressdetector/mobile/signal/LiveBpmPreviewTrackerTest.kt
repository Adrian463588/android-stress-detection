package com.stressdetector.mobile.signal

import com.stressdetector.mobile.data.PpgStreamState
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.domain.engine.BpmSignalStatus
import com.stressdetector.mobile.domain.engine.BpmSource
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.WearHeartRateBatch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBpmPreviewTrackerTest {

    @Test
    fun emitsValidGreenBpmAfterTenSecondWarmupAndDetectsTimeoutStaleness() {
        val tracker = LiveBpmPreviewTracker(staleTimeoutMillis = 5_000L)
        val hz = 25
        val seconds = 15
        val n = hz * seconds
        // 75 BPM -> 1.25 Hz sinusoidal PPG
        val timestamps = LongArray(n) { i -> 1_000_000L + i * 40L }
        val elapsed = LongArray(n) { i -> i * 40L }
        val values = IntArray(n) { i ->
            (2_000_000 + 25_000.0 * sin(2.0 * Math.PI * 1.25 * (i / 25.0))).roundToInt()
        }
        val greenWindow = WindowSnapshot(
            type = PpgType.GREEN,
            timestampsMillis = timestamps,
            rawValues = values,
            elapsedTimestampsMillis = elapsed
        )

        val live = tracker.evaluate(
            windows = mapOf(PpgType.GREEN to greenWindow),
            stream = PpgStreamState(
                sessionId = "s1",
                lastGreenBatchMonotonicMillis = 20_000L
            ),
            monotonicNowMillis = 21_000L
        )

        val green = live.getValue(BpmSource.PPG_GREEN)
        assertEquals(BpmSignalStatus.SIGNAL_VALID, green.status)
        assertFalse(green.isStale)
        assertNotNull(green.observedBpm)
        assertTrue(abs(green.observedBpm!! - 75f) < 6f)

        // Timeout > 5s clears frozen BPM cleanly
        val stale = tracker.evaluate(
            windows = mapOf(PpgType.GREEN to greenWindow),
            stream = PpgStreamState(
                sessionId = "s1",
                lastGreenBatchMonotonicMillis = 20_000L
            ),
            monotonicNowMillis = 26_000L
        ).getValue(BpmSource.PPG_GREEN)

        assertTrue(stale.isStale)
        assertNull(stale.observedBpm)
        assertEquals(BpmSignalStatus.DISCONNECTED, stale.status)
    }

    @Test
    fun keepsWatchSdkHeartRateDistinctFromPpgGreenAndPrefersValidPpgGreen() {
        val tracker = LiveBpmPreviewTracker()
        val hrBatch = WearHeartRateBatch(
            sessionId = "s1",
            sampleTimestampElapsedMillis = 12_000L,
            sampleTimestampEpochMillis = 1_760_000_012_000L,
            bpm = 82,
            heartRateStatus = 1,
            ibiMillisList = listOf(730, 745, 725, 740, 750),
            ibiStatusList = listOf(0, 0, 0, 0, 0)
        )

        val evaluated = tracker.evaluate(
            windows = emptyMap(),
            stream = PpgStreamState(sessionId = "s1"),
            monotonicNowMillis = 12_500L,
            watchHeartRate = hrBatch,
            watchHeartRateReceiptMonotonicMillis = 12_400L
        )

        assertNull(evaluated.getValue(BpmSource.PPG_GREEN).observedBpm)
        val sdkSnap = evaluated.getValue(BpmSource.WATCH_SDK_HR)
        assertEquals(82f, sdkSnap.observedBpm!!, 0.01f)
        assertNotNull(sdkSnap.prvMetrics)
        assertEquals(BpmSource.WATCH_SDK_HR, tracker.selectActivePreview(evaluated)?.source)
    }
}
