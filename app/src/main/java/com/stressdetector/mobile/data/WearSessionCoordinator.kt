package com.stressdetector.mobile.data

import android.content.Context
import com.stressdetector.mobile.domain.engine.WatchSessionCoordinator
import com.stressdetector.mobile.domain.engine.WatchSessionState
import com.stressdetector.shared.PpgSessionLease
import com.stressdetector.shared.PpgSessionLeaseAcquireResult
import com.stressdetector.shared.SessionIdentity
import com.stressdetector.shared.WearSessionMode
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface WearCommandGateway {
    suspend fun localNodeId(): String

    suspend fun startTracking(
        watchNodeId: String,
        sessionId: String,
        mode: WearSessionMode,
        sessionNonce: String,
        phonePeerNodeId: String,
        generation: Long
    ): CommandSendResult

    suspend fun stopTracking(
        watchNodeId: String,
        sessionId: String?,
        sessionNonce: String,
        completionExpected: Boolean,
        phonePeerNodeId: String,
        generation: Long
    ): CommandSendResult
}

private class DefaultWearCommandGateway(private val sender: WearCommandSender) : WearCommandGateway {
    override suspend fun localNodeId(): String = sender.localNodeId()

    override suspend fun startTracking(
        watchNodeId: String,
        sessionId: String,
        mode: WearSessionMode,
        sessionNonce: String,
        phonePeerNodeId: String,
        generation: Long
    ): CommandSendResult = sender.startTracking(
        watchNodeId,
        sessionId,
        mode,
        sessionNonce,
        phonePeerNodeId,
        generation
    )

    override suspend fun stopTracking(
        watchNodeId: String,
        sessionId: String?,
        sessionNonce: String,
        completionExpected: Boolean,
        phonePeerNodeId: String,
        generation: Long
    ): CommandSendResult = sender.stopTracking(
        watchNodeId,
        sessionId,
        sessionNonce,
        completionExpected,
        phonePeerNodeId,
        generation
    )
}

/** Explicit outcome for a Home/Verbatim start that cannot acquire the PPG owner slot. */
class PpgSessionBusyException(val activeLease: PpgSessionLease) :
    IllegalStateException(
        "PPG session is busy: ${activeLease.identity.sessionId}/${activeLease.mode}/generation=${activeLease.generation}"
    )

/** Owns the phone-side Watch command lifecycle for Home and Verbatim+ sessions. */
class WearSessionCoordinator internal constructor(
    private val context: Context,
    private val ppgRepository: IPpgRepository,
    private val connectionRepository: IWearConnectionRepository,
    private val commandGateway: WearCommandGateway
) : WatchSessionCoordinator {
    constructor(
        context: Context,
        ppgRepository: IPpgRepository,
        connectionRepository: IWearConnectionRepository,
        commandSender: WearCommandSender
    ) : this(context, ppgRepository, connectionRepository, DefaultWearCommandGateway(commandSender))

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<WatchSessionState>(WatchSessionState.Idle)
    private var activeSessionId: String? = null
    private var activeSessionNonce: String? = null
    private var activeWatchNodeId: String? = null
    private var activePhonePeerNodeId: String? = null
    private var activeGeneration: Long = 0L
    private var activeLease: PpgSessionLease? = null
    private val generations = AtomicLong(0L)
    override val state: StateFlow<WatchSessionState> = mutableState.asStateFlow()

    override suspend fun start(sessionId: String, mode: WearSessionMode): Result<Unit> = mutex.withLock {
        if (sessionId.isBlank()) return Result.failure(IllegalArgumentException("Session id must not be blank"))
        when (mutableState.value) {
            WatchSessionState.Streaming -> {
                return if (activeSessionId == sessionId) {
                    Result.success(Unit)
                } else {
                    activeLease?.let { lease ->
                        Result.failure(PpgSessionBusyException(lease))
                    } ?: Result.failure(IllegalStateException("Another Watch session is already streaming"))
                }
            }
            WatchSessionState.Connecting,
            WatchSessionState.Stopping -> return Result.failure(
                IllegalStateException("Another Watch session is still transitioning")
            )
            else -> Unit
        }
        mutableState.value = WatchSessionState.Connecting
        var sessionNonce: String? = null
        var startCommandSent = false
        var acquiredLease: PpgSessionLease? = null
        return try {
            val reachable = connectionRepository.waitForReachable(context)
            val watchNodeId = requireNotNull(reachable.commandNodeId?.takeIf { reachable.canStartSession() }) {
                "Watch app is not reachable: ${reachable.message}"
            }
            val phonePeerNodeId = commandGateway.localNodeId()
            val generation = generations.incrementAndGet()
            val nonce = UUID.randomUUID().toString()
            sessionNonce = nonce
            val lease = PpgSessionLease(
                identity = SessionIdentity(
                    sessionId = sessionId,
                    nonce = nonce,
                    protocolVersion = SessionIdentity.CURRENT_PROTOCOL_VERSION
                ),
                mode = mode,
                generation = generation
            )
            when (val acquired = ppgRepository.acquire(lease, watchNodeId, phonePeerNodeId)) {
                is PpgSessionLeaseAcquireResult.Acquired -> acquiredLease = acquired.lease
                is PpgSessionLeaseAcquireResult.Busy -> throw PpgSessionBusyException(acquired.activeLease)
            }
            val sent = commandGateway.startTracking(
                watchNodeId,
                sessionId,
                mode,
                nonce,
                phonePeerNodeId,
                generation
            )
            check(sent.isSuccess) { sent.errorMessage ?: "Watch session start could not be delivered" }
            startCommandSent = true
            // The PDSatu-compatible path starts its session clock when the
            // individual Watch trackers are registered. Samples received by
            // the phone remain the only evidence for final core coverage.
            connectionRepository.markRecording(true)
            activeSessionId = sessionId
            activeSessionNonce = nonce
            activeWatchNodeId = watchNodeId
            activePhonePeerNodeId = phonePeerNodeId
            activeGeneration = generation
            activeLease = requireNotNull(acquiredLease)
            mutableState.value = WatchSessionState.Streaming
            Result.success(Unit)
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                rollbackStart(sessionId, sessionNonce, startCommandSent, acquiredLease)
            }
            throw error
        } catch (error: Exception) {
            if (error is PpgSessionBusyException) {
                // A competing owner remains authoritative. Do not change its
                // transport state, record an error into its stream, or clear
                // any buffers while reporting the explicit busy outcome.
                mutableState.value = WatchSessionState.Ready
                return Result.failure(error)
            }
            rollbackStart(sessionId, sessionNonce, startCommandSent, acquiredLease)
            mutableState.value = WatchSessionState.Error(error.message ?: "Watch session could not start")
            ppgRepository.recordError(error.message ?: "Watch session could not start")
            Result.failure(error)
        }
    }

    override suspend fun stop(sessionId: String, completionExpected: Boolean): Result<Unit> = mutex.withLock {
        if (activeSessionId == null) {
            if (mutableState.value !is WatchSessionState.Error) mutableState.value = WatchSessionState.Ready
            return Result.success(Unit)
        }
        if (activeSessionId != null && activeSessionId != sessionId) {
            return Result.failure(IllegalArgumentException("Watch stop session does not match active session"))
        }
        if (mutableState.value == WatchSessionState.Stopping) return Result.success(Unit)
        mutableState.value = WatchSessionState.Stopping
        runCatching {
            val watchNodeId = requireNotNull(activeWatchNodeId) { "Active Watch identity is unavailable" }
            val sent = commandGateway.stopTracking(
                watchNodeId = watchNodeId,
                sessionId = sessionId,
                sessionNonce = activeSessionNonce.orEmpty(),
                completionExpected = completionExpected,
                phonePeerNodeId = activePhonePeerNodeId.orEmpty(),
                generation = activeGeneration
            )
            check(sent.isSuccess || sent.nodeCount == 0) {
                sent.errorMessage ?: "Watch session stop could not be delivered"
            }
            connectionRepository.markRecording(false)
            if (sent.nodeCount == 0) {
                // With no connected node there can be no in-flight batch or flush ACK.
                // Release the local gate so the next explicit session can start cleanly.
                releaseActiveLease(sessionId)
                activeSessionId = null
                activeSessionNonce = null
                activeWatchNodeId = null
                activePhonePeerNodeId = null
                activeGeneration = 0L
                mutableState.value = WatchSessionState.Idle
            }
        }.onFailure { error ->
            mutableState.value = WatchSessionState.Error(error.message ?: "Watch session could not stop")
        }
    }

    override suspend fun awaitDrain(sessionId: String, timeoutMillis: Long): Result<Unit> = mutex.withLock {
        if (activeSessionId == null) {
            if (mutableState.value !is WatchSessionState.Error) mutableState.value = WatchSessionState.Ready
            return Result.success(Unit)
        }
        if (activeSessionId != sessionId) {
            return Result.failure(IllegalArgumentException("Watch drain session does not match active session"))
        }
        var terminalError: Throwable? = null
        try {
            runCatching {
                val ingressDrained = withContext(Dispatchers.IO) {
                    WearBatchListenerService.awaitIngressDrain(timeoutMillis)
                }
                if (!ingressDrained) {
                    ppgRepository.recordTransportIncomplete(
                        "Phone PPG ingestion queue did not drain before finalization"
                    )
                }
                // Direct MessageClient transport has no ACK/terminal receipt.
                // Give the Watch a short bounded grace period, then process only
                // the samples already accepted by the phone.
                delay(timeoutMillis.coerceIn(250L, 1_000L))
            }.also { result ->
                terminalError = result.exceptionOrNull()
            }
        } catch (cancel: CancellationException) {
            terminalError = cancel
            throw cancel
        } finally {
            // A parent timeout must not strand the lease or allow late batches
            // to alter the session after finalization starts.
            withContext(NonCancellable) {
                releaseActiveLease(sessionId)
                clearActiveSession(sessionId)
                mutableState.value = terminalError?.let { error ->
                    WatchSessionState.Error(error.message ?: "Watch drain cancelled")
                } ?: WatchSessionState.Ready
            }
        }
    }

    private suspend fun rollbackStart(sessionId: String, sessionNonce: String?, startCommandSent: Boolean, lease: PpgSessionLease?) {
        if (startCommandSent) {
            val state = ppgRepository.state.value
            runCatching {
                commandGateway.stopTracking(
                    watchNodeId = state.sourceNodeId.orEmpty(),
                    sessionId = sessionId,
                    sessionNonce = sessionNonce.orEmpty(),
                    completionExpected = false,
                    phonePeerNodeId = state.peerNodeId,
                    generation = state.generation
                )
            }
        }
        runCatching { connectionRepository.markRecording(false) }
        lease?.let { ppgRepository.stopAndRelease(it) }
        clearActiveSession(sessionId)
        mutableState.value = WatchSessionState.Idle
    }

    private fun releaseActiveLease(sessionId: String) {
        val lease = activeLease ?: return
        if (lease.identity.sessionId != sessionId) return
        ppgRepository.stopAndRelease(lease)
    }

    private fun clearActiveSession(sessionId: String) {
        if (activeSessionId != null && activeSessionId != sessionId) return
        activeSessionId = null
        activeSessionNonce = null
        activeWatchNodeId = null
        activePhonePeerNodeId = null
        activeGeneration = 0L
        activeLease = null
    }
}
