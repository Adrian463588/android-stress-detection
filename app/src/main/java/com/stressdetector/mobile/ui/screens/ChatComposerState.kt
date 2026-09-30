package com.stressdetector.mobile.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.stressdetector.mobile.domain.voice.VoiceInputState
import com.stressdetector.mobile.ui.viewmodels.CounselorUiState
import com.stressdetector.mobile.ui.viewmodels.CounselorViewModel

internal data class ChatComposerState(
    val inputText: String,
    val onInputChanged: (String) -> Unit,
    val isGenerating: Boolean,
    val voiceBusy: Boolean,
    val onCancelGeneration: () -> Unit,
    val onSend: () -> Unit,
    val onVoice: () -> Unit
)

internal fun VoiceInputState.isVoiceBusy(): Boolean = this is VoiceInputState.Initializing ||
    this is VoiceInputState.Listening ||
    this is VoiceInputState.Transcribing

internal fun sendChatMessage(state: CounselorUiState, inputText: String, viewModel: CounselorViewModel, clearInput: () -> Unit) {
    if (inputText.isBlank()) return
    clearInput()
    if (state.voiceInputState is VoiceInputState.TranscriptReady) {
        viewModel.sendVoiceMessage(inputText)
    } else {
        viewModel.sendMessage(inputText)
    }
}

internal fun handleVoiceAction(
    state: CounselorUiState,
    viewModel: CounselorViewModel,
    context: Context,
    microphoneLauncher: ActivityResultLauncher<String>
) {
    when {
        state.voiceInputState.isVoiceBusy() -> viewModel.stopVoiceInput()
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED -> viewModel.startVoiceInput()
        else -> microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

@Composable
internal fun VoiceStatus(model: CounselorScreenModel) {
    when (val voiceState = model.runtime.state.voiceInputState) {
        is VoiceInputState.Initializing -> VoiceStatusText(voiceState.stage)
        VoiceInputState.Listening -> VoiceStatusText("Mendengarkan…")
        VoiceInputState.Transcribing -> VoiceStatusText("Memproses suara…")
        is VoiceInputState.TranscriptReady -> VoiceStatusText("Transcript siap diperiksa")
        else -> Unit
    }
}

@Composable
private fun VoiceStatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@Composable
internal fun ChatComposer(state: ChatComposerState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedTextField(
            value = state.inputText,
            onValueChange = state.onInputChanged,
            label = { Text("Pertanyaan") },
            placeholder = { Text("Tanyakan sesuatu…") },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .semantics {
                    stateDescription = if (state.isGenerating) {
                        "AI sedang menyiapkan jawaban"
                    } else {
                        "Dukungan krisis lokal tetap tersedia saat model belum siap"
                    }
                },
            maxLines = 4,
            shape = MaterialTheme.shapes.large,
            enabled = !state.isGenerating,
            trailingIcon = {
                if (state.isGenerating) {
                    IconButton(onClick = state.onCancelGeneration) {
                        Icon(Icons.Default.Close, contentDescription = "Batal", tint = MaterialTheme.colorScheme.error)
                    }
                } else {
                    IconButton(onClick = state.onSend, enabled = state.inputText.isNotBlank()) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Kirim pertanyaan",
                            tint = if (state.inputText.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            }
                        )
                    }
                }
            }
        )
        IconButton(
            onClick = state.onVoice,
            enabled = !state.isGenerating,
            modifier = Modifier.semantics {
                stateDescription = if (state.voiceBusy) "Perekaman suara aktif" else "Perekaman suara tidak aktif"
            }
        ) {
            Icon(
                if (state.voiceBusy) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (state.voiceBusy) "Berhenti merekam" else "Gunakan voice AI",
                tint = if (state.voiceBusy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    }
}
