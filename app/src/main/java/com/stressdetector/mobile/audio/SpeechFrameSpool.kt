package com.stressdetector.mobile.audio

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Disk-backed handoff for streaming ASR frames. Capture publishes only a
 * completed file reference, so a slow decoder never suspends AudioRecord or
 * grows a sample buffer in memory.
 */
data class SpeechFrame(
    val samples: FloatArray,
    val startOffsetMs: Long,
    val durationMs: Long,
    val sequence: Long = 0L,
    val sampleStartIndex: Long = 0L
)

class SpeechFrameSpool(private val directory: File, private val sampleRateHz: Int = 16_000) {
    private val sequence = AtomicLong(0L)

    init {
        require(sampleRateHz > 0)
        directory.mkdirs()
    }

    fun write(frame: SpeechFrame): File {
        require(frame.samples.isNotEmpty()) { "Cannot spool an empty speech frame." }
        require(frame.durationMs > 0L) { "Speech frame duration must be positive." }
        require(frame.samples.size <= sampleRateHz * MAX_FRAME_SECONDS) {
            "Speech frame is larger than the supported spool limit."
        }

        val id = sequence.incrementAndGet()
        val temporary = File(directory, "frame-$id.pcm.part")
        val target = File(directory, "frame-$id.pcm")
        try {
            DataOutputStream(FileOutputStream(temporary)).use { output ->
                output.writeLong(frame.sequence)
                output.writeLong(frame.sampleStartIndex)
                output.writeLong(frame.startOffsetMs)
                output.writeLong(frame.durationMs)
                output.writeInt(frame.samples.size)
                frame.samples.forEach { sample ->
                    val pcm = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                    output.writeShort(pcm)
                }
                output.flush()
            }
            check(temporary.renameTo(target)) { "Speech frame spool could not be activated." }
            return target
        } catch (error: Exception) {
            temporary.delete()
            target.delete()
            throw error
        }
    }

    fun read(file: File): SpeechFrame {
        DataInputStream(FileInputStream(file)).use { input ->
            val sequence = input.readLong()
            val sampleStartIndex = input.readLong()
            val startOffsetMs = input.readLong()
            val durationMs = input.readLong()
            val count = input.readInt()
            require(count in 1..(sampleRateHz * MAX_FRAME_SECONDS)) {
                "Speech frame spool size is invalid."
            }
            require(durationMs > 0L) { "Speech frame spool duration is invalid." }
            val samples = FloatArray(count) { input.readShort() / Short.MAX_VALUE.toFloat() }
            return SpeechFrame(samples, startOffsetMs, durationMs, sequence, sampleStartIndex)
        }
    }

    fun delete(file: File) {
        if (file.exists()) file.delete()
    }

    fun cleanup() {
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
    }

    private companion object {
        const val MAX_FRAME_SECONDS = 5
    }
}
