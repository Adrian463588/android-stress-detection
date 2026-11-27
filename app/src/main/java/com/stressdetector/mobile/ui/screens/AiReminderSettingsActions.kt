package com.stressdetector.mobile.ui.screens

import com.stressdetector.mobile.domain.engine.FollowUpReminderKind

data class AiReminderSettingsActions(
    val onDisplayNameSaved: (String) -> Unit,
    val onReminderConsentChanged: (Boolean) -> Unit,
    val onEnabledChanged: (Boolean) -> Unit,
    val onRequestNotificationPermission: () -> Unit,
    val onPause: () -> Unit,
    val onRetry: () -> Unit,
    val onScheduleFollowUp: (FollowUpReminderKind, Long, String?) -> Unit = { _, _, _ -> },
    val onCancelPending: () -> Unit = {},
    val onClearFeedback: () -> Unit = {}
)
