package com.stressdetector.mobile.audio

import kotlin.math.abs

/** Contiguous observed PCM only; a pause never compresses the speaker timeline. */
internal class RollingSpeakerWindow(
    private val sampleRateHz: Int = 16_000,
    private val maxSamples: Int = sampleRateHz * MAX_CONTEXT_SECONDS
) {
    var samples: FloatArray = FloatArray(0)
        private set
    var startOffsetMs: Long = 0L
        private set

    init {
        require(sampleRateHz > 0 && maxSamples > 0)
    }

    fun reset() {
        samples = FloatArray(0)
        startOffsetMs = 0L
    }

    fun append(chunk: FloatArray, chunkStartOffsetMs: Long) {
        require(chunkStartOffsetMs >= 0)
        require(chunk.size <= maxSamples) { "Utterance exceeds the bounded diarization window." }
        if (chunk.isEmpty()) return
        val expectedStart = startOffsetMs + samples.size * MILLIS_PER_SECOND / sampleRateHz
        if (samples.isEmpty() || abs(chunkStartOffsetMs - expectedStart) > BOUNDARY_ROUNDING_MS) {
            samples = FloatArray(0)
            startOffsetMs = chunkStartOffsetMs
        }
        samples += chunk
        val excess = samples.size - maxSamples
        if (excess > 0) {
            samples = samples.copyOfRange(excess, samples.size)
            startOffsetMs += excess * MILLIS_PER_SECOND / sampleRateHz
        }
    }

    private companion object {
        const val MAX_CONTEXT_SECONDS = 30
        const val MILLIS_PER_SECOND = 1_000L
        const val BOUNDARY_ROUNDING_MS = 1L
    }
}
