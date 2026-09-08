package com.stressdetector.mobile.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.stressdetector.mobile.data.search.SecureWebUrlPolicy
import com.stressdetector.mobile.domain.engine.CrisisSupportAction
import com.stressdetector.mobile.domain.engine.EvidenceSourceTier
import com.stressdetector.mobile.domain.engine.LlmRuntimeReadiness
import com.stressdetector.mobile.domain.engine.RagMode
import com.stressdetector.mobile.domain.search.WebSearchMode
import com.stressdetector.mobile.domain.voice.VoiceInputState
import com.stressdetector.mobile.domain.voice.VoiceOutputState
import com.stressdetector.mobile.domain.voice.VoiceSendMode
import com.stressdetector.mobile.ml.llm.ModelDownloadState
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.components.CounselorMessageText
import com.stressdetector.mobile.ui.components.WebSearchModeDialog
import com.stressdetector.mobile.ui.screens.collectAsStateWithLifecycleCompat
import com.stressdetector.mobile.ui.viewmodels.AiReminderViewModel
import com.stressdetector.mobile.ui.viewmodels.CounselorPhase
import com.stressdetector.mobile.ui.viewmodels.CounselorViewModel
import com.stressdetector.mobile.ui.viewmodels.CrisisPersistenceState
import com.stressdetector.mobile.ui.viewmodels.UiChatMessage
import com.stressdetector.mobile.ui.viewmodels.UiChatSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
fun AiCounselorScreen(
    viewModel: CounselorViewModel,
    windowSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
    onNavigateToLibrary: () -> Unit,
    aiReminderViewModel: AiReminderViewModel? = null,
    onNavigateToProfile: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {}
) {
    // using regular collectAsStateWithLifecycle to avoid custom Compat issues, assuming it works as typical Compose
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val aiReminderState = if (aiReminderViewModel == null) {
        null
    } else {
        val observed by aiReminderViewModel.uiState.collectAsStateWithLifecycleCompat()
        observed
    }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showTechnicalDetails by remember { mutableStateOf(false) }
    var sessionPendingDelete by remember { mutableStateOf<UiChatSession?>(null) }
    var showDeleteAllConfirmation by remember { mutableStateOf(false) }
    var showWebSearchMode by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val microphoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.startVoiceInput() }

    if (showWebSearchMode) {
        WebSearchModeDialog(
            mode = state.webSearchMode,
            onModeSelected = { mode ->
                viewModel.setWebSearchMode(mode)
                showWebSearchMode = false
            },
            onDismiss = { showWebSearchMode = false }
        )
    }

    if (state.webConsentRequired) {
        AlertDialog(
            onDismissRequest = viewModel::dismissWebConsent,
            title = { Text("Izinkan Web Search?") },
            text = {
                Text(
                    state.pendingWebQuery?.let {
                        "Pertanyaan berikut akan dipakai untuk mencari maksimal tiga sumber HTTPS secara otomatis " +
                            "setelah persetujuan:\n\n$it\n\nChat, transcript, riwayat stress, persona, serta " +
                            "dokumen privat tidak dikirim. Server tetap dapat melihat permintaan jaringan dan alamat perangkat."
                    }
                        ?: "AI akan mencari dan membaca maksimal tiga sumber HTTPS secara otomatis setelah Anda " +
                        "menyetujui akses jaringan. Chat, transcript, riwayat, dan dokumen privat tetap di perangkat."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmWebConsent) { Text("Izinkan") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissWebConsent) { Text("Batal") }
            }
        )
    }

    sessionPendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionPendingDelete = null },
            title = { Text("Hapus chat?") },
            text = { Text("Chat '${session.title}' dan seluruh pesannya akan dihapus dari perangkat.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteChatSession(session.id)
                    sessionPendingDelete = null
                }) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { sessionPendingDelete = null }) { Text("Batal") }
            }
        )
    }

    if (showDeleteAllConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirmation = false },
            title = { Text("Hapus semua chat?") },
            text = { Text("Semua histori chat AI Counselor akan dihapus dari perangkat dan tidak dapat dipulihkan.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllChats()
                    showDeleteAllConfirmation = false
                }) { Text("Hapus semua") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirmation = false }) { Text("Batal") }
            }
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.cancelVoiceInput()
                viewModel.stopVoiceReply()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopVoiceReply()
        }
    }

    val motion = com.stressdetector.mobile.ui.LocalAppMotion.current

    LaunchedEffect(state.voiceTranscript) {
        state.voiceTranscript?.let { transcript ->
            if (state.voiceSendMode == VoiceSendMode.REVIEW) inputText = transcript
        }
    }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content) {
        if (state.messages.isNotEmpty()) {
            if (motion.enabled) {
                listState.animateScrollToItem(state.messages.lastIndex)
            } else {
                listState.scrollToItem(state.messages.lastIndex)
            }
        }
    }

    val horizontalPadding = when (windowSizeClass) {
        WindowWidthSizeClass.Compact -> 16.dp
        WindowWidthSizeClass.Medium -> 48.dp
        WindowWidthSizeClass.Expanded -> 96.dp
        else -> 16.dp
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                ChatSessionSidebar(
                    sessions = state.chatSessions,
                    activeSessionId = state.activeChatSessionId,
                    onNewChat = {
                        viewModel.newChat()
                        scope.launch { drawerState.close() }
                    },
                    onSelectSession = { sessionId ->
                        viewModel.selectChatSession(sessionId)
                        scope.launch { drawerState.close() }
                    },
                    onDeleteSession = { session -> sessionPendingDelete = session },
                    onDeleteAll = { showDeleteAllConfirmation = true }
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding, vertical = AppSpacing.Screen)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Default.Menu, contentDescription = "Buka histori chat")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI Counselor",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = when {
                            state.phase == CounselorPhase.CRISIS_ESCALATION -> "Dukungan keselamatan"
                            !state.isModelReady && state.modelDownloadState is ModelDownloadState.Verifying -> "Model sedang diverifikasi"
                            !state.isModelReady && state.modelDownloadState is ModelDownloadState.Downloading -> "Model sedang diunduh"
                            !state.isModelReady && state.modelDownloadState is ModelDownloadState.WaitingForNetwork ->
                                (state.modelDownloadState as ModelDownloadState.WaitingForNetwork).message
                            !state.isModelReady && state.modelDownloadState is ModelDownloadState.TransferRetry ->
                                "Transfer model akan dicoba lagi"
                            !state.isModelReady -> "Model belum tersedia"
                            state.runtimeReadiness is LlmRuntimeReadiness.Unknown ||
                                state.runtimeReadiness is LlmRuntimeReadiness.Checking -> "Memeriksa AI lokal"
                            state.runtimeReadiness is LlmRuntimeReadiness.Unavailable -> "Runtime bermasalah"
                            state.retrievalMode == RagMode.WEB_GROUNDED -> "Berbasis sumber web"
                            state.retrievalMode == RagMode.BUNDLED_GROUNDED -> "Berbasis dataset bawaan"
                            state.retrievalMode == RagMode.GROUNDED -> "Berbasis dokumen"
                            state.runtimeReadiness is LlmRuntimeReadiness.Ready -> "Gemma 4 siap offline · mode umum"
                            else -> "Mode umum"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                IconButton(onClick = { showWebSearchMode = true }) {
                    Icon(
                        Icons.Default.Language,
                        contentDescription = when (state.webSearchMode) {
                            WebSearchMode.Disabled -> "Aktifkan pencarian web"
                            WebSearchMode.NextMessage -> "Pencarian web aktif untuk pesan berikutnya"
                            WebSearchMode.Persistent -> "Pencarian web otomatis selalu aktif"
                        },
                        tint = if (state.webSearchMode == WebSearchMode.Disabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
                if (windowSizeClass == WindowWidthSizeClass.Compact) {
                    IconButton(onClick = onNavigateToLibrary) {
                        Icon(Icons.Default.Description, contentDescription = "Buka dokumen")
                    }
                } else {
                    TextButton(
                        onClick = onNavigateToLibrary,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Dokumen") }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.Small))

            state.statusMessage?.let { message ->
                if (
                    state.phase == CounselorPhase.ERROR ||
                    state.phase == CounselorPhase.RETRIEVAL_UNAVAILABLE
                ) {
                    RuntimeErrorCard(
                        message = message,
                        technicalMessage = state.runtimeTechnicalMessage,
                        showTechnicalDetails = showTechnicalDetails,
                        onToggleTechnicalDetails = { showTechnicalDetails = !showTechnicalDetails },
                        onRetry = viewModel::retryLastMessage
                    )
                } else {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }

            if (!state.isModelReady && state.phase != CounselorPhase.CRISIS_ESCALATION) {
                ModelMissingCard(state.modelDownloadState)
            }

            aiReminderState?.let { reminderState ->
                ReminderStatusCard(
                    state = reminderState,
                    onRetry = {
                        if (reminderState.status is com.stressdetector.mobile.domain.reminder.AiReminderStatus.NeedsConsent) {
                            onNavigateToProfile()
                        } else {
                            checkNotNull(aiReminderViewModel).retry()
                        }
                    },
                    onRequestNotificationPermission = onRequestNotificationPermission
                )
            }

            (state.crisisPersistenceState as? CrisisPersistenceState.RetryableFailure)?.let { failure ->
                CrisisPersistenceRetryCard(
                    message = failure.message,
                    onRetry = viewModel::retryCrisisPersistence
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                if (state.messages.isEmpty() && state.isModelReady) {
                    item { emptyChatState() }
                }
                items(state.messages, key = { it.id }) { message ->
                    ChatMessageBubble(
                        message = message,
                        isSpeaking = state.voicePlayback.activeMessageId == message.id &&
                            (
                                state.voiceOutputState is VoiceOutputState.Speaking ||
                                    state.voiceOutputState is VoiceOutputState.Preparing ||
                                    state.voiceOutputState is VoiceOutputState.Stopping
                                ),
                        isVoiceStopping = state.voicePlayback.activeMessageId == message.id &&
                            state.voiceOutputState is VoiceOutputState.Stopping,
                        onSpeak = viewModel::toggleVoiceReply,
                        onStopSpeaking = viewModel::stopVoiceReply,
                        onOpenCitation = { rawUrl ->
                            SecureWebUrlPolicy.normalizeForApproval(rawUrl)?.toString()?.let { url ->
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(url)
                                        )
                                    )
                                }
                            }
                        },
                        onOpenCrisisSupport = { action ->
                            runCatching {
                                val actionType = if (action.uri.startsWith("tel:")) {
                                    android.content.Intent.ACTION_DIAL
                                } else {
                                    android.content.Intent.ACTION_VIEW
                                }
                                context.startActivity(
                                    android.content.Intent(actionType, android.net.Uri.parse(action.uri))
                                )
                            }
                        }
                    )
                }
                // Add extra space at the bottom so latest message is never hidden behind keyboard
                item { Spacer(modifier = Modifier.height(AppSpacing.Compact)) }
            }

            Spacer(modifier = Modifier.height(AppSpacing.Compact))
            state.voiceError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            when (val voiceState = state.voiceInputState) {
                is VoiceInputState.Initializing -> Text(
                    voiceState.stage,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                VoiceInputState.Listening -> Text(
                    "Mendengarkan…",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                VoiceInputState.Transcribing -> Text(
                    "Memproses suara…",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                is VoiceInputState.TranscriptReady -> Text(
                    "Transcript siap diperiksa",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                else -> Unit
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text("Pertanyaan") },
                    placeholder = { Text("Tanyakan sesuatu...") },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    maxLines = 4,
                    shape = MaterialTheme.shapes.large,
                    enabled = !state.isGenerating,
                    trailingIcon = {
                        if (state.isGenerating) {
                            IconButton(onClick = viewModel::cancelGeneration) {
                                Icon(Icons.Default.Close, contentDescription = "Batal", tint = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    if (inputText.isNotBlank()) {
                                        val textToSend = inputText
                                        inputText = ""
                                        if (state.voiceInputState is VoiceInputState.TranscriptReady) {
                                            viewModel.sendVoiceMessage(textToSend)
                                        } else {
                                            viewModel.sendMessage(textToSend)
                                        }
                                    }
                                },
                                enabled = inputText.isNotBlank()
                            ) {
                                Icon(
                                    Icons.Default.Send,
                                    contentDescription = "Kirim",
                                    tint = if (inputText.isNotBlank()) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = 0.5f
                                        )
                                    }
                                )
                            }
                        }
                    }
                )
                val voiceBusy = state.voiceInputState is VoiceInputState.Initializing ||
                    state.voiceInputState is VoiceInputState.Listening ||
                    state.voiceInputState is VoiceInputState.Transcribing
                IconButton(
                    onClick = {
                        if (voiceBusy) {
                            viewModel.stopVoiceInput()
                        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.startVoiceInput()
                        } else {
                            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !state.isGenerating
                ) {
                    Icon(
                        if (voiceBusy) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (voiceBusy) "Berhenti merekam" else "Gunakan voice AI",
                        tint = if (voiceBusy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatSessionSidebar(
    sessions: List<UiChatSession>,
    activeSessionId: String,
    onNewChat: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (UiChatSession) -> Unit,
    onDeleteAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Riwayat chat", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        }
        Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) {
            Text("Chat baru")
        }
        TextButton(onClick = onDeleteAll, modifier = Modifier.fillMaxWidth()) {
            Text("Hapus semua chat")
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (sessions.isEmpty()) {
            Text(
                "Belum ada histori chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    ChatSessionCard(
                        session = session,
                        selected = session.id == activeSessionId,
                        onClick = { onSelectSession(session.id) },
                        onDelete = { onDeleteSession(session) }
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatSessionCard(session: UiChatSession, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                this.selected = selected
                stateDescription = if (selected) "Chat aktif" else "Chat tidak aktif"
            }
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2
                )
                Text(
                    text = formatSessionDate(session.lastMessageAtMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Hapus chat")
            }
        }
    }
}

private fun formatSessionDate(timestampMillis: Long): String = SimpleDateFormat(
    "dd MMM yyyy, HH:mm",
    Locale.getDefault()
).format(Date(timestampMillis))

@Composable
private fun ModelMissingCard(downloadState: ModelDownloadState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            when (downloadState) {
                is ModelDownloadState.Verifying -> "Model sedang diverifikasi. AI+ akan siap setelah checksum selesai."
                is ModelDownloadState.Downloading -> "Model sedang diunduh. Proses dapat berlanjut saat aplikasi berada di latar belakang."
                is ModelDownloadState.WaitingForNetwork -> downloadState.message
                is ModelDownloadState.TransferRetry -> "Transfer model gagal sementara: ${downloadState.message}"
                else -> "Model AI belum tersedia. Unduh melalui Profile untuk menggunakan chatbot."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp)
        )
    }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
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
        Text(
            text = "Percakapan lokal siap",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            text = "Tulis pertanyaan untuk mulai. Pesan diproses di perangkat saat Gemma siap.",
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onRetry) {
                    Text("Coba simpan lagi")
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun ChatMessageBubble(
    message: UiChatMessage,
    isSpeaking: Boolean,
    isVoiceStopping: Boolean,
    onSpeak: (String, String) -> Unit,
    onStopSpeaking: () -> Unit,
    onOpenCitation: (String) -> Unit,
    onOpenCrisisSupport: (CrisisSupportAction) -> Unit
) {
    val isUser = message.sender == "user"
    val isCrisis = message.sender == "crisis_system"
    val background = when {
        isCrisis -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
        isUser -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            color = background,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = when {
                        isUser -> "Anda"
                        isCrisis -> "Sistem Keselamatan"
                        message.retrievalMode == RagMode.BUNDLED_GROUNDED ||
                            message.citations.any { it.sourceTier == EvidenceSourceTier.BUNDLED_LOCAL } ->
                            "AI Counselor · Dataset bawaan"
                        message.retrievalMode == RagMode.WEB_GROUNDED ||
                            message.citations.any { it.sourceTier == EvidenceSourceTier.WEB } ->
                            "AI Counselor · Web"
                        message.retrievalMode == RagMode.GROUNDED ||
                            message.citations.any { it.sourceTier == EvidenceSourceTier.APP_RAG } ->
                            "AI Counselor · Dokumen lokal"
                        else -> "AI Counselor"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (isCrisis) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                if (message.content.isBlank() && !isUser) {
                    typingIndicator()
                } else {
                    Spacer(modifier = Modifier.height(AppSpacing.Micro))
                    CounselorMessageText(text = message.content)
                }
                if (isCrisis && message.crisisSupportActions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    message.crisisSupportActions.forEach { action ->
                        Button(
                            onClick = { onOpenCrisisSupport(action) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(action.label)
                        }
                    }
                }
                if (message.citations.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    message.citations.forEachIndexed { index, citation ->
                        citationEvidence(
                            citation = citation,
                            webCitationNumber = message.citations
                                .take(index + 1)
                                .count { it.sourceType == com.stressdetector.mobile.domain.engine.CitationSource.WEB },
                            onOpenCitation = onOpenCitation
                        )
                    }
                }
                if (!isUser && !isCrisis && message.content.isNotBlank()) {
                    val onVoiceClick: () -> Unit = if (isSpeaking) {
                        onStopSpeaking
                    } else {
                        { onSpeak(message.id, message.content) }
                    }
                    IconButton(
                        onClick = onVoiceClick,
                        enabled = !isVoiceStopping
                    ) {
                        Icon(
                            if (isSpeaking) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = if (isSpeaking) "Hentikan suara" else "Bacakan jawaban"
                        )
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun citationEvidence(
    citation: com.stressdetector.mobile.domain.engine.KnowledgeCitation,
    webCitationNumber: Int,
    onOpenCitation: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val isWeb = citation.sourceType == com.stressdetector.mobile.domain.engine.CitationSource.WEB
    val sourceLabel = when (citation.sourceTier) {
        EvidenceSourceTier.APP_RAG -> "Dokumen lokal"
        EvidenceSourceTier.BUNDLED_LOCAL -> "Dataset bawaan"
        EvidenceSourceTier.WEB -> "Web"
        EvidenceSourceTier.NONE -> "Sumber belum terverifikasi"
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        val sourceName = citation.sourceFileName ?: citation.sourceTitle
        Text(
            text = if (isWeb) {
                "[W$webCitationNumber] $sourceLabel · ${citation.sourceTitle}"
            } else {
                "• $sourceLabel · $sourceName"
            },
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    stateDescription = if (expanded) "Sumber ditampilkan" else "Sumber disembunyikan"
                }
        ) {
            Text(if (expanded) "Sembunyikan sumber" else "Lihat sumber")
        }
        if (expanded) {
            citation.sourcePage?.let { page ->
                Text(
                    "Halaman $page",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            citation.sourceUrl?.let { url ->
                TextButton(onClick = { onOpenCitation(url) }, modifier = Modifier.fillMaxWidth()) {
                    Text(url, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                }
            }
            if (citation.text.isNotBlank()) {
                Text(
                    text = "\"${citation.text.take(420)}\"",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4
                )
            }
            Text(
                text = "Skor relevansi: ${(citation.relevanceScore.coerceIn(0f, 1f) * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            citation.retrievedAtMillis?.let { retrievedAt ->
                Text(
                    text = "Diambil: ${formatSessionDate(retrievedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
