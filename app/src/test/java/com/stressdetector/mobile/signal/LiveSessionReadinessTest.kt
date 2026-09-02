package com.stressdetector.mobile.signal

import com.stressdetector.mobile.data.PpgStreamState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSessionReadinessTest {
    @Test
    fun `direct transport requires only active session identity`() {
        val ready = PpgStreamState(
            sessionId = "session-2"
        )

        assertTrue(ready.readyForLiveSession("session-2"))
        assertFalse(ready.readyForLiveSession("session-1"))
        assertFalse(ready.copy(protocolRejected = true).readyForLiveSession("session-2"))
    }
}
