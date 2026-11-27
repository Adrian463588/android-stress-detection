@file:Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod", "MagicNumber")

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.domain.engine.SpeechSegment
import com.stressdetector.mobile.domain.engine.VerbatimCueAnalysis
import com.stressdetector.mobile.domain.voice.VoiceOutputState
import com.stressdetector.mobile.domain.voice.VoicePlaybackUiState
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.clinicalColors
import com.stressdetector.mobile.ui.viewmodels.VerbatimMarkerFilter

private const val QUALITY_PERCENT_SCALE = 100

/** Compact speaker-attributed conversation timeline row; zero audio/TTS computation here. */
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
    modifier: Modifier = Modifier,
    linkedMarkers: List<DivergenceEventEntity> = emptyList(),
    onOpenMarkerDetail: ((DivergenceEventEntity) -> Unit)? = null,
    onSetSpeakerLabel: ((String, String) -> Unit)? = null,
    ttsState: VoicePlaybackUiState = VoicePlaybackUiState(),
    canUseTts: Boolean = false,
    onToggleSpeakTts: ((String, String) -> Unit)? = null
) {
    val clinical = MaterialTheme.clinicalColors
    var editing by rememberSaveable(segment.sessionId, segment.id, segment.editedText) { mutableStateOf(false) }
    var draft by rememberSaveable(segment.sessionId, segment.id, segment.editedText) { mutableStateOf(segment.displayText) }
    var details by rememberSaveable(segment.sessionId, segment.id) { mutableStateOf(false) }
    var menuExpanded by rememberSaveable(segment.sessionId, segment.id) { mutableStateOf(false) }

    val speakerDisplay = if (!segment.isFinal || segment.speakerId.equals("Unknown", ignoreCase = true)) {
        "Pembicara belum pasti"
    } else {
        humanSpeakerLabel(segment.speakerId)
    }
    val speakerColor = when {
        speakerDisplay == "Psikolog" -> clinical.acoustic
        speakerDisplay == "Klien" -> clinical.physiological
        speakerDisplay.startsWith("Pembicara belum") -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val isTtsActive = ttsState.activeMessageId == segment.id &&
        (ttsState.state is VoiceOutputState.Preparing || ttsState.state is VoiceOutputState.Speaking)

    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = AppSpacing.Compact),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                verbatimAudioTime(segment.startOffsetMs),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(AppSpacing.Compact))
            Text(
                speakerDisplay,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = speakerColor,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 32.dp)
                    .then(
                        if (segment.isFinal && canEdit) {
                            Modifier.clickable { onCycleSpeaker(segment.id) }
                        } else {
                            Modifier
                        }
                    )
                    .semantics { contentDescription = "Pembicara $speakerDisplay" }
            )
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Aksi ujaran ${verbatimAudioTime(segment.startOffsetMs)}"
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    if (canPlay) {
                        DropdownMenuItem(
                            text = { Text("Putar audio asli") },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onSeekAudio(segment.startOffsetMs)
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                    if (onToggleSpeakTts != null && segment.displayText.isNotBlank()) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when {
                                        isTtsActive -> "Hentikan TTS"
                                        canUseTts -> "Bacakan teks (TTS lokal)"
                                        else -> "Bacakan TTS (jeda dulu)"
                                    }
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (isTtsActive) Icons.Default.Stop else Icons.Default.RecordVoiceOver,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onToggleSpeakTts(segment.id, segment.displayText)
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                    if (segment.isFinal && canEdit) {
                        DropdownMenuItem(
                            text = { Text("Tandai sebagai Psikolog") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onSetSpeakerLabel?.invoke(segment.id, "Psychologist") ?: onCycleSpeaker(segment.id)
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                        DropdownMenuItem(
                            text = { Text("Tandai sebagai Klien") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onSetSpeakerLabel?.invoke(segment.id, "Patient") ?: onCycleSpeaker(segment.id)
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                        DropdownMenuItem(
                            text = { Text("Edit teks") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                draft = segment.displayText
                                editing = true
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                    if (segment.isFinal) {
                        DropdownMenuItem(
                            text = { Text(if (details) "Tutup detail ujaran" else "Detail ujaran") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                details = !details
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
            }
        }

        if (editing) {
            VerbatimTranscriptEditor(
                draft = draft,
                onChange = { draft = it },
                canEdit = canEdit,
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
            if (isTtsActive && onToggleSpeakTts != null) {
                Surface(
                    modifier = Modifier.heightIn(min = 48.dp).clickable { onToggleSpeakTts(segment.id, segment.displayText) },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = AppSpacing.Small, vertical = AppSpacing.Compact),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            if (ttsState.state is VoiceOutputState.Preparing) {
                                "Menyiapkan suara TTS lokal…"
                            } else {
                                "Membacakan teks (TTS lokal) · Ketuk untuk hentikan"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            if (linkedMarkers.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
                ) {
                    linkedMarkers.distinctBy { it.eventId }.forEach { marker ->
                        val family = VerbatimMarkerFilter.family(marker.markerType)
                        val accent = when (family) {
                            VerbatimMarkerFilter.PPG -> clinical.physiological
                            VerbatimMarkerFilter.AUDIO -> clinical.acoustic
                            VerbatimMarkerFilter.THERMAL -> clinical.thermal
                            else -> clinical.marker
                        }
                        Surface(
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .then(
                                    if (onOpenMarkerDetail != null) {
                                        Modifier.clickable { onOpenMarkerDetail(marker) }
                                    } else {
                                        Modifier
                                    }
                                ),
                            shape = MaterialTheme.shapes.small,
                            color = clinical.surfaceSubtle
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = AppSpacing.Small, vertical = AppSpacing.Compact),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    marker.copyText.ifBlank { marker.markerType },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accent
                                )
                            }
                        }
                    }
                }
            }
            VerbatimRecognitionStatus(segment)
        }
        if (segment.isFinal && details) {
            VerbatimUtteranceEvidence(segment)
        }
        Divider(color = clinical.borderSubtle, modifier = Modifier.padding(top = AppSpacing.Micro))
    }
}

internal fun humanSpeakerLabel(rawSpeakerId: String): String = when (rawSpeakerId.trim()) {
    "Psychologist", "Psikolog" -> "Psikolog"
    "Patient", "Klien" -> "Klien"
    "Speaker_0" -> "Pembicara 1"
    "Speaker_1" -> "Pembicara 2"
    "", "Unknown" -> "Pembicara belum pasti"
    else -> rawSpeakerId
}

@Composable
private fun VerbatimRecognitionStatus(segment: SpeechSegment) {
    segment.recognitionReviewReason?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (!segment.isFinal) {
        Text(
            "Teks sementara · diarisasi sedang diproses",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VerbatimUtteranceEvidence(segment: SpeechSegment) {
    Column(
        modifier = Modifier.padding(top = AppSpacing.Micro),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
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
