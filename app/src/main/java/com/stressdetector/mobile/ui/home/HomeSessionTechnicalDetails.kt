package com.stressdetector.mobile.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.BuildConfig
import com.stressdetector.mobile.domain.CoreCoverageDiagnostics
import com.stressdetector.mobile.domain.PredictionAvailability
import com.stressdetector.mobile.signal.MotionAnnotation
import com.stressdetector.mobile.ui.MetricRow
import com.stressdetector.shared.StressLabel

private const val MILLIS_PER_SECOND = 1_000L
private const val MAX_SESSION_SECONDS = 300L
private const val SECONDS_PER_MINUTE = 60L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TechnicalDetailsSheet(state: HomeUiState, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Detail teknis",
                style = MaterialTheme.typography.titleLarge
            )
            val liveSession = state.status == HomeSessionStatus.RECORDING || state.status == HomeSessionStatus.FINALIZING
            if (liveSession || state.greenSamples > 0 || state.irSamples > 0) {
                MetricRow("Sampel diterima", "Green ${state.greenSamples} · IR ${state.irSamples}")
            }
            state.modelGate.reason?.let { reason ->
                Text(
                    text = "Model: $reason",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.hasPrediction) {
                Text(
                    text = "Hasil yang tersedia telah melewati filter, SQA, ekstraksi puncak dan model per kanal.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            val sessionEnded = state.status == HomeSessionStatus.RESULT_READY ||
                state.status == HomeSessionStatus.INSUFFICIENT_SIGNAL
            val hasMotionEvidence = state.greenMotionAnnotations.isNotEmpty() || state.irMotionAnnotations.isNotEmpty()
            if (sessionEnded || hasMotionEvidence) {
                MotionEvidenceRow("Green", state.greenMotionAnnotations)
                MotionEvidenceRow("IR", state.irMotionAnnotations)
            }
            TechnicalPredictionDetails("Green", state.greenPrediction)
            TechnicalPredictionDetails("IR", state.irPrediction)
        }
    }
}

@Composable
private fun MotionEvidenceRow(channel: String, annotations: List<MotionAnnotation>) {
    MetricRow("MADC $channel", motionEvidenceLabel(annotations))
}

internal fun motionEvidenceLabel(annotations: List<MotionAnnotation>): String {
    val available = annotations.count { it.motionScore != null }
    val gaps = annotations.count { it.motionAnnotation == "accelerometer_gap" }
    return if (annotations.isEmpty()) {
        "Tidak ada window yang dievaluasi"
    } else if (available == 0) {
        if (gaps > 0) {
            "$gaps/${annotations.size} window memiliki gap data akselerometer"
        } else {
            "Akselerometer tidak tersedia untuk window yang dievaluasi"
        }
    } else {
        "$available/${annotations.size} window memiliki anotasi gerak"
    }
}

internal fun evidenceScopeLabel(): String = if (BuildConfig.RESEARCH_MODEL_MODE) {
    "Model riset — development only · Decision-support"
} else {
    "Model internal · decision-support"
}

@Composable
private fun TechnicalPredictionDetails(label: String, state: HomePredictionUiState) {
    val availability = state.availability
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall
        )
        when (availability) {
            is PredictionAvailability.Ready -> {
                MetricRow("Label", StressLabel.displayName(availability.prediction.label))
                MetricRow("Probabilitas terkalibrasi", availability.prediction.stressProbability.asPercent())
                MetricRow("Keyakinan model", availability.prediction.confidence.asPercent())
                ModelProvenanceDetails(availability.prediction)
                MetricRow(
                    "Kualitas PPG",
                    availability.prediction.quality.tier
                )
                availability.prediction.researchDisclaimer?.let { disclaimer ->
                    Text(
                        text = disclaimer,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            is PredictionAvailability.WarmingUp -> Text(
                "Mengumpulkan PPG: ${availability.currentSamples}/${availability.requiredSamples} sample",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall
            )
            is PredictionAvailability.Rejected -> Text(
                "Sinyal tidak cukup untuk kanal ${state.channelLabel}.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            is PredictionAvailability.Unavailable -> Text(
                "Model kanal ${state.channelLabel} belum siap.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            is PredictionAvailability.InferenceError -> Text(
                "Analisis kanal ${state.channelLabel} gagal.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        val stats = state.windowStats
        if (stats.expectedWindowCount > 0) {
            MetricRow(
                "Window valid",
                "${stats.validWindowCount}/${stats.attemptedWindowCount} (target ${stats.expectedWindowCount})"
            )
        }
        TechnicalChannelDetails(label, state)
    }
}

@Composable
private fun TechnicalChannelDetails(label: String, state: HomePredictionUiState) {
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
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CoreCoverageDetails(diagnostics)
    }
}

@Composable
private fun CoreCoverageDetails(diagnostics: CoreCoverageDiagnostics) {
    val observedBounds = diagnostics.observedStartMillis?.let { start ->
        diagnostics.observedEndMillis?.let { end -> "$start–$end ms" }
    } ?: "tidak ada sampel inti"
    MetricRow("Core evidence", "$observedBounds · ${diagnostics.observedCoverageMillis} ms")
    MetricRow(
        "Cadence aktual",
        diagnostics.actualCadenceHz?.let { "${"%.1f".format(it)} Hz" } ?: "tidak tersedia"
    )
    MetricRow("Jeda PPG", "maks ${diagnostics.largestGapMillis} ms")
    MetricRow("Batch hilang", diagnostics.transportMissingSequenceCount.toString())
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
internal fun statusColor(status: HomeSessionStatus): Color = when (status) {
    HomeSessionStatus.PERMISSION_REQUIRED,
    HomeSessionStatus.MODEL_UNAVAILABLE,
    HomeSessionStatus.ERROR -> MaterialTheme.colorScheme.error
    HomeSessionStatus.PREREQUISITE_REQUIRED,
    HomeSessionStatus.INSUFFICIENT_SIGNAL -> MaterialTheme.colorScheme.tertiary
    HomeSessionStatus.RECORDING,
    HomeSessionStatus.RESULT_READY -> MaterialTheme.colorScheme.primary
    HomeSessionStatus.FINALIZING -> MaterialTheme.colorScheme.secondary
    HomeSessionStatus.READY -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal val HomeSessionStatus.displayName: String
    get() = when (this) {
        HomeSessionStatus.PERMISSION_REQUIRED -> "Izin diperlukan"
        HomeSessionStatus.MODEL_UNAVAILABLE -> "Model belum siap"
        HomeSessionStatus.PREREQUISITE_REQUIRED -> "Watch belum terhubung"
        HomeSessionStatus.READY -> "Siap"
        HomeSessionStatus.RECORDING -> "Merekam"
        HomeSessionStatus.FINALIZING -> "Menyelesaikan"
        HomeSessionStatus.RESULT_READY -> "Hasil tersedia"
        HomeSessionStatus.INSUFFICIENT_SIGNAL -> "Sinyal tidak cukup"
        HomeSessionStatus.ERROR -> "Kesalahan"
    }

internal fun formatSessionElapsed(elapsedMillis: Long): String {
    val seconds = (elapsedMillis / MILLIS_PER_SECOND).coerceIn(0L, MAX_SESSION_SECONDS)
    return "%02d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}

internal fun userFacingError(error: String, state: HomeUiState): String = when {
    error.contains("not reachable", ignoreCase = true) ||
        error.contains("No Galaxy Watch", ignoreCase = true) -> state.connectionGuidance
    error.contains("manifest", ignoreCase = true) ||
        error.contains("core", ignoreCase = true) -> "Konfigurasi data analisis belum siap."
    error.contains("save", ignoreCase = true) -> "Hasil sesi belum tersimpan. Coba simpan lagi."
    error.contains("BUSY", ignoreCase = true) ->
        "Watch sedang digunakan fitur lain. Hentikan sesi aktif terlebih dahulu."
    else -> "Sesi belum dapat dimulai. Coba lagi."
}
