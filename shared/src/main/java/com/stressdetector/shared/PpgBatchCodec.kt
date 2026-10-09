package com.stressdetector.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Wire codec for Wear PPG batches.
 *
 * V5 writes a bounded binary frame: raw PPG values plus optional observed
 * accelerometer axes on the same authenticated batch/session timeline.
 * [decode] deliberately retains V1 line-oriented input during the rolling
 * phone/Wear update, but every new encode is V5.
 */
object PpgBatchCodec {
    fun encode(batch: PpgBatch): ByteArray {
        require(batch.schemaVersion == PpgBatch.CURRENT_SCHEMA_VERSION) {
            "Current encoder cannot write schema ${batch.schemaVersion}"
        }
        require(batch.sessionIdentity.isCanonical) {
            "Current encoder requires a canonical SessionIdentity"
        }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(BATCH_MAGIC)
                output.writeShort(PpgBatch.CURRENT_SCHEMA_VERSION)
                output.writeByte(batch.type.ordinal)
                output.writeByte(0) // reserved flags; keep header forward-compatible.
                output.writeString(batch.sessionId)
                output.writeString(batch.sessionNonce)
                output.writeShort(batch.protocolVersion)
                output.writeString(batch.peerNodeId)
                output.writeLong(batch.generation)
                output.writeString(batch.effectiveBatchId)
                output.writeLong(batch.sequenceNumber)
                output.writeInt(batch.samplingRateHz)
                output.writeLong(batch.elapsedStartMillis)
                output.writeLong(batch.elapsedEndMillis)
                output.writeInt(batch.size)
                if (batch.size > 0) {
                    output.writeLong(batch.timestampsMillis.first())
                    for (index in 1 until batch.size) {
                        output.writeLong(batch.timestampsMillis[index] - batch.timestampsMillis[index - 1])
                    }
                    batch.rawValues.forEach(output::writeInt)
                }
                output.writeInt(batch.accelerometerSamples.size)
                batch.accelerometerSamples.forEach { sample ->
                    output.writeLong(sample.sensorTimestampNanos)
                    output.writeLong(sample.elapsedMillis)
                    output.writeFloat(sample.x)
                    output.writeFloat(sample.y)
                    output.writeFloat(sample.z)
                }
                output.flush()
            }
            bytes.toByteArray()
        }
    }

    fun encodeAck(ack: PpgBatchAck): ByteArray = buildString {
        appendLine("schemaVersion=${ack.schemaVersion}")
        appendLine("sessionId=${ack.sessionId}")
        appendLine("sessionNonce=${ack.sessionNonce}")
        appendLine("protocolVersion=${ack.protocolVersion}")
        appendLine("peerNodeId=${ack.peerNodeId}")
        appendLine("generation=${ack.generation}")
        appendLine("batchId=${ack.batchId}")
        appendLine("sequenceNumber=${ack.sequenceNumber}")
    }.toByteArray(StandardCharsets.UTF_8)

    fun decodeAck(bytes: ByteArray): PpgBatchAck {
        val fields = parseFields(bytes)
        val version = fields["schemaVersion"]?.toIntOrNull() ?: PpgBatch.LEGACY_SCHEMA_VERSION
        require(version in PpgBatch.LEGACY_SCHEMA_VERSION..PpgBatch.CURRENT_SCHEMA_VERSION) {
            "Unsupported schemaVersion: $version"
        }
        return PpgBatchAck(
            sessionId = requireField(fields, "sessionId"),
            batchId = requireField(fields, "batchId"),
            sequenceNumber = requireLong(fields, "sequenceNumber"),
            schemaVersion = version,
            sessionNonce = fields["sessionNonce"].orEmpty(),
            protocolVersion = fields["protocolVersion"]?.toIntOrNull()
                ?: SessionIdentity.LEGACY_PROTOCOL_VERSION,
            peerNodeId = fields["peerNodeId"].orEmpty(),
            generation = fields["generation"]?.toLongOrNull() ?: 0L
        )
    }

    /** Kept readable for V1 peers; V2 terminal receipt is preferred. */
    fun encodeFlushAck(ack: PpgFlushAck): ByteArray = buildString {
        appendLine("schemaVersion=${ack.schemaVersion}")
        appendLine("sessionId=${ack.sessionId}")
        appendLine("flushed=${ack.flushed}")
        appendLine("completed=${ack.completed}")
        appendLine("elapsedMillis=${ack.elapsedMillis}")
    }.toByteArray(StandardCharsets.UTF_8)

    fun decodeFlushAck(bytes: ByteArray): PpgFlushAck {
        val fields = parseFields(bytes)
        val version = fields["schemaVersion"]?.toIntOrNull() ?: PpgBatch.LEGACY_SCHEMA_VERSION
        require(version in PpgBatch.LEGACY_SCHEMA_VERSION..PpgBatch.CURRENT_SCHEMA_VERSION) {
            "Unsupported schemaVersion: $version"
        }
        return PpgFlushAck(
            sessionId = requireField(fields, "sessionId"),
            flushed = fields["flushed"]?.toBooleanStrictOrNull()
                ?: error("Missing or invalid flushed"),
            completed = fields["completed"]?.toBooleanStrictOrNull() ?: false,
            elapsedMillis = fields["elapsedMillis"]?.toLongOrNull() ?: 0L,
            schemaVersion = version
        )
    }

    fun encodeTerminalReceipt(receipt: PpgTerminalReceipt): ByteArray = buildString {
        appendLine("schemaVersion=${receipt.schemaVersion}")
        appendLine("sessionId=${receipt.sessionId}")
        appendLine("sessionNonce=${receipt.sessionNonce}")
        appendLine("protocolVersion=${receipt.protocolVersion}")
        appendLine("expectedGreenSequence=${receipt.expectedGreenSequence}")
        appendLine("expectedIrSequence=${receipt.expectedIrSequence}")
        appendLine("expectedRedSequence=${receipt.expectedRedSequence}")
        appendLine("elapsedMillis=${receipt.elapsedMillis}")
        appendLine("completed=${receipt.completed}")
        appendLine("peerNodeId=${receipt.peerNodeId}")
        appendLine("generation=${receipt.generation}")
    }.toByteArray(StandardCharsets.UTF_8)

    fun decodeTerminalReceipt(bytes: ByteArray): PpgTerminalReceipt {
        val fields = parseFields(bytes)
        return PpgTerminalReceipt(
            sessionId = requireField(fields, "sessionId"),
            sessionNonce = fields["sessionNonce"].orEmpty(),
            expectedGreenSequence = requireLong(fields, "expectedGreenSequence"),
            expectedIrSequence = requireLong(fields, "expectedIrSequence"),
            expectedRedSequence = fields["expectedRedSequence"]?.toLongOrNull() ?: 0L,
            elapsedMillis = requireLong(fields, "elapsedMillis"),
            completed = fields["completed"]?.toBooleanStrictOrNull()
                ?: error("Missing or invalid completed"),
            schemaVersion = fields["schemaVersion"]?.toIntOrNull() ?: PpgBatch.CURRENT_SCHEMA_VERSION,
            protocolVersion = fields["protocolVersion"]?.toIntOrNull()
                ?: SessionIdentity.LEGACY_PROTOCOL_VERSION,
            peerNodeId = fields["peerNodeId"].orEmpty(),
            generation = fields["generation"]?.toLongOrNull() ?: 0L
        )
    }

    fun encodeTerminalAck(ack: PpgTerminalAck): ByteArray = buildString {
        appendLine("schemaVersion=${ack.schemaVersion}")
        appendLine("sessionId=${ack.sessionId}")
        appendLine("sessionNonce=${ack.sessionNonce}")
        appendLine("protocolVersion=${ack.protocolVersion}")
        appendLine("acceptedGreenSequence=${ack.acceptedGreenSequence}")
        appendLine("acceptedIrSequence=${ack.acceptedIrSequence}")
        appendLine("acceptedRedSequence=${ack.acceptedRedSequence}")
        appendLine("complete=${ack.complete}")
        appendLine("reason=${ack.reason.replace('\n', ' ')}")
        appendLine("peerNodeId=${ack.peerNodeId}")
        appendLine("generation=${ack.generation}")
    }.toByteArray(StandardCharsets.UTF_8)

    fun decodeTerminalAck(bytes: ByteArray): PpgTerminalAck {
        val fields = parseFields(bytes)
        return PpgTerminalAck(
            sessionId = requireField(fields, "sessionId"),
            sessionNonce = fields["sessionNonce"].orEmpty(),
            acceptedGreenSequence = requireLong(fields, "acceptedGreenSequence"),
            acceptedIrSequence = requireLong(fields, "acceptedIrSequence"),
            acceptedRedSequence = fields["acceptedRedSequence"]?.toLongOrNull() ?: 0L,
            complete = fields["complete"]?.toBooleanStrictOrNull()
                ?: error("Missing or invalid complete"),
            reason = fields["reason"].orEmpty(),
            schemaVersion = fields["schemaVersion"]?.toIntOrNull() ?: PpgBatch.CURRENT_SCHEMA_VERSION,
            protocolVersion = fields["protocolVersion"]?.toIntOrNull()
                ?: SessionIdentity.LEGACY_PROTOCOL_VERSION,
            peerNodeId = fields["peerNodeId"].orEmpty(),
            generation = fields["generation"]?.toLongOrNull() ?: 0L
        )
    }

    fun encodeSessionReady(ack: SessionReadyAck): ByteArray = buildString {
        appendLine("sessionId=${ack.sessionId}")
        appendLine("sessionNonce=${ack.sessionNonce}")
        appendLine("protocolVersion=${ack.protocolVersion}")
        appendLine("elapsedMillis=${ack.elapsedMillis}")
        appendLine("greenAvailable=${ack.greenAvailable}")
        appendLine("irAvailable=${ack.irAvailable}")
        appendLine("peerNodeId=${ack.peerNodeId}")
        appendLine("generation=${ack.generation}")
    }.toByteArray(StandardCharsets.UTF_8)

    fun decodeSessionReady(bytes: ByteArray): SessionReadyAck {
        val fields = parseFields(bytes)
        return SessionReadyAck(
            sessionId = requireField(fields, "sessionId"),
            sessionNonce = fields["sessionNonce"].orEmpty(),
            elapsedMillis = fields["elapsedMillis"]?.toLongOrNull() ?: 0L,
            greenAvailable = fields["greenAvailable"]?.toBooleanStrictOrNull() ?: false,
            irAvailable = fields["irAvailable"]?.toBooleanStrictOrNull() ?: false,
            protocolVersion = fields["protocolVersion"]?.toIntOrNull()
                ?: SessionIdentity.LEGACY_PROTOCOL_VERSION,
            peerNodeId = fields["peerNodeId"].orEmpty(),
            generation = fields["generation"]?.toLongOrNull() ?: 0L
        )
    }

    fun decode(bytes: ByteArray): PpgBatch = if (isV2(bytes)) decodeV2(bytes) else decodeV1(bytes)

    private fun decodeV2(bytes: ByteArray): PpgBatch = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == BATCH_MAGIC) { "Invalid PPG V2 magic" }
        val version = input.readUnsignedShort()
        require(version in BINARY_LEGACY_SCHEMA_VERSION..PpgBatch.CURRENT_SCHEMA_VERSION) {
            "Unsupported PPG binary schema: $version"
        }
        val type = PpgType.entries.getOrNull(input.readUnsignedByte())
            ?: error("Unsupported PPG channel code")
        input.readUnsignedByte() // reserved flags
        val sessionId = input.readString()
        val sessionNonce = input.readString()
        val protocolVersion = if (version >= PpgBatch.IDENTITY_SCHEMA_VERSION) {
            input.readUnsignedShort()
        } else {
            SessionIdentity.LEGACY_PROTOCOL_VERSION
        }
        val peerNodeId = if (version >= PpgBatch.SOURCE_RATE_SCHEMA_VERSION) input.readString() else ""
        val generation = if (version >= PpgBatch.SOURCE_RATE_SCHEMA_VERSION) input.readLong() else 0L
        val batchId = input.readString()
        val sequenceNumber = input.readLong()
        val samplingRateHz = input.readInt()
        val elapsedStartMillis = input.readLong()
        val elapsedEndMillis = input.readLong()
        val (timestamps, values) = input.readPpgValues()
        val accelerometerSamples = if (version >= PpgBatch.ACCELEROMETER_SCHEMA_VERSION) {
            input.readAccelerometerSamples()
        } else {
            emptyList()
        }
        require(input.available() == 0) { "Unexpected trailing PPG V2 bytes" }
        PpgBatch(
            type = type,
            timestampsMillis = timestamps,
            rawValues = values,
            sessionId = sessionId,
            elapsedMillis = elapsedEndMillis,
            schemaVersion = version,
            batchId = batchId,
            sequenceNumber = sequenceNumber,
            samplingRateHz = samplingRateHz,
            elapsedStartMillis = elapsedStartMillis,
            elapsedEndMillis = elapsedEndMillis,
            sessionNonce = sessionNonce,
            protocolVersion = protocolVersion,
            peerNodeId = peerNodeId,
            generation = generation,
            accelerometerSamples = accelerometerSamples
        )
    }

    private fun DataInputStream.readPpgValues(): Pair<LongArray, IntArray> {
        val count = readInt()
        require(count in 0..MAX_SAMPLES_PER_BATCH) { "Invalid PPG sample count: $count" }
        val timestamps = LongArray(count)
        if (count > 0) {
            timestamps[0] = readLong()
            for (index in 1 until count) {
                val delta = readLong()
                require(delta >= 0L) { "PPG timestamps must be monotonic" }
                timestamps[index] = Math.addExact(timestamps[index - 1], delta)
            }
        }
        return timestamps to IntArray(count) { readInt() }
    }

    private fun DataInputStream.readAccelerometerSamples(): List<AccelerometerSample> {
        val count = readInt()
        require(count in 0..PpgBatch.MAX_ACCELEROMETER_SAMPLES) {
            "Invalid accelerometer sample count: $count"
        }
        return List(count) {
            AccelerometerSample(
                sensorTimestampNanos = readLong(),
                elapsedMillis = readLong(),
                x = readFloat(),
                y = readFloat(),
                z = readFloat()
            )
        }
    }

    private fun decodeV1(bytes: ByteArray): PpgBatch {
        val fields = parseFields(bytes)
        val version = fields["schemaVersion"]?.toIntOrNull() ?: PpgBatch.LEGACY_SCHEMA_VERSION
        require(version in PpgBatch.LEGACY_SCHEMA_VERSION..1) {
            "Unsupported legacy PPG schema: $version"
        }
        val type = PpgType.fromWire(fields["channel"] ?: fields["type"] ?: error("Missing channel"))
        val timestamps = parseLongArray(fields["timestamps"].orEmpty())
        val values = parseIntArray(fields["values"].orEmpty())
        val sessionId = requireField(fields, "sessionId")
        val elapsedMillis = requireLong(fields, "elapsedMillis")
        val sequenceNumber = fields["sequenceNumber"]?.toLongOrNull() ?: 0L
        val samplingRateHz = fields["samplingRateHz"]?.toIntOrNull() ?: type.samplingRateHz
        val elapsedStartMillis = fields["elapsedStartMillis"]?.toLongOrNull() ?: elapsedMillis
        val elapsedEndMillis = fields["elapsedEndMillis"]?.toLongOrNull() ?: elapsedMillis
        val batchId = fields["batchId"].orEmpty().ifBlank {
            PpgBatch(type, timestamps, values, sessionId, elapsedMillis).effectiveBatchId
        }
        return PpgBatch(
            type = type,
            timestampsMillis = timestamps,
            rawValues = values,
            sessionId = sessionId,
            elapsedMillis = elapsedMillis,
            schemaVersion = version,
            batchId = batchId,
            sequenceNumber = sequenceNumber,
            samplingRateHz = samplingRateHz,
            elapsedStartMillis = elapsedStartMillis,
            elapsedEndMillis = elapsedEndMillis,
            sessionNonce = fields["sessionNonce"].orEmpty(),
            protocolVersion = fields["protocolVersion"]?.toIntOrNull()
                ?: SessionIdentity.LEGACY_PROTOCOL_VERSION,
            peerNodeId = fields["peerNodeId"].orEmpty(),
            generation = fields["generation"]?.toLongOrNull() ?: 0L
        )
    }

    private fun isV2(bytes: ByteArray): Boolean = bytes.size >= Int.SIZE_BYTES &&
        bytes[0] == 0x50.toByte() &&
        bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x47.toByte() &&
        bytes[3] == 0x32.toByte()

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_STRING_BYTES) { "PPG string field exceeds $MAX_STRING_BYTES bytes" }
        writeShort(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readString(): String {
        val size = readUnsignedShort()
        require(size <= MAX_STRING_BYTES && size <= available()) { "Invalid PPG string length: $size" }
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun parseFields(bytes: ByteArray): Map<String, String> = String(bytes, StandardCharsets.UTF_8)
        .lineSequence()
        .filter { it.contains('=') }
        .associate { line ->
            val idx = line.indexOf('=')
            line.substring(0, idx) to line.substring(idx + 1)
        }

    private fun requireField(fields: Map<String, String>, name: String): String = fields[name].orEmpty().ifBlank { error("Missing $name") }

    private fun requireLong(fields: Map<String, String>, name: String): Long =
        requireNotNull(fields[name]?.toLongOrNull()) { "Missing or invalid $name" }

    private fun parseLongArray(value: String): LongArray =
        if (value.isBlank()) LongArray(0) else value.split(",").map { it.toLong() }.toLongArray()

    private fun parseIntArray(value: String): IntArray =
        if (value.isBlank()) IntArray(0) else value.split(",").map { it.toInt() }.toIntArray()

    private const val BATCH_MAGIC = 0x50504732 // PPG2
    private const val MAX_STRING_BYTES = 1_024
    private const val MAX_SAMPLES_PER_BATCH = 1_024
    private const val BINARY_LEGACY_SCHEMA_VERSION = 2
}
