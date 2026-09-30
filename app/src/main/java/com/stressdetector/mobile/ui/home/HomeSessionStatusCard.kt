package com.stressdetector.mobile.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.stressdetector.mobile.domain.ModelGateStatus
import com.stressdetector.mobile.domain.PredictionAvailability

internal const val HOME_MIN_TOUCH_TARGET_DP = 48

@Composable
fun HomeSessionStatusCard(state: HomeUiState, onEvent: (HomeEvent) -> Unit, modifier: Modifier = Modifier) {
    var technicalDetailsVisible by remember { mutableStateOf(false) }
    val statusColor = statusColor(state.status)
    val isCheckingConnection = state.connection.isProbeInFlight
    val showSessionData = state.shouldShowSessionData()
    val showLiveProgress = state.status.isProgressVisible()
    val canStart = state.canStartAction()
    val shouldRefreshConnection = state.shouldRefreshConnection(isCheckingConnection)
    val startLabel = state.startActionLabel(shouldRefreshConnection)
    val hasTechnicalDetails = state.hasTechnicalDetails()

    com.stressdetector.mobile.ui.PremiumCard(
        modifier = modifier,
        borderColor = statusColor.copy(alpha = 0.35f)
    ) {
        SessionStatusCardContent(
            model = HomeSessionStatusContent(
                state = state,
                statusColor = statusColor,
                isCheckingConnection = isCheckingConnection,
                showSessionData = showSessionData,
                showLiveProgress = showLiveProgress,
                canStart = canStart,
                shouldRefreshConnection = shouldRefreshConnection,
                startLabel = startLabel,
                hasTechnicalDetails = hasTechnicalDetails
            ),
            onEvent = onEvent,
            onTechnicalDetails = { technicalDetailsVisible = true }
        )
    }

    if (technicalDetailsVisible) {
        TechnicalDetailsSheet(
            state = state,
            onDismiss = { technicalDetailsVisible = false }
        )
    }
}

private fun HomeSessionStatus.isProgressVisible(): Boolean = this == HomeSessionStatus.RECORDING || this == HomeSessionStatus.FINALIZING

private fun HomeUiState.shouldShowSessionData(): Boolean = when (status) {
    HomeSessionStatus.RECORDING,
    HomeSessionStatus.FINALIZING,
    HomeSessionStatus.RESULT_READY,
    HomeSessionStatus.INSUFFICIENT_SIGNAL -> true
    HomeSessionStatus.ERROR ->
        totalGreenSamples > 0 ||
            totalIrSamples > 0 ||
            hasPrediction ||
            greenPrediction.availability !is PredictionAvailability.WarmingUp ||
            irPrediction.availability !is PredictionAvailability.WarmingUp
    HomeSessionStatus.PERMISSION_REQUIRED,
    HomeSessionStatus.MODEL_UNAVAILABLE,
    HomeSessionStatus.PREREQUISITE_REQUIRED,
    HomeSessionStatus.READY -> false
}

private fun HomeUiState.canStartAction(): Boolean = when (status) {
    HomeSessionStatus.READY,
    HomeSessionStatus.RESULT_READY,
    HomeSessionStatus.INSUFFICIENT_SIGNAL -> canStartSession
    HomeSessionStatus.ERROR -> canStartSession && !error.orEmpty().startsWith("Failed to save session")
    HomeSessionStatus.PERMISSION_REQUIRED,
    HomeSessionStatus.MODEL_UNAVAILABLE,
    HomeSessionStatus.PREREQUISITE_REQUIRED,
    HomeSessionStatus.RECORDING,
    HomeSessionStatus.FINALIZING -> false
}

private fun HomeUiState.shouldRefreshConnection(isCheckingConnection: Boolean): Boolean = permissions.allGranted &&
    modelGate.status == ModelGateStatus.READY &&
    !isCheckingConnection &&
    !connection.canStartSession() &&
    status in setOf(
        HomeSessionStatus.PREREQUISITE_REQUIRED,
        HomeSessionStatus.READY,
        HomeSessionStatus.RESULT_READY,
        HomeSessionStatus.INSUFFICIENT_SIGNAL
    )

private fun HomeUiState.startActionLabel(shouldRefreshConnection: Boolean): String = when {
    status == HomeSessionStatus.MODEL_UNAVAILABLE -> "Buka Profile"
    shouldRefreshConnection -> "Coba koneksi"
    status == HomeSessionStatus.ERROR -> "Coba lagi"
    status == HomeSessionStatus.RESULT_READY -> "Mulai sesi baru"
    status == HomeSessionStatus.INSUFFICIENT_SIGNAL -> "Coba lagi"
    else -> "Mulai deteksi"
}

private fun HomeUiState.hasTechnicalDetails(): Boolean = greenPrediction.coreDiagnostics != null ||
    irPrediction.coreDiagnostics != null ||
    modelGate.reason != null ||
    greenPrediction.availability !is PredictionAvailability.WarmingUp ||
    irPrediction.availability !is PredictionAvailability.WarmingUp
