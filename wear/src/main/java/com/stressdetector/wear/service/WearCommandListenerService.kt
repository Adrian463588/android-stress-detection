package com.stressdetector.wear.service

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.stressdetector.shared.MessagePath
import com.stressdetector.wear.data.PpgBatchSender
import com.stressdetector.wear.data.WearTrackingState

class WearCommandListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            MessagePath.CONNECTION_PING -> {
                Wearable.getMessageClient(this)
                    .sendMessage(messageEvent.sourceNodeId, MessagePath.CONNECTION_PONG, messageEvent.data)
                    .addOnSuccessListener { WearTrackingState.setPhoneReachable(true, "Correlated phone ping replied") }
                    .addOnFailureListener { WearTrackingState.setPhoneReachable(false, "Phone reply failed: ${it.message}") }
            }
            MessagePath.SESSION_START -> {
                WearTrackingState.setPhoneReachable(true, "SESSION_START received from paired phone")
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, PpgTrackingService::class.java)
                        .setAction(PpgTrackingService.ACTION_START)
                        .putExtra(PpgTrackingService.EXTRA_REPLY_NODE_ID, messageEvent.sourceNodeId)
                        .putExtra(PpgTrackingService.EXTRA_COMMAND, messageEvent.data)
                )
            }
            MessagePath.SESSION_STOP -> startService(
                Intent(this, PpgTrackingService::class.java)
                    .setAction(PpgTrackingService.ACTION_STOP)
                    .putExtra(PpgTrackingService.EXTRA_REPLY_NODE_ID, messageEvent.sourceNodeId)
                    .putExtra(PpgTrackingService.EXTRA_COMMAND, messageEvent.data)
            )
            MessagePath.PPG_ACK -> PpgBatchSender.acknowledge(this, messageEvent.sourceNodeId, messageEvent.data)
            MessagePath.PPG_TERMINAL_ACK -> PpgBatchSender.acknowledgeTerminal(
                this,
                messageEvent.sourceNodeId,
                messageEvent.data
            )
            else -> super.onMessageReceived(messageEvent)
        }
    }
}
