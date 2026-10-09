@file:Suppress("FunctionNaming", "LongParameterList")

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.viewmodels.SessionCommandState
import com.stressdetector.mobile.ui.viewmodels.VerbatimMarkerCounts
import com.stressdetector.mobile.ui.viewmodels.VerbatimMarkerFilter
import com.stressdetector.mobile.ui.viewmodels.VerbatimWorkspace

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerbatimCaptureControls(
    workspace: VerbatimWorkspace,
    stage: String,
    onStop: () -> Unit,
    onPauseOrResume: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(vertical = AppSpacing.Compact), verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
        if (workspace.canStop) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) { Text("Hentikan & simpan") }
                OutlinedButton(onClick = onPauseOrResume, enabled = workspace.canPause, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (workspace.state.sessionCommandState == SessionCommandState.Paused) "Lanjutkan" else "Jeda")
                }
            }
        } else {
            Text(stage, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerbatimArchiveActions(
    workspace: VerbatimWorkspace,
    onExportText: () -> Unit,
    onExportZip: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable(workspace.state.selectedSessionId) { mutableStateOf(false) }
    Column(modifier) {
        Button(onClick = onExportText, enabled = workspace.canExportText, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (workspace.state.isExporting) "Mengekspor…" else "Unduh transkrip TXT")
        }
        Text(
            "Termasuk timestamp, label pembicara dan keterangan marker. Ekspor disimpan sebagai teks biasa.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        VerbatimDisclosure("Opsi sesi lainnya", expanded, { expanded = it }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
                OutlinedButton(onClick = onExportZip, enabled = workspace.canExportZip, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Ekspor arsip ZIP")
                }
                TextButton(onClick = onDelete, enabled = workspace.canMutateArchive, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Hapus sesi", color = MaterialTheme.colorScheme.error)
                }
            }
            if (workspace.state.selectedSession?.summary?.artifactFolderPath.isNullOrBlank()) {
                Text("Arsip ZIP belum tersedia. Transkrip yang tersimpan tetap dapat ditinjau.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun VerbatimMarkerFilters(
    counts: VerbatimMarkerCounts,
    selected: VerbatimMarkerFilter,
    onSelect: (VerbatimMarkerFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
        VerbatimMarkerFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text("${filter.label} · ${counts.count(filter)}") },
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    stateDescription = if (filter == selected) "Filter aktif" else "Filter tidak aktif"
                }
            )
        }
    }
}
