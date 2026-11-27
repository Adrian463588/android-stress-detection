@file:Suppress("FunctionNaming", "LongParameterList", "LongMethod", "MagicNumber")

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.clinicalColors
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
    modifier: Modifier = Modifier,
    onBookmark: (() -> Unit)? = null
) {
    val clinical = MaterialTheme.clinicalColors
    val isPaused = workspace.state.sessionCommandState == SessionCommandState.Paused
    val pauseLabel = if (isPaused) "Lanjutkan" else "Jeda"
    val fontScale = LocalDensity.current.fontScale

    Column(modifier.padding(vertical = AppSpacing.Compact), verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
        if (workspace.canStop) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val useCompactRow = maxWidth >= 336.dp && fontScale < 1.5f
                if (useCompactRow) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
                    ) {
                        OutlinedButton(
                            onClick = onPauseOrResume,
                            enabled = workspace.canPause,
                            contentPadding = PaddingValues(horizontal = AppSpacing.Compact),
                            modifier = Modifier.weight(0.9f).heightIn(min = 48.dp)
                        ) {
                            Icon(
                                if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(pauseLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (onBookmark != null) {
                            OutlinedButton(
                                onClick = onBookmark,
                                enabled = workspace.canAnnotate,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = clinical.marker),
                                contentPadding = PaddingValues(horizontal = AppSpacing.Compact),
                                modifier = Modifier.weight(0.95f).heightIn(min = 48.dp)
                            ) {
                                Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Tandai", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Button(
                            onClick = onStop,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = clinical.recording,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            contentPadding = PaddingValues(horizontal = AppSpacing.Small),
                            modifier = Modifier.weight(if (onBookmark != null) 1.35f else 1f).heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Stop & simpan", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(onClick = onPauseOrResume, enabled = workspace.canPause, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(pauseLabel)
                        }
                        if (onBookmark != null) {
                            OutlinedButton(
                                onClick = onBookmark,
                                enabled = workspace.canAnnotate,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = clinical.marker),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("Tandai")
                            }
                        }
                        Button(
                            onClick = onStop,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = clinical.recording,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Stop & simpan")
                        }
                    }
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
    modifier: Modifier = Modifier,
    onExportResearch: (() -> Unit)? = null,
    onExportTemperatureCsv: (() -> Unit)? = null
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
                if (onExportTemperatureCsv != null) {
                    OutlinedButton(
                        onClick = onExportTemperatureCsv,
                        enabled = workspace.canMutateArchive && workspace.state.skinTemperatureSamples.isNotEmpty(),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Ekspor suhu (CSV)")
                    }
                }
                if (onExportResearch != null) {
                    OutlinedButton(
                        onClick = onExportResearch,
                        enabled = workspace.canMutateArchive,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Ekspor riset de-identified (CSV)")
                    }
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
    selectedFilter: VerbatimMarkerFilter,
    onSelect: (VerbatimMarkerFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
        VerbatimMarkerFilter.entries.forEach { filter ->
            val selected = selectedFilter == filter
            FilterChip(
                selected = selected,
                onClick = { onSelect(filter) },
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    stateDescription = if (selected) "Filter dipilih" else "Filter tidak dipilih"
                },
                label = { Text("${filter.label} · ${counts.count(filter)}") }
            )
        }
    }
}
