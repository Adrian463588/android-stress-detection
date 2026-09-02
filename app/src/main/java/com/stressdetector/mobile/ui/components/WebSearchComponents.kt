package com.stressdetector.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.search.WebSearchMode

@Composable
fun WebSearchModeDialog(mode: WebSearchMode, onModeSelected: (WebSearchMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Language, contentDescription = null)
                Text("  Pencarian web")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Saat aktif, aplikasi mencari maksimal tiga sumber HTTPS secara otomatis, membaca halaman yang lolos validasi, lalu menyertakan citation yang benar-benar diambil.",
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
                    description = "Cari sumber web otomatis untuk satu pertanyaan berikutnya.",
                    selected = mode == WebSearchMode.NextMessage,
                    onClick = { onModeSelected(WebSearchMode.NextMessage) }
                )
                WebSearchModeOption(
                    title = "Pencarian otomatis selalu aktif",
                    description = "Cari maksimal tiga sumber HTTPS untuk setiap pertanyaan.",
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
            .clickable(onClick = onClick),
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
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
