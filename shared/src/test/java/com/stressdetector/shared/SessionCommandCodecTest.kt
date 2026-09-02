package com.stressdetector.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionCommandCodecTest {
    @Test
    fun roundTripPreservesSessionId() {
        val decoded = SessionCommandCodec.decode(SessionCommandCodec.encode(SessionCommand("session-1")))

        assertEquals("session-1", decoded.sessionId)
        assertEquals(WearSessionMode.HOME_STRESS, decoded.mode)
    }

    @Test
    fun roundTripPreservesVerbatimMode() {
        val decoded = SessionCommandCodec.decode(
            SessionCommandCodec.encode(
                SessionCommand("session-2", WearSessionMode.VERBATIM_AROUSAL)
            )
        )

        assertEquals("session-2", decoded.sessionId)
        assertEquals(WearSessionMode.VERBATIM_AROUSAL, decoded.mode)
    }

    @Test
    fun roundTripPreservesSessionNonce() {
        val decoded = SessionCommandCodec.decode(
            SessionCommandCodec.encode(SessionCommand("session-3", sessionNonce = "phone-nonce"))
        )

        assertEquals("phone-nonce", decoded.sessionNonce)
    }

    @Test
    fun roundTripPreservesAutomaticCompletionExpectation() {
        val decoded = SessionCommandCodec.decode(
            SessionCommandCodec.encode(SessionCommand("session-4", completionExpected = true))
        )

        assertEquals(true, decoded.completionExpected)
    }

    @Test
    fun roundTripPreservesPhonePeerAndGeneration() {
        val command = SessionCommand(
            sessionId = "session-identity",
            sessionNonce = "nonce",
            peerNodeId = "phone-node",
            generation = 41L
        )

        val decoded = SessionCommandCodec.decode(SessionCommandCodec.encode(command))

        assertEquals("phone-node", decoded.peerNodeId)
        assertEquals(41L, decoded.generation)
        assertEquals(SessionIdentity.CURRENT_PROTOCOL_VERSION, decoded.protocolVersion)
        assertEquals(SessionIdentity.CURRENT_PROTOCOL_BUILD, decoded.protocolBuildFingerprint)
    }

    @Test
    fun emptyPayloadIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionCommandCodec.decode(ByteArray(0))
        }
    }

    @Test
    fun blankSessionIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionCommand("")
        }
    }
}
