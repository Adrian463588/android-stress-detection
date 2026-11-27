package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.lifecycle.SafUserBackupState
import java.text.DateFormat
import java.util.Date

private const val MIN_TOUCH_TARGET_DP = 48

internal data class ProfileBackupActions(val onConnect: () -> Unit, val onBackup: () -> Unit, val onRestore: () -> Unit)

@Composable
@Suppress("FunctionNaming")
internal fun ProfileBackupSection(state: SafUserBackupState, connected: Boolean, operationRunning: Boolean, actions: ProfileBackupActions) {
    Text(
        text = if (connected && state.enabled) {
            "Folder SAF tersambung. Cadangan otomatis mengikuti perubahan data saat aplikasi berjalan."
        } else if (connected) {
            "Folder SAF tersambung. Buat cadangan pertama atau pulihkan data dengan satu ketukan."
        } else {
            "Hubungkan kembali folder SAF yang sama untuk mencadangkan atau memulihkan data."
        },
        style = MaterialTheme.typography.bodyMedium
    )
    if (!connected) {
        OutlinedButton(
            onClick = { actions.onConnect() },
            modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH_TARGET_DP.dp)
        ) { Text("Hubungkan folder SAF") }
    }
    state.lastSuccessfulAtMillis?.let { millis ->
        Text(
            text = "Cadangan terakhir berhasil: ${DateFormat.getDateTimeInstance().format(Date(millis))}",
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (state.pendingChanges) {
        Text(
            "Ada perubahan belum tercadangkan. Tunggu cadangan selesai sebelum uninstall.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
    state.error?.let { error ->
        Text(
            text = "Cadangan belum mutakhir: $error",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
    Button(
        onClick = { actions.onBackup() },
        enabled = connected && !operationRunning && !state.running,
        modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH_TARGET_DP.dp)
    ) {
        Text(if (state.running) "Memproses cadangan…" else "Cadangkan History, AI+, dan Verbatim+")
    }
    OutlinedButton(
        onClick = { actions.onRestore() },
        enabled = connected && !operationRunning && !state.running,
        modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH_TARGET_DP.dp)
    ) { Text("Pulihkan dari cadangan SAF") }
    Text(
        text = "Cadangan disimpan terenkripsi di folder SAF dan dapat dipulihkan langsung dengan satu ketukan setelah memilih " +
            "folder yang sama.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
