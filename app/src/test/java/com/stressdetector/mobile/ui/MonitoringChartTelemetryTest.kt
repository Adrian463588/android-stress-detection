package com.stressdetector.mobile.ui

import com.stressdetector.mobile.domain.PpgChartData
import com.stressdetector.mobile.domain.PpgChartMode
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringChartTelemetryTest {
    @Test
    fun filteredCoreUsesFilteredRangeLabel() {
        val chart = PpgChartData.empty().copy(mode = PpgChartMode.CLEANED_CORE)

        assertTrue(chartTelemetry(chart).startsWith("terfilter "))
    }

    @Test
    fun rawDiagnosticKeepsRawRangeLabel() {
        val chart = PpgChartData.empty().copy(mode = PpgChartMode.RAW_DIAGNOSTIC)

        assertTrue(chartTelemetry(chart).startsWith("raw "))
    }
}
