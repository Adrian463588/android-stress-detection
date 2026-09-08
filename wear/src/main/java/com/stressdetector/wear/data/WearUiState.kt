package com.stressdetector.wear.data

import com.stressdetector.shared.PpgChannelTelemetry
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.WearSessionMode

enum class WearConnectionPhase {
    IDLE,
    DISCOVERING,
    PHONE_REACHABLE,
    SENSOR_READY,
    STREAMING,
    DRAINING,
    COMPLETE,
    FAILED
}

/** Explicit Samsung Health SDK lifecycle; it is separate from phone reachability. */
enum class HealthSdkState {
    SDK_DISCONNECTED,
    SDK_CONNECTING,
    SDK_PERMISSION_REQUIRED,
    SDK_RESOLUTION_REQUIRED,
    SDK_READY,
    SDK_UNAVAILABLE
}

data class WearUiState(
    val isTracking: Boolean = false,
    val isConnectedToSamsung: Boolean = false,
    val healthSdkState: HealthSdkState = HealthSdkState.SDK_DISCONNECTED,
    val healthSdkMessage: String? = null,
    val healthSdkResolutionAvailable: Boolean = false,
    /** Health SDK tracker exists and its listener registration succeeded. */
    val samsungListenerRegistered: Boolean = false,
    /** True only after the first accepted status-0 sample for each channel. */
    val greenSensorReady: Boolean = false,
    val irSensorReady: Boolean = false,
    /** Red is acquired for diagnostics and never gates inference readiness. */
    val redSensorReady: Boolean = false,
    val greenSensorFailure: String? = null,
    val irSensorFailure: String? = null,
    val redSensorFailure: String? = null,
    val sessionId: String? = null,
    val sessionMode: WearSessionMode? = null,
    val connectionPhase: WearConnectionPhase = WearConnectionPhase.IDLE,
    val elapsedMillis: Long = 0L,
    val progress: Float = 0f,
    val greenSamplesSent: Int = 0,
    val irSamplesSent: Int = 0,
    val redSamplesSent: Int = 0,
    val greenSamplesQueued: Int = 0,
    val irSamplesQueued: Int = 0,
    val redSamplesQueued: Int = 0,
    val greenHighestAckedSequence: Long = 0L,
    val irHighestAckedSequence: Long = 0L,
    val redHighestAckedSequence: Long = 0L,
    val isPhoneNodeDiscovered: Boolean = false,
    val isPhoneReachable: Boolean = false,
    val lastPhoneSeenMillis: Long = 0L,
    val channelTelemetry: Map<PpgType, PpgChannelTelemetry> = emptyMap(),
    val lastMessage: String = "Idle",
    val transportFailure: String? = null,
    val sensorFailure: String? = null
)
