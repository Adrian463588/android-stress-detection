package com.stressdetector.mobile.data

import android.content.Context
import android.util.Log
import com.stressdetector.shared.MessagePath
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal data class PingRequest(val nodeId: String, val nonce: Long)

/** Keeps Data Layer discovery and correlated ping delivery outside the repository state policy. */
internal class WearReachabilityProbe(
    private val dataLayer: WearDataLayerGateway,
    private val pendingPingNonces: WearConnectionRepositoryImpl.PendingPingRegistry,
    private val mutableState: MutableStateFlow<WearConnectionState>
) {
    suspend fun discoverAndPing(context: Context, timeoutMillis: Long): List<PingRequest> {
        val now = System.currentTimeMillis()
        return runCatching {
            val connectedIds = dataLayer.connectedNodeIds(context)
                .filter(String::isNotBlank)
                .distinct()
            when {
                connectedIds.isEmpty() -> {
                    markOffline(now)
                    emptyList()
                }
                else -> {
                    publishDiscovery(connectedIds, now)
                    if (mutableState.value.canStartSession(now)) {
                        emptyList()
                    } else {
                        pendingPingNonces.retainNodes(connectedIds.toSet())
                        val targetIds = preferredTargetIds(context, connectedIds)
                        val candidates = preparePingRequests(targetIds, now, timeoutMillis)
                        if (candidates.isNotEmpty()) {
                            mutableState.update {
                                it.copy(
                                    probeStatus = ReachabilityProbeStatus.CHECKING,
                                    message = "Memeriksa respons aplikasi Watch"
                                )
                            }
                        }
                        sendPingRequests(context, candidates)
                    }
                }
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            markProtocolError(now, error)
            emptyList()
        }
    }

    private suspend fun preferredTargetIds(context: Context, connectedIds: List<String>): List<String> {
        val capabilityIds = runCatching { dataLayer.reachableAppNodeIds(context) }.getOrDefault(emptySet())
        return connectedIds.filter { it in capabilityIds }.ifEmpty { connectedIds }
    }

    private fun publishDiscovery(connectedIds: List<String>, now: Long) {
        val current = mutableState.value
        val hasFreshPong = current.isPongFresh(now) && current.lastPongNodeId in connectedIds
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
                nodeCount = connectedIds.size,
                lastCheckedMillis = now,
                lastNodeIds = connectedIds,
                probeStatus = ReachabilityProbeStatus.IDLE,
                message = if (hasFreshPong) {
                    "Watch app reachable"
                } else {
                    "Watch ditemukan, menunggu respons aplikasi Watch"
                }
            )
        }
    }

    private fun preparePingRequests(targetIds: List<String>, now: Long, timeoutMillis: Long): List<PingRequest> =
        targetIds.mapNotNull { nodeId ->
            pendingPingNonces.prepare(nodeId, now, timeoutMillis)
                ?.let { nonce -> PingRequest(nodeId, nonce) }
        }

    private suspend fun sendPingRequests(context: Context, requests: List<PingRequest>): List<PingRequest> {
        val sent = requests.mapNotNull { request ->
            if (sendPing(context, request)) request else null
        }
        if (sent.isEmpty() && requests.isNotEmpty()) markNodeOnly()
        return sent
    }

    private suspend fun sendPing(context: Context, request: PingRequest): Boolean {
        val payload = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(request.nonce).array()
        return runCatching {
            dataLayer.sendMessage(context, request.nodeId, MessagePath.CONNECTION_PING, payload)
            log("PING_SENT")
            true
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            log("PING_SEND_FAILED error=${error::class.simpleName}", warning = true)
            pendingPingNonces.expireIfMatches(request.nodeId, request.nonce)
            false
        }
    }

    private fun log(message: String, warning: Boolean = false) {
        runCatching {
            if (warning) Log.w(TAG, message) else Log.i(TAG, message)
        }
    }

    private fun markOffline(now: Long) {
        pendingPingNonces.clear()
        mutableState.value = WearConnectionState(
            status = WatchConnectionStatus.DISCONNECTED,
            reachabilityPhase = WearReachabilityPhase.OFFLINE,
            lastCheckedMillis = now,
            message = "No Galaxy Watch node found"
        )
    }

    private fun markNodeOnly() {
        mutableState.update {
            it.copy(
                status = WatchConnectionStatus.NODE_ONLY,
                reachabilityPhase = WearReachabilityPhase.NODE_DISCOVERED,
                probeStatus = ReachabilityProbeStatus.IDLE,
                message = "Watch ditemukan, tetapi aplikasi Watch belum merespons"
            )
        }
    }

    private fun markProtocolError(now: Long, error: Throwable) {
        mutableState.value = WearConnectionState(
            status = WatchConnectionStatus.ERROR,
            reachabilityPhase = WearReachabilityPhase.PROTOCOL_ERROR,
            lastCheckedMillis = now,
            message = "Watch connection check failed: ${error.message}"
        )
    }

    private companion object {
        const val TAG = "WearConnection"
    }
}
