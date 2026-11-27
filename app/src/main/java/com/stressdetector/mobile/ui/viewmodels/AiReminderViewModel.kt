package com.stressdetector.mobile.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stressdetector.mobile.di.AppLocator
import com.stressdetector.mobile.domain.engine.FollowUpReminderKind
import com.stressdetector.mobile.domain.reminder.AiReminderCoordinator
import com.stressdetector.mobile.domain.reminder.AiReminderSecretStore
import com.stressdetector.mobile.domain.reminder.AiReminderState
import com.stressdetector.mobile.domain.reminder.AiReminderStatus
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
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
    val lastErrorCode: String? = null,
    val activeFollowUpKind: FollowUpReminderKind? = null,
    val activeFollowUpDelayHours: Long? = null,
    val activeFollowUpNote: String? = null,
    val isManualFollowUp: Boolean = false,
    val isSavingFollowUp: Boolean = false,
    val actionFeedback: String? = null,
    val actionError: String? = null
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
                reminderState.toUiState(displayName, mutableState.value)
            }.collect { mutableState.value = it }
        }
    }

    fun setNotificationPermissionGranted(granted: Boolean) {
        mutableState.update { it.copy(notificationPermissionGranted = granted) }
    }

    fun setEnabled(enabled: Boolean) = coordinator.setEnabled(enabled)

    fun setReminderConsent(granted: Boolean) = coordinator.setReminderConsent(granted)

    fun saveDisplayName(rawName: String?) = coordinator.saveDisplayName(rawName)

    fun retry() = coordinator.retry()

    fun pause() = coordinator.pause()

    fun markOpened(reminderId: String) = coordinator.markOpened(reminderId)

    fun reconcile() = coordinator.reconcile()

    fun clearActionFeedback() {
        mutableState.update { it.copy(actionFeedback = null, actionError = null) }
    }

    fun scheduleFollowUpReminder(kind: FollowUpReminderKind, delayHours: Long, note: String?, sourceSessionId: String = "profile-manual") {
        if (mutableState.value.isSavingFollowUp) return
        if (delayHours !in 1L..com.stressdetector.mobile.domain.reminder.AI_REMINDER_MAX_HORIZON_HOURS) {
            mutableState.update { it.copy(actionError = "Waktu tunda harus antara 1 sampai 48 jam.") }
            return
        }
        mutableState.update { it.copy(isSavingFollowUp = true, actionFeedback = null, actionError = null) }
        viewModelScope.launch {
            coordinator.scheduleFollowUpReminder(kind, delayHours, sourceSessionId, note)
                .onSuccess {
                    mutableState.update {
                        it.copy(
                            isSavingFollowUp = false,
                            actionFeedback = "Pengingat '${kind.displayLabel}' tersimpan untuk $delayHours jam.",
                            actionError = null
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isSavingFollowUp = false,
                            actionFeedback = null,
                            actionError = "Gagal menyimpan pengingat: ${error.message ?: "kesalahan penyimpanan lokal"}"
                        )
                    }
                }
        }
    }

    fun cancelPendingReminders() {
        if (mutableState.value.isSavingFollowUp) return
        mutableState.update { it.copy(isSavingFollowUp = true, actionFeedback = null, actionError = null) }
        viewModelScope.launch {
            coordinator.cancelPendingReminders()
                .onSuccess {
                    mutableState.update {
                        it.copy(
                            isSavingFollowUp = false,
                            actionFeedback = "Pengingat aktif berhasil dibatalkan.",
                            actionError = null
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isSavingFollowUp = false,
                            actionFeedback = null,
                            actionError = "Gagal membatalkan pengingat: ${error.message ?: "kesalahan lokal"}"
                        )
                    }
                }
        }
    }

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

private fun AiReminderState.toUiState(displayName: String?, current: AiReminderUiState): AiReminderUiState = AiReminderUiState(
    displayName = displayName,
    enabled = enabled,
    reminderConsentGranted = reminderConsentGranted,
    canEnable = canEnable,
    notificationPermissionGranted = current.notificationPermissionGranted,
    status = status,
    nextReminderAt = nextReminderAt,
    sourceSessionId = sourceSessionId,
    lastErrorCode = lastErrorCode,
    activeFollowUpKind = activeFollowUpKind,
    activeFollowUpDelayHours = activeFollowUpDelayHours,
    activeFollowUpNote = activeFollowUpNote,
    isManualFollowUp = isManualFollowUp,
    isSavingFollowUp = current.isSavingFollowUp,
    actionFeedback = current.actionFeedback,
    actionError = current.actionError
)
