package com.stressdetector.mobile.audio

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Disk-backed utterance handoff. Capture owns WAV writing and only places
 * short file references in the ASR queue, so slow native decoding cannot
 * suspend AudioRecord or grow a FloatArray queue without a bound.
 */
class SpeechUtteranceSpool(private val directory: File, private val sampleRateHz: Int = 16_000) {
    private val sequence = AtomicLong(0L)

    init {
        require(sampleRateHz > 0)
        directory.mkdirs()
    }

    fun write(utterance: SpeechUtterance): File {
        require(utterance.samples.isNotEmpty()) { "Cannot spool an empty utterance." }
        val target = File(directory, "utterance-${sequence.incrementAndGet()}.pcm")
        DataOutputStream(FileOutputStream(target)).use { output ->
            output.writeLong(utterance.startOffsetMs)
            output.writeInt(utterance.samples.size)
            utterance.samples.forEach { sample ->
                val pcm = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                output.writeShort(pcm)
            }
            output.flush()
        }
        return target
    }

    fun read(file: File): SpeechUtterance {
        DataInputStream(FileInputStream(file)).use { input ->
            val startOffsetMs = input.readLong()
            val count = input.readInt()
            require(count in 1..(sampleRateHz * MAX_UTTERANCE_SECONDS)) {
                "Spool utterance size is invalid."
            }
            val samples = FloatArray(count) { input.readShort() / Short.MAX_VALUE.toFloat() }
            return SpeechUtterance(samples, startOffsetMs)
        }
    }

    fun delete(file: File) {
        if (file.exists()) file.delete()
    }

    fun cleanup() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val MAX_UTTERANCE_SECONDS = 30
    }
}
