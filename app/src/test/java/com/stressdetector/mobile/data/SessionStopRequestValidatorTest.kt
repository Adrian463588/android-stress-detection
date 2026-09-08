package com.stressdetector.mobile.data

import com.stressdetector.shared.SessionCommand
import com.stressdetector.shared.SessionIdentity
import com.stressdetector.shared.WearSessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStopRequestValidatorTest {
    @Test
    fun acceptsOnlyMatchingWatchStopIdentity() {
        val active = activeState()

        assertNull(
            SessionStopRequestValidator.rejectionReason(
                command = command(),
                active = active,
                sourceNodeId = "watch-node"
            )
        )
    }

    @Test
    fun rejectsWrongSourceAndGeneration() {
        val active = activeState()

        assertEquals(
            "stale_source_node",
            SessionStopRequestValidator.rejectionReason(command(), active, "other-watch")
        )
        assertEquals(
            "stale_generation",
            SessionStopRequestValidator.rejectionReason(command(generation = 8L), active, "watch-node")
        )
    }

    private fun activeState() = PpgStreamState(
        sessionId = "session-a",
        sessionNonce = "nonce-a",
        sourceNodeId = "watch-node",
        peerNodeId = "phone-node",
        protocolVersion = SessionIdentity.CURRENT_PROTOCOL_VERSION,
        generation = 7L,
        sessionMode = WearSessionMode.VERBATIM_AROUSAL
    )

    private fun command(generation: Long = 7L) = SessionCommand(
        sessionId = "session-a",
        mode = WearSessionMode.VERBATIM_AROUSAL,
        sessionNonce = "nonce-a",
        peerNodeId = "phone-node",
        generation = generation
    )
}
