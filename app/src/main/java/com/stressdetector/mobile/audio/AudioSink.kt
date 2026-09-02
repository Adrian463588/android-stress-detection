package com.stressdetector.mobile.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface AudioSink {
    suspend fun play(samples: FloatArray, sampleRateHz: Int, isActive: () -> Boolean)
    suspend fun stopAndWait(): Result<Unit>
    suspend fun close()
}

/** One lifecycle-safe PCM sink for a single voice coordinator. */
class AndroidAudioSink : AudioSink {
    private val commandMutex = Mutex()

    @Volatile private var activeTrack: AudioTrack? = null

    @Volatile private var stopRequested = false

    @Volatile private var closed = false

    override suspend fun play(samples: FloatArray, sampleRateHz: Int, isActive: () -> Boolean) {
        require(samples.isNotEmpty()) { "Voice renderer returned empty audio." }
        require(sampleRateHz > 0) { "Voice renderer returned an invalid sample rate." }
        commandMutex.withLock {
            check(!closed) { "Audio sink sudah ditutup." }
            stopRequested = false
            withContext(Dispatchers.IO) {
                val outputRate = AudioOutputFormat.choose(sampleRateHz)
                val outputSamples = PcmResampler.resample(samples, sampleRateHz, outputRate)
                val pcm = ShortArray(outputSamples.size) { index ->
                    (outputSamples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                }
                val track = AudioOutputFormat.createTrack(outputRate, pcm.size)
                activeTrack = track
                try {
                    track.play()
                    var offset = 0
                    while (offset < pcm.size && !stopRequested && isActive()) {
                        coroutineContext.ensureActive()
                        val count = track.write(
                            pcm,
                            offset,
                            minOf(4_096, pcm.size - offset),
                            AudioTrack.WRITE_BLOCKING
                        )
                        require(count > 0) { "Audio output stopped unexpectedly." }
                        offset += count
                    }
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
                } finally {
                    activeTrack = null
                    runCatching { track.release() }
                }
            }
        }
    }

    override suspend fun stopAndWait(): Result<Unit> = runCatching {
        stopRequested = true
        activeTrack?.let { track ->
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
                track.flush()
            }
        }
        commandMutex.withLock { Unit }
    }

    override suspend fun close() {
        stopAndWait()
        closed = true
    }
}

object PcmResampler {
    fun resample(samples: FloatArray, sourceRateHz: Int, targetRateHz: Int): FloatArray {
        require(sourceRateHz > 0 && targetRateHz > 0) { "Sample rate must be positive." }
        if (sourceRateHz == targetRateHz || samples.isEmpty()) return samples.copyOf()
        val targetSize = (samples.size.toDouble() * targetRateHz / sourceRateHz).roundToInt()
            .coerceAtLeast(1)
        return FloatArray(targetSize) { targetIndex ->
            val sourcePosition = targetIndex.toDouble() * sourceRateHz / targetRateHz
            val left = sourcePosition.toInt().coerceIn(0, samples.lastIndex)
            val right = (left + 1).coerceAtMost(samples.lastIndex)
            val fraction = (sourcePosition - left).toFloat()
            samples[left] + (samples[right] - samples[left]) * fraction
        }
    }
}

private object AudioOutputFormat {
    private val candidateRates = intArrayOf(24_000, 22_050, 16_000, 48_000)

    fun choose(preferredRateHz: Int): Int = (listOf(preferredRateHz) + candidateRates.toList())
        .distinct()
        .firstOrNull { rate ->
            AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) > 0
        }
        ?: error("Perangkat tidak mendukung output audio PCM mono.")

    fun createTrack(sampleRateHz: Int, sampleCount: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "Audio output format is not supported." }
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRateHz)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, sampleCount * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { track -> check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed." } }
    }
}
