package com.stressdetector.mobile.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.stressdetector.mobile.ui.viewmodels.AiReminderViewModel
import com.stressdetector.mobile.ui.viewmodels.CounselorViewModel
import com.stressdetector.mobile.ui.viewmodels.UiChatSession

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AiCounselorScreen(
    viewModel: CounselorViewModel,
    aiReminderViewModel: AiReminderViewModel? = null,
    navigation: CounselorNavigation
) {
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val aiReminderState = if (aiReminderViewModel == null) {
        null
    } else {
        val observed by aiReminderViewModel.uiState.collectAsStateWithLifecycleCompat()
        observed
    }
    var inputText by remember { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var showTechnicalDetails by remember { mutableStateOf(false) }
    var sessionPendingDelete by remember { mutableStateOf<UiChatSession?>(null) }
    var showDeleteAllConfirmation by remember { mutableStateOf(false) }
    var showWebSearchMode by rememberSaveable { mutableStateOf(false) }
    var showHeaderMenu by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val microphoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.startVoiceInput() }

    val model = CounselorScreenModel(
        runtime = CounselorRuntime(state, aiReminderState, aiReminderViewModel, viewModel),
        navigation = navigation,
        dialogs = CounselorDialogState(
            showWebSearchMode = showWebSearchMode,
            onWebSearchModeChanged = { showWebSearchMode = it },
            showHeaderMenu = showHeaderMenu,
            onHeaderMenuChanged = { showHeaderMenu = it },
            sessionPendingDelete = sessionPendingDelete,
            onSessionPendingDelete = { sessionPendingDelete = it },
            showDeleteAllConfirmation = showDeleteAllConfirmation,
            onDeleteAllConfirmationChanged = { showDeleteAllConfirmation = it },
            showTechnicalDetails = showTechnicalDetails,
            onTechnicalDetailsChanged = { showTechnicalDetails = it }
        ),
        composer = ChatComposerState(
            inputText = inputText,
            onInputChanged = { inputText = it },
            isGenerating = state.isGenerating,
            voiceBusy = state.voiceInputState.isVoiceBusy(),
            onCancelGeneration = viewModel::cancelGeneration,
            onSend = { sendChatMessage(state, inputText, viewModel) { inputText = "" } },
            onVoice = { handleVoiceAction(state, viewModel, context, microphoneLauncher) }
        ),
        layout = CounselorLayout(listState, drawerState, scope, context, microphoneLauncher)
    )

    CounselorDialogs(model)
    CounselorLifecycleEffects(model)
    CounselorScaffold(model)
}
