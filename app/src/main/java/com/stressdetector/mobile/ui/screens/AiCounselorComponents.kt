package com.stressdetector.mobile.ui.screens

import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.stressdetector.mobile.domain.voice.VoiceSendMode
import com.stressdetector.mobile.ui.components.WebSearchModeDialog
import com.stressdetector.mobile.ui.viewmodels.AiReminderUiState
import com.stressdetector.mobile.ui.viewmodels.AiReminderViewModel
import com.stressdetector.mobile.ui.viewmodels.CounselorPhase
import com.stressdetector.mobile.ui.viewmodels.CounselorUiState
import com.stressdetector.mobile.ui.viewmodels.CounselorViewModel
import com.stressdetector.mobile.ui.viewmodels.UiChatSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class CounselorRuntime(
    val state: CounselorUiState,
    val aiReminderState: AiReminderUiState?,
    val aiReminderViewModel: AiReminderViewModel?,
    val viewModel: CounselorViewModel
)

internal data class CounselorNavigation(
    val windowSizeClass: WindowWidthSizeClass,
    val onNavigateToLibrary: () -> Unit,
    val onNavigateToProfile: () -> Unit,
    val onRequestNotificationPermission: () -> Unit
)

internal data class CounselorDialogState(
    val showWebSearchMode: Boolean,
    val onWebSearchModeChanged: (Boolean) -> Unit,
    val showHeaderMenu: Boolean,
    val onHeaderMenuChanged: (Boolean) -> Unit,
    val sessionPendingDelete: UiChatSession?,
    val onSessionPendingDelete: (UiChatSession?) -> Unit,
    val showDeleteAllConfirmation: Boolean,
    val onDeleteAllConfirmationChanged: (Boolean) -> Unit,
    val showTechnicalDetails: Boolean,
    val onTechnicalDetailsChanged: (Boolean) -> Unit
)

internal data class CounselorLayout(
    val listState: LazyListState,
    val drawerState: DrawerState,
    val scope: CoroutineScope,
    val context: android.content.Context,
    val microphoneLauncher: ActivityResultLauncher<String>
)

internal data class CounselorScreenModel(
    val runtime: CounselorRuntime,
    val navigation: CounselorNavigation,
    val dialogs: CounselorDialogState,
    val composer: ChatComposerState,
    val layout: CounselorLayout
)

@Composable
internal fun CounselorDialogs(model: CounselorScreenModel) {
    val state = model.runtime.state
    val dialogs = model.dialogs
    if (dialogs.showWebSearchMode) {
        WebSearchModeDialog(
            mode = state.webSearchMode,
            onModeSelected = { mode ->
                if (mode == com.stressdetector.mobile.domain.search.WebSearchMode.Persistent) {
                    model.runtime.viewModel.setAutomaticWebSearchEnabled(true)
                } else {
                    model.runtime.viewModel.setAutomaticWebSearchEnabled(false)
                }
                model.runtime.viewModel.setWebSearchMode(mode)
                dialogs.onWebSearchModeChanged(false)
            },
            onDismiss = { dialogs.onWebSearchModeChanged(false) }
        )
    }
    if (state.webConsentRequired) WebConsentDialog(model)
    dialogs.sessionPendingDelete?.let { session -> DeleteSessionDialog(model, session) }
    if (dialogs.showDeleteAllConfirmation) DeleteAllDialog(model)
}

@Composable
private fun WebConsentDialog(model: CounselorScreenModel) {
    val state = model.runtime.state
    val viewModel = model.runtime.viewModel
    WebQueryConsentDialog(
        initialQuery = state.pendingWebQuery.orEmpty(),
        onConfirm = viewModel::confirmWebConsent,
        onDismiss = viewModel::dismissWebConsent,
        errorMessage = state.statusMessage?.takeUnless { it.startsWith("Sumber lokal") }
    )
}

@Composable
internal fun WebQueryConsentDialog(initialQuery: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit, errorMessage: String? = null) {
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cari sumber web?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Hanya pertanyaan publik di bawah yang dikirim untuk mencari maksimal tiga sumber HTTPS.")
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Pertanyaan publik") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Hapus nama, riwayat pribadi, transcript, dan isi dokumen privat.") }
                )
                Text(
                    "Riwayat stres dan persona tidak disertakan. Penyedia web tetap dapat melihat query dan alamat jaringan perangkat.",
                    style = MaterialTheme.typography.bodySmall
                )
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(query) }, enabled = query.isNotBlank()) { Text("Izinkan dan cari") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

@Composable
private fun DeleteSessionDialog(model: CounselorScreenModel, session: UiChatSession) {
    val viewModel = model.runtime.viewModel
    val dialogs = model.dialogs
    AlertDialog(
        onDismissRequest = { dialogs.onSessionPendingDelete(null) },
        title = { Text("Hapus chat?") },
        text = { Text("Chat '${session.title}' dan seluruh pesannya akan dihapus dari perangkat.") },
        confirmButton = {
            TextButton(onClick = {
                viewModel.deleteChatSession(session.id)
                dialogs.onSessionPendingDelete(null)
            }) { Text("Hapus") }
        },
        dismissButton = { TextButton(onClick = { dialogs.onSessionPendingDelete(null) }) { Text("Batal") } }
    )
}

@Composable
private fun DeleteAllDialog(model: CounselorScreenModel) {
    val viewModel = model.runtime.viewModel
    val dialogs = model.dialogs
    AlertDialog(
        onDismissRequest = { dialogs.onDeleteAllConfirmationChanged(false) },
        title = { Text("Hapus semua chat?") },
        text = { Text("Semua histori chat AI Counselor akan dihapus dari perangkat dan tidak dapat dipulihkan.") },
        confirmButton = {
            TextButton(onClick = {
                viewModel.deleteAllChats()
                dialogs.onDeleteAllConfirmationChanged(false)
            }) { Text("Hapus semua") }
        },
        dismissButton = { TextButton(onClick = { dialogs.onDeleteAllConfirmationChanged(false) }) { Text("Batal") } }
    )
}

@Composable
internal fun CounselorLifecycleEffects(model: CounselorScreenModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = model.runtime.state
    val viewModel = model.runtime.viewModel
    val motion = com.stressdetector.mobile.ui.LocalAppMotion.current
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
    LaunchedEffect(state.voiceTranscript) {
        state.voiceTranscript?.let { transcript ->
            if (state.voiceSendMode == VoiceSendMode.REVIEW) model.composer.onInputChanged(transcript)
        }
    }
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content, state.statusMessage, state.phase) {
        if (state.messages.isNotEmpty()) {
            androidx.compose.runtime.withFrameNanos { }
            if (motion.enabled) {
                model.layout.listState.animateScrollToItem((model.layout.listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            } else {
                model.layout.listState.scrollToItem((model.layout.listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            }
        }
    }
}

@Composable
internal fun CounselorScaffold(model: CounselorScreenModel) {
    val runtime = model.runtime
    ModalNavigationDrawer(
        drawerState = model.layout.drawerState,
        drawerContent = {
            ModalDrawerSheet {
                ChatSessionSidebar(
                    sessions = runtime.state.chatSessions,
                    activeSessionId = runtime.state.activeChatSessionId,
                    actions = ChatSidebarActions(
                        onNewChat = {
                            runtime.viewModel.newChat()
                            model.layout.scope.launch { model.layout.drawerState.close() }
                        },
                        onSelectSession = { sessionId ->
                            runtime.viewModel.selectChatSession(sessionId)
                            model.layout.scope.launch { model.layout.drawerState.close() }
                        },
                        onDeleteSession = { session -> model.dialogs.onSessionPendingDelete(session) },
                        onDeleteAll = { model.dialogs.onDeleteAllConfirmationChanged(true) }
                    )
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { CounselorTopBar(model) },
            bottomBar = { CounselorBottomBar(model) }
        ) { innerPadding -> CounselorMessageList(model, innerPadding) }
    }
}

@Composable
private fun CounselorBottomBar(model: CounselorScreenModel) {
    val state = model.runtime.state
    val horizontalPadding = counselorHorizontalPadding(model.navigation.windowSizeClass)
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = horizontalPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!state.isModelReady && state.phase != CounselorPhase.CRISIS_ESCALATION) {
                Text(
                    text = "Gemma belum siap. Pertanyaan normal menunggu model; dukungan krisis lokal tetap tersedia.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics {
                        stateDescription = "Chat normal terbatas; dukungan krisis lokal tetap tersedia"
                        liveRegion = LiveRegionMode.Polite
                    }
                )
            }
            state.voiceError?.let { error ->
                Text(error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            VoiceStatus(model)
            ChatComposer(model.composer)
        }
    }
}
