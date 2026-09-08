package com.stressdetector.mobile.data

import android.content.Context
import com.google.android.gms.wearable.Wearable
import com.stressdetector.shared.MessagePath
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class WatchConnectionStatus {
    DISCONNECTED,
    NODE_ONLY,
    APP_REACHABLE,
    RECORDING,
    ERROR
}

/** Canonical reachability lifecycle; [WatchConnectionStatus] remains a UI compatibility projection. */
enum class WearReachabilityPhase {
    OFFLINE,
    NODE_DISCOVERED,
    APP_REACHABLE,
    STREAMING,
    DRAINING,
    PROTOCOL_ERROR
}

data class WearConnectionState(
    val status: WatchConnectionStatus = WatchConnectionStatus.DISCONNECTED,
    val reachabilityPhase: WearReachabilityPhase = WearReachabilityPhase.OFFLINE,
    val nodeCount: Int = 0,
    val lastCheckedMillis: Long = 0L,
    val lastPongMillis: Long = 0L,
    val lastPongNodeId: String? = null,
    val lastStreamingEvidenceMillis: Long = 0L,
    val lastStreamingNodeId: String? = null,
    val lastNodeIds: List<String> = emptyList(),
    val message: String = "Watch not connected"
) {
    val isAppReachable: Boolean
        get() = reachabilityPhase in setOf(
            WearReachabilityPhase.APP_REACHABLE,
            WearReachabilityPhase.STREAMING,
            WearReachabilityPhase.DRAINING
        )

    /** Prefer correlated app evidence, while allowing a discovered node for best-effort commands. */
    val commandNodeId: String?
        get() = lastPongNodeId?.takeIf { it in lastNodeIds } ?: lastNodeIds.firstOrNull()

    /**
     * A discovered node is sufficient to attempt a session command. This is
     * intentionally independent from [isAppReachable], which requires
     * correlated application evidence.
     */
    val canAttemptCommands: Boolean
        get() = commandNodeId != null

    fun isPongFresh(nowMillis: Long, freshnessMillis: Long = WearConnectionRepositoryImpl.PONG_FRESHNESS_MILLIS): Boolean =
        lastPongMillis > 0L && nowMillis - lastPongMillis <= freshnessMillis

    /**
     * Session start requires current application evidence, not node discovery.
     * Streaming and draining are active-session phases and cannot authorize a
     * new Home session.
     */
    fun canStartSession(nowMillis: Long = System.currentTimeMillis()): Boolean = reachabilityPhase ==
        WearReachabilityPhase.APP_REACHABLE &&
        commandNodeId != null &&
        lastPongNodeId == commandNodeId &&
        isPongFresh(nowMillis)
}

class WearConnectionRepositoryImpl : IWearConnectionRepository {
    companion object {
        const val PONG_FRESHNESS_MILLIS = 15_000L
        const val REACHABILITY_TIMEOUT_MILLIS = 4_000L
        private const val MIN_REACHABILITY_WAIT_MILLIS = 250L
        private const val PING_NONCE_LEASE_MILLIS = 15_000L
    }

    internal data class PendingPing(val nonce: Long, val issuedAtMillis: Long)

    /** Thread-safe nonce owner; a refresh must not replace an in-flight ping lease. */
    internal class PendingPingRegistry(private val leaseMillis: Long, private val nonceFactory: () -> Long) {
        private val entries = mutableMapOf<String, PendingPing>()

        @Synchronized
        fun clear() {
            entries.clear()
        }

        @Synchronized
        fun retainNodes(nodeIds: Set<String>) {
            entries.keys.retainAll(nodeIds)
        }

        @Synchronized
        fun prepare(nodeId: String, nowMillis: Long): Long? {
            val pending = entries[nodeId]
            if (pending != null && nowMillis - pending.issuedAtMillis < leaseMillis) return null
            val nonce = nonceFactory()
            entries[nodeId] = PendingPing(nonce, nowMillis)
            return nonce
        }

        @Synchronized
        fun discardIfMatches(nodeId: String, nonce: Long) {
            if (entries[nodeId]?.nonce == nonce) entries.remove(nodeId)
        }

        @Synchronized
        fun consumeIfMatches(nodeId: String, nonce: Long): Boolean {
            if (entries[nodeId]?.nonce != nonce) return false
            entries.remove(nodeId)
            return true
        }

        @Synchronized
        fun consumeAny(nodeId: String): Boolean = entries.remove(nodeId) != null
    }

    private val nonceRandom = SecureRandom()
    private val mutableState = MutableStateFlow(WearConnectionState())
    private val pendingPingNonces = PendingPingRegistry(PING_NONCE_LEASE_MILLIS, nonceRandom::nextLong)
    private val refreshMutex = Mutex()
    override val state: StateFlow<WearConnectionState> = mutableState.asStateFlow()

    override suspend fun refreshAndPing(context: Context): WearConnectionState = refreshMutex.withLock {
        refreshAndPingLocked(context)
    }

    private suspend fun refreshAndPingLocked(context: Context): WearConnectionState {
        val now = System.currentTimeMillis()
        return runCatching {
            // Node discovery is the transport truth for a paired/reachable
            // Wear node. Capability discovery is useful for feature lookup,
            // but it is not a reliable substitute for the node that will
            // receive SESSION_START and PPG messages.
            val nodes = Wearable.getNodeClient(context).connectedNodes.awaitResult()
            if (nodes.isEmpty()) {
                pendingPingNonces.clear()
                mutableState.value = WearConnectionState(
                    status = WatchConnectionStatus.DISCONNECTED,
                    reachabilityPhase = WearReachabilityPhase.OFFLINE,
                    lastCheckedMillis = now,
                    message = "No Galaxy Watch node found"
                )
                return mutableState.value
            }

            val ids = nodes.map { it.id }
            val current = mutableState.value
            val hasFreshPong = current.isPongFresh(now) &&
                current.lastPongNodeId in ids
            mutableState.update {
                it.copy(
                    status = when {
                        it.status == WatchConnectionStatus.RECORDING -> WatchConnectionStatus.RECORDING
                        hasFreshPong -> WatchConnectionStatus.APP_REACHABLE
                        else -> WatchConnectionStatus.NODE_ONLY
                    },
                    reachabilityPhase = when {
                        it.reachabilityPhase == WearReachabilityPhase.STREAMING -> WearReachabilityPhase.STREAMING
                        hasFreshPong -> WearReachabilityPhase.APP_REACHABLE
                        else -> WearReachabilityPhase.NODE_DISCOVERED
                    },
                    nodeCount = nodes.size,
                    lastCheckedMillis = now,
                    lastNodeIds = ids,
                    message = if (hasFreshPong) "Watch app reachable" else "Watch paired; waiting for app response"
                )
            }

            var failed = 0
            pendingPingNonces.retainNodes(ids.toSet())
            val pingRequests = nodes.mapNotNull { node ->
                pendingPingNonces.prepare(node.id, now)?.let { nonce -> node.id to nonce }
            }
            pingRequests.forEach { (nodeId, nonce) ->
                val payload = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(nonce).array()
                runCatching {
                    Wearable.getMessageClient(context)
                        .sendMessage(nodeId, MessagePath.CONNECTION_PING, payload)
                        .awaitResult()
                }.onFailure {
                    failed += 1
                    pendingPingNonces.discardIfMatches(nodeId, nonce)
                }
            }
            if (pingRequests.isNotEmpty() && failed == pingRequests.size) {
                mutableState.update {
                    it.copy(
                        status = WatchConnectionStatus.NODE_ONLY,
                        reachabilityPhase = WearReachabilityPhase.NODE_DISCOVERED,
                        message = "Watch ditemukan, tetapi aplikasi Watch belum merespons"
                    )
                }
            }
            mutableState.value
        }.getOrElse { error ->
            mutableState.value = WearConnectionState(
                status = WatchConnectionStatus.ERROR,
                reachabilityPhase = WearReachabilityPhase.OFFLINE,
                lastCheckedMillis = now,
                message = "Watch connection check failed: ${error.message}"
            )
            mutableState.value
        }
    }

    override suspend fun waitForReachable(context: Context, timeoutMillis: Long): WearConnectionState {
        val previousPongMillis = state.value.lastPongMillis
        val refreshed = refreshAndPing(context)
        val result = if (
            refreshed.reachabilityPhase == WearReachabilityPhase.OFFLINE ||
            refreshed.reachabilityPhase == WearReachabilityPhase.PROTOCOL_ERROR
        ) {
            refreshed
        } else {
            val reached = withTimeoutOrNull(timeoutMillis.coerceAtLeast(MIN_REACHABILITY_WAIT_MILLIS)) {
                state.filter { candidate ->
                    candidate.lastPongMillis > previousPongMillis &&
                        candidate.canStartSession()
                }.first()
            }
            reached ?: markReachabilityTimeout()
        }
        return result
    }

    private fun markReachabilityTimeout(): WearConnectionState {
        mutableState.update { current ->
            if (current.canStartSession()) {
                current
            } else {
                current.copy(
                    status = if (current.nodeCount > 0) {
                        WatchConnectionStatus.NODE_ONLY
                    } else {
                        WatchConnectionStatus.DISCONNECTED
                    },
                    reachabilityPhase = if (current.nodeCount > 0) {
                        WearReachabilityPhase.NODE_DISCOVERED
                    } else {
                        WearReachabilityPhase.OFFLINE
                    },
                    message = if (current.nodeCount > 0) {
                        "Watch ditemukan, tetapi aplikasi Watch belum merespons"
                    } else {
                        "Tidak ada koneksi Watch"
                    }
                )
            }
        }
        return state.value
    }

    override fun markPong(nodeId: String) {
        val isCorrelated = pendingPingNonces.consumeAny(nodeId)
        if (!isCorrelated) return
        recordCorrelatedPong(nodeId)
    }

    private fun recordCorrelatedPong(nodeId: String) {
        val now = System.currentTimeMillis()
        mutableState.update {
            it.copy(
                status = if (it.status == WatchConnectionStatus.RECORDING) {
                    WatchConnectionStatus.RECORDING
                } else {
                    WatchConnectionStatus.APP_REACHABLE
                },
                reachabilityPhase = if (it.reachabilityPhase == WearReachabilityPhase.STREAMING) {
                    WearReachabilityPhase.STREAMING
                } else {
                    WearReachabilityPhase.APP_REACHABLE
                },
                nodeCount = it.nodeCount.coerceAtLeast(1),
                lastCheckedMillis = now,
                lastPongMillis = now,
                lastPongNodeId = nodeId,
                lastNodeIds = if (nodeId in it.lastNodeIds) it.lastNodeIds else it.lastNodeIds + nodeId,
                message = "Watch app reachable"
            )
        }
    }

    override fun markPong(nodeId: String, payload: ByteArray) {
        if (payload.size != Long.SIZE_BYTES) return
        val receivedNonce = ByteBuffer.wrap(payload).long
        val isCorrelated = pendingPingNonces.consumeIfMatches(nodeId, receivedNonce)
        if (!isCorrelated) return
        recordCorrelatedPong(nodeId)
    }

    override fun markStreamingEvidence(nodeId: String) {
        val now = System.currentTimeMillis()
        mutableState.update {
            val knownNode = nodeId in it.lastNodeIds || it.lastNodeIds.isEmpty()
            if (!knownNode) return@update it
            it.copy(
                status = WatchConnectionStatus.RECORDING,
                reachabilityPhase = WearReachabilityPhase.STREAMING,
                nodeCount = it.nodeCount.coerceAtLeast(1),
                lastCheckedMillis = now,
                lastStreamingEvidenceMillis = now,
                lastStreamingNodeId = nodeId,
                message = "Watch PPG stream active"
            )
        }
    }

    override fun markRecording(recording: Boolean) {
        mutableState.update {
            val nextStatus = when {
                recording && it.commandNodeId != null -> WatchConnectionStatus.RECORDING
                !recording && it.status == WatchConnectionStatus.RECORDING -> {
                    if (it.isPongFresh(System.currentTimeMillis())) {
                        WatchConnectionStatus.APP_REACHABLE
                    } else {
                        WatchConnectionStatus.NODE_ONLY
                    }
                }
                else -> it.status
            }
            it.copy(
                status = nextStatus,
                reachabilityPhase = when {
                    recording && it.canStartSession() -> WearReachabilityPhase.APP_REACHABLE
                    !recording && it.reachabilityPhase == WearReachabilityPhase.STREAMING -> WearReachabilityPhase.DRAINING
                    else -> it.reachabilityPhase
                },
                message = when {
                    recording && it.canStartSession() -> "Watch session dimulai; menunggu batch PPG"
                    !recording && it.reachabilityPhase == WearReachabilityPhase.STREAMING -> "Watch session draining"
                    else -> it.message
                }
            )
        }
    }
}
