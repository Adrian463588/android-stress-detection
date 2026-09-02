package com.stressdetector.mobile.audio

import java.util.ArrayDeque

/** A bounded, deterministic VAD utterance segmenter shared by Verbatim+ capture. */
data class SpeechUtterance(val samples: FloatArray, val startOffsetMs: Long)

data class SpeechSegmenterEmission(val framesToTranscribe: List<SpeechFrame>, val completedUtterance: SpeechUtterance?)

class SpeechUtteranceSegmenter(
    private val sampleRateHz: Int = 16_000,
    /** Legacy hint retained for callers; append() uses each frame's real duration. */
    @Suppress("UNUSED_PARAMETER") private val frameDurationMs: Long = 100L,
    private val preRollMs: Long = 300L,
    private val trailingSilenceMs: Long = 850L,
    private val maxUtteranceMs: Long = 15_000L
) {
    private data class BufferedFrame(val samples: FloatArray, val startOffsetMs: Long, val durationMs: Long)

    private val preRoll = ArrayDeque<BufferedFrame>()
    private val activeFrames = ArrayList<FloatArray>()
    private var preRollStartOffsetMs: Long? = null
    private var activeStartOffsetMs: Long? = null
    private var silenceMs = 0L

    fun append(
        frame: FloatArray,
        speechDetected: Boolean,
        frameStartOffsetMs: Long,
        durationMs: Long = frame.size.toLong() * 1_000L / sampleRateHz
    ): SpeechUtterance? = appendStreaming(
        SpeechFrame(
            samples = frame,
            startOffsetMs = frameStartOffsetMs,
            durationMs = durationMs
        ),
        speechDetected
    ).completedUtterance

    /**
     * Returns every frame exactly once for streaming ASR. This is important
     * for Indonesian: the onset frame must include the buffered pre-roll.
     */
    fun appendStreaming(frame: SpeechFrame, speechDetected: Boolean): SpeechSegmenterEmission {
        require(frame.samples.isNotEmpty()) { "Speech frame must not be empty." }
        require(frame.durationMs > 0L) { "Speech frame duration must be positive." }
        if (activeFrames.isEmpty() && !speechDetected) {
            addPreRoll(frame)
            return SpeechSegmenterEmission(emptyList(), null)
        }

        val framesToTranscribe = ArrayList<SpeechFrame>()
        if (activeFrames.isEmpty()) {
            activeStartOffsetMs = preRollStartOffsetMs ?: frame.startOffsetMs
            preRoll.forEach { buffered ->
                activeFrames += buffered.samples
                framesToTranscribe += SpeechFrame(
                    samples = buffered.samples,
                    startOffsetMs = buffered.startOffsetMs,
                    durationMs = buffered.durationMs
                )
            }
            preRoll.clear()
            preRollStartOffsetMs = null
        }
        activeFrames += frame.samples
        framesToTranscribe += frame
        silenceMs = if (speechDetected) 0L else silenceMs + frame.durationMs

        val utteranceDurationMs = activeFrames.sumOf { it.size.toLong() } * 1_000L / sampleRateHz
        val completed = if (silenceMs >= trailingSilenceMs || utteranceDurationMs >= maxUtteranceMs) {
            emitAndReset()
        } else {
            null
        }
        return SpeechSegmenterEmission(framesToTranscribe, completed)
    }

    fun finish(): SpeechUtterance? = if (activeFrames.isEmpty()) null else emitAndReset()

    private fun addPreRoll(frame: SpeechFrame) {
        if (preRoll.isEmpty()) preRollStartOffsetMs = frame.startOffsetMs
        preRoll.addLast(
            BufferedFrame(
                samples = frame.samples.copyOf(),
                startOffsetMs = frame.startOffsetMs,
                durationMs = frame.durationMs
            )
        )
        while (preRoll.sumOf(BufferedFrame::durationMs) > preRollMs) {
            val removed = preRoll.removeFirst()
            preRollStartOffsetMs = preRollStartOffsetMs?.plus(removed.durationMs)
        }
    }

    private fun emitAndReset(): SpeechUtterance {
        val samples = activeFrames.flatMap { it.asIterable() }.toFloatArray()
        val utterance = SpeechUtterance(
            samples = samples,
            startOffsetMs = activeStartOffsetMs ?: 0L
        )
        activeFrames.clear()
        activeStartOffsetMs = null
        silenceMs = 0L
        return utterance
    }
}
