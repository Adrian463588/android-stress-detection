package com.stressdetector.mobile.ui.screens

data class AiReminderSettingsActions(
    val onDisplayNameSaved: (String) -> Unit,
    val onReminderConsentChanged: (Boolean) -> Unit,
    val onEnabledChanged: (Boolean) -> Unit,
    val onRequestNotificationPermission: () -> Unit,
    val onPause: () -> Unit,
    val onRetry: () -> Unit
)
