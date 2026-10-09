package com.stressdetector.mobile.data

import android.os.SystemClock
import com.stressdetector.shared.AccelerometerSample
import com.stressdetector.shared.PpgBatch
import com.stressdetector.shared.PpgBatchDisposition
import com.stressdetector.shared.PpgBatchSequenceAudit
import com.stressdetector.shared.PpgSessionLease
import com.stressdetector.shared.PpgSessionLeaseAcquireResult
import com.stressdetector.shared.PpgSessionLeaseReleaseResult
import com.stressdetector.shared.PpgSessionLeaseSnapshotResult
import com.stressdetector.shared.PpgTerminalReceipt
import com.stressdetector.shared.PpgTransportDiagnostics
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SessionConfig
import com.stressdetector.shared.SessionIdentity
import com.stressdetector.shared.SessionProtocolRejection
import com.stressdetector.shared.SessionReadyAck
import com.stressdetector.shared.WearSessionMode
import java.util.TreeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PpgStreamState(
    val greenSamples: Int = 0,
    val irSamples: Int = 0,
    val redSamples: Int = 0,
    val totalGreenSamples: Int = 0,
    val totalIrSamples: Int = 0,
    val totalRedSamples: Int = 0,
    val lastBatchMillis: Long = 0L,
    val lastError: String? = null,
    val sessionId: String? = null,
    val sessionNonce: String = "",
    /** Watch message source; never compared with phone peer identity. */
    val sourceNodeId: String? = null,
    /** Phone-local node encoded by the owning session. */
    val peerNodeId: String = "",
    /** Canonical protocol revision; generation stays wire metadata only. */
    val protocolVersion: Int = SessionIdentity.CURRENT_PROTOCOL_VERSION,
    val generation: Long = 0L,
    /** Explicit fail-closed state received before the Watch starts a tracker. */
    val protocolRejected: Boolean = false,
    val protocolRejectionReason: String? = null,
    val sessionMode: WearSessionMode? = null,
    val sessionReady: Boolean = false,
    val sessionReadyElapsedMillis: Long = 0L,
    /** Phone monotonic receipt time paired with [sessionReadyElapsedMillis]. */
    val sessionReadyReceivedAtMonotonicMillis: Long = 0L,
    val sessionReadyGreen: Boolean = false,
    val sessionReadyIr: Boolean = false,
    val elapsedMillis: Long = 0L,
    val sessionElapsedMillis: Long = 0L,
    val observedDataElapsedMillis: Long = 0L,
    val missingTailMillis: Long = 0L,
    val isComplete: Boolean = false,
    val completionReason: String? = null,
    /** A Watch terminal receipt was observed for this session. */
    val terminalReceiptReceived: Boolean = false,
    /** Both terminal expected sequences were accepted contiguously. */
    val transportComplete: Boolean = false,
    /** Transport failure is diagnostic, never a substitute for SQA/model outcome. */
    val transportIncomplete: Boolean = false,
    val transportFailureReason: String? = null,
    val flushAcknowledged: Boolean = false,
    val flushSessionId: String? = null,
    val appliedBatchCount: Int = 0,
    val duplicateBatchCount: Int = 0,
    val outOfOrderBatchCount: Int = 0,
    /** Sequence holes are isolated per acquired wavelength. */
    val missingSequenceCount: Long = 0L,
    val missingGreenSequenceCount: Long = 0L,
    val missingIrSequenceCount: Long = 0L,
    val missingRedSequenceCount: Long = 0L,
    val highestGreenSequence: Long = 0L,
    val highestIrSequence: Long = 0L,
    val highestRedSequence: Long = 0L
)

/** Immutable, lease-scoped copy of actual PPG evidence. */
data class PpgSessionLeaseSnapshot(
    val lease: PpgSessionLease,
    val streamState: PpgStreamState,
    val greenWindow: WindowSnapshot,
    val irWindow: WindowSnapshot,
    val redWindow: WindowSnapshot,
    val fullGreenWindow: WindowSnapshot,
    val fullIrWindow: WindowSnapshot,
    val fullRedWindow: WindowSnapshot
)

@Suppress("LargeClass")
class PpgRepositoryImpl(private val monotonicNowMillis: () -> Long = SystemClock::elapsedRealtime) : IPpgRepository {
    private val greenBuffer = RollingPpgBuffer(PpgType.GREEN)
    private val irBuffer = RollingPpgBuffer(PpgType.IR)
    private val redBuffer = RollingPpgBuffer(PpgType.RED)
    private val greenFullSessionBuffer = RollingPpgBuffer(PpgType.GREEN, SessionConfig.SESSION_DURATION_SECONDS)
    private val irFullSessionBuffer = RollingPpgBuffer(PpgType.IR, SessionConfig.SESSION_DURATION_SECONDS)
    private val redFullSessionBuffer = RollingPpgBuffer(PpgType.RED, SessionConfig.SESSION_DURATION_SECONDS)
    private val sequenceAudit = PpgBatchSequenceAudit()
    private val motionSamples = TreeMap<Long, AccelerometerSample>()
    private val mutableState = MutableStateFlow(PpgStreamState())
    private val mutableChartFrames = MutableStateFlow(PpgChartFrame())
    private val chartScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()
    private var chartProjectionJob: Job? = null
    private var chartGeneration = 0L
    private var chartDirtyRevision = 0L
    private var pendingTerminalReceipt: PpgTerminalReceipt? = null
    private var activeLease: PpgSessionLease? = null

    override val state: StateFlow<PpgStreamState> = mutableState
    override val chartFrames: StateFlow<PpgChartFrame> = mutableChartFrames

    override fun acquire(lease: PpgSessionLease, sourceNodeId: String?, peerNodeId: String): PpgSessionLeaseAcquireResult =
        synchronized(stateLock) {
            val currentLease = activeLease
            when {
                currentLease == null -> {
                    initializeLeaseLocked(lease, sourceNodeId, peerNodeId)
                    activeLease = lease
                    PpgSessionLeaseAcquireResult.Acquired(lease, reused = false)
                }
                currentLease.matches(lease) ->
                    PpgSessionLeaseAcquireResult.Acquired(currentLease, reused = true)
                else -> PpgSessionLeaseAcquireResult.Busy(currentLease)
            }
        }

    override fun snapshot(lease: PpgSessionLease): PpgSessionLeaseSnapshotResult<PpgSessionLeaseSnapshot> = synchronized(stateLock) {
        when (val currentLease = activeLease) {
            null -> PpgSessionLeaseSnapshotResult.NotActive
            else -> if (currentLease.matches(lease)) {
                PpgSessionLeaseSnapshotResult.Available(captureLeaseSnapshotLocked(currentLease))
            } else {
                PpgSessionLeaseSnapshotResult.NotOwner(currentLease)
            }
        }
    }

    override fun stopAndRelease(lease: PpgSessionLease): PpgSessionLeaseReleaseResult<PpgSessionLeaseSnapshot> = synchronized(stateLock) {
        when (val currentLease = activeLease) {
            null -> PpgSessionLeaseReleaseResult.NotActive
            else -> if (currentLease.matches(lease)) {
                val snapshot = captureLeaseSnapshotLocked(currentLease)
                activeLease = null
                PpgSessionLeaseReleaseResult.Released(snapshot)
            } else {
                PpgSessionLeaseReleaseResult.NotOwner(currentLease)
            }
        }
    }

    override fun startSession(
        sessionId: String,
        sessionNonce: String,
        sourceNodeId: String?,
        peerNodeId: String,
        generation: Long,
        mode: WearSessionMode?
    ) {
        val lease = PpgSessionLease(
            identity = SessionIdentity(sessionId, sessionNonce, SessionIdentity.CURRENT_PROTOCOL_VERSION),
            mode = mode ?: WearSessionMode.HOME_STRESS,
            generation = generation
        )
        when (val result = acquire(lease, sourceNodeId, peerNodeId)) {
            is PpgSessionLeaseAcquireResult.Acquired -> Unit
            is PpgSessionLeaseAcquireResult.Busy -> recordError(
                "PPG session is busy: ${result.activeLease.identity.sessionId}/${result.activeLease.mode}"
            )
        }
    }

    override fun append(batch: PpgBatch, sourceNodeId: String?): PpgBatchAppendResult {
        val shouldPublish = synchronized(stateLock) {
            if (mutableState.value.protocolRejected) {
                return@synchronized PpgBatchAppendResult(
                    disposition = PpgBatchDisposition.REJECTED,
                    reason = "protocol_mismatch"
                )
            }
            val sessionId = batch.sessionId
            if (mutableState.value.sessionId != null && mutableState.value.sessionId != sessionId) {
                mutableState.update { it.copy(lastError = "Ignored stale PPG batch for session $sessionId") }
                return@synchronized PpgBatchAppendResult(
                    disposition = PpgBatchDisposition.REJECTED,
                    reason = "stale_session"
                )
            }
            if (mutableState.value.sessionId == null) {
                mutableState.update { it.copy(lastError = "Ignored PPG batch without an active session") }
                return@synchronized PpgBatchAppendResult(
                    disposition = PpgBatchDisposition.REJECTED,
                    reason = "no_active_session"
                )
            }
            val identityError = identityError(
                actualSourceNodeId = sourceNodeId,
                payloadPeerNodeId = batch.peerNodeId,
                payloadIdentity = batch.sessionIdentity
            )
            if (identityError != null) {
                mutableState.update { it.copy(lastError = "Ignored PPG batch: $identityError") }
                return@synchronized PpgBatchAppendResult(
                    disposition = PpgBatchDisposition.REJECTED,
                    reason = identityError
                )
            }
            // Active acquisition is direct PDSatu-style transport. Do not
            // hold later real batches behind a lost batch; report the hole and
            // let core coverage/SQA decide whether prediction is possible.
            val delivery = sequenceAudit.acceptDirect(batch)
            val appliedBatches = delivery.acknowledgedBatches.takeIf {
                delivery.disposition == PpgBatchDisposition.APPLIED
            }.orEmpty()
            if (delivery.disposition == PpgBatchDisposition.DUPLICATE) {
                mutableState.update {
                    it.copy(
                        duplicateBatchCount = it.duplicateBatchCount + 1,
                        lastError = null
                    )
                }
                return@synchronized PpgBatchAppendResult(
                    disposition = delivery.disposition,
                    acknowledgedBatches = delivery.acknowledgedBatches,
                    reason = delivery.reason
                )
            }
            if (delivery.disposition == PpgBatchDisposition.BUFFERED_OUT_OF_ORDER ||
                delivery.disposition == PpgBatchDisposition.REJECTED
            ) {
                mutableState.update {
                    it.copy(
                        outOfOrderBatchCount = it.outOfOrderBatchCount +
                            if (delivery.disposition == PpgBatchDisposition.BUFFERED_OUT_OF_ORDER) 1 else 0,
                        missingSequenceCount = it.missingSequenceCount + delivery.missingSequenceCount,
                        missingGreenSequenceCount = it.missingGreenSequenceCount +
                            if (batch.type == PpgType.GREEN) delivery.missingSequenceCount else 0L,
                        missingIrSequenceCount = it.missingIrSequenceCount +
                            if (batch.type == PpgType.IR) delivery.missingSequenceCount else 0L,
                        missingRedSequenceCount = it.missingRedSequenceCount +
                            if (batch.type == PpgType.RED) delivery.missingSequenceCount else 0L,
                        lastError = delivery.reason
                    )
                }
                return@synchronized PpgBatchAppendResult(
                    disposition = delivery.disposition,
                    missingSequenceCount = delivery.missingSequenceCount,
                    reason = delivery.reason
                )
            }

            val acceptedByType = appliedBatches
                .groupBy(PpgBatch::type)
                .mapValues { (_, batches) -> batches.sumOf(::appendToBuffers) }
            if (mutableState.value.sessionMode == WearSessionMode.HOME_STRESS) {
                appliedBatches.flatMap(PpgBatch::accelerometerSamples).forEach { sample ->
                    motionSamples.putIfAbsent(sample.sensorTimestampNanos, sample)
                }
                while (motionSamples.size > MAX_HOME_MOTION_SAMPLES) motionSamples.pollFirstEntry()
            }
            val accepted = acceptedByType.values.sum()
            val observedElapsed = maxOf(
                mutableState.value.observedDataElapsedMillis,
                appliedBatches.maxOfOrNull { it.elapsedEndMillis } ?: 0L
            )
            val elapsed = maxOf(mutableState.value.elapsedMillis, observedElapsed)
            mutableState.update {
                it.copy(
                    greenSamples = greenBuffer.size,
                    irSamples = irBuffer.size,
                    redSamples = redBuffer.size,
                    totalGreenSamples = it.totalGreenSamples + (acceptedByType[PpgType.GREEN] ?: 0),
                    totalIrSamples = it.totalIrSamples + (acceptedByType[PpgType.IR] ?: 0),
                    totalRedSamples = it.totalRedSamples + (acceptedByType[PpgType.RED] ?: 0),
                    lastBatchMillis = if (accepted > 0) System.currentTimeMillis() else it.lastBatchMillis,
                    lastError = null,
                    sessionId = sessionId,
                    elapsedMillis = elapsed,
                    sessionElapsedMillis = elapsed,
                    observedDataElapsedMillis = observedElapsed,
                    missingTailMillis = (elapsed - observedElapsed).coerceAtLeast(0L),
                    // Wall-clock elapsed is progress only. Completion needs a
                    // matching terminal receipt so missing final batches are
                    // never silently accepted.
                    isComplete = it.isComplete,
                    appliedBatchCount = it.appliedBatchCount + appliedBatches.size,
                    missingSequenceCount = it.missingSequenceCount + delivery.missingSequenceCount,
                    missingGreenSequenceCount = it.missingGreenSequenceCount +
                        if (batch.type == PpgType.GREEN) delivery.missingSequenceCount else 0L,
                    missingIrSequenceCount = it.missingIrSequenceCount +
                        if (batch.type == PpgType.IR) delivery.missingSequenceCount else 0L,
                    missingRedSequenceCount = it.missingRedSequenceCount +
                        if (batch.type == PpgType.RED) delivery.missingSequenceCount else 0L,
                    highestGreenSequence = maxOf(
                        it.highestGreenSequence,
                        appliedBatches.filter { batch -> batch.type == PpgType.GREEN }
                            .maxOfOrNull(PpgBatch::sequenceNumber) ?: 0L
                    ),
                    highestIrSequence = maxOf(
                        it.highestIrSequence,
                        appliedBatches.filter { batch -> batch.type == PpgType.IR }
                            .maxOfOrNull(PpgBatch::sequenceNumber) ?: 0L
                    ),
                    highestRedSequence = maxOf(
                        it.highestRedSequence,
                        appliedBatches.filter { batch -> batch.type == PpgType.RED }
                            .maxOfOrNull(PpgBatch::sequenceNumber) ?: 0L
                    )
                )
            }
            // Terminal receipt can race a final delayed data batch. Keep the
            // receipt and re-evaluate sequence coverage after every apply so
            // completion is not dependent on another control-message retry.
            pendingTerminalReceipt?.takeIf { it.sessionId == sessionId }?.let(::applyTerminalReceiptLocked)
            PpgBatchAppendResult(
                disposition = PpgBatchDisposition.APPLIED,
                acknowledgedBatches = appliedBatches,
                missingSequenceCount = delivery.missingSequenceCount,
                reason = delivery.reason
            )
        }
        if (shouldPublish.acknowledgedBatches.isNotEmpty()) publishChartFrame()
        return shouldPublish
    }

    override fun acceptSessionReady(ack: SessionReadyAck, sourceNodeId: String?): Boolean = synchronized(stateLock) {
        val current = mutableState.value
        if (current.protocolRejected) return@synchronized false
        if (current.sessionId != ack.sessionId) return@synchronized false
        if (identityError(sourceNodeId, ack.peerNodeId, ack.sessionIdentity) != null) {
            mutableState.update { it.copy(lastError = "Ignored stale Watch SESSION_READY") }
            return@synchronized false
        }
        if (!ack.greenAvailable || !ack.irAvailable) return@synchronized false
        mutableState.update {
            it.copy(
                sessionReady = true,
                sessionReadyElapsedMillis = ack.elapsedMillis,
                sessionReadyReceivedAtMonotonicMillis = monotonicNowMillis(),
                sessionReadyGreen = ack.greenAvailable,
                sessionReadyIr = ack.irAvailable,
                lastError = null
            )
        }
        true
    }

    override fun rejectProtocol(rejection: SessionProtocolRejection, sourceNodeId: String?): Boolean = synchronized(stateLock) {
        val current = mutableState.value
        val currentIdentity = current.sessionIdentityOrNull() ?: return@synchronized false
        if (currentIdentity.sessionId != rejection.sessionId ||
            currentIdentity.nonce != rejection.sessionNonce ||
            (current.sourceNodeId != null && current.sourceNodeId != sourceNodeId)
        ) {
            return@synchronized false
        }

        chartProjectionJob?.cancel()
        chartProjectionJob = null
        chartGeneration += 1L
        chartDirtyRevision = 0L
        greenBuffer.clear()
        irBuffer.clear()
        redBuffer.clear()
        greenFullSessionBuffer.clear()
        irFullSessionBuffer.clear()
        redFullSessionBuffer.clear()
        motionSamples.clear()
        pendingTerminalReceipt = null
        sequenceAudit.resetSession(current.sessionId.orEmpty())

        val reason = "protocol_mismatch: update phone and Watch app, then refresh connection"
        mutableState.value = current.copy(
            greenSamples = 0,
            irSamples = 0,
            redSamples = 0,
            totalGreenSamples = 0,
            totalIrSamples = 0,
            totalRedSamples = 0,
            sessionReady = false,
            isComplete = false,
            terminalReceiptReceived = false,
            transportComplete = false,
            transportIncomplete = true,
            transportFailureReason = "protocol_mismatch",
            flushAcknowledged = false,
            flushSessionId = null,
            protocolRejected = true,
            protocolRejectionReason = reason,
            completionReason = "protocol_mismatch",
            lastError = reason
        )
        mutableChartFrames.value = PpgChartFrame(
            sessionId = current.sessionId,
            sessionNonce = current.sessionNonce,
            protocolVersion = current.protocolVersion,
            generation = current.generation
        )
        true
    }

    override fun markSessionElapsed(sessionId: String, elapsedMillis: Long, completionReason: String?) {
        synchronized(stateLock) {
            if (mutableState.value.sessionId != sessionId || mutableState.value.protocolRejected) return
            mutableState.update {
                val sessionElapsed = maxOf(
                    it.elapsedMillis,
                    elapsedForMode(it, elapsedMillis)
                )
                it.copy(
                    elapsedMillis = sessionElapsed,
                    sessionElapsedMillis = sessionElapsed,
                    missingTailMillis = (sessionElapsed - it.observedDataElapsedMillis).coerceAtLeast(0L),
                    // Direct PDSatu transport has no terminal receipt. Session
                    // completion is owned by the phone session clock; data
                    // coverage remains a separate prediction/SQA gate.
                    isComplete = it.isComplete ||
                        it.sessionMode == WearSessionMode.HOME_STRESS &&
                        sessionElapsed >= SessionConfig.SESSION_DURATION_MILLIS,
                    completionReason = completionReason ?: it.completionReason
                )
            }
        }
    }

    override fun markFlushAcknowledged(sessionId: String, flushed: Boolean, completed: Boolean, elapsedMillis: Long) {
        synchronized(stateLock) {
            if (mutableState.value.sessionId != sessionId || mutableState.value.protocolRejected) return
            mutableState.update {
                val sessionElapsed = maxOf(
                    it.elapsedMillis,
                    elapsedForMode(it, elapsedMillis)
                )
                // V1 has no sequence receipt. During upgrade, its explicit
                // flush/completed control acknowledgement is terminal proof.
                val sessionComplete = flushed && completed
                it.copy(
                    flushAcknowledged = flushed,
                    flushSessionId = sessionId,
                    elapsedMillis = sessionElapsed,
                    sessionElapsedMillis = sessionElapsed,
                    missingTailMillis = (sessionElapsed - it.observedDataElapsedMillis).coerceAtLeast(0L),
                    isComplete = sessionComplete,
                    terminalReceiptReceived = true,
                    transportComplete = sessionComplete,
                    transportIncomplete = !sessionComplete,
                    transportFailureReason = if (sessionComplete) null else "legacy_watch_flush_incomplete",
                    completionReason = if (sessionComplete) "watch_completed" else "watch_flush_incomplete",
                    lastError = if (flushed) it.lastError else "Watch reported incomplete PPG flush"
                )
            }
        }
    }

    override fun acceptTerminalReceipt(receipt: PpgTerminalReceipt, sourceNodeId: String?): PpgTerminalReceiptResult =
        synchronized(stateLock) {
            val current = mutableState.value
            if (current.protocolRejected) {
                return@synchronized PpgTerminalReceiptResult(
                    current.highestGreenSequence,
                    current.highestIrSequence,
                    current.highestRedSequence,
                    complete = false,
                    reason = "protocol_mismatch"
                )
            }
            val identityError = identityError(sourceNodeId, receipt.peerNodeId, receipt.sessionIdentity)
            if (identityError != null) {
                return@synchronized PpgTerminalReceiptResult(
                    current.highestGreenSequence,
                    current.highestIrSequence,
                    current.highestRedSequence,
                    complete = false,
                    reason = identityError
                )
            }
            pendingTerminalReceipt = receipt
            applyTerminalReceiptLocked(receipt)
        }

    /** Caller holds [stateLock]. */
    private fun identityError(actualSourceNodeId: String?, payloadPeerNodeId: String, payloadIdentity: SessionIdentity): String? {
        val current = mutableState.value
        if (current.sourceNodeId != null && current.sourceNodeId != actualSourceNodeId) {
            return "stale_source_node"
        }
        if (current.peerNodeId.isNotBlank() && payloadPeerNodeId != current.peerNodeId) {
            return "stale_peer_node"
        }
        val expectedIdentity = current.sessionIdentityOrNull()
        if (expectedIdentity != null && !expectedIdentity.matches(payloadIdentity)) {
            return when {
                current.sessionId != payloadIdentity.sessionId -> "stale_session"
                current.sessionNonce != payloadIdentity.nonce -> "stale_session_nonce"
                else -> "stale_protocol_version"
            }
        }
        return null
    }

    /** Caller holds [stateLock]. Keeps receipt pending until late batches fill every expected sequence. */
    private fun applyTerminalReceiptLocked(receipt: PpgTerminalReceipt): PpgTerminalReceiptResult {
        val current = mutableState.value
        if (current.sessionId != receipt.sessionId) {
            return PpgTerminalReceiptResult(
                current.highestGreenSequence,
                current.highestIrSequence,
                current.highestRedSequence,
                complete = false,
                reason = "stale_session"
            )
        }
        if (current.sessionNonce.isNotBlank() && current.sessionNonce != receipt.sessionNonce) {
            return PpgTerminalReceiptResult(
                current.highestGreenSequence,
                current.highestIrSequence,
                current.highestRedSequence,
                complete = false,
                reason = "stale_session_nonce"
            )
        }
        val streamsComplete = current.highestGreenSequence >= receipt.expectedGreenSequence &&
            current.highestIrSequence >= receipt.expectedIrSequence &&
            current.missingSequenceCount == 0L
        val complete = receipt.completed && streamsComplete
        val reason = when {
            !receipt.completed -> "watch_session_incomplete"
            current.missingSequenceCount > 0L ->
                "transport_gap_sequence_count=${current.missingSequenceCount}"
            !streamsComplete ->
                "pending_sequence_green=${current.highestGreenSequence}/${receipt.expectedGreenSequence};" +
                    "ir=${current.highestIrSequence}/${receipt.expectedIrSequence}"
            else -> ""
        }
        mutableState.update {
            val elapsed = maxOf(it.elapsedMillis, elapsedForMode(it, receipt.elapsedMillis))
            it.copy(
                flushAcknowledged = complete,
                flushSessionId = receipt.sessionId,
                elapsedMillis = elapsed,
                sessionElapsedMillis = elapsed,
                missingTailMillis = (elapsed - it.observedDataElapsedMillis).coerceAtLeast(0L),
                isComplete = complete,
                terminalReceiptReceived = true,
                transportComplete = complete,
                transportIncomplete = !complete,
                transportFailureReason = reason.ifBlank { null },
                completionReason = if (complete) "watch_terminal_receipt" else "transport_incomplete",
                lastError = reason.ifBlank { null }
            )
        }
        if (complete) pendingTerminalReceipt = null
        return PpgTerminalReceiptResult(
            current.highestGreenSequence,
            current.highestIrSequence,
            current.highestRedSequence,
            complete,
            reason
        )
    }

    override fun recordError(message: String) {
        synchronized(stateLock) {
            mutableState.update { state ->
                if (state.protocolRejected) state else state.copy(lastError = message)
            }
        }
    }

    override fun recordTransportIncomplete(message: String) {
        synchronized(stateLock) {
            mutableState.update {
                if (it.protocolRejected) return@update it
                it.copy(
                    transportIncomplete = true,
                    transportFailureReason = message,
                    completionReason = "transport_incomplete",
                    lastError = message
                )
            }
        }
    }

    override fun greenWindow(): WindowSnapshot = withTransportDiagnostics(greenBuffer.snapshot(), PpgType.GREEN)
    override fun irWindow(): WindowSnapshot = withTransportDiagnostics(irBuffer.snapshot(), PpgType.IR)
    override fun redWindow(): WindowSnapshot = withTransportDiagnostics(redBuffer.snapshot(), PpgType.RED)
    override fun fullGreenWindow(): WindowSnapshot = synchronized(stateLock) {
        withTransportDiagnostics(greenFullSessionBuffer.snapshot(), PpgType.GREEN)
            .copy(accelerometerSamples = motionSamples.values.toList())
    }
    override fun fullIrWindow(): WindowSnapshot = synchronized(stateLock) {
        withTransportDiagnostics(irFullSessionBuffer.snapshot(), PpgType.IR)
            .copy(accelerometerSamples = motionSamples.values.toList())
    }
    override fun fullRedWindow(): WindowSnapshot = synchronized(stateLock) {
        withTransportDiagnostics(redFullSessionBuffer.snapshot(), PpgType.RED)
            .copy(accelerometerSamples = motionSamples.values.toList())
    }

    /** Legacy live-monitor projection. Home renders [PpgChartFrame] snapshots through its projector. */
    override fun greenChartData(): ChartData = greenBuffer.recentChartData(CHART_WINDOW_SEC)

    /** Legacy live-monitor projection. Home renders [PpgChartFrame] snapshots through its projector. */
    override fun irChartData(): ChartData = irBuffer.recentChartData(CHART_WINDOW_SEC)

    /** Legacy live-monitor projection. Home renders [PpgChartFrame] snapshots through its projector. */
    override fun redChartData(): ChartData = redBuffer.recentChartData(CHART_WINDOW_SEC)

    /** Caller holds [stateLock]. A newly acquired lease is the only reset boundary. */
    private fun initializeLeaseLocked(lease: PpgSessionLease, sourceNodeId: String?, peerNodeId: String) {
        chartProjectionJob?.cancel()
        chartProjectionJob = null
        chartGeneration += 1L
        chartDirtyRevision = 0L
        greenBuffer.clear()
        irBuffer.clear()
        redBuffer.clear()
        greenFullSessionBuffer.clear()
        irFullSessionBuffer.clear()
        redFullSessionBuffer.clear()
        sequenceAudit.resetSession(lease.identity.sessionId)
        pendingTerminalReceipt = null
        mutableState.value = PpgStreamState(
            sessionId = lease.identity.sessionId,
            sessionNonce = lease.identity.nonce,
            sourceNodeId = sourceNodeId,
            peerNodeId = peerNodeId,
            protocolVersion = lease.identity.protocolVersion,
            generation = lease.generation,
            sessionMode = lease.mode
        )
        mutableChartFrames.value = PpgChartFrame(
            sessionId = lease.identity.sessionId,
            sessionNonce = lease.identity.nonce,
            protocolVersion = lease.identity.protocolVersion,
            generation = lease.generation
        )
    }

    /** Caller holds [stateLock]. All arrays are copied by their buffers. */
    private fun captureLeaseSnapshotLocked(lease: PpgSessionLease): PpgSessionLeaseSnapshot = PpgSessionLeaseSnapshot(
        lease = lease,
        streamState = mutableState.value,
        greenWindow = withTransportDiagnostics(greenBuffer.snapshot(), PpgType.GREEN),
        irWindow = withTransportDiagnostics(irBuffer.snapshot(), PpgType.IR),
        redWindow = withTransportDiagnostics(redBuffer.snapshot(), PpgType.RED),
        fullGreenWindow = withTransportDiagnostics(greenFullSessionBuffer.snapshot(), PpgType.GREEN)
            .copy(accelerometerSamples = motionSamples.values.toList()),
        fullIrWindow = withTransportDiagnostics(irFullSessionBuffer.snapshot(), PpgType.IR)
            .copy(accelerometerSamples = motionSamples.values.toList()),
        fullRedWindow = withTransportDiagnostics(redFullSessionBuffer.snapshot(), PpgType.RED)
            .copy(accelerometerSamples = motionSamples.values.toList())
    )

    private fun withTransportDiagnostics(snapshot: WindowSnapshot, type: PpgType): WindowSnapshot {
        val state = mutableState.value
        val missingSequences = when (type) {
            PpgType.GREEN -> state.missingGreenSequenceCount
            PpgType.IR -> state.missingIrSequenceCount
            PpgType.RED -> state.missingRedSequenceCount
        }
        val span = snapshot.timestampsMillis.lastOrNull()?.minus(
            snapshot.timestampsMillis.firstOrNull() ?: 0L
        ) ?: 0L
        val cadence = if (span > 0L && snapshot.size > 1) {
            (snapshot.size - 1) * MILLIS_PER_SECOND_FLOAT / span.toFloat()
        } else {
            null
        }
        return snapshot.copy(
            transportDiagnostics = PpgTransportDiagnostics(
                acceptedSampleCount = snapshot.size.toLong(),
                sequenceLossCount = missingSequences,
                duplicateBatchCount = state.duplicateBatchCount.toLong(),
                outOfOrderBatchCount = state.outOfOrderBatchCount.toLong(),
                actualCadenceHz = cadence
            )
        )
    }

    private fun publishChartFrame() {
        synchronized(stateLock) {
            chartDirtyRevision += 1L
            if (chartProjectionJob?.isActive == true) return
            chartProjectionJob = chartScope.launch { projectChartFrames() }
        }
    }

    /** One owner coalesces rendering without losing samples arriving mid-frame. */
    private suspend fun projectChartFrames() {
        while (true) {
            delay(CHART_COALESCE_MILLIS)
            val ownership = synchronized(stateLock) {
                val state = mutableState.value
                ChartFrameOwnership(
                    projectionGeneration = chartGeneration,
                    dirtyRevision = chartDirtyRevision,
                    sessionId = state.sessionId,
                    sessionNonce = state.sessionNonce,
                    protocolVersion = state.protocolVersion,
                    wireGeneration = state.generation
                )
            }
            val greenSnapshot = greenBuffer.snapshot()
            val irSnapshot = irBuffer.snapshot()
            val redSnapshot = redBuffer.snapshot()
            val green = greenBuffer.recentChartData(CHART_WINDOW_SEC)
            val ir = irBuffer.recentChartData(CHART_WINDOW_SEC)
            val red = redBuffer.recentChartData(CHART_WINDOW_SEC)
            val axisEnd = listOf(greenSnapshot, irSnapshot)
                .flatMap { it.elapsedTimestampsMillis.asList() }
                .maxOrNull() ?: 0L
            val frame = PpgChartFrame(
                sessionId = ownership.sessionId,
                sessionNonce = ownership.sessionNonce,
                protocolVersion = ownership.protocolVersion,
                generation = ownership.wireGeneration,
                greenSnapshot = greenSnapshot,
                irSnapshot = irSnapshot,
                redSnapshot = redSnapshot,
                green = green,
                ir = ir,
                red = red,
                capturedAtMillis = System.currentTimeMillis(),
                axisStartMillis = (axisEnd - CHART_WINDOW_SEC * 1_000f).toLong().coerceAtLeast(0L),
                axisEndMillis = axisEnd
            )
            val finished = synchronized(stateLock) {
                if (ownership.projectionGeneration != chartGeneration) {
                    true
                } else if (ownership.dirtyRevision == chartDirtyRevision) {
                    mutableChartFrames.value = frame
                    chartProjectionJob = null
                    true
                } else {
                    false
                }
            }
            if (finished) return
        }
    }

    private data class ChartFrameOwnership(
        val projectionGeneration: Long,
        val dirtyRevision: Long,
        val sessionId: String?,
        val sessionNonce: String,
        val protocolVersion: Int,
        val wireGeneration: Long
    )

    private fun appendToBuffers(batch: PpgBatch): Int = when (batch.type) {
        PpgType.GREEN -> {
            val accepted = greenBuffer.append(batch)
            // Legacy 300-second projection remains bounded and is never the
            // Verbatim raw source; Verbatim raw evidence uses VerbatimPpgArchive.
            greenFullSessionBuffer.append(batch)
            accepted
        }
        PpgType.IR -> {
            val accepted = irBuffer.append(batch)
            irFullSessionBuffer.append(batch)
            accepted
        }
        PpgType.RED -> {
            val accepted = redBuffer.append(batch)
            redFullSessionBuffer.append(batch)
            accepted
        }
    }

    private fun elapsedForMode(state: PpgStreamState, elapsedMillis: Long): Long =
        if (state.sessionMode == WearSessionMode.VERBATIM_AROUSAL) {
            elapsedMillis.coerceAtLeast(0L)
        } else {
            elapsedMillis.coerceIn(0L, SessionConfig.SESSION_DURATION_MILLIS)
        }
}

private fun PpgStreamState.sessionIdentityOrNull(): SessionIdentity? {
    val id = sessionId ?: return null
    return SessionIdentity(id, sessionNonce, protocolVersion)
}

/** Shared time window for both Green and IR chart data. Single source of truth. */
private const val CHART_WINDOW_SEC = 10f
private const val CHART_COALESCE_MILLIS = 100L
private const val MILLIS_PER_SECOND_FLOAT = 1_000f
private const val MAX_HOME_MOTION_SAMPLES = 10_000
