package com.stressdetector.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.search.WebSearchMode

@Composable
fun WebSearchModeDialog(mode: WebSearchMode, onModeSelected: (WebSearchMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Language, contentDescription = null)
                Text("Pencarian web")
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "AI tetap berjalan lokal. Web otomatis hanya mengirim topik umum, bukan pesan pribadi, " +
                        "riwayat, persona, atau dokumen. Penyedia web dapat melihat query dan alamat jaringan. Izin dapat dicabut di sini.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                WebSearchModeOption(
                    title = "Nonaktif",
                    description = "Gunakan dokumen lokal atau mode umum.",
                    selected = mode == WebSearchMode.Disabled,
                    onClick = { onModeSelected(WebSearchMode.Disabled) }
                )
                WebSearchModeOption(
                    title = "Cari untuk pesan berikutnya",
                    description = "Tawarkan sumber web bila dokumen lokal belum cukup.",
                    selected = mode == WebSearchMode.NextMessage,
                    onClick = { onModeSelected(WebSearchMode.NextMessage) }
                )
                WebSearchModeOption(
                    title = "Izinkan web otomatis",
                    description = "Cari sumber saat dokumen belum cukup, tanpa dialog per pesan. Hanya topik publik yang dikirim.",
                    selected = mode == WebSearchMode.Persistent,
                    onClick = { onModeSelected(WebSearchMode.Persistent) }
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Selesai") } }
    )
}

@Composable
private fun WebSearchModeOption(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
