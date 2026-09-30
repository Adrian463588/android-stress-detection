package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.ui.viewmodels.UiChatSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class ChatSidebarActions(
    val onNewChat: () -> Unit,
    val onSelectSession: (String) -> Unit,
    val onDeleteSession: (UiChatSession) -> Unit,
    val onDeleteAll: () -> Unit
)

@Composable
internal fun ChatSessionSidebar(sessions: List<UiChatSession>, activeSessionId: String, actions: ChatSidebarActions) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Riwayat chat", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Button(onClick = actions.onNewChat, modifier = Modifier.fillMaxWidth()) { Text("Chat baru") }
        TextButton(onClick = actions.onDeleteAll, modifier = Modifier.fillMaxWidth()) {
            Text("Hapus semua chat")
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (sessions.isEmpty()) {
            Text(
                "Belum ada histori chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = sessions, key = { it.id }) { session ->
                    ChatSessionCard(
                        session = session,
                        selected = session.id == activeSessionId,
                        onClick = { actions.onSelectSession(session.id) },
                        onDelete = { actions.onDeleteSession(session) }
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChatSessionCard(session: UiChatSession, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                this.selected = selected
                stateDescription = if (selected) "Chat aktif" else "Chat tidak aktif"
            }
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2
                )
                Text(
                    text = formatSessionDate(session.lastMessageAtMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Hapus chat")
            }
        }
    }
}

internal fun formatSessionDate(timestampMillis: Long): String = SimpleDateFormat(
    "dd MMM yyyy, HH:mm",
    Locale.getDefault()
).format(Date(timestampMillis))
