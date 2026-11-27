@file:Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod", "MagicNumber")

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.domain.engine.SpeechSegment
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.clinicalColors
import com.stressdetector.mobile.ui.viewmodels.VerbatimMarkerFilter
import java.util.Locale

private const val MAX_NOTE_LEN = 500

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VerbatimMarkerDetailSheet(
    marker: DivergenceEventEntity,
    linkedSegment: SpeechSegment?,
    playbackOffsetMs: Long?,
    canAnnotate: Boolean,
    onDismiss: () -> Unit,
    onPlayFromMarker: (Long) -> Unit,
    onSavePsychologistNote: (String) -> Unit
) {
    val clinical = MaterialTheme.clinicalColors
    val family = VerbatimMarkerFilter.family(marker.markerType)
    val accent = when (family) {
        VerbatimMarkerFilter.PPG -> clinical.physiological
        VerbatimMarkerFilter.AUDIO -> clinical.acoustic
        VerbatimMarkerFilter.THERMAL -> clinical.thermal
        else -> clinical.marker
    }
    var noteDraft by rememberSaveable(marker.eventId) { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
        ) {
            Text(
                "${verbatimAudioTime(marker.episodeStartElapsedMillis)}–${verbatimAudioTime(marker.episodeEndElapsedMillis)} · " +
                    "${family.label} (${marker.sourceChannels.ifBlank { marker.channel }})",
                style = MaterialTheme.typography.labelLarge,
                color = accent,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                marker.copyText.ifBlank { "Bukti ${marker.markerType}" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = clinical.surfaceSubtle,
                border = BorderStroke(1.dp, clinical.borderSubtle)
            ) {
                Column(modifier = Modifier.padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val unitSuffix = marker.unit?.let { " $it" }.orEmpty()
                    val baselineStr = (marker.baselineValue ?: marker.baselineBpm ?: marker.audioBaselineRmsDb)
                        ?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f%s", it, unitSuffix) } ?: "—"
                    val observedStr = (marker.observedValue ?: marker.bpm ?: marker.audioRmsDb)
                        ?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f%s", it, unitSuffix) } ?: "—"
                    val deltaVal = marker.deltaValue ?: marker.bpmDelta ?: marker.audioBaselineDeviationDb ?: marker.delta
                    val deltaStr = deltaVal?.takeIf { it.isFinite() }?.let {
                        val sign = if (it >= 0f) "+" else ""
                        String.format(Locale.US, "%s%.1f%s", sign, it, unitSuffix)
                    } ?: "—"

                    val isThermal = marker.markerType ==
                        com.stressdetector.mobile.domain.engine.ThermalPolicyConfig.MARKER_TYPE_THERMAL_DIVERGENCE
                    MetricRow(
                        if (isThermal) "Baseline suhu" else "Baseline pembicara / nadi",
                        baselineStr
                    )
                    MetricRow(
                        if (isThermal) "Observasi suhu" else "Observasi jendela",
                        observedStr
                    )
                    MetricRow("Perubahan terukur", deltaStr, valueColor = accent)
                    if (isThermal) {
                        val ambientStr = marker.ambientTemperatureC?.takeIf { it.isFinite() }
                            ?.let { String.format(Locale.US, "%.1f °C", it) } ?: "—"
                        val ambientDeltaStr = marker.ambientDeltaC?.takeIf { it.isFinite() }
                            ?.let { "${if (it >= 0f) "+" else ""}${String.format(Locale.US, "%.1f °C", it)}" }
                        MetricRow(
                            "Suhu sekitar",
                            ambientDeltaStr?.let { "$ambientStr (Δ $it)" } ?: ambientStr
                        )
                        marker.slopeCelsiusPerMin?.takeIf { it.isFinite() }?.let { slope ->
                            MetricRow(
                                "Laju perubahan",
                                String.format(Locale.US, "%+.2f °C/mnt", slope)
                            )
                        }
                        marker.thermalDirection?.let { dir ->
                            val dirLabel = when (dir) {
                                "INCREASE" -> "Meningkat"
                                "DECREASE" -> "Menurun"
                                "RECOVERY" -> "Pulih"
                                else -> dir
                            }
                            MetricRow("Arah perubahan", dirLabel, valueColor = accent)
                        }
                        MetricRow("Versi kebijakan", marker.markerConfigVersion)
                    }
                    MetricRow(
                        "Kualitas sinyal",
                        if (marker.signalQuality.isFinite() && marker.signalQuality > 0f) {
                            "${(marker.signalQuality * 100f).toInt()}%"
                        } else {
                            "—"
                        }
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, clinical.borderSubtle)
            ) {
                Column(modifier = Modifier.padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (linkedSegment != null) {
                            "Percakapan terkait · ${linkedSegment.displaySpeakerLabel} · ${verbatimAudioTime(linkedSegment.startOffsetMs)}"
                        } else {
                            "Percakapan terkait"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        linkedSegment?.displayText?.ifBlank { "(tidak ada transkrip teks)" }
                            ?: "Belum ada ujaran yang tertaut pada interval waktu ini.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    val targetPlayMs = playbackOffsetMs ?: linkedSegment?.startOffsetMs
                    OutlinedButton(
                        onClick = { targetPlayMs?.let(onPlayFromMarker) },
                        enabled = targetPlayMs != null,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Dengarkan")
                    }
                }
            }

            OutlinedTextField(
                value = noteDraft,
                onValueChange = { noteDraft = it.take(MAX_NOTE_LEN) },
                label = { Text("Catatan psikolog") },
                placeholder = { Text("Tambahkan observasi klinis…") },
                enabled = canAnnotate,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val trimmed = noteDraft.trim()
                    if (trimmed.isNotBlank()) {
                        onSavePsychologistNote(
                            "[${verbatimAudioTime(marker.episodeStartElapsedMillis)}] $trimmed"
                        )
                        noteDraft = ""
                        onDismiss()
                    }
                },
                enabled = canAnnotate && noteDraft.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text("Simpan catatan", fontWeight = FontWeight.SemiBold)
            }
            Text(
                if (marker.markerType == com.stressdetector.mobile.domain.engine.ThermalPolicyConfig.MARKER_TYPE_THERMAL_DIVERGENCE) {
                    "Perubahan suhu teramati selama interval tersebut; hubungan sebab-akibat belum diketahui dan bukan diagnosis."
                } else {
                    "Observasi akustik dan fisiologis membantu meninjau momen percakapan; interpretasi tetap pada psikolog."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = AppSpacing.Large)
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = valueColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}
