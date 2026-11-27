@file:Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod", "MagicNumber")

package com.stressdetector.mobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.data.WatchConnectionStatus
import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import com.stressdetector.mobile.domain.engine.VerbatimSessionSummary
import com.stressdetector.mobile.domain.engine.WatchWearerRole
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.clinicalColors
import com.stressdetector.mobile.ui.viewmodels.UnifiedSessionUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_SESSION_NAME_LEN = 80
private const val RECENT_SESSIONS_PREVIEW = 3

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun VerbatimSessionSetupSurface(
    uiState: UnifiedSessionUiState,
    sessionName: String,
    onSessionNameChange: (String) -> Unit,
    monitoringEnabled: Boolean,
    onMonitoringEnabledChange: (Boolean) -> Unit,
    consentChecked: Boolean,
    onConsentCheckedChange: (Boolean) -> Unit,
    microphoneGranted: Boolean,
    microphonePermanentlyDenied: Boolean = false,
    onStartSession: () -> Unit,
    onImportAudio: () -> Unit,
    onOpenDrawer: () -> Unit,
    onSelectSession: (String) -> Unit,
    onSetLanguage: (String) -> Unit,
    onSetWatchWearerRole: (WatchWearerRole) -> Unit,
    onRefreshReadiness: () -> Unit,
    onOpenProfile: () -> Unit,
    onRequestMicrophonePermission: () -> Unit,
    modifier: Modifier = Modifier,
    onImportPackage: (() -> Unit)? = null
) {
    val clinical = MaterialTheme.clinicalColors
    val canStart = consentChecked && uiState.canStartNewSession && sessionName.isNotBlank()
    var researchInfoExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
            Text("Sesi baru", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Siapkan sesi konseling Anda.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OutlinedTextField(
            value = sessionName,
            onValueChange = { onSessionNameChange(it.take(MAX_SESSION_NAME_LEN)) },
            label = { Text("Nama sesi") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
            Text(
                "Bahasa transkripsi",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LanguageSegmentedSelector(
                selectedLanguage = uiState.selectedLanguage,
                onSelect = onSetLanguage
            )
        }

        Divider(color = clinical.borderSubtle)

        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Pemantauan Galaxy Watch",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Opsional",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(AppSpacing.Compact))
                Switch(
                    checked = monitoringEnabled,
                    onCheckedChange = { enabled ->
                        onMonitoringEnabledChange(enabled)
                        if (!enabled) {
                            onSetWatchWearerRole(WatchWearerRole.UNSPECIFIED)
                        } else if (uiState.watchWearerRole == WatchWearerRole.UNSPECIFIED) {
                            onSetWatchWearerRole(WatchWearerRole.CLIENT)
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = "Aktifkan pemantauan Galaxy Watch" }
                )
            }

            AnimatedVisibility(visible = monitoringEnabled) {
                val activeRole = if (uiState.watchWearerRole == WatchWearerRole.PSYCHOLOGIST) {
                    WatchWearerRole.PSYCHOLOGIST
                } else {
                    WatchWearerRole.CLIENT
                }
                WatchWearerDropdownRow(
                    selectedRole = activeRole,
                    onSelectRole = onSetWatchWearerRole
                )
            }
        }

        Divider(color = clinical.borderSubtle)

        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Kesiapan perangkat",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRefreshReadiness, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Periksa ulang kesiapan perangkat")
                }
            }
            ReadinessStatusRow(
                icon = Icons.Default.Mic,
                label = "Mikrofon",
                statusText = when {
                    microphoneGranted -> "Siap"
                    microphonePermanentlyDenied -> "Perlu setelan"
                    else -> "Perlu izin"
                },
                statusColor = if (microphoneGranted) clinical.physiological else clinical.marker,
                actionLabel = when {
                    microphoneGranted -> null
                    microphonePermanentlyDenied -> "Buka Setelan"
                    else -> "Izinkan"
                },
                onAction = if (microphoneGranted) null else onRequestMicrophonePermission,
                onRowClick = if (microphoneGranted) null else onRequestMicrophonePermission
            )
            val sttReady = uiState.speechRuntimeState is SpeechRuntimeState.Ready
            ReadinessStatusRow(
                icon = if (sttReady) Icons.Default.CheckCircle else Icons.Default.Warning,
                label = "Transkripsi lokal",
                statusText = when (uiState.speechRuntimeState) {
                    SpeechRuntimeState.Ready -> "Siap"
                    SpeechRuntimeState.Checking -> "Memeriksa…"
                    is SpeechRuntimeState.Unavailable -> "Belum siap"
                    is SpeechRuntimeState.Failed -> "Gagal"
                },
                statusColor = if (sttReady) clinical.physiological else clinical.recording,
                actionLabel = if (sttReady || uiState.speechRuntimeState is SpeechRuntimeState.Checking) null else "Profile",
                onAction = if (sttReady) null else onOpenProfile
            )
            val watchReady = uiState.wearConnectionState.status == WatchConnectionStatus.APP_REACHABLE ||
                uiState.wearConnectionState.status == WatchConnectionStatus.RECORDING
            ReadinessStatusRow(
                icon = Icons.Default.Watch,
                label = "Galaxy Watch",
                statusText = when {
                    watchReady -> uiState.wearConnectionState.lastPongNodeId?.let { "Siap · $it" } ?: "Siap"
                    monitoringEnabled -> "Belum terhubung"
                    else -> "Opsional · nonaktif"
                },
                statusColor = when {
                    watchReady -> clinical.physiological
                    monitoringEnabled -> clinical.marker
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        Divider(color = clinical.borderSubtle)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Checkbox) { onConsentCheckedChange(!consentChecked) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = consentChecked,
                onCheckedChange = onConsentCheckedChange,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.width(AppSpacing.Compact))
            Text(
                "Persetujuan perekaman telah diberikan.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
        }

        Button(
            onClick = onStartSession,
            enabled = canStart,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.size(AppSpacing.Compact))
            Text("Mulai sesi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        uiState.startBlockMessage?.let { blocked ->
            Text(blocked, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Medium, Alignment.CenterHorizontally)
        ) {
            TextButton(onClick = onImportAudio, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Impor audio")
            }
            if (onImportPackage != null) {
                TextButton(onClick = onImportPackage, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Impor sesi (ZIP)")
                }
            }
            TextButton(onClick = onOpenDrawer, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Riwayat sesi")
            }
        }

        if (uiState.sessionHistory.isNotEmpty()) {
            Divider(color = clinical.borderSubtle)
            RecentSessionsQuickAccess(
                sessions = uiState.sessionHistory.take(RECENT_SESSIONS_PREVIEW),
                onSelectSession = onSelectSession
            )
        }

        VerbatimDisclosure(
            label = "Informasi & batasan prototipe riset",
            expanded = researchInfoExpanded,
            onExpandedChange = { researchInfoExpanded = it }
        ) {
            Text(
                "Verbatim+ adalah alat bantu riset lokal untuk meninjau percakapan, sinyal akustik, dan denyut nadi. " +
                    "Indikator yang tampil mendukung refleksi klinis, bukan diagnosis medis atau pendeteksi kebohongan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LanguageSegmentedSelector(selectedLanguage: String, onSelect: (String) -> Unit) {
    val clinical = MaterialTheme.clinicalColors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, clinical.borderSubtle)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            LanguageSegmentOption(
                label = "Indonesia",
                selected = selectedLanguage == "id",
                onClick = { onSelect("id") },
                modifier = Modifier.weight(1f)
            )
            Surface(
                modifier = Modifier.width(1.dp).height(48.dp),
                color = clinical.borderSubtle
            ) {}
            LanguageSegmentOption(
                label = "English",
                selected = selectedLanguage == "en",
                onClick = { onSelect("en") },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LanguageSegmentOption(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected },
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.Small, vertical = AppSpacing.Compact),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchWearerDropdownRow(selectedRole: WatchWearerRole, onSelectRole: (WatchWearerRole) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(WatchWearerRole.CLIENT, WatchWearerRole.PSYCHOLOGIST)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
    ) {
        Text(
            "Dipakai oleh",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f)
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(0.55f)
        ) {
            OutlinedTextField(
                value = selectedRole.uiLabel(),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .menuAnchor()
                    .semantics { stateDescription = if (expanded) "Terbuka" else "Tertutup" }
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { role ->
                    DropdownMenuItem(
                        text = { Text(role.uiLabel()) },
                        onClick = {
                            onSelectRole(role)
                            expanded = false
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadinessStatusRow(
    icon: ImageVector,
    label: String,
    statusText: String,
    statusColor: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onRowClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onRowClick != null) Modifier.clickable(role = Role.Button, onClick = onRowClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(AppSpacing.Small))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            statusText,
            style = MaterialTheme.typography.labelLarge,
            color = statusColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(AppSpacing.Compact))
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun RecentSessionsQuickAccess(sessions: List<VerbatimSessionSummary>, onSelectSession: (String) -> Unit) {
    val clinical = MaterialTheme.clinicalColors
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
        Text(
            "Akses cepat sesi sebelumnya",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        sessions.forEachIndexed { index, session ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onSelectSession(session.sessionId) }
                    .padding(vertical = AppSpacing.Compact),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        session.sessionName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${formatQuickDate(session.startedAtMillis)} · ${formatQuickDuration(session.durationSeconds)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(AppSpacing.Compact))
                Box(modifier = Modifier.heightIn(min = 32.dp), contentAlignment = Alignment.Center) {
                    Text("Tinjau", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (index < sessions.lastIndex) {
                Divider(color = clinical.borderSubtle)
            }
        }
    }
}

private fun WatchWearerRole.uiLabel(): String = when (this) {
    WatchWearerRole.CLIENT -> "Klien"
    WatchWearerRole.PSYCHOLOGIST -> "Psikolog"
    WatchWearerRole.UNSPECIFIED -> "Tidak ada"
}

private fun formatQuickDuration(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

private fun formatQuickDate(timestamp: Long): String = SimpleDateFormat("dd MMM, HH:mm", Locale("id", "ID")).format(Date(timestamp))
