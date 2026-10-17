@file:Suppress("FunctionNaming", "LongParameterList")

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.engine.SpeechSegment
import com.stressdetector.mobile.domain.engine.VerbatimCueAnalysis
import com.stressdetector.mobile.ui.AppSpacing

private const val QUALITY_PERCENT_SCALE = 100

/** Read-first transcript; native evidence is received, never computed or loaded here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerbatimTranscriptCard(
    segment: SpeechSegment,
    analysis: VerbatimCueAnalysis,
    canPlay: Boolean,
    canEdit: Boolean,
    onCycleSpeaker: (String) -> Unit,
    onEditTranscript: (String, String) -> Unit,
    onSeekAudio: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by rememberSaveable(segment.sessionId, segment.id, segment.editedText) { mutableStateOf(false) }
    var draft by rememberSaveable(segment.sessionId, segment.id, segment.editedText) { mutableStateOf(segment.displayText) }
    var details by rememberSaveable(segment.sessionId, segment.id) { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
            ) {
                Text(
                    if (!segment.isFinal || segment.speakerId == "Unknown") "Pembicara belum pasti" else segment.displaySpeakerLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${verbatimAudioTime(segment.startOffsetMs)}–${verbatimAudioTime(segment.endOffsetMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (editing) {
                VerbatimTranscriptEditor(
                    draft,
                    { draft = it },
                    canEdit,
                    onSave = {
                        onEditTranscript(segment.id, draft)
                        editing = false
                    },
                    onCancel = {
                        draft = segment.displayText
                        editing = false
                    }
                )
            } else {
                VerbatimReviewText(analysis, onSeekAudio, canPlay)
                VerbatimRecognitionStatus(segment)
                VerbatimUtteranceActions(segment, canPlay, canEdit, onSeekAudio, onCycleSpeaker) {
                    draft = segment.displayText
                    editing = true
                }
            }
            if (segment.isFinal) {
                VerbatimDisclosure("Detail ujaran", details, { details = it }) {
                    VerbatimUtteranceEvidence(segment)
                }
            }
        }
    }
}

@Composable
private fun VerbatimRecognitionStatus(segment: SpeechSegment) {
    segment.recognitionReviewReason?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (!segment.isFinal) {
        Text(
            "Teks sementara · diarization sedang diproses",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VerbatimUtteranceEvidence(segment: SpeechSegment) {
    Text("Bahasa ${segment.language} · UTC ${segment.startTimestampUtc}", style = MaterialTheme.typography.bodySmall)
    Text(
        if (segment.confidence.isFinite() && segment.confidence > 0f && segment.confidence <= 1f) {
            "Skor kualitas audio ${(segment.confidence * QUALITY_PERCENT_SCALE).toInt()}/100 · heuristik, bukan confidence ASR"
        } else {
            "Skor kualitas audio tidak tersedia"
        },
        style = MaterialTheme.typography.bodySmall
    )
    if (segment.editedText != null) Text("ASR asli: ${segment.rawText}", style = MaterialTheme.typography.bodySmall)
    segment.decoderEvidence?.let { evidence ->
        val result = evidence.crop ?: evidence.wholeUtterance
        Text("ASR ${result.backend} · ${result.modelRevision} · ${evidence.status}", style = MaterialTheme.typography.bodySmall)
        if (evidence.crop != null) Text("Ujaran utuh: ${evidence.wholeUtterance.rawText}", style = MaterialTheme.typography.bodySmall)
        evidence.precedingPartial?.let {
            Text("Hipotesis sementara, bukan hasil final: ${it.rawText}", style = MaterialTheme.typography.bodySmall)
        }
    }
    VerbatimProsodyStatus(segment.prosody)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerbatimTranscriptEditor(
    draft: String,
    onChange: (String) -> Unit,
    canEdit: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    OutlinedTextField(draft, onChange, modifier = Modifier.fillMaxWidth(), label = { Text("Koreksi transkrip") })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
        Button(enabled = canEdit && draft.isNotBlank(), onClick = onSave, modifier = Modifier.heightIn(min = 48.dp)) { Text("Simpan") }
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Batal") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerbatimUtteranceActions(
    segment: SpeechSegment,
    canPlay: Boolean,
    canEdit: Boolean,
    onSeek: (Long) -> Unit,
    onCycleSpeaker: (String) -> Unit,
    onEdit: () -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
        if (canPlay) {
            TextButton(
                onClick = { onSeek(segment.startOffsetMs) },
                enabled = segment.startOffsetMs >= 0L,
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "Dengarkan ujaran pada ${verbatimAudioTime(segment.startOffsetMs)}"
                }
            ) { Text("Dengarkan") }
        }
        if (segment.isFinal) {
            TextButton(onClick = onEdit, enabled = canEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit teks") }
            TextButton(
                onClick = { onCycleSpeaker(segment.id) },
                enabled = canEdit,
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "Ubah label pembicara ${segment.displaySpeakerLabel}"
                }
            ) { Text("Ubah label") }
        }
    }
}
