package com.stressdetector.mobile.ui.home

import com.stressdetector.mobile.MobileUiState
import com.stressdetector.mobile.data.ReachabilityProbeStatus
import com.stressdetector.mobile.data.WearConnectionState
import com.stressdetector.mobile.data.WearReachabilityPhase
import com.stressdetector.mobile.domain.CoreCoverageDiagnostics
import com.stressdetector.mobile.domain.ModelGateState
import com.stressdetector.mobile.domain.ModelGateStatus
import com.stressdetector.mobile.domain.PredictionAvailability
import com.stressdetector.mobile.domain.PredictionWindowStats
import com.stressdetector.mobile.domain.StressSessionFinalizationState
import com.stressdetector.mobile.ui.components.TransportUiState
import com.stressdetector.shared.PermissionStatus
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.StressPrediction

enum class HomeSessionStatus {
    PERMISSION_REQUIRED,
    MODEL_UNAVAILABLE,
    PREREQUISITE_REQUIRED,
    READY,
    RECORDING,
    FINALIZING,
    RESULT_READY,
    INSUFFICIENT_SIGNAL,
    ERROR
}

private const val SIGNAL_STALE_MILLIS = 5_000L

sealed interface HomeEvent {
    object Start : HomeEvent
    object Stop : HomeEvent
    object RefreshConnection : HomeEvent
    object RetryFinalization : HomeEvent
    object OpenProfile : HomeEvent
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
    val sessionId: String?,
    val progress: Float,
    val elapsedMillis: Long,
    val recordingSignalLabel: String,
    val canPlayWaitingGame: Boolean,
    val greenSamples: Int,
    val irSamples: Int,
    val totalGreenSamples: Int,
    val totalIrSamples: Int,
    val connection: WearConnectionState,
    val transport: TransportUiState,
    val greenPrediction: HomePredictionUiState,
    val irPrediction: HomePredictionUiState,
    val greenMotionAnnotations: List<com.stressdetector.mobile.signal.MotionAnnotation> = emptyList(),
    val irMotionAnnotations: List<com.stressdetector.mobile.signal.MotionAnnotation> = emptyList(),
    val permissions: PermissionStatus,
    val modelGate: ModelGateState,
    val notice: String?,
    val error: String?,
    val canRetryFinalization: Boolean,
    val finalizationStatus: HomeFinalizationStatus? = null
) {
    val canStartSession: Boolean
        get() = permissions.allGranted && modelGate.isReady && connection.canStartSession()

    /** Single primary status for the prerequisite card; technical detail stays below it. */
    val primaryStatusLabel: String
        get() = when {
            status == HomeSessionStatus.MODEL_UNAVAILABLE -> "Model belum siap"
            modelGate.status == ModelGateStatus.CHECKING -> "Memeriksa model…"
            connection.probeStatus == ReachabilityProbeStatus.CHECKING -> "Memeriksa koneksi…"
            status != HomeSessionStatus.PREREQUISITE_REQUIRED -> status.displayName
            else -> when (connection.reachabilityPhase) {
                WearReachabilityPhase.OFFLINE -> "Watch tidak tersedia"
                WearReachabilityPhase.NODE_DISCOVERED -> "Watch ditemukan — aplikasi belum merespons"
                WearReachabilityPhase.PROTOCOL_ERROR -> "Koneksi perlu diperbaiki"
                WearReachabilityPhase.DRAINING -> "Watch sedang menyelesaikan sesi"
                WearReachabilityPhase.STREAMING -> "Sesi Watch sedang berjalan"
                WearReachabilityPhase.APP_REACHABLE -> when {
                    connection.canStartSession() -> "Watch siap"
                    connection.nodeCount > 0 || connection.lastNodeIds.isNotEmpty() ->
                        "Watch ditemukan — aplikasi belum merespons"
                    else -> "Watch tidak tersedia"
                }
                else -> "Watch siap"
            }
        }

    val connectionGuidance: String
        get() = when {
            modelGate.status == ModelGateStatus.UNAVAILABLE ->
                "Model Android-ready belum tersedia. Buka Profile untuk memeriksa model dan checksum."
            modelGate.status == ModelGateStatus.CHECKING ->
                "Memeriksa kesiapan model Android-ready."
            connection.probeStatus == ReachabilityProbeStatus.CHECKING ->
                "Memeriksa aplikasi Watch. Tunggu sebentar atau coba lagi setelah pemeriksaan selesai."
            else -> when (connection.reachabilityPhase) {
                WearReachabilityPhase.OFFLINE ->
                    "Hubungkan Watch melalui Bluetooth atau Wi-Fi, buka aplikasi Watch, lalu coba lagi."
                WearReachabilityPhase.NODE_DISCOVERED ->
                    "Watch ditemukan, tetapi aplikasi Watch belum merespons. Buka aplikasi Watch lalu coba lagi."
                WearReachabilityPhase.APP_REACHABLE -> if (connection.canStartSession()) {
                    "Watch siap menerima sesi stress detection."
                } else {
                    "Respons aplikasi Watch sudah kedaluwarsa. Coba koneksi lagi."
                }
                WearReachabilityPhase.STREAMING -> "Sesi Watch sedang berjalan."
                WearReachabilityPhase.DRAINING -> "Watch sedang menyelesaikan data sesi."
                WearReachabilityPhase.PROTOCOL_ERROR ->
                    "Koneksi Watch mengalami masalah protokol. Pastikan aplikasi Watch aktif lalu coba lagi."
            }
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
        fun from(state: MobileUiState, permissions: PermissionStatus, nowMillis: Long = System.currentTimeMillis()): HomeUiState {
            val green = state.greenUiState()
            val ir = state.irUiState()
            val status = statusFor(state, permissions, green, ir)
            val processingFrozenSnapshot = state.finalizationState is StressSessionFinalizationState.Evaluating ||
                state.finalizationState == StressSessionFinalizationState.Persisting
            return HomeUiState(
                status = status,
                sessionId = state.sessionId,
                progress = state.progress,
                elapsedMillis = state.elapsedMillis,
                recordingSignalLabel = recordingSignalLabel(state, nowMillis),
                canPlayWaitingGame = status == HomeSessionStatus.FINALIZING &&
                    state.sessionId != null &&
                    state.finalizationSnapshotSessionId == state.sessionId &&
                    processingFrozenSnapshot,
                greenSamples = state.totalGreenSamples,
                irSamples = state.totalIrSamples,
                totalGreenSamples = state.totalGreenSamples,
                totalIrSamples = state.totalIrSamples,
                connection = state.connectionState,
                transport = state.transportUiState(),
                greenPrediction = green,
                irPrediction = ir,
                greenMotionAnnotations = state.greenMotionAnnotations,
                irMotionAnnotations = state.irMotionAnnotations,
                permissions = permissions,
                modelGate = state.modelGate,
                notice = homeNotice(state),
                error = state.lastError,
                canRetryFinalization = state.pendingPersistence ||
                    state.finalizationState is StressSessionFinalizationState.Failed ||
                    state.finalizationState is StressSessionFinalizationState.TimedOut ||
                    (state.finalizationState as? StressSessionFinalizationState.ResultReady)?.persisted == false,
                finalizationStatus = finalizationStatus(state.finalizationState)
            )
        }

        private fun MobileUiState.greenUiState() = HomePredictionUiState(
            channelLabel = "Green",
            prediction = primaryPrediction,
            availability = primaryPredictionState,
            coreDiagnostics = greenCoreDiagnostics
        )

        private fun MobileUiState.irUiState() = HomePredictionUiState(
            channelLabel = "IR",
            prediction = irPrediction,
            availability = irPredictionState,
            coreDiagnostics = irCoreDiagnostics
        )

        private fun MobileUiState.transportUiState() = TransportUiState(
            connectionStatus = connectionState.status,
            connectionMessage = connectionState.message,
            nodeCount = connectionState.nodeCount,
            lastNodeIds = connectionState.lastNodeIds,
            isRecording = isRecording,
            isStreaming = connectionState.reachabilityPhase == WearReachabilityPhase.STREAMING,
            appReachable = connectionState.canStartSession(),
            reachabilityPhase = connectionState.reachabilityPhase,
            probeStatus = connectionState.probeStatus,
            terminalReceiptReceived = terminalReceiptReceived,
            transportComplete = transportComplete,
            transportIncomplete = transportIncomplete,
            transportFailureReason = transportFailureReason,
            greenSamples = totalGreenSamples,
            irSamples = totalIrSamples,
            redDiagnosticSamples = totalRedDiagnosticSamples,
            observedDataElapsedMillis = observedDataElapsedMillis,
            appliedBatchCount = appliedBatchCount,
            duplicateBatchCount = duplicateBatchCount,
            outOfOrderBatchCount = outOfOrderBatchCount,
            missingSequenceCount = missingSequenceCount,
            highestGreenSequence = highestGreenSequence,
            highestIrSequence = highestIrSequence,
            highestRedDiagnosticSequence = highestRedDiagnosticSequence,
            modelStatus = primaryPrediction?.modelVersion
                ?: irPrediction?.modelVersion
                ?: "Canonical model bundle"
        )

        private fun statusFor(
            state: MobileUiState,
            permissions: PermissionStatus,
            green: HomePredictionUiState,
            ir: HomePredictionUiState
        ): HomeSessionStatus = when {
            !permissions.allGranted -> HomeSessionStatus.PERMISSION_REQUIRED
            state.isRecording -> HomeSessionStatus.RECORDING
            state.finalizationState.isActive -> HomeSessionStatus.FINALIZING
            state.finalizationState is StressSessionFinalizationState.InsufficientSignal ->
                HomeSessionStatus.INSUFFICIENT_SIGNAL
            state.modelGate.status == ModelGateStatus.UNAVAILABLE -> HomeSessionStatus.MODEL_UNAVAILABLE
            state.modelGate.status == ModelGateStatus.CHECKING -> HomeSessionStatus.PREREQUISITE_REQUIRED
            else -> statusAfterFinalization(state, green, ir)
        }

        private fun statusAfterFinalization(
            state: MobileUiState,
            green: HomePredictionUiState,
            ir: HomePredictionUiState
        ): HomeSessionStatus = when {
            state.finalizationState is StressSessionFinalizationState.Failed ||
                state.finalizationState is StressSessionFinalizationState.TimedOut -> HomeSessionStatus.ERROR
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

private val StressSessionFinalizationState.isActive: Boolean
    get() = this is StressSessionFinalizationState.Stopping ||
        this is StressSessionFinalizationState.Draining ||
        this is StressSessionFinalizationState.Evaluating ||
        this is StressSessionFinalizationState.Persisting

private fun homeNotice(state: MobileUiState): String? = when {
    state.finalizationState.isActive -> null
    state.pendingPersistence -> "Sesi belum tersimpan. Ulangi finalisasi."
    else -> finalizationNotice(state.finalizationState) ?: state.sessionNotice
}

private fun finalizationNotice(state: StressSessionFinalizationState): String? = when (state) {
    StressSessionFinalizationState.Idle,
    StressSessionFinalizationState.Completed -> null
    StressSessionFinalizationState.Stopping -> "Menghentikan pengambilan data Watch…"
    StressSessionFinalizationState.Draining -> "Menyelesaikan batch Watch yang sudah diterima…"
    is StressSessionFinalizationState.Evaluating -> when (state.channel) {
        null -> "Menyiapkan evaluasi sinyal…"
        else -> "Mengevaluasi ${state.channel.assetSuffix} (${state.completedWindows}/${state.totalWindows} window)…"
    }
    StressSessionFinalizationState.Persisting -> "Menyimpan hasil sesi…"
    is StressSessionFinalizationState.ResultReady -> state.warning?.let {
        "Hasil tersedia; penyimpanan belum selesai: $it"
    } ?: "Hasil sesi tersedia."
    is StressSessionFinalizationState.InsufficientSignal -> "Sinyal tidak cukup untuk menentukan hasil. Periksa detail teknis."
    is StressSessionFinalizationState.Failed -> "Finalisasi gagal: ${state.reason}"
    is StressSessionFinalizationState.TimedOut -> "Finalisasi melebihi batas waktu: ${state.stage.name}"
}

private fun recordingSignalLabel(state: MobileUiState, nowMillis: Long): String {
    val phase = state.connectionState.reachabilityPhase
    val lastBatchAge = nowMillis - state.connectionState.lastStreamingEvidenceMillis
    return when {
        !state.isRecording -> ""
        phase == WearReachabilityPhase.OFFLINE || phase == WearReachabilityPhase.PROTOCOL_ERROR ->
            "Watch terputus · PPG saat ini tidak tersedia"
        state.totalGreenSamples == 0 && state.totalIrSamples == 0 -> "Menunggu data PPG dari Watch"
        lastBatchAge !in 0L..SIGNAL_STALE_MILLIS -> "Data PPG belum diterima lagi"
        state.missingSequenceCount > 0L -> "PPG diterima · ada jeda dalam sesi"
        state.totalGreenSamples > 0 && state.totalIrSamples > 0 -> "PPG Green dan IR diterima"
        state.totalGreenSamples > 0 -> "PPG Green diterima · IR menunggu"
        else -> "PPG IR diterima · Green menunggu"
    }
}

private fun finalizationStatus(state: StressSessionFinalizationState): HomeFinalizationStatus? = when (state) {
    StressSessionFinalizationState.Idle,
    StressSessionFinalizationState.Completed,
    is StressSessionFinalizationState.ResultReady,
    is StressSessionFinalizationState.InsufficientSignal,
    is StressSessionFinalizationState.Failed,
    is StressSessionFinalizationState.TimedOut -> null
    StressSessionFinalizationState.Stopping ->
        HomeFinalizationStatus("Menghentikan Watch…")
    StressSessionFinalizationState.Draining ->
        HomeFinalizationStatus("Menyelesaikan batch Watch…")
    is StressSessionFinalizationState.Evaluating -> {
        val completed = if (state.channels.isEmpty()) {
            state.completedWindows
        } else {
            state.channels.values.sumOf { it.completedWindows }
        }
        val total = state.channels.values.sumOf { it.totalWindows }
            .takeIf { it > 0 } ?: state.totalWindows
        HomeFinalizationStatus(
            label = when (state.channel) {
                null -> "Memeriksa core dan menyiapkan sinyal…"
                PpgType.GREEN -> "Memeriksa kualitas dan model Green…"
                PpgType.IR -> "Memeriksa kualitas dan model IR…"
                PpgType.RED -> "Memeriksa kualitas dan model Red…"
            },
            completedWindows = completed.takeIf { total > 0 },
            totalWindows = total.takeIf { it > 0 }
        )
    }
    StressSessionFinalizationState.Persisting ->
        HomeFinalizationStatus("Menyimpan hasil sesi…")
}

data class HomeFinalizationStatus(val label: String, val completedWindows: Int? = null, val totalWindows: Int? = null)
