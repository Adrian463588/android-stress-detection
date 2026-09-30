package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.voice.VoiceOutputState
import com.stressdetector.mobile.ml.llm.ModelDownloadState
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.viewmodels.CounselorPhase
import com.stressdetector.mobile.ui.viewmodels.CrisisPersistenceState

@Composable
internal fun CounselorMessageList(model: CounselorScreenModel, innerPadding: PaddingValues) {
    val horizontalPadding = counselorHorizontalPadding(model.navigation.windowSizeClass)
    LazyColumn(
        state = model.layout.listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .widthIn(max = AppSpacing.ReadableContent)
            .padding(horizontal = horizontalPadding),
        contentPadding = PaddingValues(top = AppSpacing.Small, bottom = AppSpacing.Compact),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
    ) { counselorMessageItems(model) }
}

private fun LazyListScope.counselorMessageItems(model: CounselorScreenModel) {
    val runtime = model.runtime
    val state = runtime.state
    state.statusMessage?.let { message -> item { StatusMessageItem(model, message) } }
    if (!state.isModelReady && state.phase != CounselorPhase.CRISIS_ESCALATION) {
        item { ModelMissingCard(state.modelDownloadState, model.navigation.onNavigateToProfile) }
    }
    runtime.aiReminderState?.let { reminderState ->
        item {
            ReminderStatusRow(
                state = reminderState,
                onRetry = { retryReminder(model, reminderState) },
                onRequestNotificationPermission = model.navigation.onRequestNotificationPermission
            )
        }
    }
    (state.crisisPersistenceState as? CrisisPersistenceState.RetryableFailure)?.let { failure ->
        item { CrisisPersistenceRetryCard(failure.message, runtime.viewModel::retryCrisisPersistence) }
    }
    if (state.messages.isEmpty()) {
        item { if (state.isModelReady) emptyChatState() else modelUnavailableEmptyState() }
    }
    items(state.messages, key = { it.id }) { message ->
        ChatMessageBubble(
            message = message,
            actions = ChatMessageActions(
                isSpeaking = isSpeaking(state, message),
                isVoiceStopping = state.voicePlayback.activeMessageId == message.id &&
                    state.voiceOutputState is VoiceOutputState.Stopping,
                onSpeak = runtime.viewModel::toggleVoiceReply,
                onStopSpeaking = runtime.viewModel::stopVoiceReply,
                onOpenCitation = { rawUrl -> openCitation(model.layout.context, rawUrl) },
                onOpenCrisisSupport = { action -> openCrisisSupport(model.layout.context, action) }
            )
        )
    }
}

@Composable
private fun StatusMessageItem(model: CounselorScreenModel, message: String) {
    val state = model.runtime.state
    if (state.phase == CounselorPhase.ERROR || state.phase == CounselorPhase.RETRIEVAL_UNAVAILABLE) {
        RuntimeErrorCard(
            message = message,
            technicalMessage = state.runtimeTechnicalMessage,
            showTechnicalDetails = model.dialogs.showTechnicalDetails,
            onToggleTechnicalDetails = {
                model.dialogs.onTechnicalDetailsChanged(!model.dialogs.showTechnicalDetails)
            },
            onRetry = model.runtime.viewModel::retryLastMessage
        )
    } else {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

@Composable
private fun ModelMissingCard(downloadState: ModelDownloadState, onNavigateToProfile: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Model AI belum siap", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            Text(
                modelDownloadMessage(downloadState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = onNavigateToProfile,
                modifier = Modifier
                    .align(Alignment.End)
                    .semantics { contentDescription = "Buka Profile untuk menyiapkan model" }
            ) { Text("Buka Profile") }
        }
    }
}

private fun modelDownloadMessage(downloadState: ModelDownloadState): String = when (downloadState) {
    is ModelDownloadState.Verifying -> "Model sedang diverifikasi. AI+ akan siap setelah checksum selesai."
    is ModelDownloadState.Downloading -> "Model sedang diunduh. Proses dapat berlanjut saat aplikasi berada di latar belakang."
    is ModelDownloadState.WaitingForNetwork -> downloadState.message
    is ModelDownloadState.TransferRetry -> "Transfer model gagal sementara: ${downloadState.message}"
    else -> "Unduh dan verifikasi Gemma melalui Profile untuk menggunakan chatbot."
}

@Composable
private fun RuntimeErrorCard(
    message: String,
    technicalMessage: String?,
    showTechnicalDetails: Boolean,
    onToggleTechnicalDetails: () -> Unit,
    onRetry: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onToggleTechnicalDetails) {
                    Text(if (showTechnicalDetails) "Sembunyikan detail" else "Detail teknis")
                }
                Button(onClick = onRetry) { Text("Coba lagi") }
            }
            if (showTechnicalDetails && !technicalMessage.isNullOrBlank()) {
                Text(
                    technicalMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun emptyChatState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Percakapan lokal siap", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        Text(
            "Tulis pertanyaan untuk mulai. Pesan diproses di perangkat saat Gemma siap.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun modelUnavailableEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("AI Counselor belum siap", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        Text(
            "Unduh dan verifikasi Gemma melalui Profile untuk menjawab pertanyaan normal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CrisisPersistenceRetryCard(message: String, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRetry) { Text("Coba simpan lagi") }
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}
