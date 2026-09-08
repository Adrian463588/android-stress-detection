package com.stressdetector.mobile.data

import com.stressdetector.shared.PpgBatch
import com.stressdetector.shared.PpgSessionLease
import com.stressdetector.shared.PpgSessionLeaseAcquireResult
import com.stressdetector.shared.PpgSessionLeaseReleaseResult
import com.stressdetector.shared.PpgSessionLeaseSnapshotResult
import com.stressdetector.shared.PpgTerminalReceipt
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SessionIdentity
import com.stressdetector.shared.SessionProtocolRejection
import com.stressdetector.shared.SessionReadyAck
import com.stressdetector.shared.WearSessionMode
import kotlinx.coroutines.flow.StateFlow

interface IPpgRepository {
    val state: StateFlow<PpgStreamState>
    val chartFrames: StateFlow<PpgChartFrame>

    /**
     * Acquires the single Home/Verbatim PPG owner slot. A different lease is
     * rejected without mutating the active session buffers.
     */
    fun acquire(lease: PpgSessionLease, sourceNodeId: String? = null, peerNodeId: String = ""): PpgSessionLeaseAcquireResult

    /** Returns actual PPG data only when [lease] still owns the slot. */
    fun snapshot(lease: PpgSessionLease): PpgSessionLeaseSnapshotResult<PpgSessionLeaseSnapshot>

    /**
     * Releases only the matching lease and returns its final actual snapshot.
     * Buffer clearing is intentionally deferred to the next successful lease.
     */
    fun stopAndRelease(lease: PpgSessionLease): PpgSessionLeaseReleaseResult<PpgSessionLeaseSnapshot>

    fun startSession(
        sessionId: String,
        sessionNonce: String = "",
        sourceNodeId: String? = null,
        peerNodeId: String = "",
        generation: Long = 0L,
        mode: WearSessionMode? = null
    )
    fun append(batch: PpgBatch, sourceNodeId: String? = null): PpgBatchAppendResult
    fun acceptSessionReady(ack: SessionReadyAck, sourceNodeId: String? = null): Boolean

    /**
     * Stops only the matching phone-owned session before sensor data is
     * accepted. The Watch sent this after its protocol handshake failed.
     */
    fun rejectProtocol(rejection: SessionProtocolRejection, sourceNodeId: String? = null): Boolean
    fun markSessionElapsed(sessionId: String, elapsedMillis: Long, completionReason: String? = null)
    fun markFlushAcknowledged(sessionId: String, flushed: Boolean, completed: Boolean = false, elapsedMillis: Long = 0L)
    fun acceptTerminalReceipt(receipt: PpgTerminalReceipt, sourceNodeId: String? = null): PpgTerminalReceiptResult
    fun recordError(message: String)
    fun recordTransportIncomplete(message: String)
    fun greenWindow(): WindowSnapshot
    fun irWindow(): WindowSnapshot
    fun redWindow(): WindowSnapshot
    fun fullGreenWindow(): WindowSnapshot
    fun fullIrWindow(): WindowSnapshot
    fun fullRedWindow(): WindowSnapshot
    fun greenChartData(): ChartData
    fun irChartData(): ChartData
    fun redChartData(): ChartData
}

data class PpgBatchAppendResult(
    val disposition: com.stressdetector.shared.PpgBatchDisposition,
    val acknowledgedBatches: List<PpgBatch> = emptyList(),
    val missingSequenceCount: Long = 0L,
    val reason: String? = null
)

data class PpgTerminalReceiptResult(
    val acceptedGreenSequence: Long,
    val acceptedIrSequence: Long,
    val acceptedRedSequence: Long = 0L,
    val complete: Boolean,
    val reason: String = ""
)

data class PpgChartFrame(
    /** Session ownership is part of the frame, not inferred by the renderer. */
    val sessionId: String? = null,
    /** Canonical session identity fields used by Home; generation is wire metadata only. */
    val sessionNonce: String = "",
    val protocolVersion: Int = SessionIdentity.CURRENT_PROTOCOL_VERSION,
    val generation: Long = 0L,
    /** Immutable, elapsed-time snapshots used by the Home chart projector. */
    val greenSnapshot: WindowSnapshot = WindowSnapshot(PpgType.GREEN, LongArray(0), IntArray(0)),
    val irSnapshot: WindowSnapshot = WindowSnapshot(PpgType.IR, LongArray(0), IntArray(0)),
    val redSnapshot: WindowSnapshot = WindowSnapshot(PpgType.RED, LongArray(0), IntArray(0)),
    /** Legacy live-monitor projection; Home does not consume these fields. */
    val green: ChartData = ChartData.EMPTY,
    val ir: ChartData = ChartData.EMPTY,
    val red: ChartData = ChartData.EMPTY,
    val capturedAtMillis: Long = 0L,
    val axisStartMillis: Long = 0L,
    val axisEndMillis: Long = 0L
)
