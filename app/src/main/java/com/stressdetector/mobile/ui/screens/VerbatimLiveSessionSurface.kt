@file:Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod", "MagicNumber", "TooManyFunctions")

package com.stressdetector.mobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.data.ChartData
import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.domain.engine.AcousticAnalysisSnapshot
import com.stressdetector.mobile.domain.engine.BpmSource
import com.stressdetector.mobile.domain.engine.LiveBiosignalSnapshot
import com.stressdetector.mobile.domain.engine.LiveBpmChannelSnapshot
import com.stressdetector.mobile.domain.engine.LiveTemperatureSnapshot
import com.stressdetector.mobile.domain.engine.PrvMetrics
import com.stressdetector.mobile.domain.engine.SessionMarkerType
import com.stressdetector.mobile.domain.engine.SkinTemperatureObservation
import com.stressdetector.mobile.domain.engine.SpeakerBaselineTracker
import com.stressdetector.mobile.domain.engine.SpeechSegment
import com.stressdetector.mobile.domain.engine.ThermalSignalStatus
import com.stressdetector.mobile.domain.engine.VerbatimProsodyEvidence
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.clinicalColors
import com.stressdetector.mobile.ui.viewmodels.SessionCommandState
import com.stressdetector.mobile.ui.viewmodels.UnifiedSessionUiState
import com.stressdetector.shared.PpgType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

private const val BPM_PERCENT_SCALE = 100
private const val LIVE_GAP_BREAK_MILLIS = 500L
private const val BASELINE_MIN_VOICED_FRAMES = 3
private const val SPEAKER_ATTRIBUTION_LAG_MS = 4_000L

@Composable
internal fun VerbatimLiveStatusHeader(uiState: UnifiedSessionUiState, sessionTitle: String) {
    val clinical = MaterialTheme.clinicalColors
    val isPaused = uiState.sessionCommandState == SessionCommandState.Paused || uiState.isPaused
    val statusColor = if (isPaused) clinical.marker else clinical.recording
    val statusText = when (uiState.sessionCommandState) {
        SessionCommandState.Paused -> "Dijeda"
        SessionCommandState.Recording -> "Merekam"
        SessionCommandState.Starting -> "Memulai…"
        SessionCommandState.Stopping -> "Menghentikan…"
        SessionCommandState.Finalizing -> "Menyimpan…"
        else -> if (isPaused) "Dijeda" else "Merekam"
    }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    sessionTitle.ifBlank { "Sesi konseling aktif" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(6.dp))
                    Text(statusText, style = MaterialTheme.typography.labelLarge, color = statusColor, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                formatLiveClock(uiState.elapsedSeconds),
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold
            )
        }
        if (uiState.speechProcessingState.isDraining || uiState.speechProcessingState.backlogFrames > 0) {
            Text(
                "Transkripsi diproses · ${uiState.speechProcessingState.backlogFrames} frame antrean",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerbatimLiveAcousticSummaryCard(uiState: UnifiedSessionUiState, modifier: Modifier = Modifier) {
    val clinical = MaterialTheme.clinicalColors
    val latestAcoustic = uiState.acousticAnalysis
    val pitchViewport = remember(uiState.livePitch) { verbatimSignalViewport(uiState.livePitch, isArchive = false) }
    val observations = pitchViewport?.observations.orEmpty()
    val latestObs = observations.lastOrNull()
    val latestVoiced = observations.lastOrNull { it.frequencyHz != null }
    val liveWaveform = latestAcoustic?.waveform?.takeIf { it.size > 1 }
        ?: observations.lastOrNull { it.waveform.size > 1 }?.waveform.orEmpty()

    val attributedSpeakerId = remember(uiState.transcriptSegments, latestVoiced?.endOffsetMs) {
        val targetMs = latestVoiced?.endOffsetMs ?: return@remember null
        val match = uiState.transcriptSegments.lastOrNull { seg ->
            seg.isFinal &&
                SpeakerBaselineTracker.isAttributedSpeaker(seg.speakerId) &&
                targetMs in seg.startOffsetMs..(seg.endOffsetMs + SPEAKER_ATTRIBUTION_LAG_MS)
        }
        match?.speakerId
    }
    val baselineHz = remember(attributedSpeakerId, uiState.transcriptSegments) {
        computeSpeakerBaselineHz(attributedSpeakerId, uiState.transcriptSegments)
    }
    val currentHz = if (uiState.isPaused || latestObs?.frequencyHz == null) null else latestObs.frequencyHz
    val deltaHz = if (currentHz != null && baselineHz != null) currentHz - baselineHz else null
    val latestProsody = remember(uiState.transcriptSegments) {
        uiState.transcriptSegments.lastOrNull { it.isFinal && it.prosody?.isAvailable == true }?.prosody
    }
    var acousticDetailsExpanded by rememberSaveable { mutableStateOf(false) }

    val micStateText = when {
        uiState.isPaused -> "Audio dijeda"
        latestAcoustic?.markerType == SessionMarkerType.LOW_AUDIO_QUALITY -> "Kualitas rendah"
        latestAcoustic?.isSilence == true -> "Audio hening"
        latestAcoustic != null -> "Mikrofon aktif"
        else -> "Menunggu PCM"
    }
    val micStateColor = when {
        uiState.isPaused -> clinical.marker
        latestAcoustic?.markerType == SessionMarkerType.LOW_AUDIO_QUALITY -> clinical.recording
        latestAcoustic != null -> clinical.physiological
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, clinical.borderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Audio langsung",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(micStateText, style = MaterialTheme.typography.labelMedium, color = micStateColor, fontWeight = FontWeight.SemiBold)
            }

            LiveAudioWaveformStrip(
                waveform = liveWaveform,
                isPaused = uiState.isPaused,
                isSilence = latestAcoustic?.isSilence == true,
                rmsDb = latestAcoustic?.rmsDb,
                waveColor = clinical.physiological,
                gridColor = clinical.borderSubtle
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Amplitudo aktual", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val rmsLabel = latestAcoustic?.rmsDb?.takeIf { it.isFinite() }?.let {
                    String.format(Locale.US, "RMS %.1f dBFS", it)
                } ?: "RMS —"
                Text(
                    rmsLabel,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Divider(color = clinical.borderSubtle)

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val pitchTitle = if (attributedSpeakerId != null) {
                    "Pitch suara · ${humanSpeakerLabel(attributedSpeakerId)}"
                } else {
                    "Pitch suara"
                }
                Text(
                    pitchTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                deltaHz?.let { delta ->
                    val arrow = if (delta >= 0.0) "↑" else "↓"
                    val deltaColor = if (abs(delta) >= 12.0) clinical.marker else clinical.acoustic
                    Text(
                        "$arrow ${abs(delta).roundToInt()} Hz",
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                        color = deltaColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = currentHz?.roundToInt()?.toString() ?: "—",
                        style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Hz",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                val baselineLabel = baselineHz?.let { "Baseline ${it.roundToInt()} Hz" }
                    ?: if (uiState.isPaused) "Dijeda" else "Mengumpulkan baseline"
                Text(
                    baselineLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            if (pitchViewport != null && observations.any { it.frequencyHz != null }) {
                LivePitchAxisContourChart(
                    viewport = pitchViewport,
                    baselineHz = baselineHz,
                    markers = uiState.divergenceEvents,
                    isPaused = uiState.isPaused,
                    lineColor = clinical.acoustic,
                    markerColor = clinical.marker,
                    gridColor = clinical.borderSubtle
                )
            } else {
                Text(
                    if (uiState.isPaused) "Kontur pitch dijeda." else "Menunggu ujaran bernada untuk menampilkan grafik F0 (Hz).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { acousticDetailsExpanded = !acousticDetailsExpanded }
                    .semantics {
                        stateDescription = if (acousticDetailsExpanded) "Terbuka" else "Tertutup"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Detail akustik", style = MaterialTheme.typography.labelLarge, color = clinical.acoustic)
                Icon(
                    if (acousticDetailsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = clinical.acoustic
                )
            }

            AnimatedVisibility(visible = acousticDetailsExpanded) {
                AcousticDetailsBlock(latestAcoustic, latestProsody)
            }
        }
    }
}

@Composable
private fun LiveAudioWaveformStrip(
    waveform: List<Float>,
    isPaused: Boolean,
    isSilence: Boolean,
    rmsDb: Float?,
    waveColor: Color,
    gridColor: Color
) {
    if (waveform.size < 2) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(MaterialTheme.clinicalColors.surfaceSubtle, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center
        ) {
            Text(
                when {
                    isPaused -> "Waveform dijeda"
                    isSilence -> "Hening (waveform minimal)"
                    else -> "Menunggu sampel mikrofon…"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val activeColor = if (isPaused) MaterialTheme.colorScheme.onSurfaceVariant else waveColor
    val rmsDesc = rmsDb?.takeIf { it.isFinite() }?.let { "${it.roundToInt()} dBFS" } ?: "belum tersedia"
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics { contentDescription = "Waveform PCM aktual mikrofon, RMS $rmsDesc" }
    ) {
        val midY = size.height / 2f
        drawLine(gridColor, Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1.dp.toPx())
        val step = size.width / (waveform.size - 1).toFloat()
        val barWidth = (step * 0.65f).coerceIn(1.5.dp.toPx(), 4.dp.toPx())
        waveform.forEachIndexed { index, amplitude ->
            val x = index * step
            val halfH = amplitude.coerceIn(0f, 1f) * midY * 0.92f
            if (halfH <= 0.5f) {
                drawCircle(activeColor, radius = 1.dp.toPx(), center = Offset(x, midY))
            } else {
                drawLine(activeColor, Offset(x, midY - halfH), Offset(x, midY + halfH), strokeWidth = barWidth)
            }
        }
    }
}

@Composable
private fun LivePitchAxisContourChart(
    viewport: VerbatimSignalViewport,
    baselineHz: Double?,
    markers: List<DivergenceEventEntity>,
    isPaused: Boolean,
    lineColor: Color,
    markerColor: Color,
    gridColor: Color
) {
    val observations = viewport.observations
    val freqs = remember(observations) { observations.mapNotNull { it.frequencyHz } }
    val rawMin = freqs.minOrNull() ?: return
    val rawMax = freqs.maxOrNull() ?: return
    val allMin = baselineHz?.let { minOf(rawMin, it) } ?: rawMin
    val allMax = baselineHz?.let { maxOf(rawMax, it) } ?: rawMax
    val paddingHz = ((allMax - allMin) * 0.15).coerceAtLeast(10.0)
    val lower = (allMin - paddingHz).coerceAtLeast(40.0)
    val upper = allMax + paddingHz
    val mid = (lower + upper) / 2.0
    val activeLine = if (isPaused) MaterialTheme.colorScheme.onSurfaceVariant else lineColor
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    val span = (viewport.endOffsetMs - viewport.startOffsetMs).coerceAtLeast(1L).toFloat()

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(84.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.width(38.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${upper.roundToInt()}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${mid.roundToInt()}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${lower.roundToInt()}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics {
                        contentDescription = "Grafik kontur pitch ${lower.roundToInt()} sampai ${upper.roundToInt()} Hz"
                    }
            ) {
                listOf(0f, 0.5f, 1f).forEach { frac ->
                    val y = frac * size.height
                    drawLine(gridColor.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                baselineHz?.let { base ->
                    val baseY = (1.0 - (base - lower) / (upper - lower)).toFloat().coerceIn(0f, 1f) * size.height
                    drawLine(gridColor, Offset(0f, baseY), Offset(size.width, baseY), strokeWidth = 1.5.dp.toPx(), pathEffect = dash)
                }
                markers.forEach { marker ->
                    val offsetMs = marker.episodeStartElapsedMillis
                    if (offsetMs in viewport.startOffsetMs..viewport.endOffsetMs) {
                        val x = ((offsetMs - viewport.startOffsetMs).toFloat() / span).coerceIn(0f, 1f) * size.width
                        drawLine(markerColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx(), pathEffect = dash)
                    }
                }
                observations.forEachIndexed { idx, obs ->
                    val hz = obs.frequencyHz ?: return@forEachIndexed
                    val midObs = (obs.startOffsetMs + obs.endOffsetMs) / 2.0
                    val x2 = ((midObs - viewport.startOffsetMs) / span).toFloat().coerceIn(0f, 1f) * size.width
                    val y2 = (1.0 - (hz - lower) / (upper - lower)).toFloat().coerceIn(0f, 1f) * size.height
                    val prev = observations.getOrNull(idx - 1)
                    val prevHz = prev?.frequencyHz
                    if (prev != null && prevHz != null && pitchObservationsAreContiguous(prev, obs)) {
                        val prevMid = (prev.startOffsetMs + prev.endOffsetMs) / 2.0
                        val x1 = ((prevMid - viewport.startOffsetMs) / span).toFloat().coerceIn(0f, 1f) * size.width
                        val y1 = (1.0 - (prevHz - lower) / (upper - lower)).toFloat().coerceIn(0f, 1f) * size.height
                        drawLine(activeLine, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2.dp.toPx())
                    } else {
                        drawCircle(activeLine, radius = 2.5.dp.toPx(), center = Offset(x2, y2))
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 38.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val midMs = (viewport.startOffsetMs + viewport.endOffsetMs) / 2L
            Text(
                verbatimAudioTime(viewport.startOffsetMs),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                verbatimAudioTime(midMs),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Kini (${verbatimAudioTime(viewport.endOffsetMs)})",
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AcousticDetailsBlock(acoustic: AcousticAnalysisSnapshot?, prosody: VerbatimProsodyEvidence?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.Micro),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
    ) {
        if (acoustic != null) {
            val q = (acoustic.qualityScore * BPM_PERCENT_SCALE).roundToInt()
            val baselineRms = acoustic.baselineRmsDb?.let { String.format(Locale.US, "%.1f dBFS", it) } ?: "—"
            val devDb = acoustic.baselineDeviationDb?.let { String.format(Locale.US, "%+.1f dB", it) } ?: "—"
            Text(
                "Kualitas $q% · Baseline RMS $baselineRms ($devDb) · ZCR ${"%.3f".format(Locale.US, acoustic.zeroCrossingRate)} · " +
                    "Clipping ${(acoustic.clippingRatio * BPM_PERCENT_SCALE).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "Frame akustik langsung belum tersedia.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (prosody != null && prosody.isAvailable) {
            val f0 = prosody.pitchHz?.let { String.format(Locale.US, "%.1f Hz", it) } ?: "—"
            val loud = prosody.loudness?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
            val jit = prosody.jitter?.let { String.format(Locale.US, "%.4f", it) } ?: "—"
            val shim = prosody.shimmer?.let { String.format(Locale.US, "%.2f dB", it) } ?: "—"
            Text(
                "openSMILE eGeMAPS: F0 $f0 · Loudness $loud · Jitter $jit · Shimmer $shim " +
                    "(${prosody.voicedFrameCount}/${prosody.frameCount} voiced)",
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerbatimLiveHeartRateCard(snapshot: LiveBiosignalSnapshot, modifier: Modifier = Modifier) {
    PhysiologicalMonitoringPanel(snapshot = snapshot, modifier = modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhysiologicalMonitoringPanel(snapshot: LiveBiosignalSnapshot, modifier: Modifier = Modifier) {
    val clinical = MaterialTheme.clinicalColors
    val monitor = snapshot.monitor
    val temp = snapshot.liveTemperature
    var selectedSourceName by rememberSaveable { mutableStateOf(BpmSource.PPG_GREEN.name) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val selectedSource = BpmSource.entries.firstOrNull { it.name == selectedSourceName } ?: BpmSource.PPG_GREEN
    val preview = monitor.liveBpmBySource[selectedSource]
        ?: monitor.activeBpmPreview?.takeIf { selectedSource == BpmSource.PPG_GREEN }
    val watchHr = monitor.liveBpmBySource[BpmSource.WATCH_SDK_HR]
    val selectedPpgType = when (selectedSource) {
        BpmSource.PPG_IR -> PpgType.IR
        BpmSource.PPG_RED_DIAGNOSTIC -> PpgType.RED
        else -> PpgType.GREEN
    }
    val chart = when (selectedPpgType) {
        PpgType.IR -> snapshot.chart?.ir
        PpgType.RED -> snapshot.chart?.red
        PpgType.GREEN -> snapshot.chart?.green
    }
    val summary = monitor.bpmSummaryByChannel[selectedPpgType]
    val fallbackBpm = monitor.latestBpmByChannel[selectedPpgType] ?: snapshot.latestBpmByChannel[selectedPpgType]
    val displayedBpm = preview?.observedBpm?.takeIf { !preview.isStale } ?: fallbackBpm
    val hasLivePpgData = displayedBpm != null || (chart != null && chart.normalized.size >= 2) || watchHr?.observedBpm != null
    val hasLiveTempData = temp.totalSampleCount > 0 ||
        (temp.status != ThermalSignalStatus.UNAVAILABLE && temp.status != ThermalSignalStatus.DISCONNECTED)
    val watchActive = monitor.isMonitoring || hasLivePpgData || hasLiveTempData
    val badgeText = when {
        temp.status == ThermalSignalStatus.PAUSED -> "Dijeda"
        hasLivePpgData || temp.status == ThermalSignalStatus.ACTIVE -> "Terhubung"
        monitor.isMonitoring -> "Menunggu Watch"
        else -> "Watch belum terhubung"
    }
    val badgeColor = when {
        hasLivePpgData || temp.status == ThermalSignalStatus.ACTIVE -> clinical.physiological
        temp.status == ThermalSignalStatus.PAUSED -> clinical.marker
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (hasLivePpgData || hasLiveTempData) {
                clinical.physiological.copy(alpha = 0.4f)
            } else {
                clinical.borderSubtle
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Respons fisiologis",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    badgeText,
                    style = MaterialTheme.typography.labelMedium,
                    color = badgeColor,
                    fontWeight = FontWeight.Medium
                )
            }

            if (!watchActive) {
                Text(
                    "Watch belum terhubung · PPG & suhu kulit tidak aktif",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val fontScale = LocalDensity.current.fontScale
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val sideBySide = maxWidth >= 340.dp && fontScale < 1.3f
                    if (sideBySide) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
                        ) {
                            BpmMetricTile(
                                selectedSource = selectedSource,
                                displayedBpm = displayedBpm,
                                deltaBpm = preview?.deltaBpm,
                                chart = chart,
                                baselineText = preview?.baselineBpm?.takeIf { it.isFinite() }
                                    ?.let { "Baseline ${it.roundToInt()} BPM" }
                                    ?: summary?.medianBpm?.takeIf { it.isFinite() }
                                        ?.let { "Median ${it.roundToInt()} BPM" }
                                    ?: "Baseline 60 dtk",
                                modifier = Modifier.weight(1f)
                            )
                            Divider(
                                color = clinical.borderSubtle,
                                modifier = Modifier.fillMaxHeight().width(1.dp)
                            )
                            TemperatureMetricTile(
                                temp = temp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                            BpmMetricTile(
                                selectedSource = selectedSource,
                                displayedBpm = displayedBpm,
                                deltaBpm = preview?.deltaBpm,
                                chart = chart,
                                baselineText = preview?.baselineBpm?.takeIf { it.isFinite() }
                                    ?.let { "Baseline ${it.roundToInt()} BPM" }
                                    ?: summary?.medianBpm?.takeIf { it.isFinite() }
                                        ?.let { "Median ${it.roundToInt()} BPM" }
                                    ?: "Baseline 60 dtk",
                                modifier = Modifier.fillMaxWidth()
                            )
                            Divider(color = clinical.borderSubtle)
                            TemperatureMetricTile(
                                temp = temp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { detailsExpanded = !detailsExpanded }
                        .semantics { stateDescription = if (detailsExpanded) "Terbuka" else "Tertutup" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Detail fisiologis",
                        style = MaterialTheme.typography.labelLarge,
                        color = clinical.physiological
                    )
                    Icon(
                        if (detailsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = clinical.physiological
                    )
                }

                AnimatedVisibility(visible = detailsExpanded) {
                    PhysiologicalDetailsSection(
                        selectedSource = selectedSource,
                        onSelectSource = { selectedSourceName = it.name },
                        watchHr = watchHr,
                        temp = temp,
                        prvByChannel = monitor.latestPrvByChannel
                    )
                }
            }
        }
    }
}

@Composable
private fun BpmMetricTile(
    selectedSource: BpmSource,
    displayedBpm: Float?,
    deltaBpm: Float?,
    chart: ChartData?,
    baselineText: String,
    modifier: Modifier = Modifier
) {
    val clinical = MaterialTheme.clinicalColors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Denyut nadi · ${selectedSource.displayLabel}",
            style = MaterialTheme.typography.labelMedium,
            color = clinical.physiological,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = displayedBpm?.takeIf { it.isFinite() }?.roundToInt()?.toString() ?: "—",
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "BPM",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
        val deltaText = deltaBpm?.takeIf { it.isFinite() }?.let { delta ->
            val prefix = if (delta >= 0f) "+" else ""
            "$prefix${delta.roundToInt()} BPM"
        } ?: baselineText
        Text(
            deltaText,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        LiveBpmSparkline(
            chart = chart,
            lineColor = clinical.physiological,
            gridColor = clinical.borderSubtle,
            channelLabel = selectedSource.displayLabel
        )
    }
}

@Composable
internal fun TemperatureMetricTile(temp: LiveTemperatureSnapshot, modifier: Modifier = Modifier) {
    val clinical = MaterialTheme.clinicalColors
    val tempStatusTag = when (temp.status) {
        ThermalSignalStatus.ACTIVE -> "Aktif"
        ThermalSignalStatus.WARMING_UP -> "Kalibrasi"
        ThermalSignalStatus.PAUSED -> "Dijeda"
        ThermalSignalStatus.STALE -> "Tertunda"
        ThermalSignalStatus.INVALID_STATUS -> "Tak valid"
        ThermalSignalStatus.AMBIENT_CONFOUND -> "Lingkungan"
        ThermalSignalStatus.DISCONNECTED -> "Terputus"
        ThermalSignalStatus.UNAVAILABLE -> "Nonaktif"
    }
    val valueText = temp.currentSkinTempC?.takeIf { it.isFinite() }?.let {
        String.format(Locale.US, "%.1f", it)
    } ?: "—"
    val deltaText = when {
        temp.deltaC != null && temp.deltaC.isFinite() -> {
            val arrow = if (temp.deltaC >= 0f) "↑" else "↓"
            String.format(Locale.US, "%s %.1f °C baseline", arrow, abs(temp.deltaC))
        }
        temp.status == ThermalSignalStatus.WARMING_UP && temp.warmupRemainingSeconds > 0 ->
            "Baseline ${temp.warmupRemainingSeconds}d"
        else -> temp.statusReason ?: tempStatusTag
    }
    val warnColor = when (temp.status) {
        ThermalSignalStatus.INVALID_STATUS,
        ThermalSignalStatus.AMBIENT_CONFOUND,
        ThermalSignalStatus.STALE -> clinical.marker
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Suhu kulit",
                style = MaterialTheme.typography.labelMedium,
                color = clinical.thermal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                tempStatusTag,
                style = MaterialTheme.typography.labelSmall,
                color = warnColor,
                maxLines = 1
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = valueText,
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "°C",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
        Text(
            deltaText,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = warnColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        LiveTemperatureSparkline(
            points = temp.recentPoints,
            baselineTempC = temp.baselineSkinTempC,
            lineColor = clinical.thermal,
            gridColor = clinical.borderSubtle
        )
    }
}

@Composable
private fun LiveTemperatureSparkline(points: List<SkinTemperatureObservation>, baselineTempC: Float?, lineColor: Color, gridColor: Color) {
    val valid = points.filter { it.isValid && it.skinTemperatureC != null && it.skinTemperatureC.isFinite() }
        .sortedBy { it.elapsedMillis }
    if (valid.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                "Menunggu tren suhu 120d…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val values = valid.map { it.skinTemperatureC!! }
    val rawMin = baselineTempC?.let { minOf(values.min(), it) } ?: values.min()
    val rawMax = baselineTempC?.let { maxOf(values.max(), it) } ?: values.max()
    val spanY = (rawMax - rawMin).coerceAtLeast(0.4f)
    val startMs = valid.first().elapsedMillis
    val endMs = valid.last().elapsedMillis.coerceAtLeast(startMs + 1L)
    val spanX = (endMs - startMs).toFloat()

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics { contentDescription = "Grafik tren suhu kulit 120 detik terakhir" }
    ) {
        if (baselineTempC != null && baselineTempC.isFinite()) {
            val baseY = (1f - (baselineTempC - rawMin) / spanY).coerceIn(0.05f, 0.95f) * size.height
            drawLine(
                color = gridColor,
                start = Offset(0f, baseY),
                end = Offset(size.width, baseY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )
        }
        valid.forEachIndexed { idx, cur ->
            val x2 = ((cur.elapsedMillis - startMs) / spanX).coerceIn(0f, 1f) * size.width
            val y2 = (1f - (cur.skinTemperatureC!! - rawMin) / spanY).coerceIn(0.05f, 0.95f) * size.height
            val prev = valid.getOrNull(idx - 1)
            if (prev != null && (cur.elapsedMillis - prev.elapsedMillis) <= 90_000L) {
                val x1 = ((prev.elapsedMillis - startMs) / spanX).coerceIn(0f, 1f) * size.width
                val y1 = (1f - (prev.skinTemperatureC!! - rawMin) / spanY).coerceIn(0.05f, 0.95f) * size.height
                drawLine(lineColor, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2.dp.toPx())
            } else {
                drawCircle(lineColor, radius = 2.5.dp.toPx(), center = Offset(x2, y2))
            }
        }
    }
}

@Composable
private fun PhysiologicalDetailsSection(
    selectedSource: BpmSource,
    onSelectSource: (BpmSource) -> Unit,
    watchHr: LiveBpmChannelSnapshot?,
    temp: LiveTemperatureSnapshot,
    prvByChannel: Map<PpgType, PrvMetrics>
) {
    val clinical = MaterialTheme.clinicalColors
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.Micro),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
    ) {
        PpgChannelTogglePill(selected = selectedSource, onSelect = onSelectSource)
        if (watchHr?.observedBpm != null && !watchHr.isStale) {
            Text(
                "Galaxy Watch SDK HR (independen): ${watchHr.observedBpm.roundToInt()} BPM",
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Divider(color = clinical.borderSubtle)
        val ambientText = temp.ambientTempC?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.1f °C", it) } ?: "—"
        val slopeText = temp.slopeCPerMin?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%+.2f °C/mnt", it) } ?: "—"
        val minMaxText = if (temp.minSkinTempC != null && temp.maxSkinTempC != null) {
            String.format(Locale.US, "%.1f–%.1f °C", temp.minSkinTempC, temp.maxSkinTempC)
        } else {
            "—"
        }
        val meanMedianText = if (temp.meanSkinTempC != null && temp.medianSkinTempC != null) {
            String.format(Locale.US, "Mean %.2f · Median %.2f °C", temp.meanSkinTempC, temp.medianSkinTempC)
        } else {
            "—"
        }
        Text(
            "Diagnostik Termal · Sekitar $ambientText · Laju $slopeText",
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = clinical.thermal
        )
        Text(
            "Min/Maks $minMaxText · $meanMedianText · Valid ${temp.validSampleCount}/${temp.totalSampleCount} " +
                "· Kebijakan ${temp.algorithmVersion}",
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FullAxisTemperatureChart(
            points = temp.recentPoints,
            baselineTempC = temp.baselineSkinTempC,
            lineColor = clinical.thermal,
            gridColor = clinical.borderSubtle
        )
        if (!temp.autoMarkerEnabled) {
            Text(
                "Penanda otomatis termal nonaktif sampai validasi cadence & ambang pada Watch selesai.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        PrvTechnicalSummaryPanel(prvByChannel)
    }
}

@Composable
private fun FullAxisTemperatureChart(points: List<SkinTemperatureObservation>, baselineTempC: Float?, lineColor: Color, gridColor: Color) {
    val valid = points.filter { it.isValid && it.skinTemperatureC != null && it.skinTemperatureC.isFinite() }
        .sortedBy { it.elapsedMillis }
    if (valid.isEmpty()) return
    val values = valid.map { it.skinTemperatureC!! }
    val rawMin = baselineTempC?.let { minOf(values.min(), it) } ?: values.min()
    val rawMax = baselineTempC?.let { maxOf(values.max(), it) } ?: values.max()
    val spanY = (rawMax - rawMin).coerceAtLeast(0.4f)
    val midVal = (rawMin + rawMax) / 2f
    val startMs = valid.first().elapsedMillis
    val endMs = valid.last().elapsedMillis.coerceAtLeast(startMs + 1L)
    val spanX = (endMs - startMs).toFloat()

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.width(44.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    String.format(Locale.US, "%.1f°", rawMax),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    String.format(Locale.US, "%.1f°", midVal),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    String.format(Locale.US, "%.1f°", rawMin),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics { contentDescription = "Grafik suhu kulit bersumbu 120 detik terakhir" }
            ) {
                listOf(0f, 0.5f, 1f).forEach { frac ->
                    val y = frac * size.height
                    drawLine(gridColor.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                if (baselineTempC != null && baselineTempC.isFinite()) {
                    val baseY = (1f - (baselineTempC - rawMin) / spanY).coerceIn(0.05f, 0.95f) * size.height
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, baseY),
                        end = Offset(size.width, baseY),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    )
                }
                valid.forEachIndexed { idx, cur ->
                    val x2 = ((cur.elapsedMillis - startMs) / spanX).coerceIn(0f, 1f) * size.width
                    val y2 = (1f - (cur.skinTemperatureC!! - rawMin) / spanY).coerceIn(0.05f, 0.95f) * size.height
                    val prev = valid.getOrNull(idx - 1)
                    if (prev != null && (cur.elapsedMillis - prev.elapsedMillis) <= 90_000L) {
                        val x1 = ((prev.elapsedMillis - startMs) / spanX).coerceIn(0f, 1f) * size.width
                        val y1 = (1f - (prev.skinTemperatureC!! - rawMin) / spanY).coerceIn(0.05f, 0.95f) * size.height
                        drawLine(lineColor, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2.dp.toPx())
                    } else {
                        drawCircle(lineColor, radius = 3.dp.toPx(), center = Offset(x2, y2))
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 44.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                formatLiveClock(startMs / 1_000L),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                formatLiveClock(endMs / 1_000L),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PpgChannelTogglePill(selected: BpmSource, onSelect: (BpmSource) -> Unit) {
    val clinical = MaterialTheme.clinicalColors
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
        listOf(BpmSource.PPG_GREEN to "Green", BpmSource.PPG_IR to "IR").forEach { (source, shortLabel) ->
            val active = selected == source
            Surface(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.RadioButton) { onSelect(source) },
                shape = MaterialTheme.shapes.small,
                color = if (active) clinical.physiological.copy(alpha = 0.16f) else Color.Transparent,
                border = BorderStroke(1.dp, if (active) clinical.physiological else clinical.borderSubtle)
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = AppSpacing.Small),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        shortLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) clinical.physiological else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveBpmSparkline(chart: ChartData?, lineColor: Color, gridColor: Color, channelLabel: String) {
    if (chart == null || chart.isEmpty || chart.normalized.size < 2) return
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .semantics { contentDescription = "Grafik gelombang $channelLabel 10 detik terakhir" }
    ) {
        val midY = size.height / 2f
        drawLine(
            color = gridColor,
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        )
        val points = chart.normalized
        val timestamps = chart.timestampsMillis
        val first = timestamps.firstOrNull() ?: return@Canvas
        val last = timestamps.lastOrNull()?.takeIf { it > first } ?: return@Canvas
        val span = (last - first).toFloat()
        for (idx in 1 until points.size) {
            val delta = timestamps[idx] - timestamps[idx - 1]
            if (delta <= 0L || delta > LIVE_GAP_BREAK_MILLIS) continue
            drawLine(
                color = lineColor,
                start = Offset(
                    x = (timestamps[idx - 1] - first).toFloat() / span * size.width,
                    y = (1f - points[idx - 1].coerceIn(0f, 1f)) * size.height
                ),
                end = Offset(
                    x = (timestamps[idx] - first).toFloat() / span * size.width,
                    y = (1f - points[idx].coerceIn(0f, 1f)) * size.height
                ),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

private fun computeSpeakerBaselineHz(attributedSpeakerId: String?, segments: List<SpeechSegment>): Double? {
    if (attributedSpeakerId != null) {
        val speakerSemitones = segments
            .filter { it.isFinal && it.speakerId.equals(attributedSpeakerId, ignoreCase = true) }
            .mapNotNull { it.prosody?.takeIf { p -> p.isAvailable }?.pitchSemitones }
        if (speakerSemitones.size >= BASELINE_MIN_VOICED_FRAMES) {
            val sorted = speakerSemitones.sorted()
            val medianSemi = sorted[sorted.size / 2]
            return 27.5 * 2.0.pow(medianSemi / 12.0)
        }
    }
    return null
}

@Composable
internal fun VerbatimLatestMarkerBanner(marker: DivergenceEventEntity?, onClick: (DivergenceEventEntity) -> Unit) {
    if (marker == null) return
    val clinical = MaterialTheme.clinicalColors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(marker) },
        shape = MaterialTheme.shapes.medium,
        color = clinical.surfaceSubtle,
        border = BorderStroke(1.dp, clinical.marker.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.Small, vertical = AppSpacing.Compact),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
        ) {
            Text(
                formatLiveClock(marker.episodeStartElapsedMillis / 1_000L),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = clinical.marker,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                marker.copyText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun PrvTechnicalSummaryPanel(prvByChannel: Map<PpgType, PrvMetrics>) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
        Text(
            "Pulse Rate Variability (PRV dari PPG — tidak otomatis setara HRV ECG)",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (prvByChannel.isEmpty()) {
            Text(
                "Metrik PRV menunggu jendela PPG lolos quality gate (>= 10 interval valid).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            prvByChannel.forEach { (channel, metrics) ->
                val line = if (!metrics.isQualityPassed) {
                    "${channel.name}: belum memadai (${metrics.insufficiencyReason ?: "SQA"}) · " +
                        "${metrics.validIbiCount}/${metrics.candidateIbiCount} interval valid"
                } else {
                    String.format(
                        Locale.US,
                        "%s (%ds, %d PPI valid): RMSSD %.1f ms · SDNN %.1f ms · pNN50 %.1f%% · Mean PPI %.0f ms",
                        channel.name,
                        metrics.windowSeconds,
                        metrics.validIbiCount,
                        metrics.rmssdMs ?: 0f,
                        metrics.sdnnMs ?: 0f,
                        metrics.pnn50Pct ?: 0f,
                        metrics.meanPpiMs ?: 0f
                    )
                }
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun LiveSessionQuickBookmarkAction(enabled: Boolean, onBookmark: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onBookmark,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.clinicalColors.marker),
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        Icon(Icons.Default.BookmarkAdd, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("Tandai")
    }
}

private fun formatLiveClock(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
