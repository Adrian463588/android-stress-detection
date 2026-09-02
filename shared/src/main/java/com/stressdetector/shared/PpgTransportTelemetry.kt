package com.stressdetector.shared

/**
 * Shared timing limits for the direct Wear MessageClient transport.
 *
 * These values bound delivery latency only; they never alter sample values,
 * timestamps, or the canonical model sampling rate.
 */
object PpgTransportTiming {
    const val GREEN_MAX_CALLBACK_TO_SEND_MILLIS: Long = 1_000L
    const val MAX_BATCH_AGE_MILLIS: Long = 1_000L
}

/**
 * Transport evidence for one acquired wavelength.  Values are observational:
 * no field authorizes padding, resampling, or treating a rejected sample as
 * physiology.
 */
data class PpgTransportDiagnostics(
    val acceptedSampleCount: Long = 0L,
    val rejectedSampleCount: Long = 0L,
    val transportGapCount: Long = 0L,
    val maxGapMillis: Long = 0L,
    val sequenceLossCount: Long = 0L,
    val duplicateBatchCount: Long = 0L,
    val outOfOrderBatchCount: Long = 0L,
    val actualCadenceHz: Float? = null,
    val lastStatusCode: Int? = null,
    val invalidStatusCount: Long = 0L
)

/** Immutable per-channel evidence emitted by the Wear transport layer. */
data class PpgChannelTelemetry(
    val callbackCount: Long = 0L,
    val callbackSampleCount: Long = 0L,
    val acceptedSampleCount: Long = 0L,
    val rejectedSampleCount: Long = 0L,
    val batchCount: Long = 0L,
    val transportGapCount: Long = 0L,
    val lastCallbackElapsedRealtimeMillis: Long? = null,
    val lastBatchElapsedRealtimeMillis: Long? = null,
    val lastBatchLatencyMillis: Long? = null,
    /** Last Samsung per-point status observed at the Watch callback boundary. */
    val lastStatusCode: Int? = null,
    /** Number of callback points rejected because Samsung reported a non-normal status. */
    val invalidStatusCount: Long = 0L,
    val lastError: String? = null
) {
    fun asDiagnostics(
        sequenceLossCount: Long = 0L,
        duplicateBatchCount: Long = 0L,
        outOfOrderBatchCount: Long = 0L,
        maxGapMillis: Long = 0L,
        actualCadenceHz: Float? = null
    ): PpgTransportDiagnostics = PpgTransportDiagnostics(
        acceptedSampleCount = acceptedSampleCount,
        rejectedSampleCount = rejectedSampleCount,
        transportGapCount = transportGapCount,
        maxGapMillis = maxGapMillis,
        sequenceLossCount = sequenceLossCount,
        duplicateBatchCount = duplicateBatchCount,
        outOfOrderBatchCount = outOfOrderBatchCount,
        actualCadenceHz = actualCadenceHz,
        lastStatusCode = lastStatusCode,
        invalidStatusCount = invalidStatusCount
    )
}
