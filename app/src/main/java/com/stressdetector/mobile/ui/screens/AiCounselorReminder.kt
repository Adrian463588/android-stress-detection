package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.ui.viewmodels.AiReminderUiState

@Composable
internal fun ReminderStatusRow(state: AiReminderUiState, onRetry: () -> Unit, onRequestNotificationPermission: () -> Unit) {
    val statusText = reminderStatusText(state.status)
    val action = reminderStatusAction(state.status, onRetry, onRequestNotificationPermission)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = statusText
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Reminder", style = MaterialTheme.typography.labelLarge)
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action?.let { callback ->
                TextButton(onClick = callback, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(
                        if (state.status is com.stressdetector.mobile.domain.reminder.AiReminderStatus.NeedsNotificationPermission) {
                            "Izinkan"
                        } else {
                            "Coba lagi"
                        }
                    )
                }
            }
        }
    }
}
