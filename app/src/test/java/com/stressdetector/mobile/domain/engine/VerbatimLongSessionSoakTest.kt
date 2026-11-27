@file:Suppress("LongMethod")

package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.PpgStreamState
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.signal.LiveBpmPreviewTracker
import com.stressdetector.shared.PpgType
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic 60-minute and 120-minute endurance soak simulation covering
 * 1 Hz BPM preview ticks, 1 s acoustic frames, pause/resume intervals, and
 * bounded live history retention.
 */
class VerbatimLongSessionSoakTest {

    @Test
    fun sixtyAndOneHundredTwentyMinuteSimulationsMaintainBoundedStateAndAccurateDuration() {
        runSoakSimulation(totalMinutes = 60, pauseDurationSeconds = 90L)
        runSoakSimulation(totalMinutes = 120, pauseDurationSeconds = 180L)
    }

    private fun runSoakSimulation(totalMinutes: Int, pauseDurationSeconds: Long) {
        val totalSeconds = totalMinutes * 60L
        var wallMonoMs = 1_000L
        val timeline = SessionTimeline.start(
            sessionId = "soak-${totalMinutes}m",
            utcNowMillis = { 1_700_000_000_000L + wallMonoMs },
            monotonicNowMillis = { wallMonoMs }
        )

        val tracker = LiveBpmPreviewTracker(staleTimeoutMillis = 5_000L)
        val hz = 25
        val bufLen = hz * 15
        val raw = IntArray(bufLen) { i ->
            (2_000_000 + 20_000.0 * sin(2.0 * Math.PI * 1.2 * (i / 25.0))).roundToInt()
        }
        val ts = LongArray(bufLen) { i -> 1_000_000L + i * 40L }
        val elapsedArr = LongArray(bufLen) { i -> i * 40L }

        val speakerTracker = SpeakerBaselineTracker()
        var pitchHistory = emptyList<LivePitchObservation>()
        var lastBpmValue: Float? = null

        val pauseAtSecond = totalSeconds / 2L
        val resumeAtSecond = pauseAtSecond + pauseDurationSeconds
        var isPaused = false
        val tickNanos = mutableListOf<Long>()

        for (sec in 1L..totalSeconds) {
            wallMonoMs = 1_000L + sec * 1_000L
            if (sec == pauseAtSecond) {
                isPaused = true
                timeline.recordPauseStart((sec - 1L) * 1_000L)
            } else if (sec == resumeAtSecond) {
                isPaused = false
                timeline.recordPauseEnd((sec - 1L) * 1_000L)
            }
            if (isPaused) continue

            if (sec % 5L == 0L) {
                val t0 = System.nanoTime()
                val activeMs = timeline.activeRecordingMillis(sec * 1_000L)
                val obs = LivePitchObservation(
                    sessionId = "soak-${totalMinutes}m",
                    startOffsetMs = (activeMs - 1_000L).coerceAtLeast(0L),
                    endOffsetMs = activeMs.coerceAtLeast(1_000L),
                    frequencyHz = 165.0 + (sec % 10L),
                    status = LivePitchStatus.AVAILABLE,
                    voicedFrameCount = 8,
                    frameCount = 10
                )
                pitchHistory = appendLivePitch(pitchHistory, obs, capacity = 60)
                speakerTracker.observe(
                    SpeakerAcousticObservation(
                        segmentId = "seg-$sec",
                        sessionId = "soak-${totalMinutes}m",
                        speakerId = "Klien",
                        startOffsetMs = obs.startOffsetMs,
                        endOffsetMs = obs.endOffsetMs,
                        f0Semitones = 12.0 + (sec % 4L),
                        loudnessDb = -20.0,
                        speakingRateWps = 2.0,
                        pauseRatio = 0.1,
                        voicedRatio = 0.8,
                        audioQuality = 0.9f,
                        isOverlap = false,
                        isFinal = true
                    )
                )

                val snapshot = WindowSnapshot(
                    type = PpgType.GREEN,
                    timestampsMillis = ts,
                    rawValues = raw,
                    elapsedTimestampsMillis = elapsedArr
                )
                val eval = tracker.evaluate(
                    windows = mapOf(PpgType.GREEN to snapshot),
                    stream = PpgStreamState(
                        sessionId = "soak-${totalMinutes}m",
                        lastGreenBatchMonotonicMillis = wallMonoMs
                    ),
                    monotonicNowMillis = wallMonoMs
                )
                lastBpmValue = eval[BpmSource.PPG_GREEN]?.observedBpm ?: lastBpmValue
                tickNanos += (System.nanoTime() - t0)
            }
        }

        val expectedActiveMs = (totalSeconds - pauseDurationSeconds) * 1_000L
        assertEquals(expectedActiveMs, timeline.activeRecordingMillis(totalSeconds * 1_000L))
        assertTrue(pitchHistory.size <= 60)
        assertTrue((lastBpmValue ?: 0f) in 60f..90f)
        val sortedNanos = tickNanos.sorted()
        val p95Ms = sortedNanos[(sortedNanos.size * 95 / 100).coerceAtMost(sortedNanos.lastIndex)] / 1_000_000.0
        assertTrue("Expected p95 tick < 200ms, got ${p95Ms}ms", p95Ms < 200.0)
    }
}
