package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.counselor.CounselorPersonaRepository
import com.stressdetector.mobile.domain.counselor.CounselorPersonaStorageStatus
import com.stressdetector.mobile.domain.counselor.CounselorTone
import com.stressdetector.mobile.domain.counselor.LanguageMode
import com.stressdetector.mobile.domain.counselor.ResponseLength
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.viewmodels.CounselorPersonaViewModel

@Composable
@Suppress("LongMethod", "FunctionNaming")
fun CounselorPersonaScreen(viewModel: CounselorPersonaViewModel, onBack: () -> Unit, onConnectStorage: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    var showGuardrailDetails by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(AppSpacing.Screen),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Section)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
            ) {
                Text(
                    text = "Persona AI Counselor",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Atur gaya dan fokus konseling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ListItem(
            headlineContent = {
                Text("Guardrail aktif", fontWeight = FontWeight.SemiBold)
            },
            supportingContent = {
                Text("Persona hanya mengubah gaya dan fokus; aturan keselamatan tetap aktif.")
            },
            trailingContent = {
                TextButton(
                    onClick = { showGuardrailDetails = true },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Lihat detail guardrail" }
                ) { Text("Detail") }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        ListItem(
            headlineContent = {
                Text("Penyimpanan persona", fontWeight = FontWeight.SemiBold)
            },
            supportingContent = {
                Text(
                    text = when (state.storageStatus) {
                        CounselorPersonaStorageStatus.DURABLE_SAF -> "Tersimpan permanen di folder SAF."
                        CounselorPersonaStorageStatus.SAF_CONNECTED_NO_PERSONA ->
                            "Folder SAF tersambung; simpan untuk membuatnya permanen."
                        CounselorPersonaStorageStatus.PERMISSION_LOST ->
                            "Izin folder SAF hilang. Hubungkan kembali folder untuk memulihkan Persona."
                        CounselorPersonaStorageStatus.CORRUPT_SAF_FALLBACK ->
                            "Payload SAF tidak lolos validasi; cache lokal dipakai sebagai fallback terbatas."
                        CounselorPersonaStorageStatus.SAF_NOT_CONNECTED ->
                            "Belum tersimpan permanen. Hubungkan folder SAF dari Profile."
                        CounselorPersonaStorageStatus.LOCAL_CACHE -> "Persona tersimpan di cache lokal."
                    },
                    color = if (state.storageStatus == CounselorPersonaStorageStatus.DURABLE_SAF) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            },
            trailingContent = if (state.storageStatus != CounselorPersonaStorageStatus.DURABLE_SAF) {
                {
                    TextButton(
                        onClick = onConnectStorage,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics {
                                contentDescription = "Hubungkan folder SAF dari Profile"
                            }
                    ) { Text("Hubungkan") }
                }
            } else {
                null
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
        ) {
            Text(
                text = "Preferensi percakapan",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )

            PersonaDropdown(
                label = "Bahasa",
                selected = state.language,
                options = LanguageMode.entries,
                optionLabel = LanguageMode::label,
                onSelected = viewModel::updateLanguage
            )

            PersonaDropdown(
                label = "Nada",
                selected = state.tone,
                options = CounselorTone.entries,
                optionLabel = CounselorTone::label,
                onSelected = viewModel::updateTone
            )

            PersonaDropdown(
                label = "Panjang jawaban",
                selected = state.responseLength,
                options = ResponseLength.entries,
                optionLabel = ResponseLength::label,
                onSelected = viewModel::updateResponseLength
            )

            PersonaDropdown(
                label = "Pertanyaan reflektif",
                selected = state.reflectiveQuestions,
                options = listOf(0, 1, 2),
                optionLabel = { "$it pertanyaan" },
                onSelected = viewModel::updateReflectiveQuestions
            )
        }

        OutlinedTextField(
            value = state.focusNote,
            onValueChange = viewModel::updateFocusNote,
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 8,
            label = { Text("Catatan fokus") },
            supportingText = {
                val words = state.focusNote.trim()
                    .split(Regex("\\s+"))
                    .count(String::isNotBlank)
                Text("$words/${CounselorPersonaRepository.MAX_FOCUS_NOTE_WORDS} kata")
            }
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
        ) {
            Button(
                onClick = viewModel::save,
                enabled = !state.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) { Text(if (state.isSaving) "Menyimpan…" else "Simpan persona") }
            TextButton(
                onClick = viewModel::reset,
                enabled = !state.isSaving
            ) { Text("Kembalikan default") }
        }

        state.statusMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }

    if (showGuardrailDetails) {
        AlertDialog(
            onDismissRequest = { showGuardrailDetails = false },
            title = { Text("Guardrail tetap aktif") },
            text = {
                Text(
                    "Persona hanya mengatur gaya dan fokus. AI tetap hanya menjawab dalam konteks " +
                        "konseling, tidak mendiagnosis, mengikuti kebijakan krisis, dan menolak resep " +
                        "makanan, coding, atau permintaan di luar konseling."
                )
            },
            confirmButton = {
                TextButton(onClick = { showGuardrailDetails = false }) {
                    Text("Tutup")
                }
            }
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
private fun <T> PersonaDropdown(label: String, selected: T, options: List<T>, optionLabel: (T) -> String, onSelected: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .menuAnchor()
                .semantics {
                    stateDescription = if (expanded) "Terbuka" else "Tertutup"
                }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 280.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    modifier = Modifier.semantics {
                        this.selected = isSelected
                        stateDescription = if (isSelected) "Pilihan aktif" else "Pilihan tersedia"
                    },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun LanguageMode.label(): String = when (this) {
    LanguageMode.AUTO -> "Otomatis"
    LanguageMode.INDONESIAN -> "Indonesia"
    LanguageMode.ENGLISH -> "English"
}

private fun CounselorTone.label(): String = when (this) {
    CounselorTone.WARM -> "Hangat"
    CounselorTone.CALM -> "Tenang"
    CounselorTone.DIRECT -> "Langsung"
    CounselorTone.NEUTRAL -> "Netral"
}

private fun ResponseLength.label(): String = when (this) {
    ResponseLength.BRIEF -> "Ringkas"
    ResponseLength.BALANCED -> "Seimbang"
    ResponseLength.DETAILED -> "Rinci"
}
