@file:Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod", "MagicNumber")

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.domain.engine.AudioSignalTracePage
import com.stressdetector.mobile.domain.engine.AudioTimelineReviewMarker
import com.stressdetector.mobile.domain.engine.MultimodalComparisonCategory
import com.stressdetector.mobile.domain.engine.MultimodalComparisonFinding
import com.stressdetector.mobile.domain.engine.SkinTemperatureObservation
import com.stressdetector.mobile.domain.engine.VerbatimSessionDetail
import com.stressdetector.mobile.domain.engine.WatchWearerRole
import com.stressdetector.mobile.domain.engine.computeSkinTemperatureStatistics
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.clinicalColors
import com.stressdetector.mobile.ui.viewmodels.VerbatimMarkerCounts
import com.stressdetector.mobile.ui.viewmodels.VerbatimMarkerFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val ARCHIVE_CHART_HEIGHT_DP = 112

@Composable
internal fun VerbatimReviewSummaryHeader(
    detail: VerbatimSessionDetail,
    markerCounts: VerbatimMarkerCounts,
    aiSummaryText: String?,
    drawerStatusLabel: String,
    drawerStorageLabel: String,
    onSearchQueryChange: (String) -> Unit,
    searchQuery: String,
    modifier: Modifier = Modifier,
    temperatureSamples: List<SkinTemperatureObservation> = detail.skinTemperatureSamples
) {
    val clinical = MaterialTheme.clinicalColors
    val summary = detail.summary
    val medianBpm = remember(detail.divergenceEvents) {
        val bpms = detail.divergenceEvents.mapNotNull { it.bpm?.takeIf { b -> b.isFinite() && b > 0f } }.sorted()
        if (bpms.isEmpty()) null else bpms[bpms.size / 2].roundToInt()
    }
    val thermalStats = remember(temperatureSamples) { computeSkinTemperatureStatistics(temperatureSamples) }
    val totalMarkers = markerCounts.count(VerbatimMarkerFilter.ALL)
    val effectiveDurationSec = if (summary.audioDurationMs > 0L) {
        (summary.audioDurationMs / 1_000L).coerceAtLeast(summary.durationSeconds)
    } else {
        summary.durationSeconds
    }
    var aiSummaryExpanded by rememberSaveable(summary.sessionId) { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Text(summary.sessionName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "${formatReviewTimestamp(summary.startedAtMillis)} · ${formatReviewDuration(effectiveDurationSec)} · " +
                "Watch: ${watchRoleLabel(summary.watchWearerRole)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "$drawerStatusLabel · $drawerStorageLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
            ReviewKpiCard(
                label = "Median BPM teramati",
                value = medianBpm?.toString() ?: "—",
                accent = clinical.physiological,
                modifier = Modifier.weight(1f)
            )
            ReviewKpiCard(
                label = "Total marker & cue",
                value = totalMarkers.toString(),
                accent = clinical.marker,
                modifier = Modifier.weight(1f)
            )
        }
        if (thermalStats.totalCount > 0) {
            val medianTempText = thermalStats.medianC?.let {
                String.format(Locale.US, "%.1f °C", it)
            } ?: "—"
            val rangeText = if (thermalStats.minC != null && thermalStats.maxC != null) {
                String.format(Locale.US, "%.1f–%.1f °C", thermalStats.minC, thermalStats.maxC)
            } else {
                "Tidak valid"
            }
            val ambientText = thermalStats.latestAmbientC?.let {
                String.format(Locale.US, "%.1f °C", it)
            } ?: "—"
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = clinical.surfaceSubtle,
                border = BorderStroke(1.dp, clinical.thermal.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Median suhu kulit",
                            style = MaterialTheme.typography.labelSmall,
                            color = clinical.thermal
                        )
                        Text(
                            medianTempText,
                            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Bold,
                            color = clinical.thermal
                        )
                    }
                    Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Rentang: $rangeText",
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
                        )
                        Text(
                            "Sekitar: $ambientText · Valid ${thermalStats.validCount}/${thermalStats.totalCount}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        VerbatimDisclosure(
            label = if (aiSummaryText.isNullOrBlank()) {
                "Ringkasan AI lokal (${summary.summaryStatus})"
            } else {
                "Ringkasan AI lokal"
            },
            expanded = aiSummaryExpanded,
            onExpandedChange = { aiSummaryExpanded = it }
        ) {
            Text(
                aiSummaryText ?: "Ringkasan AI lokal belum dibuat atau belum didekripsi.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            label = { Text("Cari transkrip atau pembicara") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (searchQuery.isNotBlank()) {
                {
                    IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Clear, contentDescription = "Bersihkan pencarian")
                    }
                }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ReviewKpiCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    val clinical = MaterialTheme.clinicalColors
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, clinical.borderSubtle)
    ) {
        Column(modifier = Modifier.padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                color = accent,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerbatimUnifiedTimelineCard(
    page: AudioSignalTracePage?,
    events: List<DivergenceEventEntity>,
    markers: List<AudioTimelineReviewMarker>,
    playbackPositionMs: Long,
    totalDurationMs: Long,
    onSelectMarker: (DivergenceEventEntity) -> Unit,
    modifier: Modifier = Modifier,
    temperatureSamples: List<SkinTemperatureObservation> = emptyList()
) {
    val clinical = MaterialTheme.clinicalColors
    val pageEnd = page?.endOffsetMs?.takeIf { it > 0L } ?: totalDurationMs.coerceAtLeast(1_000L)
    val pageStart = page?.startOffsetMs ?: 0L
    val clampedEnd = if (totalDurationMs > 0L) minOf(pageEnd, totalDurationMs) else pageEnd
    val span = (clampedEnd - pageStart).coerceAtLeast(1L).toFloat()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, clinical.borderSubtle)
    ) {
        Column(modifier = Modifier.padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Timeline sesi multimodal", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${formatReviewDuration(pageStart / 1_000L)} – ${formatReviewDuration(clampedEnd / 1_000L)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ARCHIVE_CHART_HEIGHT_DP.dp)
                    .semantics {
                        contentDescription = "Grafik sinkron nadi, suara, dan marker antara " +
                            "${formatReviewDuration(pageStart / 1_000L)} sampai ${formatReviewDuration(clampedEnd / 1_000L)}"
                    }
            ) {
                val midY = size.height / 2f
                drawLine(clinical.borderSubtle, Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1.dp.toPx())

                // Upper band: Physiological BPM points from divergence/trajectory events
                val bpmEvents = events.filter {
                    it.bpm != null && it.bpm.isFinite() && it.markerElapsedMillis in pageStart..clampedEnd
                }.sortedBy { it.markerElapsedMillis }
                if (bpmEvents.size >= 2) {
                    val minBpm = bpmEvents.minOf { it.bpm!! }
                    val maxBpm = bpmEvents.maxOf { it.bpm!! }.coerceAtLeast(minBpm + 1f)
                    for (idx in 1 until bpmEvents.size) {
                        val a = bpmEvents[idx - 1]
                        val b = bpmEvents[idx]
                        val x1 = ((a.markerElapsedMillis - pageStart) / span).coerceIn(0f, 1f) * size.width
                        val y1 = (1f - (a.bpm!! - minBpm) / (maxBpm - minBpm)) * (midY * 0.8f) + size.height * 0.05f
                        val x2 = ((b.markerElapsedMillis - pageStart) / span).coerceIn(0f, 1f) * size.width
                        val y2 = (1f - (b.bpm!! - minBpm) / (maxBpm - minBpm)) * (midY * 0.8f) + size.height * 0.05f
                        drawLine(clinical.physiological, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2.dp.toPx())
                    }
                }

                // Lower band: Acoustic pitch observations from actual trace (broken lines on gaps)
                val obs = page?.observations.orEmpty().filter { it.endOffsetMs > pageStart && it.startOffsetMs < clampedEnd }
                val voiced = obs.mapNotNull { it.frequencyHz }
                if (voiced.isNotEmpty()) {
                    val minHz = voiced.min()
                    val maxHz = voiced.max().coerceAtLeast(minHz + 1.0)
                    obs.forEachIndexed { idx, cur ->
                        val hz = cur.frequencyHz ?: return@forEachIndexed
                        val x2 = ((cur.endOffsetMs.coerceIn(pageStart, clampedEnd) - pageStart) / span) * size.width
                        val y2 = midY + (1.0 - (hz - minHz) / (maxHz - minHz)).toFloat() * (midY * 0.8f) + size.height * 0.05f
                        val prev = obs.getOrNull(idx - 1)
                        val prevHz = prev?.frequencyHz
                        if (prev != null && prevHz != null && pitchObservationsAreContiguous(prev, cur)) {
                            val x1 = ((prev.endOffsetMs.coerceIn(pageStart, clampedEnd) - pageStart) / span) * size.width
                            val y1 = midY + (1.0 - (prevHz - minHz) / (maxHz - minHz)).toFloat() * (midY * 0.8f) + size.height * 0.05f
                            drawLine(clinical.acoustic, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2.dp.toPx())
                        } else {
                            drawCircle(clinical.acoustic, radius = 2.dp.toPx(), center = Offset(x2, y2))
                        }
                    }
                }

                // Third band: Skin temperature trace (gap-preserving, never interpolated)
                val validTemps = temperatureSamples.filter {
                    it.isValid &&
                        it.skinTemperatureC != null &&
                        it.skinTemperatureC.isFinite() &&
                        it.elapsedMillis in pageStart..clampedEnd
                }.sortedBy { it.elapsedMillis }
                if (validTemps.isNotEmpty()) {
                    val minT = validTemps.minOf { it.skinTemperatureC!! }
                    val maxT = validTemps.maxOf { it.skinTemperatureC!! }.coerceAtLeast(minT + 0.2f)
                    val bandTop = size.height * 0.66f
                    val bandHeight = size.height * 0.26f
                    validTemps.forEachIndexed { idx, cur ->
                        val tempVal = cur.skinTemperatureC!!
                        val x2 = ((cur.elapsedMillis - pageStart) / span).coerceIn(0f, 1f) * size.width
                        val y2 = bandTop + (1f - (tempVal - minT) / (maxT - minT)) * bandHeight
                        val prev = validTemps.getOrNull(idx - 1)
                        val prevTemp = prev?.skinTemperatureC
                        if (prev != null && prevTemp != null && (cur.elapsedMillis - prev.elapsedMillis) <= 90_000L) {
                            val x1 = ((prev.elapsedMillis - pageStart) / span).coerceIn(0f, 1f) * size.width
                            val y1 = bandTop + (1f - (prevTemp - minT) / (maxT - minT)) * bandHeight
                            drawLine(clinical.thermal, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2.dp.toPx())
                        } else {
                            drawCircle(clinical.thermal, radius = 2.5.dp.toPx(), center = Offset(x2, y2))
                        }
                    }
                }

                // Vertical dashed marker ticks
                events.filter { it.markerElapsedMillis in pageStart..clampedEnd }.forEach { ev ->
                    val x = ((ev.markerElapsedMillis - pageStart) / span).coerceIn(0f, 1f) * size.width
                    drawLine(
                        color = if (ev.markerType == "THERMAL_DIVERGENCE") clinical.thermal else clinical.marker,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    )
                }
                markers.filter { it.startOffsetMs in pageStart..clampedEnd }.forEach { m ->
                    val x = ((m.startOffsetMs - pageStart) / span).coerceIn(0f, 1f) * size.width
                    drawCircle(clinical.marker.copy(alpha = 0.7f), radius = 3.dp.toPx(), center = Offset(x, size.height - 4.dp.toPx()))
                }

                // Current playback position needle
                if (playbackPositionMs in pageStart..clampedEnd) {
                    val x = ((playbackPositionMs - pageStart) / span).coerceIn(0f, 1f) * size.width
                    drawLine(
                        color = clinical.recording,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium)) {
                LegendLabel("● Nadi (PPG)", clinical.physiological)
                LegendLabel("● Suhu (°C)", clinical.thermal)
                LegendLabel("● Suara (F0)", clinical.acoustic)
                LegendLabel("| Marker", clinical.marker)
            }
            if (events.isNotEmpty()) {
                val recentInWindow = events.take(4)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
                    recentInWindow.forEach { ev ->
                        Surface(
                            modifier = Modifier.heightIn(min = 36.dp).clickable { onSelectMarker(ev) },
                            shape = MaterialTheme.shapes.small,
                            color = clinical.surfaceSubtle,
                            border = BorderStroke(1.dp, clinical.marker.copy(alpha = 0.45f))
                        ) {
                            Text(
                                "${formatReviewDuration(ev.episodeStartElapsedMillis / 1_000L)} · ${shortMarkerTag(ev)}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MultimodalComparisonSection(findings: List<MultimodalComparisonFinding>, modifier: Modifier = Modifier) {
    if (findings.isEmpty()) return
    val clinical = MaterialTheme.clinicalColors
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
        Text(
            "Perbandingan modalitas (tanpa penggabungan skor)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        findings.forEach { finding ->
            val accent = when (finding.category) {
                MultimodalComparisonCategory.DIRECTIONAL_DIVERGENCE -> clinical.marker
                MultimodalComparisonCategory.CONCORDANT_BOTH -> clinical.physiological
                MultimodalComparisonCategory.ACOUSTIC_ONLY,
                MultimodalComparisonCategory.SINGLE_MODALITY_AVAILABLE -> clinical.acoustic
                MultimodalComparisonCategory.PHYSIOLOGICAL_ONLY -> clinical.physiological
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(AppSpacing.Small), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val rangeLabel = "${formatReviewDuration(finding.episodeStartElapsedMillis / 1_000L)}–" +
                            formatReviewDuration(finding.episodeEndElapsedMillis / 1_000L)
                        Text(
                            finding.category.uiTitle(),
                            style = MaterialTheme.typography.labelLarge,
                            color = accent,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            rangeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(finding.summary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun LegendLabel(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Medium)
}

private fun MultimodalComparisonCategory.uiTitle(): String = when (this) {
    MultimodalComparisonCategory.PHYSIOLOGICAL_ONLY -> "Perubahan fisiologis teramati"
    MultimodalComparisonCategory.ACOUSTIC_ONLY -> "Perubahan akustik teramati"
    MultimodalComparisonCategory.CONCORDANT_BOTH -> "Perubahan kedua modalitas"
    MultimodalComparisonCategory.DIRECTIONAL_DIVERGENCE -> "Perbedaan arah perubahan"
    MultimodalComparisonCategory.SINGLE_MODALITY_AVAILABLE -> "Bukti modalitas tunggal"
}

private fun shortMarkerTag(event: DivergenceEventEntity): String = when (event.markerType) {
    "BPM_RISE", "BPM_TRAJECTORY", "PHYSIOLOGICAL_BPM_TRAJECTORY" ->
        event.bpmDelta?.takeIf { it.isFinite() }?.let { "Nadi ${if (it >= 0) "+" else ""}${it.roundToInt()} BPM" } ?: "Nadi"
    "ACOUSTIC_DIVERGENCE", "PROSODY_DIVERGENCE" -> "Suara"
    "THERMAL_DIVERGENCE" -> event.deltaValue?.takeIf { it.isFinite() }?.let {
        "Suhu ${if (it >= 0f) "+" else ""}${String.format(Locale.US, "%.1f", it)} °C"
    } ?: "Suhu"
    "MULTIMODAL_CUE" -> "Multimodal"
    "MANUAL" -> "Catatan"
    else -> event.markerType.take(12)
}

private fun watchRoleLabel(role: String): String = when (role) {
    WatchWearerRole.CLIENT.name -> "Dipakai Klien"
    WatchWearerRole.PSYCHOLOGIST.name -> "Dipakai Psikolog"
    WatchWearerRole.UNSPECIFIED.name -> "Tidak Ada Watch"
    else -> role
}

private fun formatReviewDuration(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

private fun formatReviewTimestamp(timestamp: Long): String =
    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID")).format(Date(timestamp))
