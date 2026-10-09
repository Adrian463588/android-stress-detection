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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            state.isLoading -> CircularProgressIndicator()
            state.errorMessage != null -> Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
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
}

@Composable
private fun SessionSummaryCard(session: StressSessionEntity, report: StressSessionReportEntity?) {
    val status = HistoryStatusMapper.map(
        finalLabel = session.finalLabel,
        errorReason = session.errorReason,
        reportStatus = report?.reportStatus,
        transportIncomplete = session.transportIncomplete
    )
    var showTechnical by rememberSaveable(session.sessionId) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatDate(session.endedAtMillis), style = MaterialTheme.typography.titleMedium)
            HistoryStatusChip(status)
            Text(
                "Kanal utama: ${session.primarySensor.displayChannel()} · Durasi ${session.durationMillis / 1_000} detik",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Hasil ini adalah dukungan keputusan, bukan diagnosis.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = { showTechnical = !showTechnical },
                modifier = Modifier.semantics { stateDescription = if (showTechnical) "Diperluas" else "Diciutkan" }
            ) { Text(if (showTechnical) "Sembunyikan detail teknis" else "Detail teknis") }
            if (showTechnical) {
                Text("ID sesi: ${session.sessionId}")
                Text("Probabilitas model: ${session.stressProbability.asPercentOrUnavailable()}")
                Text("Kualitas: ${session.qualityTier}")
                Text("Sampel Green/IR: ${session.greenSampleCount}/${session.irSampleCount}")
                session.errorReason?.let { Text("Alasan: $it") }
                if (session.transportIncomplete) {
                    Text("Pengiriman akhir belum terkonfirmasi${session.transportFailureReason?.let { " ($it)" } ?: ""}")
                }
                if (report == null) Text("Laporan window belum tersedia")
            }
        }
    }
}

private fun String.displayChannel(): String = when (this) {
    "ppg_green" -> "Green"
    "ppg_ir" -> "IR"
    "ppg_red" -> "Red"
    else -> this
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
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sinyal PPG diproses", style = MaterialTheme.typography.titleMedium)
            Text("Segmen analisis 30–270 detik · hanya sampel yang diterima")
            when {
                artifact == null -> Text("Grafik sesi tidak tersedia; artefak belum tersimpan.")
                artifact.series.isEmpty() -> Text("Grafik tidak tersedia: ${artifact.unavailableReason ?: "cleaned core tidak tersedia"}.")
                else -> {
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.semantics { stateDescription = if (expanded) "Diperluas" else "Diciutkan" }
                    ) { Text(if (expanded) "Sembunyikan grafik" else "Lihat grafik") }
                    if (expanded) artifact.series.sortedBy { it.channel.assetSuffix }.forEach { HistorySeriesChart(it) }
                }
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
        Text(series.channel.assetSuffix.displayChannel(), color = color, fontWeight = FontWeight.Bold)
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
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Analisis sinyal", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sesi ${report.sessionDurationSeconds} detik · " +
                    "segmen ${report.coreStartSeconds}–${report.coreEndSeconds} detik"
            )
            Text("Green: ${report.greenValidWindows}/${report.greenAttemptedWindows} window valid")
            Text(
                if (report.irAttemptedWindows == 0) {
                    "IR: tidak ada window yang dievaluasi"
                } else {
                    "IR: ${report.irValidWindows}/${report.irAttemptedWindows} window valid"
                }
            )
            if (report.evidenceMode == "research") {
                Text(
                    "Model riset historis · bukan diagnosis",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            } else if (report.evidenceMode == "internal") {
                Text(
                    "Model internal · validasi eksternal belum tersedia",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text("Model aplikasi · validasi eksternal belum tersedia")
            }
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics { stateDescription = if (expanded) "Diperluas" else "Diciutkan" }
            ) { Text(if (expanded) "Sembunyikan pemrosesan" else "Detail pemrosesan") }
            if (expanded) ReportTechnicalContent(report)
        }
    }
}

@Composable
private fun ReportTechnicalContent(report: StressSessionReportEntity) {
    Text("Window diharapkan: ${report.expectedWindowCount}")
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
    Text("Status analisis: ${report.reportStatus}")
    report.selectedChannel?.let { Text("Kanal terpilih: $it") }
    report.modelVersion?.let { Text("Model: $it") }
    report.preprocessingFingerprint?.let { Text("Preprocessing: $it") }
    report.rejectionReason?.let { Text("Alasan penolakan: $it") }
}

@Composable
private fun WindowEvaluationSection(windows: List<StressWindowEvaluationEntity>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Evaluasi window", style = MaterialTheme.typography.titleMedium)
        if (windows.isEmpty()) {
            Text("Belum ada evaluasi window tersimpan.")
        } else {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics { stateDescription = if (expanded) "Diperluas" else "Diciutkan" }
            ) { Text(if (expanded) "Sembunyikan ${windows.size} evaluasi" else "Lihat ${windows.size} evaluasi") }
            if (expanded) {
                windows.groupBy { it.channel }.toSortedMap().forEach { (channel, evaluations) ->
                    Text(channel.displayChannel(), fontWeight = FontWeight.Bold)
                    evaluations.sortedBy { it.windowIndex }.forEach { WindowEvaluationCard(it) }
                }
            }
        }
    }
}

@Composable
private fun WindowEvaluationCard(evaluation: StressWindowEvaluationEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Window ${evaluation.windowIndex} · ${evaluation.status}", fontWeight = FontWeight.Bold)
            Text("Rentang: ${formatElapsed(evaluation.startElapsedMillis)}–${formatElapsed(evaluation.endElapsedMillis)}")
            evaluation.label?.let { Text("Label: ${StressLabel.displayName(it)}") }
            Text("Probabilitas model: ${evaluation.stressProbability.asPercentOrUnavailable()}")
            Text("Skor internal: ${evaluation.confidence.asPercentOrUnavailable()}")
            Text("Kualitas: ${evaluation.qualityTier ?: "Tidak tersedia"} (${evaluation.qualityScore.asPercentOrUnavailable()})")
            Text("Sampel: ${evaluation.sampleCount} · interpolasi: ${evaluation.interpolatedSampleCount}")
            evaluation.rejectionReason?.let { Text("Alasan: $it") }
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
