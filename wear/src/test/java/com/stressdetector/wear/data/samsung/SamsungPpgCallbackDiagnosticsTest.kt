package com.stressdetector.wear.data.samsung

import com.stressdetector.shared.PpgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungPpgCallbackDiagnosticsTest {
    @Test
    fun onlyNormalContinuousStatusIsEligibleForRawPpg() {
        assertNull(SamsungPpgContinuousStatusPolicy.rejectionReason(0))
        assertEquals(
            SamsungPpgSampleRejectionReason.STATUS_UNAVAILABLE,
            SamsungPpgContinuousStatusPolicy.rejectionReason(null)
        )
        listOf(-1, 1, 2, 3).forEach { status ->
            assertEquals(
                SamsungPpgSampleRejectionReason.NON_NORMAL_STATUS,
                SamsungPpgContinuousStatusPolicy.rejectionReason(status)
            )
        }
    }

    @Test
    fun recordsChannelSpecificStatusAndMissingValueFailures() {
        val diagnostics = SamsungPpgCallbackDiagnostics()

        val first = diagnostics.record(
            PpgType.GREEN,
            SamsungPpgSampleRejectionReason.NON_NORMAL_STATUS,
            status = 3
        )
        diagnostics.record(PpgType.GREEN, SamsungPpgSampleRejectionReason.VALUE_UNAVAILABLE)
        diagnostics.record(PpgType.IR, SamsungPpgSampleRejectionReason.STATUS_UNAVAILABLE)

        assertTrue(first.shouldReport)
        assertEquals(
            "green{non_normal=1,status_unavailable=0,value_unavailable=1,last_status=3}; " +
                "ir{non_normal=0,status_unavailable=1,value_unavailable=0}",
            diagnostics.readinessSummary(setOf(PpgType.GREEN, PpgType.IR))
        )
    }

    @Test
    fun reportsFirstAndPeriodicRejectionsWithoutReportingEveryCallback() {
        val diagnostics = SamsungPpgCallbackDiagnostics()
        val first = diagnostics.record(PpgType.IR, SamsungPpgSampleRejectionReason.NON_NORMAL_STATUS, 1)
        repeat(98) { diagnostics.record(PpgType.IR, SamsungPpgSampleRejectionReason.NON_NORMAL_STATUS, 1) }
        val hundredth = diagnostics.record(PpgType.IR, SamsungPpgSampleRejectionReason.NON_NORMAL_STATUS, 1)

        assertTrue(first.shouldReport)
        assertTrue(hundredth.shouldReport)
        assertFalse(diagnostics.record(PpgType.IR, SamsungPpgSampleRejectionReason.NON_NORMAL_STATUS, 1).shouldReport)
    }

    @Test
    fun resetRemovesPreviousSessionDiagnostics() {
        val diagnostics = SamsungPpgCallbackDiagnostics()
        diagnostics.record(PpgType.GREEN, SamsungPpgSampleRejectionReason.STATUS_UNAVAILABLE)

        diagnostics.reset()

        assertNull(diagnostics.readinessSummary(setOf(PpgType.GREEN, PpgType.IR)))
    }
}
