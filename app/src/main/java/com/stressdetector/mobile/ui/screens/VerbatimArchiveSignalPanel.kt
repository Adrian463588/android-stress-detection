@file:Suppress("FunctionNaming", "LongParameterList")

package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.engine.AudioSignalTracePage
import com.stressdetector.mobile.domain.engine.AudioTimelineReviewMarker
import com.stressdetector.mobile.ui.AppSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerbatimArchiveSignalPanel(
    page: AudioSignalTracePage?,
    loading: Boolean,
    markers: List<AudioTimelineReviewMarker>,
    playbackOffsetMs: Long,
    canPlay: Boolean,
    onMovePage: (Int) -> Unit,
    onPlayFromMarker: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        page?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (page?.manifest != null) {
            VerbatimPitchPanel(
                observations = page.observations,
                isPaused = false,
                markers = markers.map { if (canPlay) it else it.copy(playbackOffsetMs = null) },
                isArchive = true,
                playbackOffsetMs = playbackOffsetMs,
                archiveStartOffsetMs = page.startOffsetMs,
                onSelectMarker = { marker -> marker.playbackOffsetMs?.let(onPlayFromMarker) }
            )
        }
        if (page?.manifest != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                TextButton(
                    onClick = { onMovePage(-1) },
                    enabled = !loading && page.startOffsetMs > 0L,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("60 detik sebelumnya") }
                TextButton(
                    onClick = { onMovePage(1) },
                    enabled = !loading && page.endOffsetMs < page.recordingEndOffsetMs,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("60 detik berikutnya") }
            }
        }
    }
}
