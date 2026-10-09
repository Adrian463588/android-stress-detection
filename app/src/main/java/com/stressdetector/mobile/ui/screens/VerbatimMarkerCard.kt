@file:Suppress("FunctionNaming")

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.viewmodels.VerbatimMarkerFilter
import java.util.Locale

private const val PERCENT_SCALE = 100

@Composable
internal fun VerbatimMarkerCard(event: DivergenceEventEntity, playbackOffset: Long?, onSeekAudio: (Long) -> Unit) {
    var details by rememberSaveable(event.sessionId, event.eventId) { mutableStateOf(false) }
    val family = VerbatimMarkerFilter.family(event.markerType)
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
            Text(markerTitle(event.markerType), style = MaterialTheme.typography.titleSmall)
            Text(
                "${if (family == VerbatimMarkerFilter.ALL) "Lainnya" else family.label} · ${event.sourceChannels.ifBlank {
                    event.channel
                }} · " +
                    "${verbatimAudioTime(event.episodeStartElapsedMillis)}–${verbatimAudioTime(event.episodeEndElapsedMillis)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                event.copyText.ifBlank {
                    "Bukti ${event.markerType} memerlukan tinjauan konteks."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (event.markerType == "LOW_AUDIO_QUALITY") {
                Text("Kualitas audio, bukan marker stres.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = { playbackOffset?.let(onSeekAudio) },
                enabled = playbackOffset != null,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(if (playbackOffset != null) "Dengarkan ujaran terkait" else "Ujaran terkait belum siap diputar")
            }
            VerbatimDisclosure("Detail bukti", details, { details = it }) { MarkerEvidence(event, family) }
        }
    }
}

@Composable
private fun MarkerEvidence(event: DivergenceEventEntity, family: VerbatimMarkerFilter) {
    Text("Jenis ${event.markerType} · versi ${event.markerConfigVersion}", style = MaterialTheme.typography.bodySmall)
    Text(
        if (family == VerbatimMarkerFilter.AUDIO) {
            "Waktu audio; playback memakai ujaran terkait."
        } else {
            "Waktu event sesi; playback hanya memakai tautan ujaran nyata."
        },
        style = MaterialTheme.typography.bodySmall
    )
    when (family) {
        VerbatimMarkerFilter.AUDIO -> {
            Text("RMS ${event.audioRmsDb.evidenceValue(" dBFS")}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Fitur audio bukan probabilitas stres. Baseline dan kualitas mengikuti sumber marker.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        VerbatimMarkerFilter.PPG -> {
            Text(
                "Probabilitas model ${event.currentProbability.evidenceValue()} · baseline ${event.baselineProbability.evidenceValue()}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "BPM ${event.bpm.evidenceValue()} · baseline ${event.baselineBpm.evidenceValue()} · " +
                    "puncak ${event.peakBpm.evidenceValue()}",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Kanal independen; tidak ada fusi atau kepastian diagnosis.", style = MaterialTheme.typography.bodySmall)
        }
        else -> Unit
    }
    if (family == VerbatimMarkerFilter.AUDIO || family == VerbatimMarkerFilter.PPG) {
        Text(
            (if (event.markerType == "PROSODY_DIVERGENCE") "Skor kualitas audio (heuristik) " else "Kualitas sinyal ") +
                (
                    event.signalQuality.takeIf { it.isFinite() && it in 0f..1f }
                        ?.let { "${(it * PERCENT_SCALE).toInt()}/100" } ?: "tidak tersedia"
                    ),
            style = MaterialTheme.typography.bodySmall
        )
    }
    Text(
        "Playback memerlukan audio siap dan tautan ke ujaran nyata; gunakan playback utama jika tidak tersedia.",
        style = MaterialTheme.typography.bodySmall
    )
}

private fun Float?.evidenceValue(unit: String = ""): String = this?.takeIf { it.isFinite() }
    ?.let { String.format(Locale.ROOT, "%.2f", it) + unit } ?: "tidak tersedia"

private fun markerTitle(type: String): String = when (type) {
    "BPM_TRAJECTORY", "PHYSIOLOGICAL_BPM_TRAJECTORY" -> "Trajektori BPM"
    "BPM_RISE" -> "Kenaikan BPM"
    "BPM_RECOVERY" -> "Pemulihan BPM"
    "TEMPORAL_BASELINE_AROUSAL" -> "Perubahan respons terhadap baseline temporal"
    "CHANNEL_AGREEMENT" -> "Perbedaan antar kanal PPG"
    "ACOUSTIC_DIVERGENCE", "ACOUSTIC_ENERGY_CHANGE" -> "Perubahan energi suara"
    "PROSODY_DIVERGENCE" -> "Perubahan prosodi · openSMILE"
    "LOW_AUDIO_QUALITY" -> "Kualitas audio perlu ditinjau"
    "MODEL_AROUSAL", "STRESS_ELEVATED" -> "Respons fisiologis berbasis model"
    "INCONSISTENCY_CUE" -> "Cue untuk tinjauan konteks"
    "AUXILIARY_EVENT" -> "Bukti tambahan"
    "MANUAL" -> "Catatan manual"
    else -> "Jenis marker: $type"
}
