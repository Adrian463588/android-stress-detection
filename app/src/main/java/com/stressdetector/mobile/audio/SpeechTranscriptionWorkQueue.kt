package com.stressdetector.mobile.audio

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel

/** Ordered, disk-backed work handoff that never blocks the AudioRecord loop. */
internal class SpeechTranscriptionWorkQueue(private val directory: File, private val maxPendingItems: Int = DEFAULT_MAX_PENDING_ITEMS) {
    private val sequence = AtomicLong(nextSequence())
    private var closed = false
    val signals = Channel<Unit>(capacity = 1)

    init {
        directory.mkdirs()
    }

    @Synchronized
    fun offer(work: SpeechTranscriptionWork, allowAfterClose: Boolean = false): Boolean {
        if (closed && !allowAfterClose) return false
        val pendingItems = directory.listFiles()
            ?.count { it.isFile && it.extension == WORK_EXTENSION }
            ?: 0
        if (pendingItems >= maxPendingItems) return false
        val id = sequence.incrementAndGet()
        val temporary = File(directory, "%020d.work.part".format(id))
        val target = File(directory, "%020d.work".format(id))
        return runCatching {
            DataOutputStream(FileOutputStream(temporary)).use { output ->
                when (work) {
                    is SpeechTranscriptionWork.Capture -> {
                        output.writeUTF(TYPE_CAPTURE)
                        output.writeUTF(work.reference.absolutePath)
                    }
                    is SpeechTranscriptionWork.Frame -> {
                        output.writeUTF(TYPE_FRAME)
                        output.writeUTF(work.reference.absolutePath)
                    }
                    is SpeechTranscriptionWork.Utterance -> {
                        output.writeUTF(TYPE_UTTERANCE)
                        output.writeUTF(work.reference.absolutePath)
                    }
                    SpeechTranscriptionWork.FlushCapture -> output.writeUTF(TYPE_FLUSH_CAPTURE)
                    SpeechTranscriptionWork.FinalizeUtterance -> output.writeUTF(TYPE_FINALIZE)
                }
                output.flush()
            }
            check(temporary.renameTo(target)) { "Speech work item could not be activated." }
            signals.trySend(Unit)
        }.onFailure {
            temporary.delete()
            target.delete()
        }.isSuccess
    }

    @Synchronized
    fun poll(): SpeechTranscriptionWork? {
        val file = directory.listFiles()
            ?.filter { it.isFile && it.extension == WORK_EXTENSION }
            ?.minByOrNull(File::getName)
            ?: return null
        return runCatching {
            DataInputStream(FileInputStream(file)).use { input ->
                when (input.readUTF()) {
                    TYPE_CAPTURE -> SpeechTranscriptionWork.Capture(File(input.readUTF()))
                    TYPE_FRAME -> SpeechTranscriptionWork.Frame(File(input.readUTF()))
                    TYPE_UTTERANCE -> SpeechTranscriptionWork.Utterance(File(input.readUTF()))
                    TYPE_FLUSH_CAPTURE -> SpeechTranscriptionWork.FlushCapture
                    TYPE_FINALIZE -> SpeechTranscriptionWork.FinalizeUtterance
                    else -> error("Unknown speech work item.")
                }
            }
        }.also {
            file.delete()
        }.getOrNull()
    }

    @Synchronized
    fun close() {
        closed = true
        signals.close()
    }

    fun cleanup() {
        directory.listFiles()?.forEach(File::delete)
        directory.delete()
    }

    private fun nextSequence(): Long = directory.listFiles()
        ?.mapNotNull { it.name.substringBefore('.').toLongOrNull() }
        ?.maxOrNull()
        ?: 0L

    private companion object {
        const val DEFAULT_MAX_PENDING_ITEMS = 512
        const val TYPE_FRAME = "frame"
        const val TYPE_CAPTURE = "capture"
        const val TYPE_UTTERANCE = "utterance"
        const val TYPE_FLUSH_CAPTURE = "flush_capture"
        const val TYPE_FINALIZE = "finalize"
        const val WORK_EXTENSION = "work"
    }
}
