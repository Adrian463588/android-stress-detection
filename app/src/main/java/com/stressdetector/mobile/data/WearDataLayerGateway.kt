package com.stressdetector.mobile.data

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.stressdetector.shared.WearCapability

/** Small boundary around Google Play Services so connection policy stays testable. */
internal interface WearDataLayerGateway {
    suspend fun connectedNodeIds(context: Context): List<String>

    suspend fun reachableAppNodeIds(context: Context): Set<String>

    suspend fun sendMessage(context: Context, nodeId: String, path: String, payload: ByteArray)
}

internal class GoogleWearDataLayerGateway : WearDataLayerGateway {
    override suspend fun connectedNodeIds(context: Context): List<String> =
        Wearable.getNodeClient(context).connectedNodes.awaitResult().map { it.id }

    override suspend fun reachableAppNodeIds(context: Context): Set<String> = Wearable.getCapabilityClient(context)
        .getCapability(WearCapability.WATCH_APP, CapabilityClient.FILTER_REACHABLE)
        .awaitResult()
        .nodes
        .map { it.id }
        .toSet()

    override suspend fun sendMessage(context: Context, nodeId: String, path: String, payload: ByteArray) {
        Wearable.getMessageClient(context).sendMessage(nodeId, path, payload).awaitResult()
    }
}
