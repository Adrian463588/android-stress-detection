package com.stressdetector.mobile.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stressdetector.mobile.di.AppLocator
import com.stressdetector.mobile.domain.reminder.AiReminderCoordinator
import com.stressdetector.mobile.domain.reminder.AiReminderSecretStore
import com.stressdetector.mobile.domain.reminder.AiReminderState
import com.stressdetector.mobile.domain.reminder.AiReminderStatus
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class AiReminderUiState(
    val displayName: String? = null,
    val enabled: Boolean = false,
    val reminderConsentGranted: Boolean = false,
    val canEnable: Boolean = false,
    val notificationPermissionGranted: Boolean = false,
    val status: AiReminderStatus = AiReminderStatus.Disabled,
    val nextReminderAt: Instant? = null,
    val sourceSessionId: String? = null,
    val lastErrorCode: String? = null
)

class AiReminderViewModel(
    private val coordinator: AiReminderCoordinator,
    private val secretStore: AiReminderSecretStore,
    initialNotificationPermissionGranted: Boolean
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        AiReminderUiState(notificationPermissionGranted = initialNotificationPermissionGranted)
    )
    val uiState: StateFlow<AiReminderUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(coordinator.state, secretStore.displayName) { reminderState, displayName ->
                reminderState.toUiState(displayName, mutableState.value.notificationPermissionGranted)
            }.collect { mutableState.value = it }
        }
    }

    fun setNotificationPermissionGranted(granted: Boolean) {
        mutableState.value = mutableState.value.copy(notificationPermissionGranted = granted)
    }

    fun setEnabled(enabled: Boolean) = coordinator.setEnabled(enabled)

    fun setReminderConsent(granted: Boolean) = coordinator.setReminderConsent(granted)

    fun saveDisplayName(rawName: String?) = coordinator.saveDisplayName(rawName)

    fun retry() = coordinator.retry()

    fun pause() = coordinator.pause()

    fun markOpened(reminderId: String) = coordinator.markOpened(reminderId)

    fun reconcile() = coordinator.reconcile()

    companion object {
        fun factory(context: android.content.Context, notificationPermissionGranted: Boolean): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AiReminderViewModel(
                    coordinator = AppLocator.aiReminderCoordinator(context.applicationContext),
                    secretStore = AppLocator.aiReminderSecretStore(context.applicationContext),
                    initialNotificationPermissionGranted = notificationPermissionGranted
                ) as T
            }
    }
}

private fun AiReminderState.toUiState(displayName: String?, notificationPermissionGranted: Boolean): AiReminderUiState = AiReminderUiState(
    displayName = displayName,
    enabled = enabled,
    reminderConsentGranted = reminderConsentGranted,
    canEnable = canEnable,
    notificationPermissionGranted = notificationPermissionGranted,
    status = status,
    nextReminderAt = nextReminderAt,
    sourceSessionId = sourceSessionId,
    lastErrorCode = lastErrorCode
)
