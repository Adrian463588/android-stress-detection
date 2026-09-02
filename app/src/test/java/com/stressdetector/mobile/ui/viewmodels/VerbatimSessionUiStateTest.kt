package com.stressdetector.mobile.ui.viewmodels

import com.stressdetector.mobile.domain.engine.VerbatimSessionSummary
import com.stressdetector.mobile.ml.storage.ModelStorageState
import com.stressdetector.mobile.ml.storage.SafStorageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerbatimSessionUiStateTest {

    @Test
    fun `unselected storage exposes recovery state without blocking local runtime`() {
        val storage = ModelStorageState(status = SafStorageStatus.UNSELECTED)

        assertTrue(storage.needsVerbatimStorageRecovery())
        assertEquals("Folder belum terhubung", storage.verbatimStatusLabel())
        assertTrue(storage.verbatimRecoveryMessage().contains("Profile"))
    }

    @Test
    fun `connected storage is not presented as a recovery state`() {
        val storage = ModelStorageState(
            connected = true,
            status = SafStorageStatus.CONNECTED
        )

        assertFalse(storage.needsVerbatimStorageRecovery())
        assertEquals("Folder tersambung", storage.verbatimStatusLabel())
    }

    @Test
    fun `drawer maps internal finalization and missing artifact to concise labels`() {
        val summary = VerbatimSessionSummary(
            sessionId = "session-1",
            sessionName = "Sesi wawancara",
            startedAtMillis = 1L,
            endedAtMillis = 2L,
            durationSeconds = 120L,
            audioFilePath = "/data/audio.wav",
            artifactFolderPath = "",
            finalizationStatus = "COMPLETED",
            summaryStatus = "UNAVAILABLE"
        )

        assertEquals("Tersimpan", summary.drawerStatusLabel())
        assertEquals("audio tersimpan · artefak belum siap", summary.drawerStorageLabel())
    }
}
