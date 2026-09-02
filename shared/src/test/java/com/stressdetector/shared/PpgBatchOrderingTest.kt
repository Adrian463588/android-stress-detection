package com.stressdetector.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgBatchOrderingTest {
    @Test
    fun outOfOrderBatchIsNotAcknowledgedUntilMissingSequenceArrives() {
        val audit = PpgBatchSequenceAudit()
        val first = batch(1L, 0L)
        val third = batch(3L, 20L)
        val second = batch(2L, 10L)

        assertEquals(PpgBatchDisposition.APPLIED, audit.accept(first).disposition)

        val buffered = audit.accept(third)
        assertEquals(PpgBatchDisposition.BUFFERED_OUT_OF_ORDER, buffered.disposition)
        assertTrue(buffered.acknowledgedBatches.isEmpty())
        assertEquals(1L, buffered.missingSequenceCount)

        val delivered = audit.accept(second)
        assertEquals(PpgBatchDisposition.APPLIED, delivered.disposition)
        assertEquals(listOf(second, third), delivered.acknowledgedBatches)
    }

    @Test
    fun firstLostV2BatchNeverLetsSecondBatchAdvanceTerminalSequence() {
        val audit = PpgBatchSequenceAudit()

        val second = audit.accept(batch(2L, 10L))

        assertEquals(PpgBatchDisposition.BUFFERED_OUT_OF_ORDER, second.disposition)
        assertEquals(1L, second.missingSequenceCount)
        assertTrue(second.acknowledgedBatches.isEmpty())

        val recovered = audit.accept(batch(1L, 0L))
        assertEquals(PpgBatchDisposition.APPLIED, recovered.disposition)
        assertEquals(listOf(batch(1L, 0L), batch(2L, 10L)), recovered.acknowledgedBatches)
    }

    @Test
    fun appliedDuplicateCanBeAcknowledgedWithoutReapplying() {
        val audit = PpgBatchSequenceAudit()
        val first = batch(1L, 0L)
        audit.accept(first)

        val duplicate = audit.accept(first)

        assertEquals(PpgBatchDisposition.DUPLICATE, duplicate.disposition)
        assertEquals(listOf(first), duplicate.acknowledgedBatches)
    }

    @Test
    fun differentSessionNonceStartsIndependentSequenceAudit() {
        val audit = PpgBatchSequenceAudit()
        val first = batch(1L, 0L).copy(sessionNonce = "first")
        val restarted = batch(1L, 0L).copy(sessionNonce = "second")

        assertEquals(PpgBatchDisposition.APPLIED, audit.accept(first).disposition)
        assertEquals(PpgBatchDisposition.APPLIED, audit.accept(restarted).disposition)
    }

    @Test
    fun directTransportReportsGapAndRejectsLateSequenceWithoutQueue() {
        val audit = PpgBatchSequenceAudit()
        val first = batch(1L, 0L)
        val third = batch(3L, 20L)
        val second = batch(2L, 10L)

        assertEquals(PpgBatchDisposition.APPLIED, audit.acceptDirect(first).disposition)
        val gap = audit.acceptDirect(third)
        assertEquals(PpgBatchDisposition.APPLIED, gap.disposition)
        assertEquals(1L, gap.missingSequenceCount)

        val late = audit.acceptDirect(second)
        assertEquals(PpgBatchDisposition.REJECTED, late.disposition)
        assertTrue(late.acknowledgedBatches.isEmpty())
    }

    private fun batch(sequence: Long, elapsed: Long): PpgBatch = PpgBatch(
        type = PpgType.GREEN,
        timestampsMillis = longArrayOf(elapsed + 1L, elapsed + 2L),
        rawValues = intArrayOf(sequence.toInt(), sequence.toInt()),
        sessionId = "session",
        elapsedMillis = elapsed + 1L,
        batchId = PpgBatch.batchIdFor("session", PpgType.GREEN, sequence),
        sequenceNumber = sequence,
        elapsedStartMillis = elapsed,
        elapsedEndMillis = elapsed + 1L
    )
}
