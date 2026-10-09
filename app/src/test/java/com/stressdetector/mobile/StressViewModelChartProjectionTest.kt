package com.stressdetector.mobile

import com.stressdetector.mobile.domain.PpgChartData
import com.stressdetector.mobile.domain.PpgChartMode
import com.stressdetector.mobile.domain.StressSessionFinalizationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class StressViewModelChartProjectionTest {
    private val liveState = MobileUiState(sessionId = "session-current", isRecording = true)
    private val previewCharts = Triple(
        PpgChartData.empty("green preview").copy(mode = PpgChartMode.FULL_SESSION_PREVIEW),
        PpgChartData.empty("IR preview").copy(mode = PpgChartMode.FULL_SESSION_PREVIEW),
        PpgChartData.empty("Red preview").copy(mode = PpgChartMode.FULL_SESSION_PREVIEW)
    )

    @Test
    fun matchingLiveSessionAcceptsPreview() {
        val projected = liveState.withLiveChartFrame("session-current", previewCharts)

        assertSame(previewCharts.first, projected.greenChart)
        assertSame(previewCharts.second, projected.irChart)
        assertSame(previewCharts.third, projected.redChart)
    }

    @Test
    fun staleNewAndUnidentifiedSessionsCannotReplaceCharts() {
        listOf(null, "session-previous", "session-next").forEach { frameSessionId ->
            assertSame(liveState, liveState.withLiveChartFrame(frameSessionId, previewCharts))
        }
    }

    @Test
    fun stoppedFinalizingAndFinalizedSessionsKeepExistingCharts() {
        val finalized = liveState.copy(
            isRecording = false,
            finalizationState = StressSessionFinalizationState.Completed,
            greenChart = PpgChartData.empty("final Green").copy(mode = PpgChartMode.CLEANED_CORE),
            irChart = PpgChartData.empty("rejected IR").copy(mode = PpgChartMode.RAW_DIAGNOSTIC)
        )

        listOf(
            liveState.copy(isRecording = false),
            liveState.copy(finalizationState = StressSessionFinalizationState.Stopping),
            liveState.copy(finalizationSnapshotSessionId = "session-current"),
            finalized
        ).forEach { state ->
            assertSame(state, state.withLiveChartFrame("session-current", previewCharts))
        }
        assertEquals(PpgChartMode.CLEANED_CORE, finalized.greenChart.mode)
        assertEquals(PpgChartMode.RAW_DIAGNOSTIC, finalized.irChart.mode)
    }

    @Test
    fun acceptedCoreUsesCleanedChartWithoutProjectingRaw() {
        val cleaned = PpgChartData.empty("final core").copy(mode = PpgChartMode.CLEANED_CORE)
        var rawProjected = false

        val chart = finalHomeChannelChart(
            rejectionReason = null,
            cleanedCore = { cleaned },
            rawDiagnostic = {
                rawProjected = true
                previewCharts.first
            }
        )

        assertSame(cleaned, chart)
        assertFalse(rawProjected)
    }

    @Test
    fun rejectedAggregationShowsRawDiagnosticWithActualReason() {
        val reason = "insufficient_valid_core_windows:3/7"
        var cleanedProjected = false

        val chart = finalHomeChannelChart(
            rejectionReason = reason,
            cleanedCore = {
                cleanedProjected = true
                PpgChartData.empty("unused cleaned core")
            },
            rawDiagnostic = { previewCharts.second }
        )

        assertEquals(PpgChartMode.RAW_DIAGNOSTIC, chart.mode)
        assertEquals("RAW / DIAGNOSTIC: $reason", chart.statusMessage)
        assertFalse(cleanedProjected)
    }
}
