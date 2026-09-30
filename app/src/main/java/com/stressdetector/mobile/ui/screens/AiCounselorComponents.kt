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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
    AlertDialog(
        onDismissRequest = viewModel::dismissWebConsent,
        title = { Text("Izinkan Web Search?") },
        text = {
            Text(
                state.pendingWebQuery?.let {
                    "Pertanyaan berikut akan dipakai untuk mencari maksimal tiga sumber HTTPS secara otomatis " +
                        "setelah persetujuan:\n\n$it\n\nChat, transcript, riwayat stress, persona, serta " +
                        "dokumen privat tidak dikirim. Server tetap dapat melihat permintaan jaringan dan alamat perangkat."
                } ?: "AI akan mencari dan membaca maksimal tiga sumber HTTPS secara otomatis setelah Anda " +
                    "menyetujui akses jaringan. Chat, transcript, riwayat, dan dokumen privat tetap di perangkat."
            )
        },
        confirmButton = { TextButton(onClick = viewModel::confirmWebConsent) { Text("Izinkan") } },
        dismissButton = { TextButton(onClick = viewModel::dismissWebConsent) { Text("Batal") } }
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
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content) {
        if (state.messages.isNotEmpty()) {
            if (motion.enabled) {
                model.layout.listState.animateScrollToItem(state.messages.lastIndex)
            } else {
                model.layout.listState.scrollToItem(state.messages.lastIndex)
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
