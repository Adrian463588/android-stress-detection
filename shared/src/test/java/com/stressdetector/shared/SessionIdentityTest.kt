package com.stressdetector.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionIdentityTest {
    @Test
    fun protocolMismatchIsRejectedBeforeSensorLifecycleCanStart() {
        val rejection = SessionProtocolHandshake.rejectionFor(
            SessionIdentity("session", "nonce", SessionIdentity.LEGACY_PROTOCOL_VERSION),
            "legacy-build"
        )

        assertTrue(rejection?.reason == "PROTOCOL_MISMATCH")
    }

    @Test
    fun canonicalIdentityAcceptsCurrentProtocolBuild() {
        val rejection = SessionProtocolHandshake.rejectionFor(
            SessionIdentity("session", "nonce", SessionIdentity.CURRENT_PROTOCOL_VERSION),
            SessionIdentity.CURRENT_PROTOCOL_BUILD
        )

        assertNull(rejection)
    }

    @Test
    fun staleOutboxBatchDoesNotBelongToNewNonceEvenWhenGenerationMatches() {
        val oldBatch = PpgBatch(
            type = PpgType.GREEN,
            timestampsMillis = longArrayOf(1L),
            rawValues = intArrayOf(7),
            sessionId = "session",
            sessionNonce = "old-nonce",
            protocolVersion = SessionIdentity.CURRENT_PROTOCOL_VERSION,
            generation = 9L
        )
        val current = SessionIdentity("session", "new-nonce", SessionIdentity.CURRENT_PROTOCOL_VERSION)

        assertFalse(PpgBatchPolicy.belongsToSession(oldBatch, current))
    }

    @Test
    fun generationIsNotPartOfCanonicalBatchIdentity() {
        val batch = PpgBatch(
            type = PpgType.GREEN,
            timestampsMillis = longArrayOf(1L),
            rawValues = intArrayOf(7),
            sessionId = "session",
            sessionNonce = "nonce",
            protocolVersion = SessionIdentity.CURRENT_PROTOCOL_VERSION,
            generation = 1L
        )
        val current = SessionIdentity("session", "nonce", SessionIdentity.CURRENT_PROTOCOL_VERSION)

        assertTrue(PpgBatchPolicy.belongsToSession(batch, current))
    }

    @Test
    fun terminalConfirmationIsIdempotentAndPerChannel() {
        val receipt = PpgTerminalReceipt(
            sessionId = "session",
            sessionNonce = "nonce",
            expectedGreenSequence = 4L,
            expectedIrSequence = 5L,
            expectedRedSequence = 8L,
            elapsedMillis = 300_000L,
            completed = true
        )
        val ack = PpgTerminalAck(
            sessionId = "session",
            sessionNonce = "nonce",
            acceptedGreenSequence = 4L,
            acceptedIrSequence = 5L,
            acceptedRedSequence = 8L,
            complete = true,
            generation = 99L
        )

        assertTrue(PpgBatchPolicy.confirmsTerminalReceipt(receipt, ack))
        assertTrue(PpgBatchPolicy.confirmsTerminalReceipt(receipt, ack))
    }
}
