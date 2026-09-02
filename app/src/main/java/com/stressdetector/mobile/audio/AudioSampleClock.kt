package com.stressdetector.mobile.audio

import com.stressdetector.mobile.domain.engine.AudioGap

internal data class AudioFrameTiming(val sampleStartIndex: Long, val sampleCount: Int, val startOffsetMs: Long, val durationMs: Long)

/** Converts the monotonic PCM sample clock into the session timeline. */
internal class AudioSampleClock(private val sampleRateHz: Int) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive." }
    }

    private var sampleCursor = 0L
    private var timelineGapMs = 0L

    fun next(sampleCount: Int): AudioFrameTiming {
        require(sampleCount > 0) { "sampleCount must be positive." }
        val timing = AudioFrameTiming(
            sampleStartIndex = sampleCursor,
            sampleCount = sampleCount,
            startOffsetMs = sampleCursor * 1_000L / sampleRateHz + timelineGapMs,
            durationMs = sampleCount * 1_000L / sampleRateHz
        )
        sampleCursor += sampleCount
        return timing
    }

    fun addGap(durationMs: Long, reason: String): AudioGap {
        require(durationMs >= 0L) { "durationMs must not be negative." }
        val gap = AudioGap(
            startOffsetMs = sampleCursor * 1_000L / sampleRateHz + timelineGapMs,
            durationMs = durationMs,
            reason = reason
        )
        timelineGapMs += durationMs
        return gap
    }
}
