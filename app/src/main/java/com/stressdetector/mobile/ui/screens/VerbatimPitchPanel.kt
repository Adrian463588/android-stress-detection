@file:Suppress("FunctionNaming")

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.stressdetector.mobile.domain.engine.LivePitchObservation
import com.stressdetector.mobile.domain.engine.LivePitchStatus
import com.stressdetector.mobile.ui.AppSpacing
import java.util.Locale

private const val PITCH_HISTORY_LIMIT = 60
private const val PITCH_CHART_HEIGHT_UNITS = 3

/** Presentation only: immutable session observations arrive from the engine/ViewModel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerbatimPitchPanel(observations: List<LivePitchObservation>, isPaused: Boolean, modifier: Modifier = Modifier) {
    val history = remember(observations) { observations.takeLast(PITCH_HISTORY_LIMIT) }
    val latest = history.lastOrNull()
    val status = if (isPaused) "Pengukuran dijeda" else pitchStatusLabel(latest)
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
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
            ) {
                Text("Pitch suara", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Text(status, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { stateDescription = status })
            }
            Text(
                "Rerata geometrik 1 detik · openSMILE F0",
                style = MaterialTheme.typography.labelMedium,
                color = mutedColor
            )
            if (latest != null) {
                Text(
                    "Observasi terakhir ${verbatimAudioTime(latest.startOffsetMs)}–${verbatimAudioTime(latest.endOffsetMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = mutedColor
                )
            }
            VerbatimPitchChart(history, status, isPaused)
            if (!isPaused) {
                latest?.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = mutedColor) }
            }
            Text(
                "Pitch suara bukan ukuran stres atau bukti desepsi.",
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor
            )
        }
    }
}

@Composable
private fun VerbatimPitchChart(history: List<LivePitchObservation>, status: String, isPaused: Boolean) {
    val frequencies = remember(history) { history.mapNotNull { it.frequencyHz } }
    val minimum = frequencies.minOrNull() ?: return
    val maximum = frequencies.maxOrNull() ?: return
    val firstOffset = history.first().startOffsetMs
    val lastOffset = history.last().endOffsetMs
    val timeSpan = (lastOffset - firstOffset).coerceAtLeast(1L).toDouble()
    val frequencySpan = maximum - minimum
    val chartColor = if (isPaused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary
    val description = "Grafik pitch suara; ${frequencies.size} observasi bernada. " +
        "Rerata geometrik 1 detik, ${pitchNumber(minimum)} sampai ${pitchNumber(maximum)} Hz. " +
        "Waktu rekaman ${verbatimAudioTime(firstOffset)} sampai ${verbatimAudioTime(lastOffset)}. " +
        "Garis terputus pada data yang tidak tersedia. Status: $status."
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppSpacing.ExtraLarge * PITCH_CHART_HEIGHT_UNITS)
            .semantics {
                contentDescription = description
                stateDescription = if (isPaused) "Riwayat sebelum jeda" else status
            }
    ) {
        val inset = AppSpacing.Micro.toPx().coerceAtMost(size.minDimension * CHART_HALF)
        val plotWidth = (size.width - inset * 2f).coerceAtLeast(0f)
        val plotHeight = (size.height - inset * 2f).coerceAtLeast(0f)
        fun position(observation: LivePitchObservation, frequency: Double): Offset {
            val midpoint = observation.startOffsetMs - firstOffset +
                (observation.endOffsetMs - observation.startOffsetMs) * CHART_HALF.toDouble()
            val vertical = if (frequencySpan > 0.0) ((frequency - minimum) / frequencySpan).toFloat() else CHART_HALF
            return Offset(inset + (midpoint / timeSpan).toFloat() * plotWidth, inset + (1f - vertical) * plotHeight)
        }
        history.forEachIndexed { index, observation ->
            val frequency = observation.frequencyHz ?: return@forEachIndexed
            val point = position(observation, frequency)
            val previous = history.getOrNull(index - 1)
            if (previous != null && pitchObservationsAreContiguous(previous, observation)) {
                previous.frequencyHz?.let {
                    // Straight links join observed window means; no interpolated samples are produced.
                    drawLine(chartColor, position(previous, it), point, strokeWidth = AppSpacing.Micro.toPx() / 2f)
                }
            }
            drawCircle(chartColor, radius = AppSpacing.Micro.toPx() / 2f, center = point)
        }
    }
    Text(
        "Rentang teramati ${pitchNumber(minimum)}–${pitchNumber(maximum)} Hz · " +
            "waktu rekaman ${verbatimAudioTime(firstOffset)}–${verbatimAudioTime(lastOffset)}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Chart continuity is range-based, not an assumed cadence or a gap-filling operation. */
internal fun pitchObservationsAreContiguous(previous: LivePitchObservation, current: LivePitchObservation): Boolean =
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

private fun pitchNumber(frequency: Double): String = String.format(Locale.forLanguageTag("id-ID"), "%.1f", frequency)

private const val CHART_HALF = 0.5f
