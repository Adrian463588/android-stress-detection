package com.stressdetector.mobile.lifecycle

import com.google.gson.JsonParser
import org.junit.Assert.assertThrows
import org.junit.Test

/** Parser fixtures; complete Room/file/Keystore restore is covered by physical instrumentation. */
class PortableUserDataArchiveTest {
    @Test
    fun rejectsOutOfScopeEntry() {
        assertThrows(IllegalArgumentException::class.java) { PortableArchiveFormat.validateEntryName("raw/source.csv") }
    }

    @Test
    fun rejectsMissingManifestFields() {
        assertThrows(IllegalArgumentException::class.java) { PortableArchiveFormat.parseManifest("{}") }
    }

    @Test
    fun formatRejectsTraversalAndUnrelatedTables() {
        listOf("charts/../session.json", "files/verbatim/../../source.csv", "rows/user_consents.ndjson")
            .forEach { path ->
                assertThrows(IllegalArgumentException::class.java) { PortableArchiveFormat.validateEntryName(path) }
            }
    }

    @Test
    fun rowDecoderRejectsColumnMismatchAndNonFiniteValues() {
        val columns = listOf(ArchiveColumn("stressProbability", "REAL", false, null, 0))
        assertThrows(IllegalArgumentException::class.java) { PortableArchiveFormat.decodeRow("[]", columns) }
        assertThrows(IllegalArgumentException::class.java) { PortableArchiveFormat.decodeRow("[[\"r\",\"NaN\"]]", columns) }
    }

    @Test
    fun chartRejectsTimelineOutsideCanonicalCore() {
        assertThrows(IllegalArgumentException::class.java) { validatePortableHistoryChart(chart("[29000,30020]"), "session-fixture") }
    }

    @Test
    fun chartRejectsInvalidSampleLengthsAndOwnership() {
        assertThrows(IllegalArgumentException::class.java) { validatePortableHistoryChart(chart("[30000]"), "session-fixture") }
        assertThrows(IllegalArgumentException::class.java) { validatePortableHistoryChart(chart(), "unrelated-fixture") }
    }

    @Test
    fun chartPreservesValidObservedTimelineWithoutRepair() {
        validatePortableHistoryChart(chart(), "session-fixture")
    }

    private fun chart(elapsed: String = "[30000,30020]") = JsonParser.parseString(
        """{"version":2,"series":[{"session_id":"session-fixture","channel":"ppg_green",
        "processing_fingerprint":"test-only","elapsed_millis":$elapsed,"display_values":[1,2],
        "interpolated_mask":[false,false],"gaps":[]}]}"""
    ).asJsonObject
}
