@file:Suppress("FunctionNaming")

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.engine.VerbatimCue
import com.stressdetector.mobile.domain.engine.VerbatimCueAnalysis
import com.stressdetector.mobile.domain.engine.VerbatimCueSource
import com.stressdetector.mobile.domain.engine.VerbatimProsodyEvidence
import com.stressdetector.mobile.ui.AppSpacing
import java.util.Locale

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60

/** Rendering only: exact reviewed spans and producer explanations arrive from domain state. */
@Composable
internal fun VerbatimReviewText(analysis: VerbatimCueAnalysis, onPlayCue: (Long) -> Unit, canPlay: Boolean = true) {
    val highlight = MaterialTheme.colorScheme.secondaryContainer
    val highlightText = MaterialTheme.colorScheme.onSecondaryContainer
    val content = remember(analysis, highlight, highlightText) {
        buildAnnotatedString {
            append(analysis.text)
            analysis.cues.forEach { cue ->
                addStyle(SpanStyle(background = highlight, color = highlightText), cue.startChar, cue.endCharExclusive)
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(content, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        if (analysis.cues.isNotEmpty()) {
            var expanded by rememberSaveable(analysis.cues.first().segmentId) { mutableStateOf(false) }
            VerbatimDisclosure("${analysis.cues.size} cue leksikal", expanded, { expanded = it }) {
                analysis.cues.forEach { cue -> VerbatimCueButton(cue, onPlayCue, canPlay) }
            }
        }
    }
}

@Composable
internal fun VerbatimCueButton(cue: VerbatimCue, onPlayCue: (Long) -> Unit, canPlay: Boolean = true) {
    var expanded by rememberSaveable(cue.id) { mutableStateOf(false) }
    VerbatimDisclosure(
        "${cue.type.label} · «${cue.matchedText}»",
        expanded,
        { expanded = it },
        modifier = Modifier.semantics {
            contentDescription = "${cue.type.label}: ${cue.matchedText}. Lihat bukti leksikal."
        }
    ) {
        Column(Modifier.padding(horizontal = AppSpacing.Compact), verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
            Text(cue.explanation, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Ujaran ${verbatimAudioTime(cue.startOffsetMs)}–${verbatimAudioTime(cue.endOffsetMs)} · " +
                    if (cue.source == VerbatimCueSource.USER_EDITED) "teks diedit" else "transkrip ASR asli",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Penanda ekspresi untuk ditinjau, bukan bukti desepsi. Waktu mengikuti ujaran, bukan kata.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = { onPlayCue(cue.startOffsetMs) },
                enabled = canPlay,
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "Putar ujaran untuk ${cue.type.label}: ${cue.matchedText}"
                }
            ) { Text(if (canPlay) "Dengarkan ujaran" else "Audio belum siap diputar") }
        }
    }
}

internal fun verbatimAudioTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / MILLIS_PER_SECOND
    return "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
}

@Composable
internal fun VerbatimProsodyStatus(evidence: VerbatimProsodyEvidence?) {
    val message = when {
        evidence == null -> "openSMILE: evidence per ujaran tidak tersedia pada arsip ini."
        !evidence.isAvailable -> "openSMILE: ${evidence.unavailableReason ?: "voiced frame tidak memadai"}"
        else ->
            "openSMILE eGeMAPSv02 · ${evidence.voicedFrameCount}/${evidence.frameCount} frame voiced. " +
                prosodyValues(evidence) + "\n" +
                "Pitch, loudness, jitter, shimmer adalah fitur akustik, bukan prediksi stres."
    }
    Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun prosodyValues(evidence: VerbatimProsodyEvidence): String = listOfNotNull(
    evidence.pitchHz?.let { "Pitch rerata geometrik ujaran ${"%.1f".format(Locale.ROOT, it)} Hz" },
    evidence.loudness?.let { "Loudness ${"%.3f".format(Locale.ROOT, it)}" },
    evidence.jitter?.let { "Jitter ${"%.4f".format(Locale.ROOT, it)}" },
    evidence.shimmer?.let { "Shimmer ${"%.2f".format(Locale.ROOT, it)} dB" }
).joinToString(" · ")
