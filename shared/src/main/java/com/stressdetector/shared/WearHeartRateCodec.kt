package com.stressdetector.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Deterministic binary codec for [WearHeartRateBatch] over Wear MessageClient (`/ppg/heart-rate`). */
object WearHeartRateCodec {
    private const val MAGIC = 0x57485231 // "WHR1"
    private const val VERSION = 1

    fun encode(batch: WearHeartRateBatch): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { stream ->
            stream.writeInt(MAGIC)
            stream.writeInt(VERSION)
            stream.writeUTF(batch.sessionId)
            stream.writeUTF(batch.sessionNonce)
            stream.writeLong(batch.generation)
            stream.writeLong(batch.sampleTimestampElapsedMillis)
            stream.writeLong(batch.sampleTimestampEpochMillis)
            stream.writeInt(batch.bpm)
            stream.writeInt(batch.heartRateStatus)
            stream.writeUTF(batch.source)
            stream.writeInt(batch.ibiMillisList.size)
            batch.ibiMillisList.forEach { stream.writeInt(it) }
            stream.writeInt(batch.ibiStatusList.size)
            batch.ibiStatusList.forEach { stream.writeInt(it) }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): WearHeartRateBatch {
        require(bytes.size >= MIN_HEADER_BYTES) { "HeartRate payload too short: ${bytes.size}" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val magic = stream.readInt()
            require(magic == MAGIC) { "Unexpected HeartRate magic: $magic" }
            val version = stream.readInt()
            require(version == VERSION) { "Unsupported HeartRate codec version: $version" }
            val sessionId = stream.readUTF()
            val sessionNonce = stream.readUTF()
            val generation = stream.readLong()
            val elapsedMillis = stream.readLong()
            val epochMillis = stream.readLong()
            val bpm = stream.readInt()
            val status = stream.readInt()
            val source = stream.readUTF()
            val ibiCount = stream.readInt()
            require(ibiCount in 0..WearHeartRateBatch.MAX_IBI_ENTRIES) { "Invalid IBI count: $ibiCount" }
            val ibiList = List(ibiCount) { stream.readInt() }
            val statusCount = stream.readInt()
            require(statusCount in 0..WearHeartRateBatch.MAX_IBI_ENTRIES) { "Invalid IBI status count: $statusCount" }
            val ibiStatuses = List(statusCount) { stream.readInt() }
            WearHeartRateBatch(
                sessionId = sessionId,
                sessionNonce = sessionNonce,
                generation = generation,
                sampleTimestampElapsedMillis = elapsedMillis,
                sampleTimestampEpochMillis = epochMillis,
                bpm = bpm,
                heartRateStatus = status,
                ibiMillisList = ibiList,
                ibiStatusList = ibiStatuses,
                source = source
            )
        }
    }

    private const val MIN_HEADER_BYTES = 16
}
