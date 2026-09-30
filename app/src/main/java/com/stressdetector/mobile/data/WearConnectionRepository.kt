package com.stressdetector.mobile.data

import android.content.Context
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

enum class ReachabilityProbeStatus {
    IDLE,
    CHECKING
}

enum class PongDisposition {
    ACCEPTED,
    MALFORMED_PAYLOAD,
    UNKNOWN_NONCE,
    EXPIRED_NONCE
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
    val probeStatus: ReachabilityProbeStatus = ReachabilityProbeStatus.IDLE,
    val message: String = "Watch not connected"
) {
    val isProbeInFlight: Boolean
        get() = probeStatus == ReachabilityProbeStatus.CHECKING

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

    fun isPongFresh(nowMillis: Long, freshnessMillis: Long = WearConnectionRepositoryImpl.PONG_FRESHNESS_MILLIS): Boolean {
        val ageMillis = nowMillis - lastPongMillis
        return lastPongMillis > 0L && ageMillis in 0L..freshnessMillis
    }

    /**
     * Session start requires current application evidence, not node discovery.
     * Streaming and draining are active-session phases and cannot authorize a
     * new Home session.
     */
    fun canStartSession(nowMillis: Long = System.currentTimeMillis()): Boolean = reachabilityPhase ==
        WearReachabilityPhase.APP_REACHABLE &&
        probeStatus == ReachabilityProbeStatus.IDLE &&
        commandNodeId != null &&
        lastPongNodeId == commandNodeId &&
        isPongFresh(nowMillis)
}

class WearConnectionRepositoryImpl internal constructor(private val dataLayer: WearDataLayerGateway = GoogleWearDataLayerGateway()) :
    IWearConnectionRepository {
    companion object {
        const val PONG_FRESHNESS_MILLIS = 15_000L
        const val REACHABILITY_TIMEOUT_MILLIS = 4_000L
        private const val MIN_REACHABILITY_WAIT_MILLIS = 250L
        private const val PING_NONCE_LEASE_MILLIS = 15_000L
    }

    internal data class PendingPing(val nonce: Long, val issuedAtMillis: Long, val expiresAtMillis: Long)

    /** Thread-safe nonce owner; a probe must not replace an active ping lease. */
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
        fun prepare(nodeId: String, nowMillis: Long, validityMillis: Long = leaseMillis): Long? {
            val pending = entries[nodeId]
            if (pending != null && nowMillis < pending.expiresAtMillis) return null
            val nonce = nonceFactory()
            entries[nodeId] = PendingPing(
                nonce = nonce,
                issuedAtMillis = nowMillis,
                expiresAtMillis = nowMillis + validityMillis.coerceAtLeast(1L)
            )
            return nonce
        }

        @Synchronized
        fun expireIfMatches(nodeId: String, nonce: Long) {
            if (entries[nodeId]?.nonce == nonce) entries.remove(nodeId)
        }

        @Synchronized
        fun consumeIfMatches(nodeId: String, nonce: Long, nowMillis: Long): PongDisposition {
            val pending = entries[nodeId]
            return when {
                pending == null -> PongDisposition.UNKNOWN_NONCE
                nowMillis >= pending.expiresAtMillis -> {
                    entries.remove(nodeId)
                    PongDisposition.EXPIRED_NONCE
                }
                pending.nonce != nonce -> PongDisposition.UNKNOWN_NONCE
                else -> {
                    entries.remove(nodeId)
                    PongDisposition.ACCEPTED
                }
            }
        }
    }

    private val nonceRandom = SecureRandom()
    private val mutableState = MutableStateFlow(WearConnectionState())
    private val pendingPingNonces = PendingPingRegistry(PING_NONCE_LEASE_MILLIS, nonceRandom::nextLong)
    private val refreshMutex = Mutex()
    private val reachabilityProbe = WearReachabilityProbe(
        dataLayer = dataLayer,
        pendingPingNonces = pendingPingNonces,
        mutableState = mutableState
    )
    override val state: StateFlow<WearConnectionState> = mutableState.asStateFlow()

    override suspend fun refreshAndPing(context: Context): WearConnectionState = probe(context, REACHABILITY_TIMEOUT_MILLIS)

    override suspend fun waitForReachable(context: Context, timeoutMillis: Long): WearConnectionState = probe(context, timeoutMillis)

    /** Runs one complete bounded probe so no caller leaves an orphaned nonce lease. */
    private suspend fun probe(context: Context, timeoutMillis: Long): WearConnectionState = refreshMutex.withLock {
        val timeout = timeoutMillis.coerceAtLeast(MIN_REACHABILITY_WAIT_MILLIS)
        val previousPongMillis = state.value.lastPongMillis
        val requests = discoverAndPing(context, timeout)
        if (requests.isEmpty()) return@withLock state.value

        val reached = withTimeoutOrNull(timeout) {
            state.filter { candidate ->
                candidate.lastPongMillis > previousPongMillis &&
                    candidate.canStartSession()
            }.first()
        }
        if (reached != null) return@withLock reached

        requests.forEach { request ->
            pendingPingNonces.expireIfMatches(request.nodeId, request.nonce)
        }
        markReachabilityTimeout()
    }

    private suspend fun discoverAndPing(context: Context, timeoutMillis: Long): List<PingRequest> =
        reachabilityProbe.discoverAndPing(context, timeoutMillis)

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
                    probeStatus = ReachabilityProbeStatus.IDLE,
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

    private fun recordCorrelatedPong(nodeId: String) {
        pendingPingNonces.clear()
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
                probeStatus = ReachabilityProbeStatus.IDLE,
                message = "Watch app reachable"
            )
        }
    }

    override fun markPong(nodeId: String, payload: ByteArray): PongDisposition {
        if (payload.size != Long.SIZE_BYTES) return PongDisposition.MALFORMED_PAYLOAD
        val receivedNonce = ByteBuffer.wrap(payload).long
        val disposition = pendingPingNonces.consumeIfMatches(
            nodeId = nodeId,
            nonce = receivedNonce,
            nowMillis = System.currentTimeMillis()
        )
        if (disposition == PongDisposition.ACCEPTED) recordCorrelatedPong(nodeId)
        return disposition
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
