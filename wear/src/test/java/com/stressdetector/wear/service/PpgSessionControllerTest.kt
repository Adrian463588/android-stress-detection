package com.stressdetector.wear.service

import com.stressdetector.shared.SessionConfig
import com.stressdetector.shared.WearSessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgSessionControllerTest {
    @Test
    fun clampsElapsedToFiveMinutes() {
        val session = PpgSessionState("s1", startedAtMillis = 1000L)

        assertEquals(SessionConfig.SESSION_DURATION_MILLIS, session.elapsed(1000L + SessionConfig.SESSION_DURATION_MILLIS + 1))
    }

    @Test
    fun completesAtFiveMinutes() {
        val session = PpgSessionState("s1", startedAtMillis = 1000L)

        assertFalse(session.isComplete(1000L + SessionConfig.SESSION_DURATION_MILLIS - 1))
        assertTrue(session.isComplete(1000L + SessionConfig.SESSION_DURATION_MILLIS))
    }

    @Test
    fun verbatimElapsedPassesFiveMinutesWithoutClamping() {
        val session = PpgSessionState(
            sessionId = "s1",
            mode = WearSessionMode.VERBATIM_AROUSAL,
            startedAtMillis = 1000L
        )

        assertEquals(
            SessionConfig.SESSION_DURATION_MILLIS + 1_000L,
            session.elapsed(1000L + SessionConfig.SESSION_DURATION_MILLIS + 1_000L)
        )
        assertFalse(session.isComplete(1000L + SessionConfig.SESSION_DURATION_MILLIS + 1_000L))
    }
}
