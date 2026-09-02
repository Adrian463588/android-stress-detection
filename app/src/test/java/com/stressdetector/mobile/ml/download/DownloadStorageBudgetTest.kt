package com.stressdetector.mobile.ml.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStorageBudgetTest {

    @Test
    fun competingLaneReservationIsRejectedWhenCombinedSpaceIsInsufficient() {
        val result = evaluateStorageReservation(
            lane = DownloadLane.SHARED_SPEECH,
            requiredBytes = 400L,
            availableBytes = 500L,
            competingBytes = 100L,
            existingReservationBytes = 0L
        )

        assertTrue(result is StorageReservationResult.Insufficient)
        assertEquals(100L, (result as StorageReservationResult.Insufficient).competingBytes)
    }

    @Test
    fun existingReservationIsIdempotentForRepeatedClicks() {
        val result = evaluateStorageReservation(
            lane = DownloadLane.GEMMA,
            requiredBytes = 400L,
            availableBytes = 0L,
            competingBytes = 10_000L,
            existingReservationBytes = 500L
        )

        assertTrue(result is StorageReservationResult.Reserved)
    }

    @Test
    fun competingReservationIsCountedOnlyOnTheSameVolume() {
        val reservations = listOf(
            StorageReservationEntry(DownloadLane.GEMMA, "volume-a", 100L),
            StorageReservationEntry(DownloadLane.GEMMA, "volume-b", 900L)
        )

        assertEquals(
            100L,
            competingReservationBytes(
                lane = DownloadLane.SHARED_SPEECH,
                volumeId = "volume-a",
                reservations = reservations
            )
        )
        assertEquals(
            900L,
            competingReservationBytes(
                lane = DownloadLane.SHARED_SPEECH,
                volumeId = "volume-b",
                reservations = reservations
            )
        )
        assertEquals(
            0L,
            competingReservationBytes(
                lane = DownloadLane.SHARED_SPEECH,
                volumeId = "volume-c",
                reservations = reservations
            )
        )
    }
}
