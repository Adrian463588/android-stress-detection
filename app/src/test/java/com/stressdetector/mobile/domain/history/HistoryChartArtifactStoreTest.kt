package com.stressdetector.mobile.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryChartArtifactStoreTest {
    @Test
    fun statusMapperSeparatesStressNormalAndInvalid() {
        assertEquals(
            HistoryResultStatus.STRESS,
            HistoryStatusMapper.map("Stress", null, "available")
        )
        assertEquals(
            HistoryResultStatus.NORMAL,
            HistoryStatusMapper.map("Normal", null, "available")
        )
        assertEquals(
            HistoryResultStatus.NORMAL,
            HistoryStatusMapper.map("Normal", null, "available", transportIncomplete = true)
        )
    }

    @Test
    fun transportWarningDoesNotReplaceAValidPredictionOutcome() {
        val outcome = SessionOutcomeResolver.resolve(
            finalLabel = "Stress",
            errorReason = "transport_incomplete",
            reportStatus = "available",
            transportIncomplete = true
        )

        assertEquals(PredictionStatus.VALID, outcome.predictionStatus)
        assertEquals("STRESS", outcome.label?.value)
        assertEquals("transport_incomplete", outcome.transportWarning?.reason)
        assertEquals(null, outcome.rejectionReason)
    }

    @Test
    fun emptyArtifactDoesNotInventChart() {
        val artifact = HistoryChartArtifact(
            series = emptyList(),
            unavailableReason = "cleaned_core_chart_unavailable"
        )
        assertTrue(artifact.series.isEmpty())
        assertEquals("cleaned_core_chart_unavailable", artifact.unavailableReason)
    }
}
