package com.stressdetector.mobile.ui.viewmodels

import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.domain.engine.PlaybackState

/** Presentation policy only; native evidence and model decisions remain unchanged. */
internal class VerbatimWorkspace(val state: UnifiedSessionUiState) {
    private val command = state.sessionCommandState
    val capturing = state.isSessionActive || command.locksSessionSelection()
    val canStop = command == SessionCommandState.Recording ||
        command == SessionCommandState.Paused ||
        (state.isSessionActive && command is SessionCommandState.Error)
    val canPause = command == SessionCommandState.Recording || command == SessionCommandState.Paused
    val archiveReady = !capturing &&
        !state.isFinalizing &&
        state.selectedSessionId != null &&
        state.selectedSession?.summary?.sessionId == state.selectedSessionId
    val canManageStoredSessions =
        !capturing && !state.isFinalizing && !state.isSavingTranscript && !state.isExporting && !state.isDeletingSession
    val canMutateArchive = archiveReady && canManageStoredSessions
    val canExportText = canMutateArchive && state.transcriptSegments.any { it.isFinal && it.displayText.isNotBlank() }
    val canExportZip = canMutateArchive && !state.selectedSession?.summary?.artifactFolderPath.isNullOrBlank()
    val canPrepareAudio = archiveReady && !state.isDeletingSession
    val canReviewAudio = canPrepareAudio &&
        state.playbackState != PlaybackState.Idle &&
        state.playbackState != PlaybackState.Preparing &&
        state.playbackState !is PlaybackState.Error
    val canEditTranscript = !state.isSavingTranscript &&
        !state.isDeletingSession &&
        !state.isExporting &&
        (canStop || archiveReady) &&
        !state.isFinalizing
    val canAnnotate = state.selectedSessionId != null &&
        !state.isFinalizing &&
        (canStop || canMutateArchive)
}

internal enum class VerbatimMarkerFilter(val label: String) {
    ALL("Semua"),
    TEXT("Teks"),
    AUDIO("Audio"),
    PPG("PPG"),
    MANUAL("Catatan");

    fun includes(event: DivergenceEventEntity): Boolean = this == ALL || this == family(event.markerType)

    companion object {
        /** Unknown legacy events stay in All, never mislabeled as physiological evidence. */
        fun family(type: String): VerbatimMarkerFilter = when (type) {
            "INCONSISTENCY_CUE" -> TEXT
            "LOW_AUDIO_QUALITY", "ACOUSTIC_DIVERGENCE", "ACOUSTIC_ENERGY_CHANGE", "PROSODY_DIVERGENCE" -> AUDIO
            "BPM_TRAJECTORY", "BPM_RISE", "BPM_RECOVERY", "PHYSIOLOGICAL_BPM_TRAJECTORY",
            "TEMPORAL_BASELINE_AROUSAL", "MODEL_AROUSAL", "STRESS_ELEVATED", "CHANNEL_AGREEMENT" -> PPG
            "MANUAL" -> MANUAL
            else -> ALL
        }
    }
}

internal class VerbatimMarkerCounts(state: UnifiedSessionUiState) {
    private val events = state.divergenceEvents.distinctBy { it.eventId }
    private val cues = state.reviewCues.size
    private val notes = state.annotations.distinctBy { it.id }.size

    fun count(filter: VerbatimMarkerFilter): Int = events.count(filter::includes) +
        (if (filter == VerbatimMarkerFilter.ALL || filter == VerbatimMarkerFilter.TEXT) cues else 0) +
        (if (filter == VerbatimMarkerFilter.ALL || filter == VerbatimMarkerFilter.MANUAL) notes else 0)
}
