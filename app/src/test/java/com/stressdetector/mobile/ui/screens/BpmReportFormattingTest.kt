package com.stressdetector.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class BpmReportFormattingTest {
    @Test
    fun missingObservedBpmIsNotRenderedAsZero() {
        assertEquals(
            "Green BPM: Tidak tersedia",
            bpmReportLine("Green", null, null, observationCount = 0, validPeakCount = 0)
        )
    }

    @Test
    fun observedBpmIncludesMeanMedianAndEvidenceCounts() {
        assertEquals(
            "IR BPM: mean 70.5 · median 71.0 · 6 observasi · 1234 peak valid",
            bpmReportLine("IR", 70.5f, 71f, observationCount = 6, validPeakCount = 1_234)
        )
    }
}
