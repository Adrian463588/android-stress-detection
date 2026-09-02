package com.stressdetector.mobile.ui.viewmodels

import com.stressdetector.mobile.domain.engine.LiveBiosignalSnapshot
import com.stressdetector.mobile.domain.engine.UnifiedSessionLiveSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCommandStateTest {
    @Test
    fun `selection stays locked through every live transition`() {
        assertFalse(SessionCommandState.Idle.locksSessionSelection())
        assertFalse(SessionCommandState.Error("failed").locksSessionSelection())
        assertTrue(SessionCommandState.Starting.locksSessionSelection())
        assertTrue(SessionCommandState.Recording.locksSessionSelection())
        assertTrue(SessionCommandState.Stopping.locksSessionSelection())
        assertTrue(SessionCommandState.Finalizing.locksSessionSelection())
    }

    @Test
    fun `live snapshot generation must agree with biosignal generation`() {
        val accepted = UnifiedSessionLiveSnapshot(
            sessionId = "session",
            generation = 4L,
            biosignal = LiveBiosignalSnapshot(sessionId = "session", sessionGeneration = 4L)
        )
        val stale = accepted.copy(
            generation = 3L,
            biosignal = accepted.biosignal.copy(sessionGeneration = 3L)
        )

        assertTrue(isLiveSessionGenerationAccepted(0L, accepted))
        assertTrue(isLiveSessionGenerationAccepted(4L, accepted))
        assertFalse(isLiveSessionGenerationAccepted(4L, stale))
    }

    @Test
    fun `initial session snapshot can render before timeline generation is published`() {
        val initial = UnifiedSessionLiveSnapshot(
            sessionId = "session",
            generation = 0L,
            biosignal = LiveBiosignalSnapshot(sessionId = "session", sessionGeneration = 0L)
        )

        assertTrue(isLiveSessionGenerationAccepted(0L, initial))
        assertFalse(isLiveSessionGenerationAccepted(2L, initial))
    }
}
