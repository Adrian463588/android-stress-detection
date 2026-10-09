package com.stressdetector.mobile.ui.viewmodels

import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.domain.engine.DomainAnnotation
import com.stressdetector.mobile.domain.engine.PlaybackState
import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import com.stressdetector.mobile.domain.engine.SpeechSegment
import com.stressdetector.mobile.domain.engine.VerbatimSessionDetail
import com.stressdetector.mobile.domain.engine.VerbatimSessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerbatimWorkspaceTest {
    @Test fun activeCommandFailureRetainsStopButNeverNewStartOrArchiveActions() {
        val state = archive().copy(isSessionActive = true, sessionCommandState = SessionCommandState.Error("pause send failed"))
        val view = VerbatimWorkspace(state)
        assertTrue(view.canStop)
        assertFalse(view.canPause)
        assertFalse(state.canStartNewSession)
        assertFalse(view.canExportText)
        assertFalse(view.canReviewAudio)
    }

    @Test fun inactiveErrorIsNotAnActiveSession() {
        val state = archive().copy(sessionCommandState = SessionCommandState.Error("archive warning"))
        assertFalse(VerbatimWorkspace(state).canStop)
        assertTrue(VerbatimWorkspace(state).canExportText)
    }

    @Test fun everyPendingCommandBlocksArchiveMutationsAndPlayback() {
        listOf(
            SessionCommandState.Starting,
            SessionCommandState.Pausing,
            SessionCommandState.Resuming,
            SessionCommandState.Stopping,
            SessionCommandState.Finalizing
        ).forEach { command ->
            val view = VerbatimWorkspace(archive().copy(sessionCommandState = command))
            assertFalse(view.canMutateArchive)
            assertFalse(view.canReviewAudio)
            assertFalse(view.canAnnotate)
        }
    }

    @Test fun finalizationBlocksReviewEvenWithIdleCommand() {
        val view = VerbatimWorkspace(archive().copy(isFinalizing = true))
        assertFalse(view.canExportText)
        assertFalse(view.canEditTranscript)
        assertFalse(view.canAnnotate)
    }

    @Test fun missingOrMismatchedArchiveDoesNotEnableActions() {
        assertFalse(VerbatimWorkspace(archive().copy(selectedSession = null)).canExportText)
        assertFalse(VerbatimWorkspace(archive().copy(selectedSessionId = "other")).canMutateArchive)
    }

    @Test fun txtDoesNotRequireZipOrWatchEvidence() {
        val state = archive()
        assertTrue(VerbatimWorkspace(state).canExportText)
        assertFalse(VerbatimWorkspace(state).canExportZip)
    }

    @Test fun partialOrEmptyTranscriptDoesNotEnableTxt() {
        assertFalse(VerbatimWorkspace(archive().copy(transcriptSegments = listOf(segment().copy(isFinal = false)))).canExportText)
        assertFalse(VerbatimWorkspace(archive().copy(transcriptSegments = emptyList())).canExportText)
    }

    @Test fun saveAndExportBlockConflictingMutationsAndNewStart() {
        listOf(archive().copy(isSavingTranscript = true), archive().copy(isExporting = true)).forEach { state ->
            val view = VerbatimWorkspace(state)
            assertFalse(view.canExportText)
            assertFalse(view.canExportZip)
            assertFalse(view.canEditTranscript)
            assertFalse(view.canMutateArchive)
            assertFalse(state.canStartNewSession)
        }
    }

    @Test fun playbackNeedsRealPreparedState() {
        listOf(PlaybackState.Idle, PlaybackState.Preparing, PlaybackState.Error("missing audio")).forEach {
            assertFalse(VerbatimWorkspace(archive().copy(playbackState = it)).canReviewAudio)
        }
        assertTrue(VerbatimWorkspace(archive().copy(playbackState = PlaybackState.Ready)).canReviewAudio)
        assertTrue(VerbatimWorkspace(archive().copy(playbackState = PlaybackState.Error("retry allowed"))).canPrepareAudio)
    }

    @Test fun partialFailureArchiveCanBeReviewedOnlyWithPreparedAudio() {
        val state = archive()
        val detail = checkNotNull(state.selectedSession)
        val failed = state.copy(
            selectedSession = detail.copy(summary = detail.summary.copy(finalizationStatus = "FAILED_WITH_EVIDENCE")),
            playbackState = PlaybackState.Ready
        )
        assertTrue(VerbatimWorkspace(failed).canReviewAudio)
        assertTrue(VerbatimWorkspace(failed).canExportText)
        assertFalse(VerbatimWorkspace(failed.copy(playbackState = PlaybackState.Error("missing artifact"))).canReviewAudio)
    }

    @Test fun deletionBlocksExportsReviewEditsAndNewStart() {
        val state = archive().copy(isDeletingSession = true)
        val view = VerbatimWorkspace(state)
        assertFalse(view.canManageStoredSessions)
        assertFalse(view.canPrepareAudio)
        assertFalse(view.canExportText)
        assertFalse(view.canReviewAudio)
        assertFalse(view.canEditTranscript)
        assertFalse(state.canStartNewSession)
    }

    @Test fun recordingAndPauseAllowNotesWithoutPlayback() {
        listOf(SessionCommandState.Recording, SessionCommandState.Paused).forEach {
            val view = VerbatimWorkspace(archive().copy(isSessionActive = true, sessionCommandState = it))
            assertTrue(view.canAnnotate)
            assertTrue(view.canEditTranscript)
            assertFalse(view.canReviewAudio)
        }
    }

    @Test fun acousticAndQualitySourcesNeverBecomePpg() {
        listOf("PROSODY_DIVERGENCE", "LOW_AUDIO_QUALITY", "ACOUSTIC_DIVERGENCE", "ACOUSTIC_ENERGY_CHANGE").forEach {
            assertEquals(VerbatimMarkerFilter.AUDIO, VerbatimMarkerFilter.family(it))
            assertFalse(VerbatimMarkerFilter.PPG.includes(event(it, "GREEN")))
        }
    }

    @Test fun everyPhysiologicalTaxonomyRemainsPpgWithoutFusion() {
        listOf(
            "BPM_TRAJECTORY",
            "BPM_RISE",
            "BPM_RECOVERY",
            "PHYSIOLOGICAL_BPM_TRAJECTORY",
            "TEMPORAL_BASELINE_AROUSAL",
            "MODEL_AROUSAL",
            "STRESS_ELEVATED",
            "CHANNEL_AGREEMENT"
        ).forEach {
            assertEquals(VerbatimMarkerFilter.PPG, VerbatimMarkerFilter.family(it))
        }
        val green = event("BPM_TRAJECTORY", "GREEN")
        val ir = event("BPM_TRAJECTORY", "IR")
        assertEquals(2, VerbatimMarkerCounts(archive().copy(divergenceEvents = listOf(green, ir))).count(VerbatimMarkerFilter.PPG))
    }

    @Test fun allPreservesUnknownLegacyMarkers() {
        val unknown = event("LEGACY_UNKNOWN", "")
        assertTrue(VerbatimMarkerFilter.ALL.includes(unknown))
        assertFalse(VerbatimMarkerFilter.PPG.includes(unknown))
        assertEquals(VerbatimMarkerFilter.TEXT, VerbatimMarkerFilter.family("INCONSISTENCY_CUE"))
    }

    @Test fun countsUseDistinctObservedEvidenceAndLiteralCues() {
        val audio = event("LOW_AUDIO_QUALITY", "audio")
        val note = DomainAnnotation("note", "test-session", 100, "test note", "user")
        val counts = VerbatimMarkerCounts(archive().copy(divergenceEvents = listOf(audio, audio), annotations = listOf(note, note)))
        assertEquals(3, counts.count(VerbatimMarkerFilter.ALL))
        assertEquals(1, counts.count(VerbatimMarkerFilter.TEXT))
        assertEquals(1, counts.count(VerbatimMarkerFilter.AUDIO))
        assertEquals(1, counts.count(VerbatimMarkerFilter.MANUAL))
        assertEquals(0, counts.count(VerbatimMarkerFilter.PPG))
    }

    private fun archive() = UnifiedSessionUiState(
        selectedSessionId = "test-session",
        speechRuntimeState = SpeechRuntimeState.Ready,
        transcriptSegments = listOf(segment()),
        selectedSession = VerbatimSessionDetail(
            VerbatimSessionSummary("test-session", "Test", 0, 10_000, 10, "audio.wav", "", "COMPLETED", "UNAVAILABLE"),
            listOf(segment()),
            emptyList()
        )
    )

    private fun segment() = SpeechSegment("utterance", "Speaker_0", 100, 1_000, 100, 1_000, "mungkin besok", 0.9f)
    private fun event(type: String, channel: String) = DivergenceEventEntity(
        "$type:$channel", "test-session", 100,
        null, null, null, "observed test fixture", channel = channel, markerType = type
    )
}
