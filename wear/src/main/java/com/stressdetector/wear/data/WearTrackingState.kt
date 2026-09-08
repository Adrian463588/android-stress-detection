package com.stressdetector.wear.data

import com.stressdetector.shared.PpgChannelTelemetry
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SessionConfig
import com.stressdetector.shared.WearSessionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Single immutable state source for truthful Wear connection and transport UI. */
object WearTrackingState {
    private val mutableState = MutableStateFlow(WearUiState())
    val state: StateFlow<WearUiState> = mutableState

    fun setHealthSdkConnecting(message: String = "Connecting to Samsung Health SDK") {
        mutableState.update {
            it.copy(
                isConnectedToSamsung = false,
                healthSdkState = HealthSdkState.SDK_CONNECTING,
                healthSdkMessage = message,
                healthSdkResolutionAvailable = false,
                lastMessage = message
            )
        }
    }

    fun setHealthSdkPermissionRequired(message: String = "Samsung Health sensor permission required") {
        mutableState.update {
            it.copy(
                isConnectedToSamsung = false,
                healthSdkState = HealthSdkState.SDK_PERMISSION_REQUIRED,
                healthSdkMessage = message,
                healthSdkResolutionAvailable = false,
                lastMessage = message
            )
        }
    }

    /** Called only after HealthTrackingService and at least one tracker exist. */
    fun setHealthSdkReady(message: String = "Samsung Health SDK ready") {
        mutableState.update {
            it.copy(
                isConnectedToSamsung = true,
                healthSdkState = HealthSdkState.SDK_READY,
                healthSdkMessage = message,
                healthSdkResolutionAvailable = false,
                lastMessage = if (it.connectionPhase == WearConnectionPhase.FAILED ||
                    it.connectionPhase == WearConnectionPhase.COMPLETE
                ) {
                    it.lastMessage
                } else {
                    message
                }
            )
        }
    }

    fun setHealthSdkFailure(message: String, resolutionAvailable: Boolean = false) {
        mutableState.update {
            it.copy(
                isConnectedToSamsung = false,
                healthSdkState = if (resolutionAvailable) {
                    HealthSdkState.SDK_RESOLUTION_REQUIRED
                } else {
                    HealthSdkState.SDK_UNAVAILABLE
                },
                healthSdkMessage = message,
                healthSdkResolutionAvailable = resolutionAvailable,
                lastMessage = message
            )
        }
    }

    fun setHealthSdkDisconnected(message: String = "Samsung Health SDK disconnected") {
        mutableState.update {
            it.copy(
                isConnectedToSamsung = false,
                healthSdkState = HealthSdkState.SDK_DISCONNECTED,
                healthSdkMessage = message,
                healthSdkResolutionAvailable = false
            )
        }
    }

    fun beginSession(sessionId: String, mode: WearSessionMode, sourceNodeId: String? = null) {
        val previous = mutableState.value
        val phoneNodeKnown = previous.isPhoneNodeDiscovered || !sourceNodeId.isNullOrBlank()
        // The source node is discovery/command evidence only. A correlated
        // pong, command delivery, or direct batch send establishes reachability.
        // Keeping this false avoids a misleading online state during startup.
        val phoneReachable = previous.isPhoneReachable
        mutableState.value = WearUiState(
            isTracking = true,
            isConnectedToSamsung = previous.isConnectedToSamsung,
            healthSdkState = previous.healthSdkState,
            healthSdkMessage = previous.healthSdkMessage,
            healthSdkResolutionAvailable = previous.healthSdkResolutionAvailable,
            sessionId = sessionId,
            sessionMode = mode,
            connectionPhase = if (phoneReachable) {
                WearConnectionPhase.PHONE_REACHABLE
            } else {
                WearConnectionPhase.DISCOVERING
            },
            isPhoneNodeDiscovered = phoneNodeKnown,
            isPhoneReachable = phoneReachable,
            lastPhoneSeenMillis = if (phoneReachable) System.currentTimeMillis() else previous.lastPhoneSeenMillis,
            lastMessage = if (phoneReachable) {
                "Waiting for Samsung sensor"
            } else {
                "Searching for phone..."
            }
        )
    }

    fun setTracking(
        value: Boolean,
        sessionId: String? = mutableState.value.sessionId,
        mode: WearSessionMode? = mutableState.value.sessionMode
    ) {
        if (value && !sessionId.isNullOrBlank() && mode != null) {
            beginSession(sessionId, mode)
            return
        }
        mutableState.update {
            it.copy(
                isTracking = false,
                sessionMode = null,
                connectionPhase = when (it.connectionPhase) {
                    WearConnectionPhase.COMPLETE, WearConnectionPhase.FAILED -> it.connectionPhase
                    else -> WearConnectionPhase.IDLE
                }
            )
        }
    }

    fun setPhoneNodeDiscovered(value: Boolean, message: String) {
        mutableState.update {
            val reachabilityEvidence = it.isPhoneReachable
            it.copy(
                isPhoneNodeDiscovered = value,
                // Capability changes are discovery evidence only. They must
                // not erase a correlated ping/SESSION_START while a session
                // is active; the ping owner is the only reachability writer.
                isPhoneReachable = if (value || it.isTracking) it.isPhoneReachable else false,
                connectionPhase = if (!value &&
                    it.isTracking &&
                    !reachabilityEvidence &&
                    it.connectionPhase != WearConnectionPhase.FAILED &&
                    it.connectionPhase != WearConnectionPhase.COMPLETE
                ) {
                    WearConnectionPhase.DISCOVERING
                } else {
                    it.connectionPhase
                },
                lastMessage = if (it.connectionPhase == WearConnectionPhase.FAILED ||
                    it.connectionPhase == WearConnectionPhase.COMPLETE ||
                    (it.isTracking && reachabilityEvidence && !value)
                ) {
                    it.lastMessage
                } else if (value && !it.isTracking) {
                    message
                } else {
                    it.lastMessage
                }
            )
        }
    }

    /** True only after a correlated ping, command, or direct batch—not capability discovery alone. */
    fun setPhoneReachable(value: Boolean, message: String) {
        mutableState.update {
            it.copy(
                isPhoneNodeDiscovered = it.isPhoneNodeDiscovered || value,
                isPhoneReachable = value,
                lastPhoneSeenMillis = if (value) System.currentTimeMillis() else it.lastPhoneSeenMillis,
                connectionPhase = when {
                    !it.isTracking -> it.connectionPhase
                    value &&
                        it.isConnectedToSamsung &&
                        it.sensorReady &&
                        it.hasInferenceSamples -> WearConnectionPhase.STREAMING
                    value && it.isConnectedToSamsung && it.sensorReady -> WearConnectionPhase.SENSOR_READY
                    value && it.isConnectedToSamsung -> WearConnectionPhase.PHONE_REACHABLE
                    value && it.connectionPhase == WearConnectionPhase.DISCOVERING -> WearConnectionPhase.PHONE_REACHABLE
                    !value &&
                        it.connectionPhase in setOf(
                            WearConnectionPhase.PHONE_REACHABLE,
                            WearConnectionPhase.SENSOR_READY
                        ) -> WearConnectionPhase.DISCOVERING
                    else -> it.connectionPhase
                },
                lastMessage = if (it.connectionPhase == WearConnectionPhase.FAILED || it.connectionPhase == WearConnectionPhase.COMPLETE) {
                    it.lastMessage
                } else {
                    message
                }
            )
        }
    }

    fun setSamsungConnected(value: Boolean, failureMessage: String? = null) {
        mutableState.update {
            val next = it.copy(
                isConnectedToSamsung = value,
                healthSdkState = when {
                    value -> HealthSdkState.SDK_READY
                    it.healthSdkState == HealthSdkState.SDK_RESOLUTION_REQUIRED ->
                        HealthSdkState.SDK_RESOLUTION_REQUIRED
                    failureMessage != null -> HealthSdkState.SDK_UNAVAILABLE
                    else -> HealthSdkState.SDK_DISCONNECTED
                },
                healthSdkMessage = failureMessage ?: it.healthSdkMessage,
                healthSdkResolutionAvailable = if (
                    it.healthSdkState == HealthSdkState.SDK_RESOLUTION_REQUIRED
                ) {
                    it.healthSdkResolutionAvailable
                } else {
                    false
                },
                samsungListenerRegistered = if (value) it.samsungListenerRegistered else false,
                greenSensorReady = if (value) it.greenSensorReady else false,
                irSensorReady = if (value) it.irSensorReady else false,
                redSensorReady = if (value) it.redSensorReady else false,
                greenSensorFailure = if (value) it.greenSensorFailure else failureMessage ?: it.greenSensorFailure,
                irSensorFailure = if (value) it.irSensorFailure else failureMessage ?: it.irSensorFailure,
                redSensorFailure = if (value) it.redSensorFailure else failureMessage ?: it.redSensorFailure,
                sensorFailure = if (value) null else failureMessage ?: it.sensorFailure
            )
            next.copy(
                connectionPhase = when {
                    it.connectionPhase == WearConnectionPhase.FAILED ||
                        it.connectionPhase == WearConnectionPhase.COMPLETE -> it.connectionPhase
                    !it.isTracking -> it.connectionPhase
                    value &&
                        it.isPhoneReachable &&
                        next.sensorReady &&
                        (it.greenSamplesSent > 0 && it.irSamplesSent > 0) -> WearConnectionPhase.STREAMING
                    value && it.isPhoneReachable && next.sensorReady -> WearConnectionPhase.SENSOR_READY
                    value && it.isPhoneReachable -> WearConnectionPhase.PHONE_REACHABLE
                    !value && it.isPhoneReachable -> WearConnectionPhase.PHONE_REACHABLE
                    !value -> WearConnectionPhase.DISCOVERING
                    else -> it.connectionPhase
                }
            )
                .copy(
                    lastMessage = if (it.connectionPhase == WearConnectionPhase.FAILED ||
                        it.connectionPhase == WearConnectionPhase.COMPLETE
                    ) {
                        it.lastMessage
                    } else {
                        failureMessage ?: if (value && !next.sensorReady) {
                            "Samsung connected; waiting for usable Green + IR samples"
                        } else {
                            it.lastMessage
                        }
                    }
                )
        }
    }

    /**
     * Records an unexpected Health SDK disconnect without allowing a normal stop callback
     * to overwrite DRAINING/COMPLETE with a failure.
     */
    fun onSamsungConnectionEnded(message: String, intentional: Boolean) {
        mutableState.update {
            if (intentional ||
                !it.isTracking ||
                it.connectionPhase in setOf(
                    WearConnectionPhase.DRAINING,
                    WearConnectionPhase.COMPLETE
                )
            ) {
                return@update it
            }
            it.copy(
                isConnectedToSamsung = false,
                healthSdkState = HealthSdkState.SDK_DISCONNECTED,
                healthSdkMessage = message,
                healthSdkResolutionAvailable = false,
                samsungListenerRegistered = false,
                greenSensorReady = false,
                irSensorReady = false,
                redSensorReady = false,
                greenSensorFailure = message,
                irSensorFailure = message,
                redSensorFailure = message,
                connectionPhase = WearConnectionPhase.FAILED,
                sensorFailure = message,
                lastMessage = message
            )
        }
    }

    fun setSamsungListenerRegistered(value: Boolean) {
        mutableState.update {
            it.copy(
                samsungListenerRegistered = value && it.isConnectedToSamsung,
                greenSensorReady = if (value) it.greenSensorReady else false,
                irSensorReady = if (value) it.irSensorReady else false,
                redSensorReady = if (value) it.redSensorReady else false,
                lastMessage = when {
                    it.connectionPhase == WearConnectionPhase.FAILED ||
                        it.connectionPhase == WearConnectionPhase.COMPLETE -> it.lastMessage
                    value -> "Samsung PPG listener registered; waiting for Green or IR data"
                    else -> "Samsung PPG listener stopped"
                }
            )
        }
    }

    fun markChannelReady(type: PpgType) {
        if (!type.isAcquisitionChannel) return
        mutableState.update {
            val next = it.copy(
                greenSensorReady = it.greenSensorReady || type == PpgType.GREEN,
                irSensorReady = it.irSensorReady || type == PpgType.IR,
                redSensorReady = it.redSensorReady || type == PpgType.RED,
                greenSensorFailure = if (type == PpgType.GREEN) null else it.greenSensorFailure,
                irSensorFailure = if (type == PpgType.IR) null else it.irSensorFailure,
                redSensorFailure = if (type == PpgType.RED) null else it.redSensorFailure
            )
            next.copy(
                connectionPhase = when {
                    next.connectionPhase == WearConnectionPhase.FAILED ||
                        next.connectionPhase == WearConnectionPhase.COMPLETE -> next.connectionPhase
                    !next.isTracking -> next.connectionPhase
                    next.isPhoneReachable &&
                        next.isConnectedToSamsung &&
                        next.sensorReady &&
                        next.hasInferenceSamples -> WearConnectionPhase.STREAMING
                    next.isPhoneReachable && next.isConnectedToSamsung && next.sensorReady -> WearConnectionPhase.SENSOR_READY
                    next.isPhoneReachable -> WearConnectionPhase.PHONE_REACHABLE
                    else -> next.connectionPhase
                }
            )
        }
    }

    fun markChannelFailure(type: PpgType, message: String) {
        if (!type.isAcquisitionChannel) return
        mutableState.update {
            if (type == PpgType.RED) {
                return@update it.copy(
                    redSensorReady = false,
                    redSensorFailure = message
                )
            }
            val next = it.copy(
                greenSensorReady = if (type == PpgType.GREEN) false else it.greenSensorReady,
                irSensorReady = if (type == PpgType.IR) false else it.irSensorReady,
                redSensorReady = if (type == PpgType.RED) false else it.redSensorReady,
                greenSensorFailure = if (type == PpgType.GREEN) message else it.greenSensorFailure,
                irSensorFailure = if (type == PpgType.IR) message else it.irSensorFailure,
                redSensorFailure = if (type == PpgType.RED) message else it.redSensorFailure,
                sensorFailure = message
            )
            next.copy(
                connectionPhase = when {
                    next.connectionPhase == WearConnectionPhase.FAILED ||
                        next.connectionPhase == WearConnectionPhase.COMPLETE -> next.connectionPhase
                    next.isTracking && next.isPhoneReachable -> WearConnectionPhase.PHONE_REACHABLE
                    next.isTracking -> WearConnectionPhase.DISCOVERING
                    else -> next.connectionPhase
                },
                lastMessage = message
            )
        }
    }

    fun recordQueued(type: PpgType, count: Int) {
        if (!type.isAcquisitionChannel || count <= 0) return
        mutableState.update {
            it.copy(
                greenSamplesQueued = it.greenSamplesQueued + if (type == PpgType.GREEN) count else 0,
                irSamplesQueued = it.irSamplesQueued + if (type == PpgType.IR) count else 0,
                redSamplesQueued = it.redSamplesQueued + if (type == PpgType.RED) count else 0,
                // Queueing is local persistence only. STREAMING is truthful
                // after the phone has accepted the first batch below.
                lastMessage = "${type.assetSuffix} callback accepted: $count samples"
            )
        }
    }

    fun recordCallback(type: PpgType, sampleCount: Int, receivedAtElapsedRealtimeMillis: Long) {
        if (!type.isAcquisitionChannel || sampleCount <= 0) return
        mutableState.update { state ->
            state.copy(
                channelTelemetry = state.channelTelemetry.updated(type) { telemetry ->
                    telemetry.copy(
                        callbackCount = telemetry.callbackCount + 1L,
                        callbackSampleCount = telemetry.callbackSampleCount + sampleCount,
                        lastCallbackElapsedRealtimeMillis = receivedAtElapsedRealtimeMillis,
                        lastError = null
                    )
                }
            )
        }
    }

    fun recordAccepted(type: PpgType, statusCode: Int? = null) {
        if (!type.isAcquisitionChannel) return
        mutableState.update { state ->
            state.copy(
                channelTelemetry = state.channelTelemetry.updated(type) { telemetry ->
                    telemetry.copy(
                        acceptedSampleCount = telemetry.acceptedSampleCount + 1L,
                        lastStatusCode = statusCode ?: telemetry.lastStatusCode
                    )
                }
            )
        }
    }

    fun recordRejected(type: PpgType, reason: String, statusCode: Int? = null) {
        if (!type.isAcquisitionChannel) return
        mutableState.update { state ->
            state.copy(
                channelTelemetry = state.channelTelemetry.updated(type) { telemetry ->
                    telemetry.copy(
                        rejectedSampleCount = telemetry.rejectedSampleCount + 1L,
                        lastStatusCode = statusCode ?: telemetry.lastStatusCode,
                        invalidStatusCount = telemetry.invalidStatusCount +
                            if (statusCode != null || reason.startsWith("sensor_status_")) 1L else 0L,
                        lastError = reason
                    )
                }
            )
        }
    }

    fun recordBatchSent(type: PpgType, latencyMillis: Long) {
        if (!type.isAcquisitionChannel) return
        mutableState.update { state ->
            state.copy(
                channelTelemetry = state.channelTelemetry.updated(type) { telemetry ->
                    telemetry.copy(
                        batchCount = telemetry.batchCount + 1L,
                        lastBatchElapsedRealtimeMillis = System.nanoTime() / 1_000_000L,
                        lastBatchLatencyMillis = latencyMillis.coerceAtLeast(0L),
                        lastError = null
                    )
                }
            )
        }
    }

    fun recordTransportGap(type: PpgType, reason: String) {
        if (!type.isAcquisitionChannel) return
        mutableState.update { state ->
            state.copy(
                channelTelemetry = state.channelTelemetry.updated(type) { telemetry ->
                    telemetry.copy(
                        transportGapCount = telemetry.transportGapCount + 1L,
                        lastError = reason
                    )
                }
            )
        }
    }

    fun awaitingBatchAck(type: PpgType) {
        if (!type.isAcquisitionChannel) return
        mutableState.update { it.copy(lastMessage = "${type.assetSuffix} direct batch sent") }
    }

    fun addSent(type: PpgType, count: Int, sequenceNumber: Long) {
        if (!type.isAcquisitionChannel || count <= 0) return
        mutableState.update {
            val greenSamplesSent = it.greenSamplesSent + if (type == PpgType.GREEN) count else 0
            val irSamplesSent = it.irSamplesSent + if (type == PpgType.IR) count else 0
            val redSamplesSent = it.redSamplesSent + if (type == PpgType.RED) count else 0
            it.copy(
                greenSamplesSent = greenSamplesSent,
                irSamplesSent = irSamplesSent,
                redSamplesSent = redSamplesSent,
                greenHighestAckedSequence = if (type == PpgType.GREEN) {
                    maxOf(it.greenHighestAckedSequence, sequenceNumber)
                } else {
                    it.greenHighestAckedSequence
                },
                irHighestAckedSequence = if (type == PpgType.IR) {
                    maxOf(it.irHighestAckedSequence, sequenceNumber)
                } else {
                    it.irHighestAckedSequence
                },
                redHighestAckedSequence = if (type == PpgType.RED) {
                    maxOf(it.redHighestAckedSequence, sequenceNumber)
                } else {
                    it.redHighestAckedSequence
                },
                isPhoneNodeDiscovered = true,
                isPhoneReachable = true,
                lastPhoneSeenMillis = System.currentTimeMillis(),
                connectionPhase = if (
                    it.isTracking &&
                    it.isConnectedToSamsung &&
                    it.sensorReady &&
                    (greenSamplesSent > 0 || irSamplesSent > 0)
                ) {
                    WearConnectionPhase.STREAMING
                } else {
                    it.connectionPhase
                },
                lastMessage = "${type.assetSuffix} accepted by phone"
            )
        }
    }

    fun setDraining() {
        mutableState.update {
            it.copy(connectionPhase = WearConnectionPhase.DRAINING, lastMessage = "Stopping trackers and flushing direct PPG batches")
        }
    }

    fun transportComplete(message: String = "Direct PPG session finished; phone evidence is authoritative") {
        mutableState.update {
            it.copy(
                connectionPhase = WearConnectionPhase.COMPLETE,
                transportFailure = null,
                sensorFailure = null,
                lastMessage = message
            )
        }
    }

    fun transportFailed(message: String) {
        mutableState.update {
            it.copy(connectionPhase = WearConnectionPhase.FAILED, transportFailure = message, lastMessage = message)
        }
    }

    fun message(value: String) {
        mutableState.update { it.copy(lastMessage = value) }
    }

    fun updateElapsed(elapsedMillis: Long) {
        val progress = if (mutableState.value.sessionMode == WearSessionMode.HOME_STRESS) {
            (elapsedMillis.toFloat() / SessionConfig.SESSION_DURATION_MILLIS).coerceIn(0f, 1f)
        } else {
            0f
        }
        mutableState.update { it.copy(elapsedMillis = elapsedMillis, progress = progress) }
    }
}

private val WearUiState.sensorReady: Boolean
    get() = greenSensorReady || irSensorReady

private val WearUiState.hasInferenceSamples: Boolean
    get() = greenSamplesSent > 0 || irSamplesSent > 0

private fun Map<PpgType, PpgChannelTelemetry>.updated(
    type: PpgType,
    update: (PpgChannelTelemetry) -> PpgChannelTelemetry
): Map<PpgType, PpgChannelTelemetry> {
    val next = toMutableMap()
    next[type] = update(next[type] ?: PpgChannelTelemetry())
    return next.toMap()
}
