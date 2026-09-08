package com.stressdetector.mobile.data

import android.content.Context
import com.google.android.gms.wearable.Wearable
import com.stressdetector.mobile.di.AppLocator
import com.stressdetector.shared.MessagePath
import com.stressdetector.shared.SessionCommand
import com.stressdetector.shared.SessionCommandCodec
import com.stressdetector.shared.WearSessionMode

data class CommandSendResult(val nodeCount: Int, val sentCount: Int, val failedCount: Int, val errorMessage: String? = null) {
    val isSuccess: Boolean get() = sentCount > 0 && failedCount == 0
}

class WearCommandSender(private val context: Context) {
    suspend fun localNodeId(): String = Wearable.getNodeClient(context).localNode.awaitResult().id

    suspend fun startTracking(
        watchNodeId: String,
        sessionId: String,
        mode: WearSessionMode,
        sessionNonce: String = "",
        phonePeerNodeId: String,
        generation: Long
    ): CommandSendResult = send(
        watchNodeId,
        MessagePath.SESSION_START,
        SessionCommandCodec.encode(
            SessionCommand(
                sessionId = sessionId,
                mode = mode,
                sessionNonce = sessionNonce,
                peerNodeId = phonePeerNodeId,
                generation = generation
            )
        )
    )

    suspend fun stopTracking(
        watchNodeId: String,
        sessionId: String?,
        sessionNonce: String = "",
        completionExpected: Boolean = false,
        phonePeerNodeId: String = "",
        generation: Long = 0L
    ): CommandSendResult = send(
        watchNodeId,
        MessagePath.SESSION_STOP,
        sessionId?.takeIf { it.isNotBlank() }
            ?.let {
                SessionCommandCodec.encode(
                    SessionCommand(
                        sessionId = it,
                        sessionNonce = sessionNonce,
                        completionExpected = completionExpected,
                        peerNodeId = phonePeerNodeId,
                        generation = generation
                    )
                )
            }
            ?: ByteArray(0)
    )

    private suspend fun send(watchNodeId: String, path: String, payload: ByteArray): CommandSendResult = runCatching {
        require(watchNodeId.isNotBlank()) { "Watch node id must not be blank" }
        Wearable.getMessageClient(context).sendMessage(watchNodeId, path, payload).awaitResult()
        CommandSendResult(nodeCount = 1, sentCount = 1, failedCount = 0)
    }.getOrElse {
        AppLocator.ppgRepository.recordError("Command send failed: ${it.message}")
        CommandSendResult(
            nodeCount = if (watchNodeId.isBlank()) 0 else 1,
            sentCount = 0,
            failedCount = 1,
            errorMessage = it.message
        )
    }
}
