@file:Suppress("FunctionNaming")

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.reminder.AiReminderStatus
import com.stressdetector.mobile.domain.reminder.ReminderNamePolicy
import com.stressdetector.mobile.ui.viewmodels.AiReminderUiState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun AiReminderSettingsSection(state: AiReminderUiState, actions: AiReminderSettingsActions) {
    var draftName by rememberSaveable { mutableStateOf(state.displayName.orEmpty()) }
    LaunchedEffect(state.displayName) {
        if (state.displayName.orEmpty() != draftName) draftName = state.displayName.orEmpty()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AI+ Reminder", style = MaterialTheme.typography.titleSmall)
        Text(
            "AI memilih satu waktu perkiraan dalam 48 jam setelah sesi stress yang valid. " +
                "WorkManager dapat menunda delivery.",
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
            subtitle = "Izinkan ringkasan sesi lokal yang valid dipakai untuk membuat reminder.",
            checked = state.reminderConsentGranted,
            onCheckedChange = actions.onReminderConsentChanged
        )
        ReminderToggle(
            title = "Aktifkan reminder AI+",
            subtitle = "Tidak mengirim raw PPG, transcript, dokumen, atau data ke web.",
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
        ReminderPauseAction(enabled = state.enabled, onPause = actions.onPause)
        ReminderStatusCard(
            state = state,
            onRetry = actions.onRetry,
            onRequestNotificationPermission = actions.onRequestNotificationPermission
        )
    }
}

@Composable
private fun ReminderNameEditor(draftName: String, savedName: String?, onDraftNameChanged: (String) -> Unit, onSave: () -> Unit) {
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
    ) { Text("Simpan nama") }
}

@Composable
private fun ReminderPauseAction(enabled: Boolean, onPause: () -> Unit) {
    if (enabled) {
        TextButton(
            onClick = onPause,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text("Jeda reminder") }
    }
}

@Composable
private fun ReminderToggle(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = title
                stateDescription = if (checked) "Aktif" else "Tidak aktif"
            },
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.heightIn(min = 48.dp)
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
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Status reminder", style = MaterialTheme.typography.labelLarge)
            Text(statusText, style = MaterialTheme.typography.bodyMedium)
            if (state.nextReminderAt != null && state.status is AiReminderStatus.Scheduled) {
                Text(
                    "Waktu bersifat perkiraan; Android dapat menunda pekerjaan latar belakang.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            action?.let { callback ->
                TextButton(onClick = callback, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (state.status is AiReminderStatus.NeedsNotificationPermission) "Izinkan notifikasi" else "Coba lagi")
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
