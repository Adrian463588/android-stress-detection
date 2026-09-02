package com.stressdetector.shared

data class PpgBatch(
    val type: PpgType,
    val timestampsMillis: LongArray,
    val rawValues: IntArray,
    val sessionId: String,
    val elapsedMillis: Long = 0L,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val batchId: String = "",
    val sequenceNumber: Long = 0L,
    /** Wire rate. V3+ is always the truthful Samsung source rate. */
    val samplingRateHz: Int = if (schemaVersion >= SOURCE_RATE_SCHEMA_VERSION) type.sourceRateHz else type.samplingRateHz,
    val elapsedStartMillis: Long = elapsedMillis,
    val elapsedEndMillis: Long = elapsedMillis,
    /** Opaque nonce created by phone when it owns a session. Empty only for V1 compatibility. */
    val sessionNonce: String = "",
    /** Canonical phone-Wear protocol revision. Generation is not part of session identity. */
    val protocolVersion: Int = SessionIdentity.CURRENT_PROTOCOL_VERSION,
    /** Phone-local node that owns the session. */
    val peerNodeId: String = "",
    val generation: Long = 0L
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(elapsedMillis >= 0L) { "elapsedMillis must be non-negative" }
        require(schemaVersion in LEGACY_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION) {
            "Unsupported schemaVersion: $schemaVersion"
        }
        require(sequenceNumber >= 0L) { "sequenceNumber must be non-negative" }
        val expectedRate = if (schemaVersion >= SOURCE_RATE_SCHEMA_VERSION) type.sourceRateHz else type.samplingRateHz
        require(samplingRateHz == expectedRate) {
            "samplingRateHz $samplingRateHz does not match ${type.name} source contract ($expectedRate)"
        }
        require(generation >= 0L) { "generation must be non-negative" }
        require(protocolVersion >= SessionIdentity.LEGACY_PROTOCOL_VERSION) {
            "protocolVersion must not be negative"
        }
        require(protocolVersion <= UShort.MAX_VALUE.toInt()) {
            "protocolVersion exceeds wire range"
        }
        require(elapsedStartMillis >= 0L) { "elapsedStartMillis must be non-negative" }
        require(elapsedEndMillis >= elapsedStartMillis) {
            "elapsedEndMillis must not be before elapsedStartMillis"
        }
        require(timestampsMillis.size == rawValues.size) {
            "timestampsMillis and rawValues must have the same size"
        }
        require(rawValues.isNotEmpty()) { "PpgBatch must not be empty" }
        require(
            (1 until timestampsMillis.size).all { index ->
                timestampsMillis[index] >= timestampsMillis[index - 1]
            }
        ) {
            "Sensor timestamps must be monotonic"
        }
    }

    val size: Int get() = rawValues.size
    val channel: PpgType get() = type
    val effectiveBatchId: String
        get() = batchId.ifBlank { legacyBatchId() }

    val hasSessionNonce: Boolean get() = sessionNonce.isNotBlank()
    val sourceRateHz: Int get() = samplingRateHz
    val sessionIdentity: SessionIdentity
        get() = SessionIdentity(sessionId, sessionNonce, protocolVersion)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PpgBatch) return false
        return type == other.type &&
            timestampsMillis.contentEquals(other.timestampsMillis) &&
            rawValues.contentEquals(other.rawValues) &&
            sessionId == other.sessionId &&
            elapsedMillis == other.elapsedMillis &&
            schemaVersion == other.schemaVersion &&
            batchId == other.batchId &&
            sequenceNumber == other.sequenceNumber &&
            samplingRateHz == other.samplingRateHz &&
            elapsedStartMillis == other.elapsedStartMillis &&
            elapsedEndMillis == other.elapsedEndMillis &&
            sessionNonce == other.sessionNonce &&
            protocolVersion == other.protocolVersion &&
            peerNodeId == other.peerNodeId &&
            generation == other.generation
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + timestampsMillis.contentHashCode()
        result = 31 * result + rawValues.contentHashCode()
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + elapsedMillis.hashCode()
        result = 31 * result + schemaVersion
        result = 31 * result + batchId.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + samplingRateHz
        result = 31 * result + elapsedStartMillis.hashCode()
        result = 31 * result + elapsedEndMillis.hashCode()
        result = 31 * result + sessionNonce.hashCode()
        result = 31 * result + protocolVersion
        result = 31 * result + peerNodeId.hashCode()
        result = 31 * result + generation.hashCode()
        return result
    }

    private fun legacyBatchId(): String = buildString {
        append("legacy|")
        append(sessionId)
        append('|').append(type.name)
        append('|').append(sequenceNumber)
        append('|').append(elapsedStartMillis)
        append('|').append(elapsedEndMillis)
        append('|').append(timestampsMillis.contentHashCode())
        append('|').append(rawValues.contentHashCode())
    }

    companion object {
        const val LEGACY_SCHEMA_VERSION: Int = 0

        /** V1 is line transport, V2 binary legacy, V3 adds source rate, V4 adds protocol identity. */
        const val CURRENT_SCHEMA_VERSION: Int = 4
        const val SOURCE_RATE_SCHEMA_VERSION: Int = 3
        const val IDENTITY_SCHEMA_VERSION: Int = 4

        fun batchIdFor(sessionId: String, type: PpgType, sequenceNumber: Long): String = "$sessionId|${type.name}|$sequenceNumber"

        fun batchIdFor(identity: SessionIdentity, type: PpgType, sequenceNumber: Long): String =
            "${identity.storageKey()}|${type.name}|$sequenceNumber"
    }
}

data class PpgBatchAck(
    val sessionId: String,
    val batchId: String,
    val sequenceNumber: Long,
    val schemaVersion: Int = PpgBatch.CURRENT_SCHEMA_VERSION,
    val sessionNonce: String = "",
    val protocolVersion: Int = SessionIdentity.CURRENT_PROTOCOL_VERSION,
    val peerNodeId: String = "",
    val generation: Long = 0L
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(batchId.isNotBlank()) { "batchId must not be blank" }
        require(sequenceNumber >= 0L) { "sequenceNumber must be non-negative" }
        require(schemaVersion in PpgBatch.LEGACY_SCHEMA_VERSION..PpgBatch.CURRENT_SCHEMA_VERSION) {
            "Unsupported schemaVersion: $schemaVersion"
        }
        require(generation >= 0L) { "generation must be non-negative" }
        require(protocolVersion >= SessionIdentity.LEGACY_PROTOCOL_VERSION) {
            "protocolVersion must not be negative"
        }
    }

    val sessionIdentity: SessionIdentity
        get() = SessionIdentity(sessionId, sessionNonce, protocolVersion)
}

data class PpgFlushAck(
    val sessionId: String,
    val flushed: Boolean,
    val completed: Boolean = false,
    val elapsedMillis: Long = 0L,
    val schemaVersion: Int = PpgBatch.CURRENT_SCHEMA_VERSION
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(schemaVersion in PpgBatch.LEGACY_SCHEMA_VERSION..PpgBatch.CURRENT_SCHEMA_VERSION) {
            "Unsupported schemaVersion: $schemaVersion"
        }
        require(elapsedMillis >= 0L) { "elapsedMillis must be non-negative" }
    }
}

/**
 * Watch terminal receipt. It reports the last persisted sequence per production
 * channel after the Wear outbox has drained; phone replies with [PpgTerminalAck].
 */
data class PpgTerminalReceipt(
    val sessionId: String,
    val sessionNonce: String,
    val expectedGreenSequence: Long,
    val expectedIrSequence: Long,
    val elapsedMillis: Long,
    val completed: Boolean,
    /** Diagnostic Red sequence; it never gates Green/IR inference readiness. */
    val expectedRedSequence: Long = 0L,
    val schemaVersion: Int = PpgBatch.CURRENT_SCHEMA_VERSION,
    val protocolVersion: Int = SessionIdentity.CURRENT_PROTOCOL_VERSION,
    val peerNodeId: String = "",
    val generation: Long = 0L
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(expectedGreenSequence >= 0L) { "expectedGreenSequence must be non-negative" }
        require(expectedIrSequence >= 0L) { "expectedIrSequence must be non-negative" }
        require(expectedRedSequence >= 0L) { "expectedRedSequence must be non-negative" }
        require(elapsedMillis >= 0L) { "elapsedMillis must be non-negative" }
        require(generation >= 0L) { "generation must be non-negative" }
        require(protocolVersion >= SessionIdentity.LEGACY_PROTOCOL_VERSION) {
            "protocolVersion must not be negative"
        }
    }

    val sessionIdentity: SessionIdentity
        get() = SessionIdentity(sessionId, sessionNonce, protocolVersion)
}

/** Phone acknowledgement that both production streams are durably accepted. */
data class PpgTerminalAck(
    val sessionId: String,
    val sessionNonce: String,
    val acceptedGreenSequence: Long,
    val acceptedIrSequence: Long,
    val complete: Boolean,
    /** Diagnostic Red sequence accepted by phone; not part of the completion gate. */
    val acceptedRedSequence: Long = 0L,
    val reason: String = "",
    val schemaVersion: Int = PpgBatch.CURRENT_SCHEMA_VERSION,
    val protocolVersion: Int = SessionIdentity.CURRENT_PROTOCOL_VERSION,
    val peerNodeId: String = "",
    val generation: Long = 0L
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(acceptedGreenSequence >= 0L) { "acceptedGreenSequence must be non-negative" }
        require(acceptedIrSequence >= 0L) { "acceptedIrSequence must be non-negative" }
        require(acceptedRedSequence >= 0L) { "acceptedRedSequence must be non-negative" }
        require(generation >= 0L) { "generation must be non-negative" }
        require(protocolVersion >= SessionIdentity.LEGACY_PROTOCOL_VERSION) {
            "protocolVersion must not be negative"
        }
    }

    val sessionIdentity: SessionIdentity
        get() = SessionIdentity(sessionId, sessionNonce, protocolVersion)
}

/** Minimal compatible Watch readiness acknowledgement for a phone-owned session. */
data class SessionReadyAck(
    val sessionId: String,
    val sessionNonce: String,
    val elapsedMillis: Long = 0L,
    val greenAvailable: Boolean = false,
    val irAvailable: Boolean = false,
    val protocolVersion: Int = SessionIdentity.CURRENT_PROTOCOL_VERSION,
    val peerNodeId: String = "",
    val generation: Long = 0L
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(elapsedMillis >= 0L) { "elapsedMillis must be non-negative" }
        require(greenAvailable && irAvailable) { "Green and IR must both be ready" }
        require(generation >= 0L) { "generation must be non-negative" }
        require(protocolVersion >= SessionIdentity.LEGACY_PROTOCOL_VERSION) {
            "protocolVersion must not be negative"
        }
    }

    val sessionIdentity: SessionIdentity
        get() = SessionIdentity(sessionId, sessionNonce, protocolVersion)
}
