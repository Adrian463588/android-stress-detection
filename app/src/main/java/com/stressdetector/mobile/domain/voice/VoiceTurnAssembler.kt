package com.stressdetector.mobile.domain.voice

import kotlin.math.sqrt

/** A validated PCM frame at the boundary of the voice domain. */
data class VoicePcmFrame(
    val samples: ShortArray,
    val sampleRateHz: Int = VoiceTurnAssembler.SAMPLE_RATE_HZ,
    val channelCount: Int = VoiceTurnAssembler.CHANNEL_COUNT
) {
    init {
        require(samples.isNotEmpty()) { "Voice PCM frame must not be empty" }
    }
}

data class VoiceTurn(
    val samples: ShortArray,
    val sampleRateHz: Int = VoiceTurnAssembler.SAMPLE_RATE_HZ,
    val channelCount: Int = VoiceTurnAssembler.CHANNEL_COUNT,
    val containsSpeech: Boolean
) {
    val durationMillis: Long
        get() = samples.size * 1_000L / sampleRateHz
}

enum class VoiceTurnEndReason {
    SILENCE_ENDPOINT,
    MAX_DURATION
}

sealed interface VoiceTurnAssemblyResult {
    data object AwaitingSpeech : VoiceTurnAssemblyResult

    data class Collecting(val durationMillis: Long, val speechDetected: Boolean) : VoiceTurnAssemblyResult

    data class Completed(val turn: VoiceTurn, val reason: VoiceTurnEndReason) : VoiceTurnAssemblyResult
}

/**
 * Pure PCM turn assembler. It is deliberately independent from AudioRecord,
 * VAD implementations, Compose, and Android lifecycle APIs.
 */
class VoiceTurnAssembler(private val config: Config = Config()) {
    private val maxSamples = config.maxDurationMillis.toSampleCount(SAMPLE_RATE_HZ)
    private val preSpeechSamples = config.preSpeechBufferMillis.toSampleCount(SAMPLE_RATE_HZ)
    private val turnBuffer = ShortArray(maxSamples)
    private val preSpeechBuffer = ShortArray(preSpeechSamples.coerceAtLeast(1))

    private var turnSize = 0
    private var preSpeechSize = 0
    private var silenceMillis = 0L
    private var speechDetected = false
    private var terminal = false

    fun append(frame: VoicePcmFrame): VoiceTurnAssemblyResult {
        val isSpeech = rms(frame.samples) >= config.speechRmsThreshold
        return append(frame, isSpeech)
    }

    /** Uses a trusted VAD decision while keeping the PCM assembly policy pure. */
    fun append(frame: VoicePcmFrame, speechDetectedByVad: Boolean): VoiceTurnAssemblyResult {
        check(!terminal) { "Voice turn is complete; call reset() before appending" }
        require(frame.sampleRateHz == SAMPLE_RATE_HZ) {
            "Voice turn requires ${SAMPLE_RATE_HZ}Hz PCM"
        }
        require(frame.channelCount == CHANNEL_COUNT) {
            "Voice turn requires mono PCM"
        }

        val samplesToAppend = (maxSamples - turnSize).coerceAtMost(frame.samples.size)
        val frameSamples = frame.samples.copyOf(samplesToAppend)
        val isSpeech = speechDetectedByVad

        if (!speechDetected && isSpeech) {
            appendPreSpeechToTurn()
            speechDetected = true
        }

        if (speechDetected) {
            appendToTurn(frameSamples)
        } else {
            appendToPreSpeech(frameSamples)
        }

        if (speechDetected) {
            silenceMillis = if (isSpeech) 0L else silenceMillis + frameSamples.durationMillis()
        }

        val reachedMaxDuration = turnSize >= maxSamples
        val reachedSilenceEndpoint = speechDetected && silenceMillis >= config.silenceEndpointMillis
        if (reachedMaxDuration || reachedSilenceEndpoint) {
            terminal = true
            return VoiceTurnAssemblyResult.Completed(
                turn = snapshotTurn(),
                reason = if (reachedMaxDuration) {
                    VoiceTurnEndReason.MAX_DURATION
                } else {
                    VoiceTurnEndReason.SILENCE_ENDPOINT
                }
            )
        }

        return if (speechDetected) {
            VoiceTurnAssemblyResult.Collecting(
                durationMillis = turnSize.durationMillis(),
                speechDetected = true
            )
        } else {
            VoiceTurnAssemblyResult.AwaitingSpeech
        }
    }

    fun cancel() {
        reset()
    }

    /** Finishes a user-stopped turn without inventing a partial transcript. */
    fun finish(): VoiceTurn? {
        if (terminal || !speechDetected || turnSize == 0) return null
        terminal = true
        return snapshotTurn()
    }

    fun reset() {
        turnSize = 0
        preSpeechSize = 0
        silenceMillis = 0L
        speechDetected = false
        terminal = false
    }

    private fun appendPreSpeechToTurn() {
        val samplesToAppend = preSpeechBuffer.copyOf(preSpeechSize)
        appendToTurn(samplesToAppend)
        preSpeechSize = 0
    }

    private fun appendToTurn(samples: ShortArray) {
        val samplesToCopy = (maxSamples - turnSize).coerceAtMost(samples.size)
        samples.copyInto(turnBuffer, destinationOffset = turnSize, endIndex = samplesToCopy)
        turnSize += samplesToCopy
    }

    private fun appendToPreSpeech(samples: ShortArray) {
        val samplesToKeep = samples.takeLast(preSpeechBuffer.size)
        val existing = preSpeechBuffer.copyOf(preSpeechSize).toList()
        val combined = (existing + samplesToKeep).takeLast(preSpeechBuffer.size)
        combined.forEachIndexed { index, sample -> preSpeechBuffer[index] = sample }
        preSpeechSize = combined.size
    }

    private fun snapshotTurn(): VoiceTurn = VoiceTurn(
        samples = turnBuffer.copyOf(turnSize),
        containsSpeech = speechDetected
    )

    data class Config(
        val maxDurationMillis: Long = MAX_DURATION_MILLIS,
        val silenceEndpointMillis: Long = SILENCE_ENDPOINT_MILLIS,
        val preSpeechBufferMillis: Long = PRE_SPEECH_BUFFER_MILLIS,
        val speechRmsThreshold: Double = DEFAULT_SPEECH_RMS_THRESHOLD
    ) {
        init {
            require(maxDurationMillis > 0) { "maxDurationMillis must be positive" }
            require(silenceEndpointMillis > 0) { "silenceEndpointMillis must be positive" }
            require(preSpeechBufferMillis >= 0) { "preSpeechBufferMillis cannot be negative" }
            require(speechRmsThreshold >= 0) { "speechRmsThreshold cannot be negative" }
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_COUNT = 1
        const val MAX_DURATION_MILLIS = 30_000L
        const val SILENCE_ENDPOINT_MILLIS = 900L
        const val PRE_SPEECH_BUFFER_MILLIS = 300L
        const val DEFAULT_SPEECH_RMS_THRESHOLD = 700.0
    }
}

private fun Long.toSampleCount(sampleRateHz: Int): Int = (this * sampleRateHz / 1_000L).toInt()

private fun Int.durationMillis(sampleRateHz: Int = VoiceTurnAssembler.SAMPLE_RATE_HZ): Long = this * 1_000L / sampleRateHz

private fun ShortArray.durationMillis(sampleRateHz: Int = VoiceTurnAssembler.SAMPLE_RATE_HZ): Long = size * 1_000L / sampleRateHz

private fun rms(samples: ShortArray): Double {
    if (samples.isEmpty()) return 0.0
    val meanSquare = samples.fold(0.0) { total, sample ->
        total + sample.toDouble() * sample.toDouble()
    } / samples.size
    return sqrt(meanSquare)
}
