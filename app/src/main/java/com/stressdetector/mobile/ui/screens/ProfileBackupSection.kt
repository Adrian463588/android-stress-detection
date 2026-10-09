package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.lifecycle.SafUserBackupState
import com.stressdetector.mobile.ui.AppSpacing
import java.text.DateFormat
import java.util.Date

private const val MIN_TOUCH_TARGET_DP = 48
private const val MIN_BACKUP_PASSPHRASE_LENGTH = 12

internal enum class ProfileBackupAction { BACKUP, RESTORE }
internal data class ProfileBackupActions(val onConnect: () -> Unit, val onBackup: () -> Unit, val onRestore: () -> Unit)

@Composable
@Suppress("FunctionNaming")
internal fun ProfileBackupSection(state: SafUserBackupState, connected: Boolean, operationRunning: Boolean, actions: ProfileBackupActions) {
    Text(
        text = if (connected && state.enabled) {
            "Folder SAF tersambung. Cadangan otomatis mengikuti perubahan data saat aplikasi berjalan."
        } else if (connected) {
            "Folder SAF tersambung. Buat cadangan pertama untuk mengaktifkan cadangan otomatis."
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
        text = "Simpan kata sandi pemulihan di tempat aman. Setelah uninstall, pilih lagi folder yang sama; " +
            "tanpa kata sandi, data terenkripsi tidak dapat dibuka.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
@Suppress("FunctionNaming")
internal fun ProfileBackupDialog(action: ProfileBackupAction, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    // Secrets are ephemeral: never rememberSaveable, log, or include them in the SAF archive.
    var backupPassphrase by remember { mutableStateOf("") }
    var backupConfirmation by remember { mutableStateOf("") }
    val dismiss = {
        backupPassphrase = ""
        backupConfirmation = ""
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (action == ProfileBackupAction.BACKUP) "Cadangkan data terenkripsi" else "Pulihkan data") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
            ) {
                Text(
                    if (action == ProfileBackupAction.BACKUP) {
                        "Pilih kata sandi minimal 12 karakter. Kata sandi ini diperlukan setelah instal ulang."
                    } else {
                        "Masukkan kata sandi cadangan dari folder SAF yang dipilih. Pemulihan menolak data lokal yang sudah terisi."
                    }
                )
                RecoverySecretField(backupPassphrase, "Kata sandi pemulihan") { backupPassphrase = it }
                if (action == ProfileBackupAction.BACKUP) {
                    RecoverySecretField(backupConfirmation, "Ulangi kata sandi") { backupConfirmation = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(backupPassphrase)
                    dismiss()
                },
                enabled = backupPassphrase.length >= MIN_BACKUP_PASSPHRASE_LENGTH &&
                    (action == ProfileBackupAction.RESTORE || backupPassphrase == backupConfirmation),
                modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET_DP.dp)
            ) { Text(if (action == ProfileBackupAction.BACKUP) "Cadangkan" else "Pulihkan") }
        },
        dismissButton = {
            TextButton(
                onClick = dismiss,
                modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET_DP.dp)
            ) { Text("Batal") }
        }
    )
}

@Composable
@Suppress("FunctionNaming")
private fun RecoverySecretField(value: String, label: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
