package com.stressdetector.shared

/** Shared transport timing contract for phone and Wear PPG outboxes. */
object PpgBatchPolicy {
    /**
     * Upper bound for how long a live sample may wait for the next transport
     * batch. This is independent from the canonical model sampling rate:
     * Health Services may deliver fewer callbacks than the model rate.
     */
    const val MAX_BATCH_AGE_MILLIS = PpgTransportTiming.MAX_BATCH_AGE_MILLIS

    /** At most 300 seconds × two one-second batches, with bounded recovery margin. */
    const val MAX_BATCHES_PER_SESSION = 720

    const val ACK_TIMEOUT_MILLIS = 5_000L

    /** Terminal receipt/ACK uses the same 25-second phone drain boundary. */
    const val TERMINAL_ACK_TIMEOUT_MILLIS = 25_000L
    const val READINESS_TIMEOUT_MILLIS = 15_000L

    /** Maximum time the Wear outbox waits for current-session delivery. */
    const val FLUSH_TIMEOUT_MILLIS = 20_000L

    /** Phone-side margin for Wear flush, ACK delivery, and repository update. */
    const val PHONE_DRAIN_TIMEOUT_MILLIS = 25_000L

    /**
     * Pure flush policy shared by the Wear repository and its tests. Green
     * callbacks are forwarded immediately because their source tracker can
     * deliver a long timestamp span in one callback.
     */
    fun shouldFlush(type: PpgType, sampleCount: Int, callbackAgeMillis: Long): Boolean {
        if (sampleCount <= 0) return false
        if (type == PpgType.GREEN) return true
        return sampleCount >= type.sourceSamplesPerBatch ||
            callbackAgeMillis.coerceAtLeast(0L) >= MAX_BATCH_AGE_MILLIS
    }

    /**
     * Timestamp-aware variant used by the Samsung callback consumer.
     *
     * A callback can contain a long sensor-time span even though all samples
     * arrive at one wall-clock instant. That span is a flush boundary; it
     * must not be held behind the nominal batch size. The three-argument
     * overload remains source-compatible for legacy callers/tests.
     */
    fun shouldFlush(type: PpgType, sampleCount: Int, callbackAgeMillis: Long, sampleSpanMillis: Long): Boolean {
        if (sampleCount <= 0) return false
        val ageReached = callbackAgeMillis.coerceAtLeast(0L) >= MAX_BATCH_AGE_MILLIS
        val spanReached = sampleSpanMillis.coerceAtLeast(0L) >= MAX_BATCH_AGE_MILLIS
        return when {
            // Green can arrive in a sparse callback cadence. Forward its
            // immutable callback immediately instead of waiting for a nominal
            // batch span; this keeps callback-to-send latency below one
            // second whenever the SDK supplies data, without resampling it.
            type == PpgType.GREEN -> true
            sampleCount >= type.sourceSamplesPerBatch -> true
            ageReached || spanReached -> true
            else -> false
        }
    }

    /** Generation is telemetry only; current-session matching uses [SessionIdentity]. */
    fun belongsToSession(batch: PpgBatch, identity: SessionIdentity): Boolean = batch.sessionIdentity.matches(identity)

    /** Terminal ACK evaluation is pure and idempotent for the same receipt/ACK pair. */
    fun confirmsTerminalReceipt(receipt: PpgTerminalReceipt, ack: PpgTerminalAck): Boolean =
        ack.sessionIdentity.matches(receipt.sessionIdentity) &&
            ack.acceptedGreenSequence >= receipt.expectedGreenSequence &&
            ack.acceptedIrSequence >= receipt.expectedIrSequence &&
            ack.complete
}
