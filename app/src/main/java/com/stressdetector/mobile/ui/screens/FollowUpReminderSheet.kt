@file:Suppress("FunctionNaming")

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.engine.FollowUpReminderKind
import com.stressdetector.mobile.ui.AppSpacing

private const val MAX_REMINDER_NOTE_CHARS = 120

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FollowUpReminderSheet(
    initialKind: FollowUpReminderKind,
    initialDelayHours: Long,
    initialNote: String,
    hasActiveScheduled: Boolean,
    isSaving: Boolean,
    onConfirm: (FollowUpReminderKind, Long, String?) -> Unit,
    onCancelExisting: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedKind by rememberSaveable { mutableStateOf(initialKind) }
    var delayHours by rememberSaveable { mutableLongStateOf(initialDelayHours.coerceIn(12L, 48L)) }
    var note by rememberSaveable { mutableStateOf(initialNote.take(MAX_REMINDER_NOTE_CHARS)) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cleanNote = note.trim().takeIf(String::isNotBlank)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Compact),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
        ) {
            Text(
                text = if (hasActiveScheduled) "Ubah Pengingat Tindak Lanjut" else "Jadwalkan Pengingat Tindak Lanjut",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = "Notifikasi lokal bersifat non-klinis dan hanya dijadwalkan setelah Anda menekan tombol simpan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Jenis pengingat", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
            ) {
                FollowUpReminderKind.entries.forEach { kind ->
                    val selected = selectedKind == kind
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedKind = kind
                            if (!hasActiveScheduled) delayHours = kind.defaultDelayHours
                        },
                        label = { Text(kind.displayLabel, style = MaterialTheme.typography.labelLarge) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics {
                                contentDescription = "Jenis ${kind.displayLabel}"
                                stateDescription = if (selected) "Dipilih" else "Tidak dipilih"
                            }
                    )
                }
            }

            Text("Waktu tunda", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
            ) {
                listOf(12L, 24L, 48L).forEach { hours ->
                    val selected = delayHours == hours
                    FilterChip(
                        selected = selected,
                        onClick = { delayHours = hours },
                        label = { Text("$hours jam", style = MaterialTheme.typography.labelLarge) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .semantics {
                                contentDescription = "Tunda $hours jam"
                                stateDescription = if (selected) "Dipilih" else "Tidak dipilih"
                            }
                    )
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(MAX_REMINDER_NOTE_CHARS) },
                label = { Text("Catatan singkat non-klinis (opsional)") },
                supportingText = { Text("${note.length}/$MAX_REMINDER_NOTE_CHARS karakter") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (!isSaving) {
                            onConfirm(selectedKind, delayHours, cleanNote)
                            onDismiss()
                        }
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(AppSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
                ) {
                    Text("Pratinjau notifikasi", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = buildString {
                            append(selectedKind.defaultNonClinicalTitle)
                            if (cleanNote != null) append(" ($cleanNote)")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("Batal", style = MaterialTheme.typography.labelLarge) }
                Button(
                    onClick = {
                        onConfirm(selectedKind, delayHours, cleanNote)
                        onDismiss()
                    },
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text(
                        text = if (hasActiveScheduled) "Simpan Perubahan" else "Simpan Pengingat",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            if (hasActiveScheduled) {
                TextButton(
                    onClick = {
                        onCancelExisting()
                        onDismiss()
                    },
                    enabled = !isSaving,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text("Batalkan Pengingat Aktif", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.Compact))
        }
    }
}
