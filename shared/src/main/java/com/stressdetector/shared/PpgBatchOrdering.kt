package com.stressdetector.shared

/** Result of accepting one transport batch into per-channel sequence order. */
data class PpgBatchDelivery(
    val disposition: PpgBatchDisposition,
    val acknowledgedBatches: List<PpgBatch> = emptyList(),
    val missingSequenceCount: Long = 0L,
    val reason: String? = null
)

enum class PpgBatchDisposition {
    APPLIED,
    DUPLICATE,
    BUFFERED_OUT_OF_ORDER,
    REJECTED
}

/**
 * Applies batches in sequence order independently for every session/channel.
 * Out-of-order batches stay buffered and are never acknowledged until applied.
 */
class PpgBatchSequenceAudit {
    private data class StreamKey(val sessionId: String, val sessionNonce: String, val type: PpgType)

    private class StreamState(initialExpected: Long) {
        // Sender sequence numbers are persisted per session/channel and begin
        // at one. Never infer a new stream's first expected value from its
        // first arrival: receiving sequence two before one must stay pending.
        var nextExpected: Long = initialExpected
        val pending = sortedMapOf<Long, PpgBatch>()
        val appliedById = mutableMapOf<String, PpgBatch>()
    }

    private val streams = mutableMapOf<StreamKey, StreamState>()

    fun accept(batch: PpgBatch): PpgBatchDelivery {
        if (batch.schemaVersion >= PpgBatch.CURRENT_SCHEMA_VERSION && batch.sequenceNumber < FIRST_SEQUENCE_NUMBER) {
            return PpgBatchDelivery(
                disposition = PpgBatchDisposition.REJECTED,
                reason = "invalid_v2_sequence:${batch.sequenceNumber}"
            )
        }
        val stream = streams.getOrPut(StreamKey(batch.sessionId, batch.sessionNonce, batch.type)) {
            StreamState(firstExpectedSequence(batch))
        }
        val batchId = batch.effectiveBatchId
        stream.appliedById[batchId]?.let { applied ->
            return PpgBatchDelivery(
                disposition = PpgBatchDisposition.DUPLICATE,
                acknowledgedBatches = listOf(applied),
                reason = "duplicate_batch"
            )
        }

        val existing = stream.pending[batch.sequenceNumber]
        if (existing != null) {
            return PpgBatchDelivery(
                disposition = PpgBatchDisposition.BUFFERED_OUT_OF_ORDER,
                reason = "duplicate_pending_batch"
            )
        }

        val expected = stream.nextExpected
        if (batch.sequenceNumber < expected) {
            return PpgBatchDelivery(
                disposition = PpgBatchDisposition.REJECTED,
                reason = "sequence_regression:${batch.sequenceNumber}<$expected"
            )
        }
        if (batch.sequenceNumber > expected) {
            stream.pending[batch.sequenceNumber] = batch
            return PpgBatchDelivery(
                disposition = PpgBatchDisposition.BUFFERED_OUT_OF_ORDER,
                missingSequenceCount = batch.sequenceNumber - expected,
                reason = "missing_sequence:$expected-${batch.sequenceNumber - 1}"
            )
        }

        val applied = mutableListOf<PpgBatch>()
        apply(batch, stream, applied)
        while (true) {
            val next = stream.nextExpected
            val pending = stream.pending.remove(next) ?: break
            apply(pending, stream, applied)
        }
        return PpgBatchDelivery(
            disposition = PpgBatchDisposition.APPLIED,
            acknowledgedBatches = applied
        )
    }

    /**
     * Direct MessageClient transport cannot wait indefinitely for a missing
     * batch. Apply each unseen batch immediately and report the sequence hole
     * as telemetry; never manufacture the missing samples or block all later
     * real samples behind an in-memory pending queue.
     */
    fun acceptDirect(batch: PpgBatch): PpgBatchDelivery {
        if (batch.schemaVersion >= PpgBatch.CURRENT_SCHEMA_VERSION &&
            batch.sequenceNumber < FIRST_SEQUENCE_NUMBER
        ) {
            return PpgBatchDelivery(
                disposition = PpgBatchDisposition.REJECTED,
                reason = "invalid_v2_sequence:${batch.sequenceNumber}"
            )
        }
        val stream = streams.getOrPut(StreamKey(batch.sessionId, batch.sessionNonce, batch.type)) {
            StreamState(firstExpectedSequence(batch))
        }
        val batchId = batch.effectiveBatchId
        stream.appliedById[batchId]?.let { applied ->
            return PpgBatchDelivery(
                disposition = PpgBatchDisposition.DUPLICATE,
                acknowledgedBatches = listOf(applied),
                reason = "duplicate_batch"
            )
        }
        stream.appliedById.values.firstOrNull { it.sequenceNumber == batch.sequenceNumber }?.let { applied ->
            return PpgBatchDelivery(
                disposition = PpgBatchDisposition.DUPLICATE,
                acknowledgedBatches = listOf(applied),
                reason = "duplicate_sequence:${batch.sequenceNumber}"
            )
        }
        if (batch.sequenceNumber < stream.nextExpected) {
            return PpgBatchDelivery(
                disposition = PpgBatchDisposition.REJECTED,
                reason = "sequence_regression:${batch.sequenceNumber}<${stream.nextExpected}"
            )
        }
        val missing = (batch.sequenceNumber - stream.nextExpected).coerceAtLeast(0L)
        stream.appliedById[batchId] = batch
        if (batch.sequenceNumber >= stream.nextExpected) {
            stream.nextExpected = batch.sequenceNumber + 1L
        }
        return PpgBatchDelivery(
            disposition = PpgBatchDisposition.APPLIED,
            acknowledgedBatches = listOf(batch),
            missingSequenceCount = missing,
            reason = if (missing > 0L) "transport_gap_before_sequence:${batch.sequenceNumber}" else null
        )
    }

    fun resetSession(sessionId: String) {
        streams.keys.removeIf { it.sessionId == sessionId }
    }

    private fun apply(batch: PpgBatch, stream: StreamState, applied: MutableList<PpgBatch>) {
        stream.appliedById[batch.effectiveBatchId] = batch
        applied += batch
        stream.nextExpected = batch.sequenceNumber + 1L
    }

    private companion object {
        const val FIRST_SEQUENCE_NUMBER = 1L

        /** V1 peers started at zero; V2 persistently starts at one. */
        fun firstExpectedSequence(batch: PpgBatch): Long =
            if (batch.schemaVersion >= PpgBatch.CURRENT_SCHEMA_VERSION) FIRST_SEQUENCE_NUMBER else 0L
    }
}
