package com.stressdetector.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.data.ReachabilityProbeStatus
import com.stressdetector.mobile.data.WatchConnectionStatus
import com.stressdetector.mobile.data.WearReachabilityPhase
import com.stressdetector.mobile.ui.MetricRow
import com.stressdetector.mobile.ui.StatusDot

/** Presentation-only transport projection. Keeps connection truth out of composables. */
data class TransportUiState(
    val connectionStatus: WatchConnectionStatus,
    val connectionMessage: String,
    val nodeCount: Int,
    val lastNodeIds: List<String>,
    val isRecording: Boolean,
    val isStreaming: Boolean = false,
    val appReachable: Boolean = false,
    val reachabilityPhase: WearReachabilityPhase = WearReachabilityPhase.OFFLINE,
    val probeStatus: ReachabilityProbeStatus = ReachabilityProbeStatus.IDLE,
    val terminalReceiptReceived: Boolean,
    val transportComplete: Boolean,
    val transportIncomplete: Boolean,
    val transportFailureReason: String?,
    val greenSamples: Int,
    val irSamples: Int,
    val redDiagnosticSamples: Int = 0,
    val observedDataElapsedMillis: Long,
    val appliedBatchCount: Int,
    val duplicateBatchCount: Int,
    val outOfOrderBatchCount: Int,
    val missingSequenceCount: Long,
    val highestGreenSequence: Long,
    val highestIrSequence: Long,
    val highestRedDiagnosticSequence: Long = 0L,
    val modelStatus: String,
    val debugCaptureAvailable: Boolean = false,
    val debugCaptureEnabled: Boolean = false
) {
    val hasNode: Boolean
        get() = nodeCount > 0 || lastNodeIds.isNotEmpty()

    /** A terminal receipt is not required for an active stream to be connected. */
    val hasAppEvidence: Boolean
        get() = appReachable ||
            isStreaming ||
            terminalReceiptReceived ||
            transportComplete ||
            (isRecording && (appliedBatchCount > 0 || observedDataElapsedMillis > 0L))

    val statusText: String
        get() = when {
            isRecording && isStreaming -> "Watch terhubung · streaming"
            isRecording && hasAppEvidence -> "Watch terhubung · menunggu PPG"
            probeStatus == ReachabilityProbeStatus.CHECKING -> "Memeriksa koneksi"
            reachabilityPhase == WearReachabilityPhase.PROTOCOL_ERROR -> "Koneksi perlu diperbaiki"
            reachabilityPhase == WearReachabilityPhase.APP_REACHABLE && appReachable -> "Watch siap"
            reachabilityPhase == WearReachabilityPhase.APP_REACHABLE ->
                if (hasNode) "Watch ditemukan — aplikasi belum merespons" else "Watch tidak tersedia"
            reachabilityPhase == WearReachabilityPhase.NODE_DISCOVERED ->
                "Watch ditemukan — aplikasi belum merespons"
            reachabilityPhase == WearReachabilityPhase.DRAINING -> "Watch menyelesaikan sesi"
            hasAppEvidence -> "Watch siap"
            hasNode -> "Watch ditemukan — aplikasi belum merespons"
            else -> "Watch tidak tersedia"
        }
}

@Composable
fun TransportDiagnosticsCard(state: TransportUiState, modifier: Modifier = Modifier, onDebugCaptureEnabledChange: (Boolean) -> Unit = {}) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = if (expanded) "Detail transport terbuka" else "Detail transport tertutup"
                contentDescription = if (expanded) {
                    "Sembunyikan detail transport"
                } else {
                    "Buka detail transport"
                }
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            transportCardHeader(state, expanded)
            if (expanded) transportCardDetails(state, onDebugCaptureEnabledChange)
        }
    }
}

@Composable
private fun transportCardHeader(state: TransportUiState, expanded: Boolean) {
    val statusColor = transportStatusColor(state)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatusDot(color = statusColor)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Transport",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = state.statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor
            )
        }
        Text(
            text = if (expanded) "Sembunyikan" else "Diagnostik",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun transportCardDetails(state: TransportUiState, onDebugCaptureEnabledChange: (Boolean) -> Unit) {
    if (state.connectionMessage.isNotBlank()) {
        Text(
            text = state.connectionMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    MetricRow("Batches", "${state.appliedBatchCount}")
    MetricRow("Green / IR samples", "${state.greenSamples} / ${state.irSamples}")
    MetricRow("Red samples", "${state.redDiagnosticSamples} (diagnostic only)")
    MetricRow("Observed data", formatDuration(state.observedDataElapsedMillis))
    MetricRow("Duplicate / out-of-order", "${state.duplicateBatchCount} / ${state.outOfOrderBatchCount}")
    MetricRow("Missing sequences", state.missingSequenceCount.toString())
    MetricRow("Green / IR ACK", "${state.highestGreenSequence} / ${state.highestIrSequence}")
    MetricRow("Red ACK", "${state.highestRedDiagnosticSequence} (diagnostic only)")
    MetricRow("Model", state.modelStatus)
    if (state.debugCaptureAvailable) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Capture PPG Watch aktual (debug)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Tersimpan privat untuk verifikasi offline; tidak dikirim ke log.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = state.debugCaptureEnabled,
                onCheckedChange = onDebugCaptureEnabledChange
            )
        }
    }
    when {
        !state.transportFailureReason.isNullOrBlank() -> Text(
            text = "Transport error: ${state.transportFailureReason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        state.transportIncomplete -> Text(
            text = "Transport incomplete; prediction evidence remains separately visible.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
        )
    }
}

@Composable
private fun transportStatusColor(state: TransportUiState): Color = when {
    state.probeStatus == ReachabilityProbeStatus.CHECKING -> MaterialTheme.colorScheme.primary
    state.reachabilityPhase == WearReachabilityPhase.PROTOCOL_ERROR -> MaterialTheme.colorScheme.error
    state.reachabilityPhase == WearReachabilityPhase.APP_REACHABLE && state.appReachable ->
        MaterialTheme.colorScheme.primary
    state.reachabilityPhase == WearReachabilityPhase.NODE_DISCOVERED -> MaterialTheme.colorScheme.tertiary
    state.hasAppEvidence -> MaterialTheme.colorScheme.primary
    state.hasNode -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

private fun formatDuration(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    return "%02d:%02d".format(seconds / 60L, seconds % 60L)
}
