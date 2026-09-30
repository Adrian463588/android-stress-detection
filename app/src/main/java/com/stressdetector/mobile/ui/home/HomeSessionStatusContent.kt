package com.stressdetector.mobile.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.ui.MetricRow
import com.stressdetector.mobile.ui.StatusDot

internal data class HomeSessionStatusContent(
    val state: HomeUiState,
    val statusColor: Color,
    val isCheckingConnection: Boolean,
    val showSessionData: Boolean,
    val showLiveProgress: Boolean,
    val canStart: Boolean,
    val shouldRefreshConnection: Boolean,
    val startLabel: String,
    val hasTechnicalDetails: Boolean
)

private data class SessionActionModel(
    val state: HomeUiState,
    val isCheckingConnection: Boolean,
    val canStart: Boolean,
    val shouldRefreshConnection: Boolean,
    val startLabel: String
)

@Composable
internal fun SessionStatusCardContent(model: HomeSessionStatusContent, onEvent: (HomeEvent) -> Unit, onTechnicalDetails: () -> Unit) {
    val state = model.state
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = evidenceScopeLabel(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SessionStatusHeader(state, model.statusColor)
        if (model.showLiveProgress) {
            SessionProgress(state, model.statusColor)
        }
        if (model.showSessionData) {
            SessionResultSummary(
                green = state.greenPrediction,
                ir = state.irPrediction,
                disagreement = state.hasChannelDisagreement
            )
        } else if (model.showLiveProgress) {
            MetricRow(
                label = "Sampel diterima",
                value = "Green ${state.greenSamples} · IR ${state.irSamples}"
            )
        }
        SessionMessages(state, model.isCheckingConnection)
        SessionActionButtons(
            model = SessionActionModel(
                state = state,
                isCheckingConnection = model.isCheckingConnection,
                canStart = model.canStart,
                shouldRefreshConnection = model.shouldRefreshConnection,
                startLabel = model.startLabel
            ),
            onEvent = onEvent
        )
        if (model.hasTechnicalDetails) {
            TextButton(
                onClick = onTechnicalDetails,
                modifier = Modifier
                    .heightIn(min = HOME_MIN_TOUCH_TARGET_DP.dp)
                    .semantics {
                        role = Role.Button
                        stateDescription = "Membuka detail teknis"
                    }
            ) {
                Text("Lihat detail teknis")
            }
        }
        if (state.canRetryFinalization) {
            TextButton(
                onClick = { onEvent(HomeEvent.RetryFinalization) },
                modifier = Modifier.heightIn(min = HOME_MIN_TOUCH_TARGET_DP.dp)
            ) {
                Text("Ulangi finalisasi")
            }
        }
    }
}

@Composable
private fun SessionStatusHeader(state: HomeUiState, statusColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatusDot(color = statusColor, size = 10.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "SESSION STATUS",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = state.primaryStatusLabel,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = statusColor,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

@Composable
private fun SessionProgress(state: HomeUiState, statusColor: Color) {
    val finalization = state.finalizationProgress
    val progress = finalization?.progress ?: state.progress
    @Suppress("DEPRECATION")
    LinearProgressIndicator(
        progress = progress.coerceIn(0f, 1f),
        modifier = Modifier.fillMaxWidth().height(6.dp),
        color = statusColor,
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = finalization?.label ?: "Session time ${formatSessionElapsed(state.elapsedMillis)} / 05:00",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${(progress * 100f).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SessionMessages(state: HomeUiState, isCheckingConnection: Boolean) {
    state.notice?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
    if (state.status == HomeSessionStatus.MODEL_UNAVAILABLE ||
        state.status == HomeSessionStatus.PREREQUISITE_REQUIRED ||
        isCheckingConnection
    ) {
        Text(
            text = state.connectionGuidance,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
    state.error?.let { error ->
        Text(
            text = userFacingError(error, state),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
        )
    }
}

@Composable
private fun SessionActionButtons(model: SessionActionModel, onEvent: (HomeEvent) -> Unit) {
    val state = model.state
    when {
        state.status == HomeSessionStatus.RECORDING -> StopSessionButton { onEvent(HomeEvent.Stop) }
        state.status == HomeSessionStatus.MODEL_UNAVAILABLE -> ModelUnavailableButton(model.startLabel) {
            onEvent(HomeEvent.OpenProfile)
        }
        model.isCheckingConnection -> CheckingConnectionButton()
        model.shouldRefreshConnection -> SessionActionButton(
            label = model.startLabel,
            contentDescription = "Coba koneksi Watch",
            onClick = { onEvent(HomeEvent.RefreshConnection) }
        )
        model.canStart -> SessionActionButton(
            label = model.startLabel,
            contentDescription = model.startLabel,
            onClick = { onEvent(HomeEvent.Start) }
        )
    }
}

@Composable
private fun StopSessionButton(onStop: () -> Unit) {
    Button(
        onClick = onStop,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HOME_MIN_TOUCH_TARGET_DP.dp)
            .semantics { contentDescription = "Hentikan sesi Watch" },
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
        )
    ) { Text("Hentikan sesi") }
}

@Composable
private fun ModelUnavailableButton(label: String, onOpenProfile: () -> Unit) {
    Button(
        onClick = onOpenProfile,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HOME_MIN_TOUCH_TARGET_DP.dp)
            .semantics { contentDescription = "Buka Profile untuk memperbaiki model" },
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) { Text(label) }
}

@Composable
private fun CheckingConnectionButton() {
    Button(
        onClick = {},
        enabled = false,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HOME_MIN_TOUCH_TARGET_DP.dp)
            .semantics {
                contentDescription = "Memeriksa koneksi Watch"
                stateDescription = "Pemeriksaan koneksi sedang berlangsung"
            },
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(8.dp))
        Text("Memeriksa koneksi…")
    }
}

@Composable
private fun SessionActionButton(label: String, contentDescription: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HOME_MIN_TOUCH_TARGET_DP.dp)
            .semantics { this.contentDescription = contentDescription },
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(label)
    }
}
