package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.engine.LlmRuntimeReadiness
import com.stressdetector.mobile.domain.engine.RagMode
import com.stressdetector.mobile.domain.search.WebSearchMode
import com.stressdetector.mobile.ml.llm.ModelDownloadState
import com.stressdetector.mobile.ui.viewmodels.CounselorPhase
import com.stressdetector.mobile.ui.viewmodels.CounselorUiState
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CounselorTopBar(model: CounselorScreenModel) {
    val state = model.runtime.state
    val navigation = model.navigation
    val dialogs = model.dialogs
    val horizontalPadding = counselorHorizontalPadding(navigation.windowSizeClass)
    TopAppBar(
        modifier = Modifier.padding(horizontal = horizontalPadding),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        navigationIcon = {
            IconButton(onClick = { model.layout.scope.launch { model.layout.drawerState.open() } }) {
                Icon(Icons.Default.Menu, contentDescription = "Buka histori chat")
            }
        },
        title = {
            Column {
                Text(
                    text = "AI Counselor",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
                Text(
                    text = counselorSubtitle(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        },
        actions = { CounselorTopBarActions(model, dialogs) }
    )
}

internal fun counselorHorizontalPadding(windowSizeClass: WindowWidthSizeClass): Dp = when (windowSizeClass) {
    WindowWidthSizeClass.Compact -> 16.dp
    WindowWidthSizeClass.Medium -> 48.dp
    WindowWidthSizeClass.Expanded -> 96.dp
    else -> 16.dp
}

@Composable
private fun CounselorTopBarActions(model: CounselorScreenModel, dialogs: CounselorDialogState) {
    val navigation = model.navigation
    val state = model.runtime.state
    if (navigation.windowSizeClass == WindowWidthSizeClass.Compact) {
        Box {
            IconButton(onClick = { dialogs.onHeaderMenuChanged(true) }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Aksi AI Counselor")
            }
            DropdownMenu(
                expanded = dialogs.showHeaderMenu,
                onDismissRequest = { dialogs.onHeaderMenuChanged(false) }
            ) {
                DropdownMenuItem(
                    text = { Text("Sumber web") },
                    leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) },
                    onClick = {
                        dialogs.onHeaderMenuChanged(false)
                        dialogs.onWebSearchModeChanged(true)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Dokumen") },
                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                    onClick = {
                        dialogs.onHeaderMenuChanged(false)
                        navigation.onNavigateToLibrary()
                    }
                )
            }
        }
    } else {
        IconButton(onClick = { dialogs.onWebSearchModeChanged(true) }) {
            Icon(
                Icons.Default.Language,
                contentDescription = when (state.webSearchMode) {
                    WebSearchMode.Disabled -> "Aktifkan pencarian web"
                    WebSearchMode.NextMessage -> "Pencarian web aktif untuk pesan berikutnya"
                    WebSearchMode.Persistent -> "Pencarian web otomatis selalu aktif"
                },
                tint = if (state.webSearchMode == WebSearchMode.Disabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }
        TextButton(onClick = navigation.onNavigateToLibrary, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Dokumen")
        }
    }
}

private fun counselorSubtitle(state: CounselorUiState): String = if (state.phase == CounselorPhase.CRISIS_ESCALATION) {
    "Dukungan keselamatan"
} else {
    modelDownloadSubtitle(state) ?: runtimeSubtitle(state)
}

private fun modelDownloadSubtitle(state: CounselorUiState): String? {
    if (state.isModelReady) return null
    return when (val downloadState = state.modelDownloadState) {
        is ModelDownloadState.Verifying -> "Model sedang diverifikasi"
        is ModelDownloadState.Downloading -> "Model sedang diunduh"
        is ModelDownloadState.WaitingForNetwork -> downloadState.message
        is ModelDownloadState.TransferRetry -> "Transfer model akan dicoba lagi"
        else -> "Model belum tersedia"
    }
}

private fun runtimeSubtitle(state: CounselorUiState): String = when {
    state.runtimeReadiness is LlmRuntimeReadiness.Unknown ||
        state.runtimeReadiness is LlmRuntimeReadiness.Checking -> "Memeriksa AI lokal"
    state.runtimeReadiness is LlmRuntimeReadiness.Unavailable -> "Runtime bermasalah"
    state.retrievalMode == RagMode.WEB_GROUNDED -> "Berbasis sumber web"
    state.retrievalMode == RagMode.BUNDLED_GROUNDED -> "Berbasis dataset bawaan"
    state.retrievalMode == RagMode.GROUNDED -> "Berbasis dokumen"
    state.runtimeReadiness is LlmRuntimeReadiness.Ready -> "Gemma 4 siap offline · mode umum"
    else -> "Mode umum"
}
