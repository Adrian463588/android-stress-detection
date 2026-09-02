package com.stressdetector.mobile.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class VerbatimArtifactLocationResolverTest {
    private val resolver = CanonicalVerbatimArtifactLocationResolver()

    @Test
    fun finalizedFolderWinsOverRetiredRecordingPath() {
        val session = VerbatimSessionSummary(
            sessionId = "session-1",
            sessionName = "Sesi",
            startedAtMillis = 1L,
            endedAtMillis = 2L,
            durationSeconds = 1L,
            audioFilePath = "verbatim/recording/session-1.wav.enc",
            artifactFolderPath = "verbatim_sessions/Sesi_01-01-2026_00-00-00",
            finalizationStatus = "COMPLETED",
            summaryStatus = "GENERATED"
        )

        assertEquals(
            "verbatim_sessions/Sesi_01-01-2026_00-00-00/audio.wav.enc",
            resolver.resolveAudio(session)
        )
    }

    @Test
    fun legacyPathRemainsSupportedWhenFolderIsMissing() {
        val session = VerbatimSessionSummary(
            sessionId = "session-2",
            sessionName = "Sesi",
            startedAtMillis = 1L,
            endedAtMillis = 2L,
            durationSeconds = 1L,
            audioFilePath = "verbatim/recording/session-2.wav.enc",
            artifactFolderPath = "",
            finalizationStatus = "FAILED",
            summaryStatus = "ERROR"
        )

        assertEquals("verbatim/recording/session-2.wav.enc", resolver.resolveAudio(session))
    }
}
