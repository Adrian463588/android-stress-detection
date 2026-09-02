package com.stressdetector.mobile.ui.home

import com.stressdetector.mobile.MobileUiState
import com.stressdetector.mobile.data.WearConnectionState
import com.stressdetector.mobile.data.WearReachabilityPhase
import com.stressdetector.mobile.domain.CoreCoverageDiagnostics
import com.stressdetector.mobile.domain.PredictionAvailability
import com.stressdetector.mobile.domain.PredictionWindowStats
import com.stressdetector.mobile.ui.components.TransportUiState
import com.stressdetector.shared.PermissionStatus
import com.stressdetector.shared.StressPrediction

enum class HomeSessionStatus {
    PERMISSION_REQUIRED,
    PREREQUISITE_REQUIRED,
    READY,
    RECORDING,
    FINALIZING,
    RESULT_READY,
    INSUFFICIENT_SIGNAL,
    ERROR
}

sealed interface HomeEvent {
    object Start : HomeEvent
    object Stop : HomeEvent
    object RefreshConnection : HomeEvent
    object RetryFinalization : HomeEvent
}

data class HomePredictionUiState(
    val channelLabel: String,
    val prediction: StressPrediction?,
    val availability: PredictionAvailability,
    val coreDiagnostics: CoreCoverageDiagnostics? = null
) {
    val windowStats: PredictionWindowStats
        get() = when (val current = availability) {
            is PredictionAvailability.WarmingUp -> current.windowStats
            is PredictionAvailability.Ready -> current.windowStats
            is PredictionAvailability.Rejected -> current.windowStats
            is PredictionAvailability.Unavailable -> current.windowStats
            is PredictionAvailability.InferenceError -> current.windowStats
        }
}

data class HomeUiState(
    val status: HomeSessionStatus,
    val progress: Float,
    val elapsedMillis: Long,
    val greenSamples: Int,
    val irSamples: Int,
    val totalGreenSamples: Int,
    val totalIrSamples: Int,
    val connection: WearConnectionState,
    val transport: TransportUiState,
    val greenPrediction: HomePredictionUiState,
    val irPrediction: HomePredictionUiState,
    val permissions: PermissionStatus,
    val notice: String?,
    val error: String?
) {
    val canStartSession: Boolean
        get() = permissions.allGranted && connection.canStartSession()

    val connectionGuidance: String
        get() = when (connection.reachabilityPhase) {
            WearReachabilityPhase.OFFLINE ->
                "Hubungkan Watch melalui Bluetooth atau Wi-Fi, buka aplikasi Watch, lalu coba lagi."
            WearReachabilityPhase.NODE_DISCOVERED ->
                "Watch ditemukan, tetapi aplikasi Watch belum merespons. Buka aplikasi Watch lalu coba lagi."
            WearReachabilityPhase.APP_REACHABLE -> "Watch siap menerima sesi stress detection."
            WearReachabilityPhase.STREAMING -> "Sesi Watch sedang berjalan."
            WearReachabilityPhase.DRAINING -> "Watch sedang menyelesaikan data sesi."
            WearReachabilityPhase.PROTOCOL_ERROR ->
                "Koneksi Watch mengalami masalah protokol. Pastikan aplikasi Watch aktif lalu coba lagi."
        }

    val hasPrediction: Boolean
        get() = greenPrediction.prediction != null || irPrediction.prediction != null

    /**
     * Green and IR are independent research models. A disagreement is review
     * evidence, never an invitation to fuse or overwrite either channel.
     */
    val hasChannelDisagreement: Boolean
        get() = greenPrediction.prediction?.label != null &&
            irPrediction.prediction?.label != null &&
            greenPrediction.prediction.label != irPrediction.prediction.label

    companion object {
        fun from(state: MobileUiState, permissions: PermissionStatus): HomeUiState {
            val green = HomePredictionUiState(
                channelLabel = "Green",
                prediction = state.primaryPrediction,
                availability = state.primaryPredictionState,
                coreDiagnostics = state.greenCoreDiagnostics
            )
            val ir = HomePredictionUiState(
                channelLabel = "IR",
                prediction = state.irPrediction,
                availability = state.irPredictionState,
                coreDiagnostics = state.irCoreDiagnostics
            )
            val transport = TransportUiState(
                connectionStatus = state.connectionState.status,
                connectionMessage = state.connectionState.message,
                nodeCount = state.connectionState.nodeCount,
                lastNodeIds = state.connectionState.lastNodeIds,
                isRecording = state.isRecording,
                isStreaming = state.connectionState.reachabilityPhase == WearReachabilityPhase.STREAMING,
                appReachable = state.connectionState.canStartSession(),
                terminalReceiptReceived = state.terminalReceiptReceived,
                transportComplete = state.transportComplete,
                transportIncomplete = state.transportIncomplete,
                transportFailureReason = state.transportFailureReason,
                // User-facing transport telemetry is cumulative. The rolling
                // 180-second buffers are inference implementation details and
                // otherwise appear to "freeze" at their retention capacity.
                greenSamples = state.totalGreenSamples,
                irSamples = state.totalIrSamples,
                redDiagnosticSamples = state.totalRedDiagnosticSamples,
                observedDataElapsedMillis = state.observedDataElapsedMillis,
                appliedBatchCount = state.appliedBatchCount,
                duplicateBatchCount = state.duplicateBatchCount,
                outOfOrderBatchCount = state.outOfOrderBatchCount,
                missingSequenceCount = state.missingSequenceCount,
                highestGreenSequence = state.highestGreenSequence,
                highestIrSequence = state.highestIrSequence,
                highestRedDiagnosticSequence = state.highestRedDiagnosticSequence,
                modelStatus = state.primaryPrediction?.modelVersion
                    ?: state.irPrediction?.modelVersion
                    ?: "Canonical model bundle"
            )
            return HomeUiState(
                status = statusFor(state, permissions, green, ir),
                progress = state.progress,
                elapsedMillis = state.elapsedMillis,
                greenSamples = state.totalGreenSamples,
                irSamples = state.totalIrSamples,
                totalGreenSamples = state.totalGreenSamples,
                totalIrSamples = state.totalIrSamples,
                connection = state.connectionState,
                transport = transport,
                greenPrediction = green,
                irPrediction = ir,
                permissions = permissions,
                notice = state.sessionNotice,
                error = state.lastError
            )
        }

        private fun statusFor(
            state: MobileUiState,
            permissions: PermissionStatus,
            green: HomePredictionUiState,
            ir: HomePredictionUiState
        ): HomeSessionStatus = when {
            !permissions.allGranted -> HomeSessionStatus.PERMISSION_REQUIRED
            state.isRecording -> HomeSessionStatus.RECORDING
            state.sessionNotice != null -> HomeSessionStatus.FINALIZING
            state.lastError != null -> HomeSessionStatus.ERROR
            hasPrediction(green, ir) -> HomeSessionStatus.RESULT_READY
            hasInferenceFailure(green, ir) -> HomeSessionStatus.ERROR
            bothChannelsRejected(green, ir) -> HomeSessionStatus.INSUFFICIENT_SIGNAL
            !state.connectionState.canStartSession() -> HomeSessionStatus.PREREQUISITE_REQUIRED
            else -> HomeSessionStatus.READY
        }

        private fun hasPrediction(green: HomePredictionUiState, ir: HomePredictionUiState): Boolean =
            green.prediction != null || ir.prediction != null

        private fun hasInferenceFailure(green: HomePredictionUiState, ir: HomePredictionUiState): Boolean =
            green.availability is PredictionAvailability.Unavailable ||
                ir.availability is PredictionAvailability.Unavailable ||
                green.availability is PredictionAvailability.InferenceError ||
                ir.availability is PredictionAvailability.InferenceError

        private fun bothChannelsRejected(green: HomePredictionUiState, ir: HomePredictionUiState): Boolean =
            green.availability is PredictionAvailability.Rejected &&
                ir.availability is PredictionAvailability.Rejected
    }
}
