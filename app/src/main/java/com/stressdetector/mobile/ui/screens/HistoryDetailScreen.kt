package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.data.db.StressSessionEntity
import com.stressdetector.mobile.data.db.StressSessionReportEntity
import com.stressdetector.mobile.data.db.StressWindowEvaluationEntity
import com.stressdetector.mobile.domain.history.GapAwareHistoryChartProjector
import com.stressdetector.mobile.domain.history.HistoryChartArtifact
import com.stressdetector.mobile.domain.history.HistoryChartSeries
import com.stressdetector.mobile.domain.history.HistoryResultStatus
import com.stressdetector.mobile.domain.history.HistoryStatusMapper
import com.stressdetector.mobile.ui.viewmodels.HistoryDetailUiState
import com.stressdetector.mobile.ui.viewmodels.HistoryDetailViewModel
import com.stressdetector.shared.StressLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryDetailScreen(sessionId: String, viewModel: HistoryDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(sessionId) { viewModel.open(sessionId) }

    BoxWithConstraints {
        val horizontalPadding = when {
            maxWidth >= 840.dp -> 32.dp
            maxWidth >= 600.dp -> 24.dp
            else -> 16.dp
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Laporan sesi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Button(onClick = onBack) { Text("Kembali") }
                }
            }
            item { HistoryDetailContent(state = state, onRetry = viewModel::retry) }
        }
    }
}

@Composable
private fun HistoryDetailContent(state: HistoryDetailUiState, onRetry: () -> Unit) {
    when {
        state.isLoading -> CircularProgressIndicator()
        state.errorMessage != null -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.errorMessage, color = MaterialTheme.colorScheme.onErrorContainer)
                Button(onClick = onRetry) { Text("Coba lagi") }
            }
        }
        state.session != null -> {
            SessionSummaryCard(state.session, state.report)
            HistoryChartSection(state.chartArtifact)
            if (state.report == null) {
                Card {
                    Text(
                        "Detail report tidak tersedia untuk sesi legacy.",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                ReportSummaryCard(state.report)
                WindowEvaluationSection(state.windows)
            }
        }
    }
}

@Composable
private fun SessionSummaryCard(session: StressSessionEntity, report: StressSessionReportEntity?) {
    val status = HistoryStatusMapper.map(
        finalLabel = session.finalLabel,
        errorReason = session.errorReason,
        reportStatus = report?.reportStatus,
        transportIncomplete = session.transportIncomplete
    )
    val statusLabel = when (status) {
        HistoryResultStatus.STRESS -> "Stress"
        HistoryResultStatus.NORMAL -> "Normal"
        HistoryResultStatus.INVALID -> "Tidak valid"
    }
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${formatDate(session.endedAtMillis)} · ${session.sessionId}", fontWeight = FontWeight.Bold)
            HistoryStatusChip(status)
            Text("Hasil: $statusLabel")
            Text("Primary channel: ${session.primarySensor}")
            Text("Durasi: ${session.durationMillis / 1_000}s")
            Text("Confidence: ${session.confidence.asPercentOrUnavailable()}")
            Text("Probabilitas stres: ${session.stressProbability.asPercentOrUnavailable()}")
            Text("Quality: ${session.qualityTier}")
            Text("Sample Green/IR: ${session.greenSampleCount}/${session.irSampleCount}")
            session.errorReason?.let { Text("Catatan: $it") }
            if (session.transportIncomplete) {
                Text("Transport: incomplete${session.transportFailureReason?.let { " ($it)" } ?: ""}")
            }
            if (report == null) Text("Report window: belum tersedia")
        }
    }
}

@Composable
private fun HistoryStatusChip(status: HistoryResultStatus) {
    val (label, background, foreground) = when (status) {
        HistoryResultStatus.STRESS -> Triple("Stress", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        HistoryResultStatus.NORMAL -> Triple(
            "Normal",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        HistoryResultStatus.INVALID -> Triple(
            "Invalid",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Text(
        text = label,
        modifier = Modifier
            .background(background, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        color = foreground,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun HistoryChartSection(artifact: HistoryChartArtifact?) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PPG CLEANED CORE", fontWeight = FontWeight.Bold)
            Text("Core 30–270 detik · data aktual hasil preprocessing")
            when {
                artifact == null -> Text("Grafik sesi tidak tersedia; artefak belum tersimpan.")
                artifact.series.isEmpty() -> Text("Grafik tidak tersedia: ${artifact.unavailableReason ?: "cleaned core tidak tersedia"}.")
                else -> artifact.series.sortedBy { it.channel.assetSuffix }.forEach { HistorySeriesChart(it) }
            }
        }
    }
}

@Composable
private fun HistorySeriesChart(series: HistoryChartSeries) {
    val color = if (series.channel.assetSuffix == "ppg_green") Color(0xFF00C98B) else Color(0xFF4D8DFF)
    val projector = remember { GapAwareHistoryChartProjector() }
    val density = LocalDensity.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(series.channel.assetSuffix, color = color, fontWeight = FontWeight.Bold)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val renderWidth = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
            val projection = remember(series, renderWidth) {
                projector.project(series = series, pixelWidth = renderWidth)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                ) {
                    val points = projection.segments.flatMap { it.points }
                    if (points.size < 2) return@Canvas
                    val min = points.minOf { it.value }
                    val max = points.maxOf { it.value }
                    val span = (max - min).takeIf { it > 0f && it.isFinite() } ?: 1f
                    val start = series.elapsedMillis.firstOrNull() ?: return@Canvas
                    val end = (series.elapsedMillis.lastOrNull() ?: start).coerceAtLeast(start + 1L)
                    fun x(elapsedMillis: Long): Float = ((elapsedMillis - start).toFloat() / (end - start).toFloat()) * size.width
                    fun y(value: Float): Float = size.height - ((value - min) / span).coerceIn(0f, 1f) * size.height

                    projection.segments.forEach { segment ->
                        val first = segment.points.firstOrNull() ?: return@forEach
                        val path = Path().apply { moveTo(x(first.elapsedMillis), y(first.value)) }
                        segment.points.drop(1).forEach { point ->
                            path.lineTo(x(point.elapsedMillis), y(point.value))
                        }
                        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                        segment.points.filter { it.interpolated }.forEach { point ->
                            drawCircle(
                                Color(0xFFFFC107),
                                radius = 3.dp.toPx(),
                                center = Offset(x(point.elapsedMillis), y(point.value))
                            )
                        }
                    }
                }
                Text(
                    "${series.elapsedMillis.firstOrNull()?.div(1000) ?: 0}s–${series.elapsedMillis.lastOrNull()?.div(1000) ?: 0}s · " +
                        "${projection.renderedPointCount}/${projection.sourceSampleCount} titik ditampilkan",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ReportSummaryCard(report: StressSessionReportEntity) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("CORE ANALYSIS", fontWeight = FontWeight.Bold)
            Text("Sesi ${report.sessionDurationSeconds}s · core ${report.coreStartSeconds}–${report.coreEndSeconds}s")
            Text("Window expected: ${report.expectedWindowCount}")
            Text("Green valid: ${report.greenValidWindows}/${report.greenAttemptedWindows}")
            Text("IR valid: ${report.irValidWindows}/${report.irAttemptedWindows}")
            Text(
                bpmReportLine(
                    channel = "Green",
                    meanBpm = report.greenMeanBpm,
                    medianBpm = report.greenMedianBpm,
                    observationCount = report.greenBpmObservationCount,
                    validPeakCount = report.greenValidPeakCount
                )
            )
            Text(
                bpmReportLine(
                    channel = "IR",
                    meanBpm = report.irMeanBpm,
                    medianBpm = report.irMedianBpm,
                    observationCount = report.irBpmObservationCount,
                    validPeakCount = report.irValidPeakCount
                )
            )
            Text("Status: ${report.reportStatus}")
            if (report.evidenceMode == "research") {
                Text(
                    "Model riset — development only · external validation required",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text("Evidence mode: production")
            }
            report.selectedChannel?.let { Text("Selected channel: $it") }
            report.modelVersion?.let { Text("Model: $it") }
            report.preprocessingFingerprint?.let { Text("Preprocessing: $it") }
            report.rejectionReason?.let { Text("Rejection: $it") }
        }
    }
}

@Composable
private fun WindowEvaluationSection(windows: List<StressWindowEvaluationEntity>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("WINDOW EVALUATIONS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (windows.isEmpty()) {
            Card { Text("Belum ada evaluasi window tersimpan.", modifier = Modifier.padding(16.dp)) }
        } else {
            windows.groupBy { it.channel }.toSortedMap().forEach { (channel, evaluations) ->
                Text(channel, fontWeight = FontWeight.Bold)
                evaluations.sortedBy { it.windowIndex }.forEach { WindowEvaluationCard(it) }
            }
        }
    }
}

@Composable
private fun WindowEvaluationCard(evaluation: StressWindowEvaluationEntity) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Window ${evaluation.windowIndex} · ${evaluation.status}", fontWeight = FontWeight.Bold)
            Text("Elapsed: ${formatElapsed(evaluation.startElapsedMillis)}–${formatElapsed(evaluation.endElapsedMillis)}")
            evaluation.label?.let { Text("Label: ${StressLabel.displayName(it)}") }
            Text("Stress probability: ${evaluation.stressProbability.asPercentOrUnavailable()}")
            Text("Confidence: ${evaluation.confidence.asPercentOrUnavailable()}")
            Text("Quality: ${evaluation.qualityTier ?: "Tidak tersedia"} (${evaluation.qualityScore.asPercentOrUnavailable()})")
            Text("Samples: ${evaluation.sampleCount} · interpolated: ${evaluation.interpolatedSampleCount}")
            evaluation.rejectionReason?.let { Text("Reason: $it") }
            evaluation.modelVersion?.let { Text("Model: $it") }
        }
    }
}

private fun formatDate(timeMillis: Long): String = SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.getDefault()).format(Date(timeMillis))

private fun formatElapsed(value: Long?): String = value?.let { "%.1fs".format(Locale.US, it / 1_000f) } ?: "–"

private fun Float.asPercentOrUnavailable(): String = if (isFinite() && this in 0f..1f) "${(this * 100f).toInt()}%" else "Tidak tersedia"

private fun Float?.asPercentOrUnavailable(): String = this?.asPercentOrUnavailable() ?: "Tidak tersedia"

internal fun bpmReportLine(channel: String, meanBpm: Float?, medianBpm: Float?, observationCount: Int, validPeakCount: Int): String {
    val mean = meanBpm?.takeIf(Float::isFinite)?.let { String.format(Locale.US, "%.1f", it) }
    val median = medianBpm?.takeIf(Float::isFinite)?.let { String.format(Locale.US, "%.1f", it) }
    if (mean == null || median == null || observationCount <= 0 || validPeakCount <= 0) {
        return "$channel BPM: Tidak tersedia"
    }
    return "$channel BPM: mean $mean · median $median · $observationCount observasi · $validPeakCount peak valid"
}
