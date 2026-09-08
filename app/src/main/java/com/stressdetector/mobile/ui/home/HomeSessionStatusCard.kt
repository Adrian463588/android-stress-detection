@file:Suppress("TooManyFunctions")

package com.stressdetector.mobile.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.BuildConfig
import com.stressdetector.mobile.domain.CoreCoverageDiagnostics
import com.stressdetector.mobile.domain.PredictionAvailability
import com.stressdetector.mobile.ui.AppPalette
import com.stressdetector.mobile.ui.MetricRow
import com.stressdetector.mobile.ui.PremiumCard
import com.stressdetector.mobile.ui.StatusDot
import com.stressdetector.shared.StressLabel
import com.stressdetector.shared.StressPrediction

@Composable
fun HomeSessionStatusCard(state: HomeUiState, onEvent: (HomeEvent) -> Unit, modifier: Modifier = Modifier) {
    var technicalDetailsExpanded by rememberSaveable { mutableStateOf(false) }
    val statusColor = statusColor(state.status)
    val hasTechnicalDetails = state.greenPrediction.coreDiagnostics != null ||
        state.irPrediction.coreDiagnostics != null ||
        state.greenPrediction.availability !is PredictionAvailability.WarmingUp ||
        state.irPrediction.availability !is PredictionAvailability.WarmingUp
    val showSessionData = when (state.status) {
        HomeSessionStatus.RECORDING,
        HomeSessionStatus.FINALIZING,
        HomeSessionStatus.RESULT_READY,
        HomeSessionStatus.INSUFFICIENT_SIGNAL -> true
        HomeSessionStatus.ERROR -> when {
            state.totalGreenSamples > 0 -> true
            state.totalIrSamples > 0 -> true
            state.hasPrediction -> true
            state.greenPrediction.availability !is PredictionAvailability.WarmingUp -> true
            state.irPrediction.availability !is PredictionAvailability.WarmingUp -> true
            else -> false
        }
        HomeSessionStatus.PERMISSION_REQUIRED,
        HomeSessionStatus.PREREQUISITE_REQUIRED,
        HomeSessionStatus.READY -> false
    }
    val showLiveProgress = when (state.status) {
        HomeSessionStatus.RECORDING, HomeSessionStatus.FINALIZING -> true
        else -> false
    }
    val canStart = when (state.status) {
        HomeSessionStatus.READY,
        HomeSessionStatus.RESULT_READY,
        HomeSessionStatus.INSUFFICIENT_SIGNAL -> state.canStartSession
        HomeSessionStatus.ERROR -> when {
            !state.canStartSession -> false
            state.error?.startsWith("Failed to save session") == true -> false
            else -> true
        }
        HomeSessionStatus.PERMISSION_REQUIRED,
        HomeSessionStatus.PREREQUISITE_REQUIRED,
        HomeSessionStatus.RECORDING,
        HomeSessionStatus.FINALIZING -> false
    }
    val shouldRefreshConnection = state.permissions.allGranted &&
        !state.connection.canStartSession() &&
        state.status in setOf(
            HomeSessionStatus.PREREQUISITE_REQUIRED,
            HomeSessionStatus.READY,
            HomeSessionStatus.RESULT_READY,
            HomeSessionStatus.INSUFFICIENT_SIGNAL
        )
    val startLabel = when {
        shouldRefreshConnection -> "Coba koneksi"
        state.status == HomeSessionStatus.ERROR -> "Coba lagi"
        state.status == HomeSessionStatus.RESULT_READY -> "Mulai sesi baru"
        state.status == HomeSessionStatus.INSUFFICIENT_SIGNAL -> "Coba lagi"
        else -> "Mulai deteksi"
    }

    PremiumCard(modifier = modifier, borderColor = statusColor.copy(alpha = 0.35f)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = evidenceScopeLabel(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                        text = state.status.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = statusColor,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                Text(
                    text = state.transport.statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.transport.hasAppEvidence) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (showLiveProgress) {
                @Suppress("DEPRECATION")
                LinearProgressIndicator(
                    progress = state.progress.coerceIn(0f, 1f),
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
                        text = "Session time ${formatSessionElapsed(state.elapsedMillis)} / 05:00",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${(state.progress * 100f).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showSessionData) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricRow("Green samples", state.greenSamples.toString(), Modifier.weight(1f))
                    MetricRow("IR samples", state.irSamples.toString(), Modifier.weight(1f))
                }
            }

            state.notice?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (state.status == HomeSessionStatus.PREREQUISITE_REQUIRED) {
                Text(
                    text = state.connectionGuidance,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.error?.let { error ->
                Text(
                    text = userFacingError(error, state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (state.status == HomeSessionStatus.RECORDING) {
                Button(
                    onClick = { onEvent(HomeEvent.Stop) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Stop watch session" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Hentikan sesi")
                }
            } else if (shouldRefreshConnection) {
                Button(
                    onClick = { onEvent(HomeEvent.RefreshConnection) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Coba koneksi Watch" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(startLabel)
                }
            } else if (canStart) {
                Button(
                    onClick = { onEvent(HomeEvent.Start) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = startLabel },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(startLabel)
                }
            }

            if (showSessionData) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PredictionSummary(state.greenPrediction)
                    PredictionSummary(state.irPrediction)
                    if (state.hasChannelDisagreement) {
                        Text(
                            text = "CHANNEL_DISAGREEMENT — hasil Green dan IR berbeda; perlu ditinjau.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (hasTechnicalDetails) {
                        TextButton(
                            onClick = { technicalDetailsExpanded = !technicalDetailsExpanded },
                            modifier = Modifier.semantics {
                                stateDescription = if (technicalDetailsExpanded) {
                                    "Detail teknis terbuka"
                                } else {
                                    "Detail teknis tertutup"
                                }
                            }
                        ) {
                            Text(if (technicalDetailsExpanded) "Sembunyikan detail teknis" else "Detail teknis")
                        }
                        if (technicalDetailsExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                technicalChannelDetails("Green", state.greenPrediction)
                                technicalChannelDetails("IR", state.irPrediction)
                            }
                        }
                    }
                    if (state.error?.startsWith("Failed to save session") == true) {
                        TextButton(onClick = { onEvent(HomeEvent.RetryFinalization) }) {
                            Text("Retry save")
                        }
                    }
                }
            }
        }
    }
}

internal fun evidenceScopeLabel(): String = if (BuildConfig.RESEARCH_MODEL_MODE) {
    "Model riset — development only · external validation required"
} else {
    "Model produksi — hanya bundle accepted"
}

@Composable
private fun PredictionSummary(state: HomePredictionUiState) {
    val availability = state.availability
    val accent = when (availability) {
        is PredictionAvailability.Ready -> if (availability.prediction.label == StressLabel.STRESS.value) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        }
        is PredictionAvailability.WarmingUp -> MaterialTheme.colorScheme.tertiary
        is PredictionAvailability.Rejected,
        is PredictionAvailability.Unavailable,
        is PredictionAvailability.InferenceError -> MaterialTheme.colorScheme.error
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Prediction ${state.channelLabel}",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = accent
        )
        when (availability) {
            is PredictionAvailability.Ready -> {
                ReadyPredictionTitle(availability.prediction, accent)
                ReadyPredictionMetrics(availability.prediction, state)
                availability.prediction.researchDisclaimer?.let { disclaimer ->
                    Text(
                        text = disclaimer,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            is PredictionAvailability.WarmingUp -> Text(
                "Collecting PPG: ${availability.currentSamples}/${availability.requiredSamples} samples",
                color = accent,
                style = MaterialTheme.typography.bodySmall
            )
            is PredictionAvailability.Rejected -> Text(
                "Sinyal tidak cukup untuk kanal ${state.channelLabel}.",
                color = accent,
                style = MaterialTheme.typography.bodySmall
            )
            is PredictionAvailability.Unavailable -> Text(
                "Model kanal ${state.channelLabel} belum siap.",
                color = accent,
                style = MaterialTheme.typography.bodySmall
            )
            is PredictionAvailability.InferenceError -> Text(
                "Analisis kanal ${state.channelLabel} gagal.",
                color = accent,
                style = MaterialTheme.typography.bodySmall
            )
        }
        val stats = state.windowStats
        if (stats.expectedWindowCount > 0) {
            MetricRow(
                "Valid windows",
                "${stats.validWindowCount}/${stats.attemptedWindowCount} (expected ${stats.expectedWindowCount})"
            )
        }
    }
}

@Composable
private fun technicalChannelDetails(label: String, state: HomePredictionUiState) {
    val reason = when (val availability = state.availability) {
        is PredictionAvailability.Rejected -> "rejected: ${availability.reason}"
        is PredictionAvailability.Unavailable -> "unavailable: ${availability.reason}"
        is PredictionAvailability.InferenceError -> "inference error: ${availability.reason}"
        is PredictionAvailability.WarmingUp,
        is PredictionAvailability.Ready -> null
    }
    reason?.let {
        Text(
            text = "$label: $it",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall
        )
    }
    state.coreDiagnostics?.let { diagnostics ->
        Text(
            text = "$label core",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        coreCoverageDetails(diagnostics)
    }
}

@Composable
private fun coreCoverageDetails(diagnostics: CoreCoverageDiagnostics) {
    val observedBounds = diagnostics.observedStartMillis?.let { start ->
        diagnostics.observedEndMillis?.let { end -> "$start–$end ms" }
    } ?: "tidak ada sampel inti"
    MetricRow(
        "Core evidence",
        "$observedBounds · ${diagnostics.observedCoverageMillis} ms"
    )
    MetricRow(
        "Cadence aktual",
        diagnostics.actualCadenceHz?.let { "${"%.1f".format(it)} Hz" } ?: "tidak tersedia"
    )
    MetricRow(
        "Gap transport",
        "${diagnostics.significantGapCount} · maks ${diagnostics.largestGapMillis} ms · " +
            "urutan hilang ${diagnostics.transportMissingSequenceCount}"
    )
    diagnostics.rawMinimum?.let { minimum ->
        diagnostics.rawMaximum?.let { maximum ->
            MetricRow("Raw range preview", "$minimum – $maximum")
        }
    }
    if (diagnostics.rejectionReasons.isNotEmpty()) {
        Text(
            "Alasan core: ${diagnostics.rejectionReasons.joinToString()}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun ReadyPredictionTitle(prediction: StressPrediction, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(color = accent)
        Text(
            text = StressLabel.displayName(prediction.label),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = accent
        )
    }
}

@Composable
private fun ReadyPredictionMetrics(prediction: StressPrediction, state: HomePredictionUiState) {
    MetricRow("Stress probability", prediction.stressProbability.asPercent())
    MetricRow("Confidence", prediction.confidence.asPercent())
    MetricRow("Signal quality", "${prediction.quality.qualityScore.asPercent()} — ${prediction.quality.tier}")
}

private fun statusColor(status: HomeSessionStatus): Color = when (status) {
    HomeSessionStatus.PERMISSION_REQUIRED,
    HomeSessionStatus.ERROR -> AppPalette.Error
    HomeSessionStatus.PREREQUISITE_REQUIRED -> AppPalette.Warning
    HomeSessionStatus.INSUFFICIENT_SIGNAL -> AppPalette.Warning
    HomeSessionStatus.RECORDING,
    HomeSessionStatus.RESULT_READY -> AppPalette.Primary
    HomeSessionStatus.FINALIZING -> AppPalette.Secondary
    HomeSessionStatus.READY -> AppPalette.TextSecondary
}

private val HomeSessionStatus.displayName: String
    get() = when (this) {
        HomeSessionStatus.PERMISSION_REQUIRED -> "Izin diperlukan"
        HomeSessionStatus.PREREQUISITE_REQUIRED -> "Watch belum terhubung"
        HomeSessionStatus.READY -> "Siap"
        HomeSessionStatus.RECORDING -> "Merekam"
        HomeSessionStatus.FINALIZING -> "Menyelesaikan"
        HomeSessionStatus.RESULT_READY -> "Hasil tersedia"
        HomeSessionStatus.INSUFFICIENT_SIGNAL -> "Sinyal tidak cukup"
        HomeSessionStatus.ERROR -> "Kesalahan"
    }

private fun formatSessionElapsed(elapsedMillis: Long): String {
    val seconds = (elapsedMillis / 1_000L).coerceIn(0L, 300L)
    return "%02d:%02d".format(seconds / 60L, seconds % 60L)
}

private fun Float.asPercent(): String = "${(this.coerceIn(0f, 1f) * 100f).toInt()}%"

private fun userFacingError(error: String, state: HomeUiState): String = when {
    error.contains("not reachable", ignoreCase = true) ||
        error.contains("No Galaxy Watch", ignoreCase = true) -> state.connectionGuidance
    error.contains("manifest", ignoreCase = true) ||
        error.contains("core", ignoreCase = true) -> "Konfigurasi data analisis belum siap."
    error.contains("save", ignoreCase = true) -> "Hasil sesi belum tersimpan. Coba simpan lagi."
    error.contains("BUSY", ignoreCase = true) -> "Watch sedang digunakan fitur lain. Hentikan sesi aktif terlebih dahulu."
    else -> "Sesi belum dapat dimulai. Coba lagi."
}
