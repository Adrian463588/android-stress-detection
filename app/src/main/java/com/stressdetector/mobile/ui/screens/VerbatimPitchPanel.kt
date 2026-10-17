@file:Suppress("FunctionNaming")

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.stressdetector.mobile.domain.engine.AudioTimelineReviewMarker
import com.stressdetector.mobile.domain.engine.LivePitchObservation
import com.stressdetector.mobile.domain.engine.LivePitchStatus
import com.stressdetector.mobile.ui.AppSpacing
import java.util.Locale

/** Data-only presentation; only marker disclosure is local. Playback stays with the caller. */
@Suppress("LongParameterList") // Preserve the positional API while adding optional chart/review inputs.
@Composable
internal fun VerbatimPitchPanel(
    observations: List<LivePitchObservation>,
    isPaused: Boolean,
    modifier: Modifier = Modifier,
    markers: List<AudioTimelineReviewMarker> = emptyList(),
    isArchive: Boolean = false,
    playbackOffsetMs: Long? = null,
    onSelectMarker: (AudioTimelineReviewMarker) -> Unit = {},
    archiveStartOffsetMs: Long? = null
) {
    val viewport = remember(observations, isArchive, archiveStartOffsetMs) {
        verbatimSignalViewport(observations, isArchive, archiveStartOffsetMs)
    }
    val history = viewport?.observations.orEmpty()
    val visibleMarkers = remember(markers, viewport) {
        markers.filter { marker ->
            viewport == null ||
                marker.startOffsetMs < viewport.endOffsetMs &&
                (
                    marker.endOffsetMs > viewport.startOffsetMs ||
                        marker.startOffsetMs == marker.endOffsetMs &&
                        marker.startOffsetMs >= viewport.startOffsetMs
                    )
        }.sortedBy { it.startOffsetMs }
    }
    var selectedMarkerId by remember(history.firstOrNull()?.sessionId, isArchive) { mutableStateOf<String?>(null) }
    val chartMarkers = VerbatimChartMarkers(visibleMarkers, selectedMarkerId, playbackOffsetMs.takeIf { isArchive })
    val latest = history.lastOrNull()
    val status = when {
        isPaused -> "Pengukuran dijeda"
        isArchive && latest == null -> "Pitch tidak tersedia"
        else -> pitchStatusLabel(latest)
    }
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier.padding(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
        ) {
            VerbatimAmplitudeSection(viewport, isPaused, chartMarkers)
            VerbatimPitchSection(viewport, status, isPaused, chartMarkers)
            VerbatimTimelineCaption(viewport, isArchive, playbackOffsetMs)
            VerbatimMarkerReview(visibleMarkers, selectedMarkerId, isArchive, { selectedMarkerId = it }, onSelectMarker)
            Text(
                "Pitch suara bukan ukuran stres atau bukti desepsi.",
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor
            )
        }
    }
}

@Composable
private fun VerbatimAmplitudeSection(viewport: VerbatimSignalViewport?, isPaused: Boolean, markers: VerbatimChartMarkers) {
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    Text("Amplitudo suara", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    Text(
        "Envelope peak PCM terukur · skala 0–1 dicerminkan ± · bukan F0",
        style = MaterialTheme.typography.labelMedium,
        color = mutedColor
    )
    if (viewport == null) {
        Text("Envelope amplitudo belum tersedia.", style = MaterialTheme.typography.bodySmall, color = mutedColor)
    } else {
        VerbatimAmplitudeChart(viewport, isPaused, markers)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerbatimPitchSection(viewport: VerbatimSignalViewport?, status: String, isPaused: Boolean, markers: VerbatimChartMarkers) {
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val latest = viewport?.observations?.lastOrNull()
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
    ) {
        Text("Pitch suara", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Text(status, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { stateDescription = status })
    }
    Text("Rerata geometrik 1 detik · openSMILE F0", style = MaterialTheme.typography.labelMedium, color = mutedColor)
    if (latest != null) {
        Text(
            "Observasi terakhir ${verbatimAudioTime(latest.startOffsetMs)}–${verbatimAudioTime(latest.endOffsetMs)}",
            style = MaterialTheme.typography.labelMedium,
            color = mutedColor
        )
    }
    if (viewport == null) {
        Text("Belum ada nilai F0 terukur.", style = MaterialTheme.typography.bodySmall, color = mutedColor)
    } else {
        VerbatimPitchChart(viewport, status, isPaused, markers)
    }
    if (!isPaused) latest?.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = mutedColor) }
}

@Composable
private fun VerbatimTimelineCaption(viewport: VerbatimSignalViewport?, isArchive: Boolean, playbackOffsetMs: Long?) {
    if (viewport == null) return
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        if (isArchive) "Arsip · halaman hingga 60 detik" else "Live · hingga 60 detik terakhir",
        style = MaterialTheme.typography.labelMedium,
        color = mutedColor
    )
    Text(
        "Garis putus-putus: batas marker. Garis utuh: posisi putar.",
        style = MaterialTheme.typography.bodySmall,
        color = mutedColor
    )
    if (isArchive && playbackOffsetMs != null) {
        val outside = playbackOffsetMs < viewport.startOffsetMs || playbackOffsetMs >= viewport.endOffsetMs
        Text(
            "Posisi putar ${verbatimSignalTime(playbackOffsetMs)}" + if (outside) " · di luar rentang" else "",
            style = MaterialTheme.typography.labelMedium,
            color = mutedColor
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerbatimMarkerReview(
    markers: List<AudioTimelineReviewMarker>,
    selectedId: String?,
    isArchive: Boolean,
    onInspect: (String?) -> Unit,
    onListen: (AudioTimelineReviewMarker) -> Unit
) {
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    if (markers.isEmpty()) {
        Text("Tidak ada marker pada rentang ini.", style = MaterialTheme.typography.bodySmall, color = mutedColor)
        return
    }
    val selectedIndex = markers.indexOfFirst { it.id == selectedId }
    val index = selectedIndex.coerceAtLeast(0)
    val marker = markers[index]
    val expanded = selectedIndex >= 0
    val actionModifier = Modifier.sizeIn(minWidth = AppSpacing.Large * 2, minHeight = AppSpacing.Large * 2)
    // One review slot covers every supplied marker without an unbounded row or another scroll owner.
    TextButton(
        onClick = { onInspect(if (expanded) null else marker.id) },
        modifier = actionModifier.fillMaxWidth().semantics {
            selected = expanded
            stateDescription = if (expanded) "Bukti marker terbuka" else "Bukti marker tertutup"
        }
    ) {
        Text(
            "Tinjau marker ${index + 1}/${markers.size}: ${marker.label} · " +
                "${verbatimSignalTime(marker.startOffsetMs)}–${verbatimSignalTime(marker.endOffsetMs)}"
        )
    }
    if (markers.size > 1) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
        ) {
            TextButton(onClick = { onInspect(markers[index - 1].id) }, modifier = actionModifier, enabled = index > 0) {
                Text("Marker sebelumnya")
            }
            TextButton(
                onClick = { onInspect(markers[index + 1].id) },
                modifier = actionModifier,
                enabled = index < markers.lastIndex
            ) {
                Text("Marker berikutnya")
            }
        }
    }
    if (expanded) VerbatimMarkerEvidence(marker, isArchive, actionModifier, onListen)
}

@Composable
private fun VerbatimMarkerEvidence(
    marker: AudioTimelineReviewMarker,
    isArchive: Boolean,
    modifier: Modifier,
    onListen: (AudioTimelineReviewMarker) -> Unit
) {
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
            Text(marker.label, style = MaterialTheme.typography.titleSmall)
            Text(
                "${verbatimSignalTime(marker.startOffsetMs)}–${verbatimSignalTime(marker.endOffsetMs)} · Sumber: ${marker.source}",
                style = MaterialTheme.typography.labelMedium,
                color = mutedColor
            )
            Text(marker.explanation, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (isArchive) {
        if (marker.playbackOffsetMs != null) {
            TextButton(onClick = { onListen(marker) }, modifier = modifier.fillMaxWidth()) {
                Text("Dengarkan marker · ${verbatimSignalTime(marker.playbackOffsetMs)}")
            }
        } else {
            Text("Audio marker tidak tersedia.", style = MaterialTheme.typography.bodySmall, color = mutedColor)
        }
    }
}

/** Chart continuity is range-based, not an assumed cadence or a gap-filling operation. */
fun pitchObservationsAreContiguous(previous: LivePitchObservation, current: LivePitchObservation): Boolean =
    previous.status == LivePitchStatus.AVAILABLE &&
        current.status == LivePitchStatus.AVAILABLE &&
        previous.sessionId == current.sessionId &&
        previous.startOffsetMs < current.startOffsetMs &&
        previous.endOffsetMs == current.startOffsetMs

private fun pitchStatusLabel(observation: LivePitchObservation?): String = when (observation?.status) {
    null -> "Mengumpulkan audio"
    LivePitchStatus.AVAILABLE -> "${pitchNumber(requireNotNull(observation.frequencyHz))} Hz"
    LivePitchStatus.UNVOICED -> "Tanpa suara bernada"
    LivePitchStatus.QUALITY_REJECTED -> "Kualitas audio tidak memadai"
    LivePitchStatus.NATIVE_UNAVAILABLE -> "Pitch tidak tersedia"
    LivePitchStatus.PROCESSING_GAP -> "Data pitch terputus"
}

internal fun pitchNumber(frequency: Double): String = String.format(Locale.forLanguageTag("id-ID"), "%.1f", frequency)
