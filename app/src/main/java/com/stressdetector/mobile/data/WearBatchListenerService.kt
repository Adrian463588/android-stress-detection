package com.stressdetector.mobile.data

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.stressdetector.mobile.di.AppLocator
import com.stressdetector.mobile.service.UnifiedSessionService
import com.stressdetector.shared.MessagePath
import com.stressdetector.shared.PpgBatchCodec
import com.stressdetector.shared.PpgBatchDisposition
import com.stressdetector.shared.PpgTerminalAck
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SessionCommandCodec
import com.stressdetector.shared.SessionProtocolCodec

class WearBatchListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == MessagePath.CONNECTION_PONG) {
            AppLocator.wearConnectionRepository.markPong(messageEvent.sourceNodeId, messageEvent.data)
            return
        }
        if (messageEvent.path == MessagePath.SESSION_READY) {
            if (!batchIngress.submit {
                    val ready = runCatching {
                        PpgBatchCodec.decodeSessionReady(messageEvent.data)
                    }.getOrNull()
                    if (ready == null ||
                        !AppLocator.ppgRepository.acceptSessionReady(ready, messageEvent.sourceNodeId)
                    ) {
                        AppLocator.ppgRepository.recordError("Ignored invalid or stale Watch SESSION_READY")
                    }
                }
            ) {
                AppLocator.ppgRepository.recordTransportIncomplete(
                    "Phone PPG ingestion queue full; SESSION_READY was not accepted"
                )
            }
            return
        }
        if (messageEvent.path == MessagePath.SESSION_PROTOCOL_REJECTED) {
            if (!batchIngress.submit {
                    val rejection = runCatching {
                        SessionProtocolCodec.decode(messageEvent.data)
                    }.getOrNull()
                    if (rejection == null ||
                        !AppLocator.ppgRepository.rejectProtocol(rejection, messageEvent.sourceNodeId)
                    ) {
                        AppLocator.ppgRepository.recordError("Ignored stale Watch protocol rejection")
                    }
                }
            ) {
                AppLocator.ppgRepository.recordTransportIncomplete(
                    "Phone PPG ingestion queue full; protocol rejection could not be applied"
                )
            }
            return
        }
        if (messageEvent.path == MessagePath.SESSION_STOP_REQUEST) {
            if (!batchIngress.submit {
                    handleStopRequest(messageEvent.sourceNodeId, messageEvent.data)
                }
            ) {
                AppLocator.ppgRepository.recordTransportIncomplete(
                    "Phone PPG ingestion queue full; Watch stop request was not accepted"
                )
            }
            return
        }
        if (messageEvent.path == MessagePath.PPG_TERMINAL_RECEIPT) {
            if (!batchIngress.submit {
                    handleTerminalReceipt(messageEvent.sourceNodeId, messageEvent.data)
                }
            ) {
                // Do not acknowledge a terminal receipt that could not be
                // ordered after the already queued raw batches. The Watch
                // outbox will retry it, preserving the drain barrier.
                AppLocator.ppgRepository.recordTransportIncomplete(
                    "Phone PPG ingestion queue full; terminal receipt retained on Watch for retry"
                )
            }
            return
        }

        val type = PpgType.fromPath(messageEvent.path)
        if (type == null) {
            super.onMessageReceived(messageEvent)
            return
        }

        runCatching {
            val batch = PpgBatchCodec.decode(messageEvent.data)
            require(batch.type == type) {
                "Message path ${messageEvent.path} does not match payload channel ${batch.type}"
            }
            val sourceNodeId = messageEvent.sourceNodeId
            if (!batchIngress.submit {
                    runCatching {
                        val result = AppLocator.ppgRepository.append(batch, sourceNodeId)
                        if (result.disposition == PpgBatchDisposition.APPLIED &&
                            result.acknowledgedBatches.isNotEmpty()
                        ) {
                            AppLocator.wearConnectionRepository.markStreamingEvidence(sourceNodeId)
                            if (AppLocator.ppgRepository.state.value.sessionMode ==
                                com.stressdetector.shared.WearSessionMode.VERBATIM_AROUSAL
                            ) {
                                AppLocator.verbatimPpgArchive(applicationContext)
                                    .append(result.acknowledgedBatches)
                            }
                        }
                    }.onFailure { error ->
                        AppLocator.ppgRepository.recordTransportIncomplete(
                            "Phone PPG ingestion failed: ${error.message}"
                        )
                    }
                }
            ) {
                AppLocator.ppgRepository.recordTransportIncomplete(
                    "Phone PPG ingestion queue full; batch retained on Watch for retry"
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Rejected Watch PPG path=${messageEvent.path}: ${error.message}", error)
            AppLocator.ppgRepository.recordError("Batch decode failed: ${error.message}")
        }
    }

    private fun handleStopRequest(sourceNodeId: String, payload: ByteArray) {
        val command = runCatching { SessionCommandCodec.decode(payload) }.getOrNull()
        if (command == null) {
            AppLocator.ppgRepository.recordError("Ignored invalid Watch stop request")
            return
        }
        val active = AppLocator.ppgRepository.state.value
        val rejection = SessionStopRequestValidator.rejectionReason(command, active, sourceNodeId)
        if (rejection != null) {
            AppLocator.ppgRepository.recordError("Ignored Watch stop request: $rejection")
            return
        }
        startService(
            Intent(applicationContext, UnifiedSessionService::class.java)
                .setAction(UnifiedSessionService.ACTION_STOP_SESSION)
                .putExtra(
                    UnifiedSessionService.EXTRA_COMMAND_ID,
                    "watch-stop:${command.sessionId}:${command.sessionNonce}:${command.generation}"
                )
        )
    }

    private fun handleTerminalReceipt(sourceNodeId: String, payload: ByteArray) {
        val terminalReceipt = runCatching {
            PpgBatchCodec.decodeTerminalReceipt(payload)
        }.getOrNull()
        if (terminalReceipt != null) {
            val result = AppLocator.ppgRepository.acceptTerminalReceipt(
                terminalReceipt,
                sourceNodeId
            )
            val state = AppLocator.ppgRepository.state.value
            Wearable.getMessageClient(applicationContext).sendMessage(
                sourceNodeId,
                MessagePath.PPG_TERMINAL_ACK,
                PpgBatchCodec.encodeTerminalAck(
                    PpgTerminalAck(
                        sessionId = terminalReceipt.sessionId,
                        sessionNonce = terminalReceipt.sessionNonce,
                        acceptedGreenSequence = result.acceptedGreenSequence,
                        acceptedIrSequence = result.acceptedIrSequence,
                        acceptedRedSequence = result.acceptedRedSequence,
                        complete = result.complete,
                        reason = result.reason,
                        protocolVersion = terminalReceipt.protocolVersion,
                        peerNodeId = state.peerNodeId,
                        generation = state.generation
                    )
                )
            )
            if (!result.complete) {
                AppLocator.ppgRepository.recordError("PPG transport incomplete: ${result.reason}")
            }
            return
        }

        // V1 Watch builds use the old flush payload on this path while upgrades roll out.
        runCatching { PpgBatchCodec.decodeFlushAck(payload) }
            .onSuccess { legacy ->
                AppLocator.ppgRepository.markFlushAcknowledged(
                    sessionId = legacy.sessionId,
                    flushed = legacy.flushed,
                    completed = legacy.completed,
                    elapsedMillis = legacy.elapsedMillis
                )
            }
            .onFailure {
                AppLocator.ppgRepository.recordError("Terminal receipt decode failed: ${it.message}")
            }
    }

    internal companion object {
        const val TAG = "WearBatchListener"
        const val PHONE_INGRESS_CAPACITY = 64
        val batchIngress = BoundedTaskExecutor(PHONE_INGRESS_CAPACITY, "PhonePpgIngress")

        fun awaitIngressDrain(timeoutMillis: Long): Boolean = batchIngress.awaitIdle(timeoutMillis)
    }
}
