package com.stressdetector.mobile.data

import com.stressdetector.shared.AccelerometerSample
import com.stressdetector.shared.PpgBatch
import com.stressdetector.shared.PpgBatchDisposition
import com.stressdetector.shared.PpgTerminalReceipt
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SessionProtocolRejection
import com.stressdetector.shared.SessionReadyAck
import com.stressdetector.shared.WearSessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgRepositoryTest {
    @Test
    fun homeSnapshotContainsMotionOnlyFromAcceptedBatchAndDeduplicatesAcrossChannels() {
        val repository = PpgRepositoryImpl()
        repository.startSession("session-a")
        val sample = AccelerometerSample(1_000_000_000L, 0L, 1f, 2f, 3f)
        val green = batch(1L, 0L).copy(accelerometerSamples = listOf(sample))
        val ir = batch(1L, 0L, PpgType.IR).copy(accelerometerSamples = listOf(sample))
        assertEquals(PpgBatchDisposition.APPLIED, repository.append(green).disposition)
        assertEquals(PpgBatchDisposition.APPLIED, repository.append(ir).disposition)
        val staleMotion = AccelerometerSample(1_001_000_000L, 1L, 9f, 9f, 9f)
        assertEquals(
            PpgBatchDisposition.DUPLICATE,
            repository.append(green.copy(accelerometerSamples = listOf(staleMotion))).disposition
        )
        repository.append(green.copy(sequenceNumber = 0L, sessionId = "wrong"))

        assertEquals(listOf(sample), repository.fullGreenWindow().accelerometerSamples)
        assertEquals(listOf(sample), repository.fullIrWindow().accelerometerSamples)
    }

    @Test
    fun unsolicitedBatchIsRejectedUntilSessionOwnershipIsEstablished() {
        val repository = PpgRepositoryImpl()

        val result = repository.append(batch(1L, 0L))

        assertEquals(PpgBatchDisposition.REJECTED, result.disposition)
        assertEquals("no_active_session", result.reason)
        assertEquals(0, repository.greenWindow().size)
    }

    @Test
    fun staleIncomingSessionDoesNotResetActiveBuffers() {
        val repository = PpgRepositoryImpl()
        repository.startSession("session-a")
        repository.append(batch(1L, 0L))

        repository.append(batch(1L, 10L).copy(sessionId = "session-b"))

        val state = repository.state.value
        assertEquals("session-a", state.sessionId)
        assertEquals(2, state.greenSamples)
        assertEquals(2, state.totalGreenSamples)
    }

    @Test
    fun directTransportAppliesLaterBatchAndReportsMissingSequence() {
        val repository = PpgRepositoryImpl()
        repository.startSession("session-a")
        repository.append(batch(1L, 0L))

        val outOfOrder = repository.append(batch(3L, 250_000L))
        assertEquals(PpgBatchDisposition.APPLIED, outOfOrder.disposition)
        assertEquals(2, repository.greenWindow().size)
        assertEquals(1L, outOfOrder.missingSequenceCount)

        val delivered = repository.append(batch(2L, 10L))
        assertEquals(PpgBatchDisposition.REJECTED, delivered.disposition)
        assertEquals(0, delivered.acknowledgedBatches.size)
        assertEquals(4, repository.fullGreenWindow().size)
        assertEquals(0, repository.state.value.outOfOrderBatchCount)
        assertEquals(1L, repository.state.value.missingSequenceCount)
        assertEquals(1L, repository.state.value.missingGreenSequenceCount)
        assertEquals(0L, repository.state.value.missingIrSequenceCount)
    }

    @Test
    fun sessionClockCompletionAndObservedDurationRemainSeparate() {
        val repository = PpgRepositoryImpl()
        repository.startSession("session-a")
        repository.append(batch(1L, 0L))
        repository.markSessionElapsed("session-a", 300_000L, "auto_session_deadline")

        val state = repository.state.value
        assertEquals(300_000L, state.sessionElapsedMillis)
        assertEquals(1L, state.observedDataElapsedMillis)
        assertEquals(299_999L, state.missingTailMillis)
        assertTrue(state.isComplete)
        assertTrue(!state.transportComplete)
    }

    @Test
    fun terminalReceiptRequiresBothProductionSequenceStreamsBeforeCompletion() {
        val repository = PpgRepositoryImpl()
        repository.startSession("session-a", "nonce-a")
        repository.append(batch(1L, 0L).copy(sessionNonce = "nonce-a"))

        val incomplete = repository.acceptTerminalReceipt(
            PpgTerminalReceipt("session-a", "nonce-a", 1L, 1L, 300_000L, completed = true)
        )

        assertTrue(!incomplete.complete)
        assertEquals("transport_incomplete", repository.state.value.completionReason)
        assertTrue(repository.state.value.transportIncomplete)
        assertTrue(repository.state.value.terminalReceiptReceived)
        assertEquals("session-a", repository.state.value.flushSessionId)
    }

    @Test
    fun terminalReceiptCannotCompleteWhenFirstV2SequenceWasLost() {
        val repository = PpgRepositoryImpl()
        repository.startSession("session-a", "nonce-a")
        val delayed = repository.append(batch(2L, 10L).copy(sessionNonce = "nonce-a"))

        val receipt = repository.acceptTerminalReceipt(
            PpgTerminalReceipt("session-a", "nonce-a", 2L, 0L, 300_000L, completed = true)
        )

        assertEquals(PpgBatchDisposition.APPLIED, delayed.disposition)
        assertTrue(!receipt.complete)
        assertEquals(2L, receipt.acceptedGreenSequence)
        assertTrue(repository.state.value.transportIncomplete)
    }

    @Test
    fun sessionReadyRequiresTheActiveSessionAndNonce() {
        val repository = PpgRepositoryImpl(monotonicNowMillis = { 4_567L })
        repository.startSession("session-a", "nonce-a")

        assertTrue(!repository.acceptSessionReady(ready("session-b", "nonce-a")))
        assertTrue(!repository.acceptSessionReady(ready("session-a", "nonce-b")))
        assertTrue(!repository.state.value.sessionReady)

        assertTrue(repository.acceptSessionReady(ready("session-a", "nonce-a", 123L)))
        assertTrue(repository.state.value.sessionReady)
        assertEquals(123L, repository.state.value.sessionReadyElapsedMillis)
        assertEquals(4_567L, repository.state.value.sessionReadyReceivedAtMonotonicMillis)
    }

    @Test
    fun sourceWatchAndPhonePeerIdentitiesRemainSeparate() {
        val repository = PpgRepositoryImpl(monotonicNowMillis = { 4_567L })
        repository.startSession("session-a", "nonce-a", "watch-node", "phone-node", 3L)
        val identified = batch(1L, 0L).copy(
            sessionNonce = "nonce-a",
            peerNodeId = "phone-node",
            generation = 3L
        )

        assertEquals(
            "stale_source_node",
            repository.append(identified, "other-watch").reason
        )
        assertEquals(
            "stale_peer_node",
            repository.append(identified.copy(peerNodeId = "watch-node"), "watch-node").reason
        )
        assertEquals(PpgBatchDisposition.APPLIED, repository.append(identified, "watch-node").disposition)
        assertTrue(
            repository.acceptSessionReady(
                ready(
                    sessionId = "session-a",
                    nonce = "nonce-a",
                    peerNodeId = "phone-node",
                    generation = 3L
                ),
                "watch-node"
            )
        )
    }

    @Test
    fun sessionModeIsOwnedByTheStartedSession() {
        val repository = PpgRepositoryImpl()

        repository.startSession("session-a", mode = WearSessionMode.VERBATIM_AROUSAL)

        assertEquals(WearSessionMode.VERBATIM_AROUSAL, repository.state.value.sessionMode)
    }

    @Test
    fun verbatimElapsedPassesFiveMinutesWithoutAutoCompletion() {
        val repository = PpgRepositoryImpl()
        repository.startSession("session-a", "nonce-a", mode = WearSessionMode.VERBATIM_AROUSAL)

        repository.markSessionElapsed("session-a", 301_000L, "watch_still_recording")

        assertEquals(301_000L, repository.state.value.sessionElapsedMillis)
        assertTrue(!repository.state.value.isComplete)
    }

    @Test
    fun verbatimTerminalReceiptPreservesObservedDurationAfterFiveMinutes() {
        val repository = PpgRepositoryImpl()
        repository.startSession("session-a", "nonce-a", mode = WearSessionMode.VERBATIM_AROUSAL)
        repository.append(batch(1L, 600_000L).copy(sessionNonce = "nonce-a"))
        repository.append(batch(1L, 600_000L, PpgType.IR).copy(sessionNonce = "nonce-a"))

        val result = repository.acceptTerminalReceipt(
            PpgTerminalReceipt("session-a", "nonce-a", 1L, 1L, 600_001L, completed = true)
        )

        assertTrue(result.complete)
        assertEquals(600_001L, repository.state.value.sessionElapsedMillis)
        assertEquals(2, repository.fullGreenWindow().size)
    }

    @Test
    fun pendingTerminalReceiptCompletesWhenLateExpectedBatchArrives() {
        val repository = PpgRepositoryImpl()
        repository.startSession("session-a", "nonce-a")
        repository.append(batch(1L, 0L).copy(sessionNonce = "nonce-a"))
        repository.append(batch(1L, 0L, PpgType.IR).copy(sessionNonce = "nonce-a"))

        val beforeLateBatch = repository.acceptTerminalReceipt(
            PpgTerminalReceipt("session-a", "nonce-a", 2L, 1L, 300_000L, completed = true)
        )
        assertTrue(!beforeLateBatch.complete)

        repository.append(batch(2L, 10L).copy(sessionNonce = "nonce-a"))

        assertTrue(repository.state.value.terminalReceiptReceived)
        assertTrue(repository.state.value.transportComplete)
        assertTrue(repository.state.value.isComplete)
    }

    @Test
    fun redIsStoredForDiagnosticsButDoesNotGateGreenIrCompletion() {
        val repository = PpgRepositoryImpl()
        repository.startSession("session-a", "nonce-a")
        repository.append(batch(1L, 0L).copy(sessionNonce = "nonce-a"))
        repository.append(batch(1L, 0L, PpgType.IR).copy(sessionNonce = "nonce-a"))
        repository.append(batch(1L, 0L, PpgType.RED).copy(sessionNonce = "nonce-a"))

        val result = repository.acceptTerminalReceipt(
            PpgTerminalReceipt(
                "session-a",
                "nonce-a",
                1L,
                1L,
                300_000L,
                completed = true,
                expectedRedSequence = 2L
            )
        )

        assertTrue(result.complete)
        assertEquals(1L, result.acceptedRedSequence)
        assertEquals(2, repository.redWindow().size)
        assertEquals(2, repository.state.value.totalRedSamples)
    }

    @Test
    fun matchingProtocolRejectionClearsChartEvidenceAndBlocksFinalizationInputs() {
        val repository = PpgRepositoryImpl()
        repository.startSession("session-a", "nonce-a")
        repository.append(batch(1L, 0L).copy(sessionNonce = "nonce-a"))

        val accepted = repository.rejectProtocol(
            SessionProtocolRejection(
                sessionId = "session-a",
                sessionNonce = "nonce-a",
                expectedProtocolVersion = 1,
                receivedProtocolVersion = 0,
                expectedBuildFingerprint = "phone",
                receivedBuildFingerprint = "watch"
            )
        )

        assertTrue(accepted)
        assertTrue(repository.state.value.protocolRejected)
        assertEquals("protocol_mismatch", repository.state.value.transportFailureReason)
        assertEquals(0, repository.greenWindow().size)
        assertTrue(repository.chartFrames.value.greenSnapshot.size == 0)
        assertEquals(
            "protocol_mismatch",
            repository.append(batch(2L, 10L).copy(sessionNonce = "nonce-a")).reason
        )
    }

    @Test
    fun staleProtocolRejectionCannotClearTheActiveSession() {
        val repository = PpgRepositoryImpl()
        repository.startSession("session-a", "nonce-a")
        repository.append(batch(1L, 0L).copy(sessionNonce = "nonce-a"))

        val accepted = repository.rejectProtocol(
            SessionProtocolRejection(
                sessionId = "session-a",
                sessionNonce = "other-nonce",
                expectedProtocolVersion = 1,
                receivedProtocolVersion = 0,
                expectedBuildFingerprint = "phone",
                receivedBuildFingerprint = "watch"
            )
        )

        assertTrue(!accepted)
        assertTrue(!repository.state.value.protocolRejected)
        assertEquals(2, repository.greenWindow().size)
    }

    private fun batch(sequence: Long, elapsed: Long, type: PpgType = PpgType.GREEN): PpgBatch = PpgBatch(
        type = type,
        timestampsMillis = longArrayOf(elapsed + 1L, elapsed + 2L),
        rawValues = intArrayOf(1, 2),
        sessionId = "session-a",
        elapsedMillis = elapsed + 1L,
        batchId = PpgBatch.batchIdFor("session-a", type, sequence),
        sequenceNumber = sequence,
        elapsedStartMillis = elapsed,
        elapsedEndMillis = elapsed + 1L
    )

    private fun ready(sessionId: String, nonce: String, elapsedMillis: Long = 0L, peerNodeId: String = "", generation: Long = 0L) =
        SessionReadyAck(
            sessionId = sessionId,
            sessionNonce = nonce,
            elapsedMillis = elapsedMillis,
            greenAvailable = true,
            irAvailable = true,
            peerNodeId = peerNodeId,
            generation = generation
        )
}
