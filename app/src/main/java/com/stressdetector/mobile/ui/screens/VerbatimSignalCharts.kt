@file:Suppress("FunctionNaming")

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.stressdetector.mobile.domain.engine.AudioTimelineReviewMarker
import com.stressdetector.mobile.domain.engine.LivePitchObservation
import com.stressdetector.mobile.domain.engine.LivePitchStatus
import com.stressdetector.mobile.ui.AppSpacing

private const val SIGNAL_PAGE_MILLIS = 60_000L
private const val SIGNAL_CHART_HEIGHT_UNITS = 3
private const val CHART_HALF = 0.5f
private const val SINGLE_PITCH_AXIS_MARGIN_HZ = 1.0
private const val SIGNAL_MILLIS_PER_SECOND = 1_000L
private const val SIGNAL_DECIMAL_FRACTION_DIGITS = 3

/** A display crop only: observations and their measured peak bins are never rewritten. */
internal data class VerbatimSignalViewport(val observations: List<LivePitchObservation>, val startOffsetMs: Long, val endOffsetMs: Long) {
    fun offsetFraction(offsetMs: Long): Float = ((offsetMs - startOffsetMs).toDouble() / (endOffsetMs - startOffsetMs)).toFloat()

    val timeGapCount: Int
        get() {
            var coveredUntil = startOffsetMs
            var gaps = 0
            observations.forEach { observation ->
                if (observation.status != LivePitchStatus.PROCESSING_GAP) {
                    val start = maxOf(startOffsetMs, observation.startOffsetMs)
                    val end = minOf(endOffsetMs, observation.endOffsetMs)
                    if (start > coveredUntil) gaps++
                    coveredUntil = maxOf(coveredUntil, end)
                }
            }
            if (coveredUntil < endOffsetMs) gaps++
            return gaps
        }
}

internal fun verbatimSignalViewport(
    observations: List<LivePitchObservation>,
    isArchive: Boolean,
    archiveStartOffsetMs: Long? = null
): VerbatimSignalViewport? {
    val ordered = observations.filter { it.endOffsetMs > it.startOffsetMs }.sortedBy { it.startOffsetMs }
    if (isArchive && archiveStartOffsetMs != null) {
        require(archiveStartOffsetMs in 0..Long.MAX_VALUE - SIGNAL_PAGE_MILLIS)
        val end = archiveStartOffsetMs + SIGNAL_PAGE_MILLIS
        return VerbatimSignalViewport(
            ordered.filter { it.endOffsetMs > archiveStartOffsetMs && it.startOffsetMs < end },
            archiveStartOffsetMs,
            end
        )
    }
    val first = ordered.firstOrNull()?.startOffsetMs
    return if (first == null) {
        null
    } else {
        val last = ordered.maxOf { it.endOffsetMs }
        // The caller supplies a bounded archive page; an oversized input is cropped from its start, never rebased to the live tail.
        val start = if (isArchive) first else (last - SIGNAL_PAGE_MILLIS).coerceAtLeast(first)
        val end = if (isArchive) first + (last - first).coerceAtMost(SIGNAL_PAGE_MILLIS) else last
        VerbatimSignalViewport(ordered.filter { it.endOffsetMs > start && it.startOffsetMs < end }, start, end)
    }
}

internal data class VerbatimChartMarkers(
    val visible: List<AudioTimelineReviewMarker>,
    val selectedId: String?,
    /** Already on the trace timeline; conversion to/from WAV time belongs to the caller. */
    val playbackOffsetMs: Long?
)

/** Retain subsecond evidence offsets without changing the existing whole-second transcript formatter. */
internal fun verbatimSignalTime(offsetMs: Long): String {
    val fraction = offsetMs % SIGNAL_MILLIS_PER_SECOND
    val fractionText = fraction.toString().padStart(SIGNAL_DECIMAL_FRACTION_DIGITS, '0')
    return verbatimAudioTime(offsetMs) + if (fraction == 0L) "" else ".$fractionText"
}

@Composable
internal fun VerbatimAmplitudeChart(viewport: VerbatimSignalViewport, isPaused: Boolean, markers: VerbatimChartMarkers) {
    val measured = remember(viewport) { viewport.observations.count { it.waveform.isNotEmpty() } }
    val maximum = remember(viewport) { viewport.observations.mapNotNull { it.waveform.maxOrNull() }.maxOrNull() }
    val gapSummary = "Tanpa envelope: ${viewport.observations.size - measured} jendela · jeda waktu: ${viewport.timeGapCount}."
    val summary = if (maximum == null) {
        "Envelope amplitudo belum tersedia."
    } else {
        "$measured jendela PCM terukur · peak maksimum ${maximum.toString().replace('.', ',')} pada skala 0–1."
    }
    val color = if (isPaused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    val zeroColor = MaterialTheme.colorScheme.outline
    VerbatimSignalCanvas(
        viewport,
        "Grafik envelope amplitudo PCM; $summary $gapSummary",
        if (isPaused) "Riwayat sebelum jeda" else summary,
        "+1 · amplitudo relatif PCM",
        "−1 · envelope dicerminkan",
        markers
    ) {
        val middle = size.height * CHART_HALF
        drawLine(zeroColor, Offset(0f, middle), Offset(size.width, middle))
        viewport.observations.forEach { observation ->
            val bins = observation.waveform
            val binMillis = (observation.endOffsetMs - observation.startOffsetMs).toDouble() / bins.size.coerceAtLeast(1)
            val relativeStart = (observation.startOffsetMs - viewport.startOffsetMs).toDouble()
            val span = (viewport.endOffsetMs - viewport.startOffsetMs).toDouble()
            bins.forEachIndexed { index, peak ->
                val left = ((relativeStart + index * binMillis) / span).toFloat() * size.width
                val right = ((relativeStart + (index + 1) * binMillis) / span).toFloat() * size.width
                // Each rectangle is one observed absolute PCM peak, mirrored for an amplitude envelope, never F0 or resynthesis.
                if (peak == 0f) {
                    drawLine(color, Offset(left, middle), Offset(right, middle))
                } else {
                    drawRect(color, Offset(left, middle - peak * middle), Size(right - left, peak * size.height))
                }
            }
        }
    }
    SignalSummary("$summary $gapSummary")
}

@Composable
internal fun VerbatimPitchChart(viewport: VerbatimSignalViewport, status: String, isPaused: Boolean, markers: VerbatimChartMarkers) {
    val history = viewport.observations
    val frequencies = remember(history) { history.mapNotNull { it.frequencyHz } }
    val minimum = frequencies.minOrNull()
    val maximum = frequencies.maxOrNull()
    val rangeSummary = if (minimum == null || maximum == null) {
        "Belum ada nilai F0 terukur."
    } else {
        "Rentang teramati ${pitchNumber(minimum)}–${pitchNumber(maximum)} Hz"
    }
    val gapSummary = "Tanpa nilai F0: ${history.size - frequencies.size} jendela · jeda waktu: ${viewport.timeGapCount}."
    val lower = if (minimum == maximum) ((minimum ?: 0.0) - SINGLE_PITCH_AXIS_MARGIN_HZ).coerceAtLeast(0.0) else minimum
    val upper = if (minimum == maximum) (maximum ?: 0.0) + SINGLE_PITCH_AXIS_MARGIN_HZ else maximum
    val chartColor = if (isPaused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary
    val description = "Grafik pitch suara; ${frequencies.size} observasi bernada. Rerata geometrik 1 detik. " +
        "$rangeSummary. $gapSummary Garis terputus pada data yang tidak tersedia. Status: $status."
    VerbatimSignalCanvas(
        viewport,
        description,
        if (isPaused) "Riwayat sebelum jeda" else status,
        if (maximum == null) "Hz · tidak tersedia" else "${pitchNumber(requireNotNull(upper))} Hz",
        if (minimum == null) "Tanpa nilai F0" else "${pitchNumber(requireNotNull(lower))} Hz",
        markers
    ) {
        drawPitchObservations(viewport, lower, upper, chartColor)
    }
    SignalSummary("$rangeSummary · $gapSummary")
}

private fun DrawScope.drawPitchObservations(viewport: VerbatimSignalViewport, lower: Double?, upper: Double?, color: Color) {
    if (lower == null || upper == null) return
    fun position(observation: LivePitchObservation, frequency: Double): Offset {
        val midpoint = viewport.offsetFraction(observation.startOffsetMs) +
            (observation.endOffsetMs - observation.startOffsetMs).toFloat() /
            (viewport.endOffsetMs - viewport.startOffsetMs) * CHART_HALF
        return Offset(midpoint * size.width, (1.0 - (frequency - lower) / (upper - lower)).toFloat() * size.height)
    }
    val history = viewport.observations
    history.forEachIndexed { index, observation ->
        val frequency = observation.frequencyHz ?: return@forEachIndexed
        val point = position(observation, frequency)
        val previous = history.getOrNull(index - 1)
        if (previous != null && pitchObservationsAreContiguous(previous, observation)) {
            previous.frequencyHz?.let {
                // Straight links connect only contiguous observed window means; no values fill a gap.
                drawLine(color, position(previous, it), point, strokeWidth = AppSpacing.Micro.toPx() * CHART_HALF)
            }
        }
        drawCircle(color, radius = AppSpacing.Micro.toPx() * CHART_HALF, center = point)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList") // Shared chart axes and semantics use the same draw/overlay coordinate space.
@Composable
private fun VerbatimSignalCanvas(
    viewport: VerbatimSignalViewport,
    description: String,
    state: String,
    topAxis: String,
    bottomAxis: String,
    markers: VerbatimChartMarkers,
    drawSignal: DrawScope.() -> Unit
) {
    val axisColor = MaterialTheme.colorScheme.outline
    val markerColor = MaterialTheme.colorScheme.onSurfaceVariant
    val selectedColor = MaterialTheme.colorScheme.onSurface
    val selected = markers.visible.firstOrNull { it.id == markers.selectedId }
    val markerSummary = "${markers.visible.size} marker pada offset asli." +
        (selected?.let { " Marker terpilih: ${it.label}, ${verbatimSignalTime(it.startOffsetMs)}." } ?: "")
    SignalSummary(topAxis)
    Canvas(
        Modifier.fillMaxWidth().height(AppSpacing.ExtraLarge * SIGNAL_CHART_HEIGHT_UNITS).semantics {
            contentDescription = "$description Waktu rekaman ${verbatimSignalTime(viewport.startOffsetMs)} sampai " +
                "${verbatimSignalTime(viewport.endOffsetMs)}. $markerSummary"
            stateDescription = state
        }
    ) {
        inset(AppSpacing.Micro.toPx(), AppSpacing.Micro.toPx()) {
            clipRect {
                drawLine(axisColor, Offset.Zero, Offset(0f, size.height))
                drawLine(axisColor, Offset(0f, size.height), Offset(size.width, size.height))
                drawSignal()
                drawReviewMarkers(viewport, markers, markerColor, selectedColor)
            }
        }
    }
    SignalSummary(bottomAxis)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
    ) {
        SignalSummary("Waktu ${verbatimSignalTime(viewport.startOffsetMs)}")
        SignalSummary(verbatimSignalTime(viewport.endOffsetMs))
    }
}

private fun DrawScope.drawReviewMarkers(
    viewport: VerbatimSignalViewport,
    markers: VerbatimChartMarkers,
    markerColor: Color,
    selectedColor: Color
) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(AppSpacing.Micro.toPx(), AppSpacing.Micro.toPx()))
    markers.visible.forEach { marker ->
        val selected = marker.id == markers.selectedId
        val color = if (selected) selectedColor else markerColor
        val stroke = if (selected) AppSpacing.Micro.toPx() * CHART_HALF else 1f
        if (marker.endOffsetMs > marker.startOffsetMs) {
            // The duration remains visible even when both real boundaries lie outside this page.
            drawLine(
                color,
                Offset(viewport.offsetFraction(marker.startOffsetMs) * size.width, AppSpacing.Micro.toPx()),
                Offset(viewport.offsetFraction(marker.endOffsetMs) * size.width, AppSpacing.Micro.toPx()),
                strokeWidth = stroke,
                pathEffect = dash
            )
        }
        listOf(marker.startOffsetMs, marker.endOffsetMs).distinct().forEach { offset ->
            if (offset >= viewport.startOffsetMs && offset <= viewport.endOffsetMs) {
                val x = viewport.offsetFraction(offset) * size.width
                drawLine(
                    color,
                    Offset(x, 0f),
                    Offset(x, size.height),
                    strokeWidth = stroke,
                    pathEffect = dash
                )
            }
        }
    }
    markers.playbackOffsetMs?.takeIf { it >= viewport.startOffsetMs && it < viewport.endOffsetMs }?.let { offset ->
        val x = viewport.offsetFraction(offset) * size.width
        drawLine(selectedColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = AppSpacing.Micro.toPx() * CHART_HALF)
    }
}

@Composable
private fun SignalSummary(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
