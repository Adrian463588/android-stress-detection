@file:Suppress("FunctionNaming")

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.engine.FollowUpReminderKind
import com.stressdetector.mobile.domain.reminder.AiReminderStatus
import com.stressdetector.mobile.domain.reminder.ReminderNamePolicy
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.viewmodels.AiReminderUiState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun AiReminderSettingsSection(state: AiReminderUiState, actions: AiReminderSettingsActions) {
    var draftName by rememberSaveable { mutableStateOf(state.displayName.orEmpty()) }
    var showScheduleSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.displayName) {
        if (state.displayName.orEmpty() != draftName) draftName = state.displayName.orEmpty()
    }

    val hasActiveScheduled = state.isManualFollowUp || state.status is AiReminderStatus.Scheduled

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Text(
            text = "AI+ Reminder",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            text = "Kelola pengingat otomatis sesi stres dan jadwal tindak lanjut konseling di satu tempat. " +
                "WorkManager dapat menyesuaikan waktu pengiriman.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ReminderNameEditor(
            draftName = draftName,
            savedName = state.displayName,
            onDraftNameChanged = { draftName = it },
            onSave = { actions.onDisplayNameSaved(draftName) }
        )

        ReminderToggle(
            title = "Persetujuan AI+ Reminder",
            subtitle = "Izinkan ringkasan sesi lokal yang valid dipakai untuk pengingat otomatis.",
            checked = state.reminderConsentGranted,
            onCheckedChange = actions.onReminderConsentChanged
        )
        ReminderToggle(
            title = "Aktifkan reminder otomatis",
            subtitle = "Jadwalkan refleksi dalam 48 jam setelah sesi stres valid tanpa mengirim data ke web.",
            checked = state.enabled,
            enabled = state.enabled || state.canEnable,
            onCheckedChange = { enabled ->
                if (enabled && !state.notificationPermissionGranted) {
                    actions.onRequestNotificationPermission()
                } else {
                    actions.onEnabledChanged(enabled)
                }
            }
        )

        if (state.enabled) {
            TextButton(
                onClick = actions.onPause,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text("Jeda reminder otomatis", style = MaterialTheme.typography.labelLarge) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
        ) {
            FilledTonalButton(
                onClick = {
                    actions.onClearFeedback()
                    showScheduleSheet = true
                },
                enabled = !state.isSavingFollowUp,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) {
                Text(
                    text = if (hasActiveScheduled) "Ubah Pengingat" else "Jadwalkan Tindak Lanjut",
                    style = MaterialTheme.typography.labelLarge
                )
            }
            if (hasActiveScheduled) {
                OutlinedButton(
                    onClick = actions.onCancelPending,
                    enabled = !state.isSavingFollowUp,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Batalkan", style = MaterialTheme.typography.labelLarge) }
            }
        }

        (state.actionFeedback ?: state.actionError)?.let { feedback ->
            val isError = state.actionError != null
            Text(
                text = feedback,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        ReminderStatusCard(
            state = state,
            onRetry = actions.onRetry,
            onRequestNotificationPermission = actions.onRequestNotificationPermission
        )
    }

    if (showScheduleSheet) {
        FollowUpReminderSheet(
            initialKind = state.activeFollowUpKind ?: FollowUpReminderKind.HOMEWORK_PRACTICE,
            initialDelayHours = state.activeFollowUpDelayHours
                ?: (state.activeFollowUpKind ?: FollowUpReminderKind.HOMEWORK_PRACTICE).defaultDelayHours,
            initialNote = state.activeFollowUpNote.orEmpty(),
            hasActiveScheduled = hasActiveScheduled,
            isSaving = state.isSavingFollowUp,
            onConfirm = actions.onScheduleFollowUp,
            onCancelExisting = actions.onCancelPending,
            onDismiss = { showScheduleSheet = false }
        )
    }
}

@Composable
private fun ReminderNameEditor(draftName: String, savedName: String?, onDraftNameChanged: (String) -> Unit, onSave: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
        OutlinedTextField(
            value = draftName,
            onValueChange = { onDraftNameChanged(it.take(ReminderNamePolicy.MAX_CODE_POINTS)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Nama panggilan (opsional)") },
            supportingText = {
                Text("Nama disimpan terenkripsi di perangkat. Kosong memakai sapaan generik.")
            },
            singleLine = true
        )
        Button(
            onClick = onSave,
            enabled = draftName != savedName.orEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text("Simpan nama", style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun ReminderToggle(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = AppSpacing.Micro, vertical = AppSpacing.Compact)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                stateDescription = if (checked) "Aktif" else "Tidak aktif"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null
        )
    }
}

@Composable
fun ReminderStatusCard(state: AiReminderUiState, onRetry: () -> Unit, onRequestNotificationPermission: () -> Unit) {
    val statusText = reminderStatusText(state.status)
    val action = reminderStatusAction(state.status, onRetry, onRequestNotificationPermission)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = statusText
            }
    ) {
        Column(modifier = Modifier.padding(AppSpacing.Medium), verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
            Text("Status reminder", style = MaterialTheme.typography.labelLarge)
            Text(statusText, style = MaterialTheme.typography.bodyMedium)
            if (state.isManualFollowUp && state.activeFollowUpKind != null) {
                val notePart = state.activeFollowUpNote?.let { " · \"$it\"" }.orEmpty()
                Text(
                    text = "Jenis: ${state.activeFollowUpKind.displayLabel}$notePart",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (state.nextReminderAt != null && state.status is AiReminderStatus.Scheduled) {
                Text(
                    "Waktu bersifat perkiraan; Android dapat menunda pekerjaan latar belakang.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            action?.let { callback ->
                TextButton(onClick = callback, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(
                        text = if (state.status is AiReminderStatus.NeedsNotificationPermission) {
                            "Izinkan notifikasi"
                        } else {
                            "Coba lagi"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

internal fun reminderStatusText(status: AiReminderStatus): String = when (status) {
    AiReminderStatus.Disabled -> "Reminder tidak aktif."
    is AiReminderStatus.NeedsConsent -> status.reason
    AiReminderStatus.NeedsNotificationPermission -> "Izin notifikasi diperlukan agar reminder dapat dikirim."
    AiReminderStatus.NoEligibleSession -> "Belum ada sesi stress lokal yang valid untuk grounding reminder."
    is AiReminderStatus.ModelUnavailable -> "Model AI lokal belum tersedia atau runtime belum siap: ${status.reason}"
    is AiReminderStatus.Scheduled -> "Terjadwal secara perkiraan untuk ${formatReminderTime(status.nextReminderAt)}."
    AiReminderStatus.Generating -> "AI lokal sedang membuat reminder."
    is AiReminderStatus.Delivered -> "Reminder terakhir dikirim ${formatReminderTime(status.deliveredAt)}."
    is AiReminderStatus.Failed -> "Reminder tidak dikirim: ${status.reason}."
}

internal fun reminderStatusAction(
    status: AiReminderStatus,
    onRetry: () -> Unit,
    onRequestNotificationPermission: () -> Unit
): (() -> Unit)? = when (status) {
    AiReminderStatus.NeedsNotificationPermission -> onRequestNotificationPermission
    is AiReminderStatus.Failed,
    is AiReminderStatus.ModelUnavailable -> onRetry
    else -> null
}

private fun formatReminderTime(time: java.time.Instant): String =
    time.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE, d MMM HH:mm", Locale("id", "ID")))
