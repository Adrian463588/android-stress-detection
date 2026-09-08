package com.stressdetector.wear.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.Wearable
import com.stressdetector.shared.PpgBatch
import com.stressdetector.shared.PpgBatchCodec
import com.stressdetector.shared.PpgTerminalReceipt
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SessionIdentity
import java.util.EnumMap

data class PpgExpectedSequences(val green: Long, val ir: Long, val red: Long)

/**
 * Sends immutable PPG callback batches directly to the connected phone.
 *
 * The active Wear path intentionally follows the proven PDSatu lifecycle:
 * sensor callbacks are batched in memory and sent with MessageClient. A
 * failed send is telemetry, not a reason to stop Samsung tracking. Historical
 * outbox/ACK files are left untouched but are not part of this active route.
 */
class PpgBatchSender(context: Context) {
    private val appContext = context.applicationContext
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val sequences = EnumMap<PpgType, Long>(PpgType::class.java)
    private var activeIdentity: SessionIdentity? = null
    private var activePhoneNodeId: String? = null
    private var activeGeneration: Long = 0L
    private var inFlightSends: Int = 0

    fun beginSession(identity: SessionIdentity, phoneNodeId: String?, generation: Long) {
        require(identity.isCanonical) { "Wear PPG requires a canonical SessionIdentity" }
        synchronized(lock) {
            activeIdentity = identity
            activePhoneNodeId = phoneNodeId?.takeIf(String::isNotBlank)
            activeGeneration = generation
            sequences.clear()
            PpgType.acquisitionChannels.forEach { sequences[it] = 0L }
        }
    }

    fun send(
        type: PpgType,
        timestamps: LongArray,
        values: IntArray,
        sessionId: String,
        elapsedMillis: Long,
        elapsedStartMillis: Long = elapsedMillis,
        elapsedEndMillis: Long = elapsedMillis,
        callbackReceivedAtElapsedRealtimeMillis: Long = monotonicNowMillis()
    ) {
        if (values.isEmpty()) return
        require(type.isAcquisitionChannel) { "Only acquired PPG channels may be transported" }
        require(timestamps.size == values.size) { "timestamps and values must have the same size" }

        val dispatch = synchronized(lock) {
            val identity = requireNotNull(activeIdentity) { "PPG send has no active session identity" }
            require(identity.sessionId == sessionId) { "PPG send does not own active session" }
            val nodeId = activePhoneNodeId
            val sequence = (sequences[type] ?: 0L) + 1L
            sequences[type] = sequence
            val batch = PpgBatch(
                type = type,
                timestampsMillis = timestamps.copyOf(),
                rawValues = values.copyOf(),
                sessionId = sessionId,
                elapsedMillis = elapsedMillis,
                schemaVersion = PpgBatch.CURRENT_SCHEMA_VERSION,
                batchId = PpgBatch.batchIdFor(identity, type, sequence),
                sequenceNumber = sequence,
                samplingRateHz = type.sourceRateHz,
                elapsedStartMillis = elapsedStartMillis,
                elapsedEndMillis = elapsedEndMillis,
                sessionNonce = identity.nonce,
                protocolVersion = identity.protocolVersion,
                peerNodeId = nodeId.orEmpty(),
                generation = activeGeneration
            )
            DirectDispatch(
                nodeId = nodeId,
                batch = batch,
                payload = PpgBatchCodec.encode(batch),
                createdAtElapsedRealtimeMillis = callbackReceivedAtElapsedRealtimeMillis
            )
        }

        WearTrackingState.recordQueued(dispatch.batch.type, dispatch.batch.size)
        val nodeId = dispatch.nodeId
        if (nodeId.isNullOrBlank()) {
            WearTrackingState.recordTransportGap(dispatch.batch.type, "phone_node_unavailable")
            WearTrackingState.setPhoneReachable(false, "Phone node unavailable; direct PPG batch dropped")
            WearTrackingState.message("PPG ${type.assetSuffix} transport gap")
            return
        }

        synchronized(lock) { inFlightSends += 1 }

        runCatching {
            Wearable.getMessageClient(appContext)
                .sendMessage(nodeId, dispatch.batch.type.messagePath, dispatch.payload)
                .addOnSuccessListener {
                    WearTrackingState.recordBatchSent(
                        dispatch.batch.type,
                        android.os.SystemClock.elapsedRealtime() - dispatch.createdAtElapsedRealtimeMillis
                    )
                    WearTrackingState.addSent(
                        dispatch.batch.type,
                        dispatch.batch.size,
                        dispatch.batch.sequenceNumber
                    )
                    WearTrackingState.setPhoneReachable(true, "Direct PPG batch sent")
                    markSendComplete()
                }
                .addOnFailureListener { error ->
                    WearTrackingState.recordTransportGap(
                        dispatch.batch.type,
                        error.message ?: "message_send_failed"
                    )
                    // A single MessageClient failure is a transport gap, not
                    // proof that the phone node disappeared. Keep the last
                    // reachability evidence so the Wear UI does not flicker
                    // to PHONE OFFLINE while the next batch can still arrive.
                    WearTrackingState.message("PPG transport gap: ${dispatch.batch.type.assetSuffix}")
                    markSendComplete()
                }
        }.onFailure { error ->
            WearTrackingState.recordTransportGap(
                dispatch.batch.type,
                error.message ?: "message_dispatch_failed"
            )
            WearTrackingState.message("PPG transport gap: ${dispatch.batch.type.assetSuffix}")
            markSendComplete()
        }
    }

    fun expectedSequences(sessionId: String): PpgExpectedSequences = synchronized(lock) {
        check(activeIdentity?.sessionId == sessionId) { "Expected sequences does not own active session" }
        PpgExpectedSequences(
            green = sequences[PpgType.GREEN] ?: 0L,
            ir = sequences[PpgType.IR] ?: 0L,
            red = sequences[PpgType.RED] ?: 0L
        )
    }

    /** Direct transport has no durable queue, but all in-flight MessageClient callbacks must settle. */
    fun awaitFlush(timeoutMillis: Long = 2_000L, onComplete: (Boolean) -> Unit) {
        val deadlineMillis = android.os.SystemClock.elapsedRealtime() +
            timeoutMillis.coerceIn(0L, MAX_FLUSH_TIMEOUT_MILLIS)
        fun poll() {
            val drained = synchronized(lock) { inFlightSends == 0 }
            if (drained || android.os.SystemClock.elapsedRealtime() >= deadlineMillis) {
                onComplete(drained)
            } else {
                callbackHandler.postDelayed(::poll, FLUSH_POLL_MILLIS)
            }
        }
        callbackHandler.post(::poll)
    }

    /** Kept as a source-compatible no-op for legacy callers; no terminal ACK is required. */
    fun awaitTerminalAcknowledgement(
        receipt: PpgTerminalReceipt,
        nodeId: String?,
        timeoutMillis: Long = 500L,
        onComplete: (Boolean) -> Unit
    ) {
        awaitFlush(timeoutMillis, onComplete)
    }

    private data class DirectDispatch(
        val nodeId: String?,
        val batch: PpgBatch,
        val payload: ByteArray,
        val createdAtElapsedRealtimeMillis: Long
    )

    private fun markSendComplete() {
        synchronized(lock) { inFlightSends = (inFlightSends - 1).coerceAtLeast(0) }
    }

    private fun monotonicNowMillis(): Long = System.nanoTime() / 1_000_000L

    companion object {
        private const val FLUSH_POLL_MILLIS = 25L
        private const val MAX_FLUSH_TIMEOUT_MILLIS = 5_000L

        /** Legacy ACK messages are ignored because the direct path has no ACK contract. */
        fun acknowledge(context: Context, sourceNodeId: String, payload: ByteArray): Boolean =
            runCatching { PpgBatchCodec.decodeAck(payload) }.isSuccess

        /** Legacy terminal ACK messages are ignored for the same reason. */
        fun acknowledgeTerminal(context: Context, sourceNodeId: String, payload: ByteArray): Boolean =
            runCatching { PpgBatchCodec.decodeTerminalAck(payload) }.isSuccess
    }
}
