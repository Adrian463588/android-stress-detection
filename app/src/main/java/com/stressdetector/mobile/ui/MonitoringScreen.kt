@file:Suppress("FunctionNaming", "LongParameterList", "LongMethod")

package com.stressdetector.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stressdetector.mobile.data.WatchConnectionStatus
import com.stressdetector.mobile.domain.PpgChartData
import com.stressdetector.mobile.domain.PpgChartMode
import com.stressdetector.mobile.ui.components.TransportDiagnosticsCard
import com.stressdetector.mobile.ui.components.TransportUiState

// ─── Color tokens ─────────────────────────────────────────────────────────────
private val NeonEmerald = Color(0xFF10B981)
private val AlertRed = Color(0xFFEF4444)
private val OceanBlue = Color(0xFF3B82F6)
private val DiagnosticRed = Color(0xFFF97316)
private val DeepSlate = Color(0xFF0F172A)
private val CharcoalSlate = Color(0xFF1E293B)
private val SlateCard = Color(0xFF263447)
private val GridLine = Color(0xFF334155)
private val WarnAmber = Color(0xFFF59E0B)
private val TextPrimary = Color(0xFFF1F5F9)
private val TextSecondary = Color(0xFF94A3B8)

// ─── Constants ────────────────────────────────────────────────────────────────
private const val SESSION_DURATION_SEC = 300

// ─── Root Screen ──────────────────────────────────────────────────────────────

@Composable
fun MonitoringScreen(
    progress: Float,
    elapsedMillis: Long,
    greenSamples: Int,
    irSamples: Int,
    redDiagnosticSamples: Int = 0,
    greenChart: PpgChartData = PpgChartData.empty(),
    irChart: PpgChartData = PpgChartData.empty(),
    redChart: PpgChartData = PpgChartData.empty("Menunggu data diagnostik"),
    connectionStatus: WatchConnectionStatus = WatchConnectionStatus.RECORDING,
    connectionPhase: String = "STREAMING",
    phoneMessage: String = "Menunggu status phone",
    terminalReceiptReceived: Boolean = false,
    transportComplete: Boolean = false,
    transportIncomplete: Boolean = false,
    transportFailureReason: String? = null,
    observedDataElapsedMillis: Long = 0L,
    appliedBatchCount: Int = 0,
    duplicateBatchCount: Int = 0,
    outOfOrderBatchCount: Int = 0,
    missingSequenceCount: Long = 0L,
    highestGreenSequence: Long = 0L,
    highestIrSequence: Long = 0L,
    highestRedDiagnosticSequence: Long = 0L,
    modelStatus: String = "Warm-up / menunggu core",
    twoColumnLayout: Boolean = false,
    onStop: () -> Unit
) {
    val honestConnectionPhase = honestConnectionPhase(connectionStatus, connectionPhase)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSlate)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MonitoringHeader(
            progress = progress,
            elapsedMillis = elapsedMillis,
            connectionPhase = honestConnectionPhase
        )
        SignalStatusCard(
            connectionPhase = honestConnectionPhase,
            modelStatus = modelStatus,
            qualityTier = greenChart.qualityTier ?: irChart.qualityTier,
            observedSamples = maxOf(greenChart.acceptedSampleCount, irChart.acceptedSampleCount)
        )
        TransportDiagnosticsCard(
            state = TransportUiState(
                connectionStatus = connectionStatus,
                connectionMessage = phoneMessage,
                nodeCount = if (connectionStatus == WatchConnectionStatus.DISCONNECTED) 0 else 1,
                lastNodeIds = emptyList(),
                isRecording = true,
                isStreaming = honestConnectionPhase == "STREAMING",
                appReachable = honestConnectionPhase == "APP_REACHABLE",
                terminalReceiptReceived = terminalReceiptReceived,
                transportComplete = transportComplete,
                transportIncomplete = transportIncomplete,
                transportFailureReason = transportFailureReason,
                greenSamples = greenSamples,
                irSamples = irSamples,
                redDiagnosticSamples = redDiagnosticSamples,
                observedDataElapsedMillis = observedDataElapsedMillis,
                appliedBatchCount = appliedBatchCount,
                duplicateBatchCount = duplicateBatchCount,
                outOfOrderBatchCount = outOfOrderBatchCount,
                missingSequenceCount = missingSequenceCount,
                highestGreenSequence = highestGreenSequence,
                highestIrSequence = highestIrSequence,
                highestRedDiagnosticSequence = highestRedDiagnosticSequence,
                modelStatus = modelStatus
            )
        )

        DualPpgChartSection(
            green = greenChart,
            ir = irChart,
            red = redChart,
            greenSamples = greenSamples,
            irSamples = irSamples,
            redSamples = redDiagnosticSamples,
            twoColumnLayout = twoColumnLayout
        )

        StopButton(onStop = onStop)
    }
}

internal fun honestConnectionPhase(status: WatchConnectionStatus, requestedPhase: String): String {
    val canonical = setOf(
        "NODE_DISCOVERED",
        "APP_REACHABLE",
        "STREAMING",
        "DRAINING",
        "OFFLINE",
        "PROTOCOL_ERROR"
    )
    if (requestedPhase in canonical) return requestedPhase
    return when (status) {
        WatchConnectionStatus.DISCONNECTED -> "OFFLINE"
        WatchConnectionStatus.NODE_ONLY -> "NODE_DISCOVERED"
        WatchConnectionStatus.APP_REACHABLE -> "APP_REACHABLE"
        WatchConnectionStatus.RECORDING -> "STREAMING"
        WatchConnectionStatus.ERROR -> "PROTOCOL_ERROR"
    }
}

/**
 * Shows both acquired channels without overlaying their amplitudes or mixing
 * their inference state. Each panel keeps its own real timestamps, quality,
 * and gap rendering.
 */
@Composable
fun DualPpgChartSection(
    green: PpgChartData,
    ir: PpgChartData,
    greenSamples: Int,
    irSamples: Int,
    red: PpgChartData = PpgChartData.empty("Menunggu data diagnostik"),
    redSamples: Int = 0,
    twoColumnLayout: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (twoColumnLayout) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LivePpgChartCard(
                    title = "PPG Green",
                    color = NeonEmerald,
                    data = green,
                    samples = greenSamples,
                    modifier = Modifier.weight(1f).height(210.dp)
                )
                LivePpgChartCard(
                    title = "PPG IR",
                    color = OceanBlue,
                    data = ir,
                    samples = irSamples,
                    modifier = Modifier.weight(1f).height(210.dp)
                )
            }
        } else {
            LivePpgChartCard(
                title = "PPG Green",
                color = NeonEmerald,
                data = green,
                samples = greenSamples,
                modifier = Modifier.fillMaxWidth().height(210.dp)
            )
            LivePpgChartCard(
                title = "PPG IR",
                color = OceanBlue,
                data = ir,
                samples = irSamples,
                modifier = Modifier.fillMaxWidth().height(210.dp)
            )
        }
        LivePpgChartCard(
            title = "PPG Red · diagnostik",
            color = DiagnosticRed,
            data = red,
            samples = redSamples,
            modifier = Modifier.fillMaxWidth().height(210.dp)
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun SignalStatusCard(connectionPhase: String, modelStatus: String, qualityTier: String?, observedSamples: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CharcoalSlate),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(recordingStatusLabel(connectionPhase), color = recordingStatusColor(connectionPhase))
            Text(
                modelStatus,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Sensor quality: ${qualityTier ?: if (observedSamples > 0) "collecting (raw preview)" else "no data"}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

// ─── Header ───────────────────────────────────────────────────────────────────

@Composable
private fun MonitoringHeader(progress: Float, elapsedMillis: Long, connectionPhase: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CharcoalSlate),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(NeonEmerald))
                Text(
                    text = recordingStatusLabel(connectionPhase),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = recordingStatusColor(connectionPhase)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatElapsed(elapsedMillis),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
            }
            @Suppress("DEPRECATION")
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                color = NeonEmerald,
                trackColor = SlateCard,
                strokeCap = StrokeCap.Round
            )
            val remaining = (SESSION_DURATION_SEC * (1f - progress)).toInt()
            Text(
                "Sesi 300 detik · prediksi core 30–270 detik",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Text(
                "${(progress * 100).toInt()}% — ${remaining}s remaining",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

private fun formatElapsed(millis: Long): String {
    val s = (millis / 1000).toInt()
    return "%02d:%02d".format(s / 60, s % 60)
}

// ─── Generic single-signal chart card (SRP + DRY) ────────────────────────────

/**
 * Self-contained real-time chart card for one PPG signal channel.
 *
 * Responsibilities:
 * - Renders bounded chart data projected by HomePpgProcessingCoordinator.
 * - Renders clean-signal range, or explicitly labeled raw diagnostic range.
 * - Shares same X-axis duration between channel cards.
 *
 * @param title       Display name (e.g. "PPG Green").
 * @param color       Line and accent color.
 * @param data Latest projected [PpgChartData] for this channel.
 * @param samples Raw sample count shown in subtitle.
 */
@Composable
private fun LivePpgChartCard(title: String, color: Color, data: PpgChartData, samples: Int, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CharcoalSlate),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$title ${chartAccessibilityStatus(data)}"
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 6.dp)
        ) {
            // ── Title row ──────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = color
                )
                Spacer(Modifier.weight(1f))
                val accepted = data.acceptedSampleCount.takeIf { it > 0 } ?: samples
                Text(
                    "$accepted accepted · ${data.renderedPointCount} displayed",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            Text(
                text = chartStatus(data),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                color = chartStatusColor(data),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!data.isEmpty) {
                Text(
                    text = chartTelemetry(data),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(4.dp))

            // ── Chart body: Y-axis labels + Canvas ─────────────────────────
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Y-axis uses clean range when cleaned data exists. Raw diagnostic is labeled.
                Column(
                    modifier = Modifier.width(46.dp).fillMaxHeight(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (data.isEmpty) "—" else formatSignal(data.max),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                        color = color.copy(alpha = 0.8f),
                        maxLines = 1
                    )
                    Text(
                        if (data.isEmpty) "—" else formatSignal(data.min),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                        color = color.copy(alpha = 0.8f),
                        maxLines = 1
                    )
                }

                Spacer(Modifier.width(4.dp))

                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(SlateCard)
                ) {
                    drawChartGrid()
                    if (!data.isEmpty) {
                        val normalized = data.normalizedValues
                            .takeIf { it.size == data.values.size }
                            ?: return@Canvas
                        drawSignalPath(
                            normalized,
                            data.timestampsMillis,
                            color,
                            data.axisStartMillis,
                            data.axisEndMillis,
                            data.gapThresholdMillis
                        )
                        drawInterpolatedPoints(
                            normalized,
                            data.timestampsMillis,
                            data.interpolatedMask,
                            data.axisStartMillis,
                            data.axisEndMillis
                        )
                    }
                }
            }

            Spacer(Modifier.height(3.dp))

            // ── X-axis time labels ─────────────────────────────────────────
            val windowSec = if (data.axisStartMillis != null && data.axisEndMillis != null) {
                (data.axisEndMillis - data.axisStartMillis) / 1_000f
            } else if (data.isEmpty || data.timestampsMillis.size < 2) {
                10f
            } else {
                (data.timestampsMillis.last() - data.timestampsMillis.first()) / 1000f
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 50.dp, end = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "0s",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                    color = TextSecondary
                )
                Text(
                    formatSec(windowSec / 2),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                    color = TextSecondary
                )
                Text(
                    formatSec(windowSec),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                    color = TextSecondary
                )
            }
        }
    }
}

// ─── Canvas helpers ───────────────────────────────────────────────────────────

private fun DrawScope.drawChartGrid() {
    // 3 horizontal guide lines
    listOf(0.25f, 0.5f, 0.75f).forEach { ratio ->
        val y = size.height * (1f - ratio)
        drawLine(GridLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
    // 1 vertical centre line
    drawLine(GridLine, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = 1f)
}

private fun DrawScope.drawSignalPath(
    points: FloatArray,
    timestampsMillis: LongArray,
    color: Color,
    axisStartMillis: Long?,
    axisEndMillis: Long?,
    gapThresholdMillis: Long
) {
    val pointCount = minOf(points.size, timestampsMillis.size)
    if (pointCount < 2) return
    val firstTimestamp = axisStartMillis ?: timestampsMillis.first()
    val lastTimestamp = axisEndMillis ?: timestampsMillis[pointCount - 1]
    val span = (lastTimestamp - firstTimestamp).coerceAtLeast(1L).toFloat()
    fun x(index: Int): Float = ((timestampsMillis[index] - firstTimestamp).toFloat() / span * size.width)
    val path = Path().apply {
        moveTo(x(0), size.height * (1f - points[0]))
        for (i in 1 until pointCount) {
            val gap = (timestampsMillis[i] - timestampsMillis[i - 1]).toFloat()
            if (gap <= 0f || gap > gapThresholdMillis.toFloat()) {
                moveTo(x(i), size.height * (1f - points[i]))
            } else {
                lineTo(x(i), size.height * (1f - points[i]))
            }
        }
    }
    drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawInterpolatedPoints(
    points: FloatArray,
    timestampsMillis: LongArray,
    mask: BooleanArray,
    axisStartMillis: Long?,
    axisEndMillis: Long?
) {
    val pointCount = minOf(points.size, timestampsMillis.size, mask.size)
    if (pointCount == 0) return
    val firstTimestamp = axisStartMillis ?: timestampsMillis.first()
    val span = ((axisEndMillis ?: timestampsMillis[pointCount - 1]) - firstTimestamp)
        .coerceAtLeast(1L).toFloat()
    for (index in 0 until pointCount) {
        if (!mask[index]) continue
        drawCircle(
            color = WarnAmber,
            radius = 2.5.dp.toPx(),
            center = Offset(
                ((timestampsMillis[index] - firstTimestamp).toFloat() / span * size.width),
                size.height * (1f - points[index])
            )
        )
    }
}

// ─── Format helpers ───────────────────────────────────────────────────────────

private fun formatSignal(v: Float): String = when {
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000)
    v >= 1_000 -> "%.1fk".format(v / 1_000)
    else -> "%.0f".format(v)
}

private fun recordingStatusLabel(phase: String): String = when (phase.uppercase()) {
    "OFFLINE" -> "Watch terputus"
    "NODE_DISCOVERED" -> "Watch ditemukan; menunggu aplikasi"
    "APP_REACHABLE" -> "Watch terhubung; menunggu PPG"
    "DISCOVERING" -> "Mencari Galaxy Watch"
    "PHONE_REACHABLE" -> "Phone tersambung"
    "SENSOR_READY" -> "Sensor siap"
    "STREAMING" -> "Streaming Green + IR"
    "DRAINING" -> "Menyelesaikan data"
    "COMPLETE" -> "Sesi selesai"
    "FAILED" -> "Koneksi gagal"
    else -> phase.ifBlank { "Menunggu koneksi" }
}

private fun recordingStatusColor(phase: String): Color = when (phase.uppercase()) {
    "FAILED", "PROTOCOL_ERROR", "OFFLINE" -> AlertRed
    "DRAINING" -> WarnAmber
    "COMPLETE", "STREAMING" -> NeonEmerald
    "PHONE_REACHABLE", "APP_REACHABLE", "SENSOR_READY" -> OceanBlue
    else -> TextSecondary
}

private fun chartAccessibilityStatus(data: PpgChartData): String = buildString {
    append(chartStatus(data))
    append(", durasi ")
    val duration = data.axisStartMillis?.let { start ->
        data.axisEndMillis?.let { end -> ((end - start).coerceAtLeast(0L) / 1_000f) }
    } ?: data.timestampsMillis.firstOrNull()?.let { first ->
        data.timestampsMillis.lastOrNull()?.let { last -> ((last - first).coerceAtLeast(0L) / 1_000f) }
    } ?: 0f
    append(formatSec(duration))
    append(", ").append(data.values.size).append(" titik")
}

private fun chartStatus(data: PpgChartData): String {
    val base = when (data.mode) {
        PpgChartMode.FULL_SESSION_PREVIEW -> data.statusMessage ?: "PREVIEW / bukan input model"
        PpgChartMode.CLEANED_CORE -> data.statusMessage ?: "CLEANED_CORE / 30–270s"
        PpgChartMode.CLEANED_PARTIAL -> data.statusMessage ?: "CLEANED / partial"
        PpgChartMode.RAW_DIAGNOSTIC -> data.statusMessage ?: "RAW / BELUM DIPROSES"
        PpgChartMode.EMPTY -> data.statusMessage ?: "Menunggu data sensor"
    }
    return data.qualityTier
        ?.takeIf(String::isNotBlank)
        ?.let { "$base · quality=$it" }
        ?: base
}

private fun chartTelemetry(data: PpgChartData): String = buildString {
    append("raw ").append(formatSignal(data.observedMin)).append("–").append(formatSignal(data.observedMax))
    data.actualCadenceHz?.let { append(" · cadence ").append("%.1f".format(it)).append(" Hz") }
    append(" · gap ").append(data.significantGapCount).append(" (maks ").append(data.largestGapMillis).append(" ms)")
}

private fun chartStatusColor(data: PpgChartData): Color = when (data.mode) {
    PpgChartMode.FULL_SESSION_PREVIEW -> OceanBlue
    PpgChartMode.CLEANED_CORE -> NeonEmerald
    PpgChartMode.CLEANED_PARTIAL -> NeonEmerald
    PpgChartMode.RAW_DIAGNOSTIC -> WarnAmber
    PpgChartMode.EMPTY -> TextSecondary
}

private fun formatSec(sec: Float): String = if (sec == sec.toLong().toFloat()) "${sec.toInt()}s" else "%.1fs".format(sec)

// ─── Stop button ──────────────────────────────────────────────────────────────

@Composable
private fun StopButton(onStop: () -> Unit) {
    Button(
        onClick = onStop,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .semantics { contentDescription = "Hentikan sesi stress detection" },
        shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AlertRed, contentColor = Color.White)
    ) {
        Icon(Icons.Default.Stop, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Stop session", fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}
