package com.stressdetector.mobile.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.stressdetector.mobile.data.search.SecureWebUrlPolicy
import com.stressdetector.mobile.domain.engine.CrisisSupportAction
import com.stressdetector.mobile.domain.voice.VoiceOutputState
import com.stressdetector.mobile.ui.viewmodels.AiReminderUiState
import com.stressdetector.mobile.ui.viewmodels.CounselorUiState
import com.stressdetector.mobile.ui.viewmodels.UiChatMessage

internal fun retryReminder(model: CounselorScreenModel, state: AiReminderUiState) {
    if (state.status is com.stressdetector.mobile.domain.reminder.AiReminderStatus.NeedsConsent) {
        model.navigation.onNavigateToProfile()
    } else {
        model.runtime.aiReminderViewModel?.retry()
    }
}

internal fun isSpeaking(state: CounselorUiState, message: UiChatMessage): Boolean = state.voicePlayback.activeMessageId == message.id &&
    (
        state.voiceOutputState is VoiceOutputState.Speaking ||
            state.voiceOutputState is VoiceOutputState.Preparing ||
            state.voiceOutputState is VoiceOutputState.Stopping
        )

internal fun openCitation(context: Context, rawUrl: String) {
    SecureWebUrlPolicy.normalizeForApproval(rawUrl)?.toString()?.let { url ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}

internal fun openCrisisSupport(context: Context, action: CrisisSupportAction) {
    runCatching {
        val actionType = if (action.uri.startsWith("tel:")) Intent.ACTION_DIAL else Intent.ACTION_VIEW
        context.startActivity(Intent(actionType, Uri.parse(action.uri)))
    }
}
