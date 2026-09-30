package com.stressdetector.mobile.data

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

interface IWearConnectionRepository {
    val state: StateFlow<WearConnectionState>

    suspend fun refreshAndPing(context: Context): WearConnectionState
    suspend fun waitForReachable(
        context: Context,
        timeoutMillis: Long = WearConnectionRepositoryImpl.REACHABILITY_TIMEOUT_MILLIS
    ): WearConnectionState
    fun markPong(nodeId: String, payload: ByteArray): PongDisposition

    /** A real accepted PPG batch is stronger streaming evidence than discovery. */
    fun markStreamingEvidence(nodeId: String) = Unit
    fun markRecording(recording: Boolean)
}
