package com.stressdetector.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Deterministic binary codec for [WearTemperatureBatch] over `/ppg/skin-temperature`. */
object WearTemperatureCodec {
    private const val MAGIC = 0x57544D31 // "WTM1"
    private const val VERSION = 1

    fun encode(batch: WearTemperatureBatch): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { stream ->
            stream.writeInt(MAGIC)
            stream.writeInt(VERSION)
            stream.writeUTF(batch.sessionId)
            stream.writeUTF(batch.sessionNonce)
            stream.writeLong(batch.generation)
            stream.writeLong(batch.sequenceNumber)
            stream.writeUTF(batch.peerNodeId)
            stream.writeLong(batch.sentAtEpochMillis)
            stream.writeInt(batch.readings.size)
            batch.readings.forEach { reading ->
                stream.writeLong(reading.sensorTimestampEpochMillis)
                stream.writeLong(reading.elapsedMillis)
                writeNullableFloat(stream, reading.skinTemperatureC)
                writeNullableFloat(stream, reading.ambientTemperatureC)
                stream.writeInt(reading.status)
                stream.writeBoolean(reading.isValid)
                stream.writeUTF(reading.source)
                writeNullableLong(stream, reading.clockUncertaintyMillis)
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): WearTemperatureBatch {
        require(bytes.size >= MIN_HEADER_BYTES) { "Skin temperature payload too short: ${bytes.size}" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val magic = stream.readInt()
            require(magic == MAGIC) { "Unexpected skin temperature magic: $magic" }
            val version = stream.readInt()
            require(version == VERSION) { "Unsupported skin temperature codec version: $version" }
            val sessionId = stream.readUTF()
            val sessionNonce = stream.readUTF()
            val generation = stream.readLong()
            val sequenceNumber = stream.readLong()
            val peerNodeId = stream.readUTF()
            val sentAtEpochMillis = stream.readLong()
            val count = stream.readInt()
            require(count in 1..WearTemperatureBatch.MAX_READINGS_PER_BATCH) {
                "Invalid skin temperature reading count: $count"
            }
            val readings = List(count) {
                SkinTemperatureReading(
                    sensorTimestampEpochMillis = stream.readLong(),
                    elapsedMillis = stream.readLong(),
                    skinTemperatureC = readNullableFloat(stream),
                    ambientTemperatureC = readNullableFloat(stream),
                    status = stream.readInt(),
                    isValid = stream.readBoolean(),
                    source = stream.readUTF(),
                    clockUncertaintyMillis = readNullableLong(stream)
                )
            }
            WearTemperatureBatch(
                sessionId = sessionId,
                sessionNonce = sessionNonce,
                generation = generation,
                sequenceNumber = sequenceNumber,
                peerNodeId = peerNodeId,
                readings = readings,
                sentAtEpochMillis = sentAtEpochMillis
            )
        }
    }

    private fun writeNullableFloat(stream: DataOutputStream, value: Float?) {
        stream.writeBoolean(value != null)
        if (value != null) stream.writeFloat(value)
    }

    private fun readNullableFloat(stream: DataInputStream): Float? = if (stream.readBoolean()) stream.readFloat() else null

    private fun writeNullableLong(stream: DataOutputStream, value: Long?) {
        stream.writeBoolean(value != null)
        if (value != null) stream.writeLong(value)
    }

    private fun readNullableLong(stream: DataInputStream): Long? = if (stream.readBoolean()) stream.readLong() else null

    private const val MIN_HEADER_BYTES = 24
}
