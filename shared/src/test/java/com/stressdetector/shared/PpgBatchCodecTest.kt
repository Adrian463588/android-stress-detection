package com.stressdetector.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgBatchCodecTest {
    @Test
    fun roundTripPreservesBatch() {
        val batch = PpgBatch(
            type = PpgType.GREEN,
            timestampsMillis = longArrayOf(1L, 2L),
            rawValues = intArrayOf(10, 20),
            sessionId = "s1",
            elapsedMillis = 300L,
            batchId = "s1|GREEN|1",
            sequenceNumber = 1L,
            elapsedStartMillis = 280L,
            elapsedEndMillis = 300L,
            sessionNonce = "nonce-1",
            peerNodeId = "phone-1",
            generation = 7L
        )

        val decoded = PpgBatchCodec.decode(PpgBatchCodec.encode(batch))

        assertEquals(PpgType.GREEN, decoded.type)
        assertArrayEquals(longArrayOf(1L, 2L), decoded.timestampsMillis)
        assertArrayEquals(intArrayOf(10, 20), decoded.rawValues)
        assertEquals("s1", decoded.sessionId)
        assertEquals(300L, decoded.elapsedMillis)
        assertEquals(PpgBatch.CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals("s1|GREEN|1", decoded.batchId)
        assertEquals(1L, decoded.sequenceNumber)
        assertEquals(PpgType.GREEN.sourceRateHz, decoded.sourceRateHz)
        assertEquals(280L, decoded.elapsedStartMillis)
        assertEquals(300L, decoded.elapsedEndMillis)
        assertEquals("nonce-1", decoded.sessionNonce)
        assertEquals(SessionIdentity.CURRENT_PROTOCOL_VERSION, decoded.protocolVersion)
        assertEquals("phone-1", decoded.peerNodeId)
        assertEquals(7L, decoded.generation)
        assertFalse(PpgBatchCodec.encode(batch).decodeToString().contains("sessionId=s1"))
    }

    @Test
    fun ackAndFlushAckRoundTrip() {
        val ack = PpgBatchAck("s1", "s1|GREEN|1", 1L, sessionNonce = "nonce-1")
        assertEquals(ack, PpgBatchCodec.decodeAck(PpgBatchCodec.encodeAck(ack)))

        val flushAck = PpgFlushAck("s1", flushed = true)
        assertEquals(flushAck, PpgBatchCodec.decodeFlushAck(PpgBatchCodec.encodeFlushAck(flushAck)))
    }

    @Test
    fun binaryTransportPreservesDuplicateAndIrregularRawTimestampDeltas() {
        val batch = PpgBatch(
            type = PpgType.IR,
            timestampsMillis = longArrayOf(1_000L, 1_000L, 1_017L, 1_116L),
            rawValues = intArrayOf(4, -2, 9, 17),
            sessionId = "session",
            sessionNonce = "nonce",
            elapsedMillis = 500L,
            batchId = "batch",
            sequenceNumber = 2L,
            elapsedStartMillis = 400L,
            elapsedEndMillis = 500L
        )

        val decoded = PpgBatchCodec.decode(PpgBatchCodec.encode(batch))

        assertEquals(PpgBatch.CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
        assertArrayEquals(batch.timestampsMillis, decoded.timestampsMillis)
        assertArrayEquals(batch.rawValues, decoded.rawValues)
    }

    @Test
    fun terminalReceiptAndAckRoundTrip() {
        val receipt = PpgTerminalReceipt(
            "s1",
            "nonce",
            4L,
            9L,
            300_000L,
            completed = true,
            expectedRedSequence = 7L
        )
        assertEquals(receipt, PpgBatchCodec.decodeTerminalReceipt(PpgBatchCodec.encodeTerminalReceipt(receipt)))

        val ack = PpgTerminalAck("s1", "nonce", 4L, 9L, complete = true, acceptedRedSequence = 7L)
        assertEquals(ack, PpgBatchCodec.decodeTerminalAck(PpgBatchCodec.encodeTerminalAck(ack)))
    }

    @Test
    fun terminalCodecDefaultsRedSequenceForLegacyGreenIrPayload() {
        val legacy = """
            sessionId=s1
            sessionNonce=nonce
            expectedGreenSequence=4
            expectedIrSequence=9
            elapsedMillis=300000
            completed=true
        """.trimIndent().toByteArray()

        assertEquals(0L, PpgBatchCodec.decodeTerminalReceipt(legacy).expectedRedSequence)
    }

    @Test
    fun sessionReadyRequiresExplicitDualChannelIdentity() {
        val ready = SessionReadyAck(
            sessionId = "s1",
            sessionNonce = "nonce",
            elapsedMillis = 5L,
            greenAvailable = true,
            irAvailable = true,
            peerNodeId = "phone-1",
            generation = 9L
        )

        assertEquals(ready, PpgBatchCodec.decodeSessionReady(PpgBatchCodec.encodeSessionReady(ready)))
        assertThrows(IllegalArgumentException::class.java) {
            SessionReadyAck("s1", "nonce", greenAvailable = true, irAvailable = false)
        }
    }

    @Test
    fun legacyPayloadRemainsReadable() {
        val legacy = """
            sessionId=s1
            elapsedMillis=300
            type=IR
            timestamps=1,2
            values=10,20
        """.trimIndent().toByteArray()

        val decoded = PpgBatchCodec.decode(legacy)

        assertEquals(PpgBatch.LEGACY_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(PpgType.IR, decoded.channel)
        assertEquals(PpgType.IR.samplingRateHz, decoded.samplingRateHz)
        assertEquals(300L, decoded.elapsedStartMillis)
        assertEquals(300L, decoded.elapsedEndMillis)
        assertTrue(decoded.effectiveBatchId.isNotBlank())
    }

    @Test
    fun messagePathsStayStable() {
        assertEquals("/ppg/green", MessagePath.PPG_GREEN)
        assertEquals("/ppg/ir", MessagePath.PPG_IR)
        assertEquals("/ppg/terminal-receipt", MessagePath.PPG_TERMINAL_RECEIPT)
        assertEquals("/session/ppg/terminal-ack", MessagePath.PPG_TERMINAL_ACK)
        assertEquals(setOf(PpgType.GREEN, PpgType.IR), PpgType.productionChannels)
    }

    @Test
    fun rejectsNonMonotonicSensorTimestamps() {
        assertThrows(IllegalArgumentException::class.java) {
            PpgBatch(PpgType.GREEN, longArrayOf(2L, 1L), intArrayOf(10, 20), "s1")
        }
    }

    @Test
    fun rejectsEmptyBatch() {
        assertThrows(IllegalArgumentException::class.java) {
            PpgBatch(PpgType.GREEN, longArrayOf(), intArrayOf(), "s1")
        }
    }

    @Test
    fun rejectsSamplingRateContractMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            PpgBatch(
                type = PpgType.GREEN,
                timestampsMillis = longArrayOf(1L),
                rawValues = intArrayOf(10),
                sessionId = "s1",
                samplingRateHz = 100
            )
        }
    }
}
