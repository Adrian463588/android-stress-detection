package com.stressdetector.mobile.data

import com.stressdetector.shared.PpgBatch
import com.stressdetector.shared.PpgSessionLease
import com.stressdetector.shared.PpgSessionLeaseAcquireResult
import com.stressdetector.shared.PpgSessionLeaseReleaseResult
import com.stressdetector.shared.PpgSessionLeaseSnapshotResult
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SessionIdentity
import com.stressdetector.shared.WearSessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgSessionLeaseTest {
    @Test
    fun secondModeReturnsBusyWithoutClearingTheCurrentBuffers() {
        val repository = PpgRepositoryImpl(monotonicNowMillis = { 0L })
        val home = lease("home", "nonce-home", WearSessionMode.HOME_STRESS, generation = 1L)
        val verbatim = lease("verbatim", "nonce-verbatim", WearSessionMode.VERBATIM_AROUSAL, generation = 2L)

        assertTrue(repository.acquire(home) is PpgSessionLeaseAcquireResult.Acquired)
        repository.append(batch(home))

        val result = repository.acquire(verbatim)

        assertTrue(result is PpgSessionLeaseAcquireResult.Busy)
        assertEquals(home, (result as PpgSessionLeaseAcquireResult.Busy).activeLease)
        assertEquals(2, repository.fullGreenWindow().size)
        val snapshot = repository.snapshot(home)
        assertTrue(snapshot is PpgSessionLeaseSnapshotResult.Available<*>)
        assertEquals(
            "home",
            (snapshot as PpgSessionLeaseSnapshotResult.Available<*>).value
                .let { it as PpgSessionLeaseSnapshot }
                .streamState.sessionId
        )
    }

    @Test
    fun generationIsPartOfTheExclusiveLeaseKey() {
        val repository = PpgRepositoryImpl(monotonicNowMillis = { 0L })
        val original = lease("home", "nonce-home", WearSessionMode.HOME_STRESS, generation = 1L)
        val nextGeneration = original.copy(generation = 2L)

        assertTrue(repository.acquire(original) is PpgSessionLeaseAcquireResult.Acquired)

        val result = repository.acquire(nextGeneration)

        assertTrue(result is PpgSessionLeaseAcquireResult.Busy)
        assertEquals(original, (result as PpgSessionLeaseAcquireResult.Busy).activeLease)
    }

    @Test
    fun lateStopCannotReleaseOrClearANewerLease() {
        val repository = PpgRepositoryImpl(monotonicNowMillis = { 0L })
        val home = lease("home", "nonce-home", WearSessionMode.HOME_STRESS, generation = 1L)
        val verbatim = lease("verbatim", "nonce-verbatim", WearSessionMode.VERBATIM_AROUSAL, generation = 2L)
        assertTrue(repository.acquire(home) is PpgSessionLeaseAcquireResult.Acquired)
        repository.append(batch(home))
        assertTrue(repository.stopAndRelease(home) is PpgSessionLeaseReleaseResult.Released<*>)
        assertTrue(repository.acquire(verbatim) is PpgSessionLeaseAcquireResult.Acquired)
        repository.append(batch(verbatim))

        val lateStop = repository.stopAndRelease(home)

        assertTrue(lateStop is PpgSessionLeaseReleaseResult.NotOwner)
        assertEquals(verbatim, (lateStop as PpgSessionLeaseReleaseResult.NotOwner).activeLease)
        val current = repository.snapshot(verbatim)
        assertTrue(current is PpgSessionLeaseSnapshotResult.Available<*>)
        assertEquals(
            2,
            (current as PpgSessionLeaseSnapshotResult.Available<*>).value
                .let { it as PpgSessionLeaseSnapshot }
                .fullGreenWindow.size
        )
    }

    @Test
    fun matchingReleaseReturnsActualSnapshotAndClosesTheLease() {
        val repository = PpgRepositoryImpl(monotonicNowMillis = { 0L })
        val home = lease("home", "nonce-home", WearSessionMode.HOME_STRESS, generation = 1L)
        assertTrue(repository.acquire(home) is PpgSessionLeaseAcquireResult.Acquired)
        repository.append(batch(home))

        val release = repository.stopAndRelease(home)

        assertTrue(release is PpgSessionLeaseReleaseResult.Released<*>)
        assertEquals(
            2,
            (release as PpgSessionLeaseReleaseResult.Released<*>).snapshot
                .let { it as PpgSessionLeaseSnapshot }
                .fullGreenWindow.size
        )
        assertTrue(repository.snapshot(home) is PpgSessionLeaseSnapshotResult.NotActive)
    }

    private fun lease(sessionId: String, nonce: String, mode: WearSessionMode, generation: Long): PpgSessionLease = PpgSessionLease(
        identity = SessionIdentity(sessionId, nonce, SessionIdentity.CURRENT_PROTOCOL_VERSION),
        mode = mode,
        generation = generation
    )

    private fun batch(lease: PpgSessionLease): PpgBatch = PpgBatch(
        type = PpgType.GREEN,
        timestampsMillis = longArrayOf(1L, 2L),
        rawValues = intArrayOf(101, 102),
        sessionId = lease.identity.sessionId,
        elapsedMillis = 1L,
        batchId = PpgBatch.batchIdFor(lease.identity, PpgType.GREEN, 1L),
        sequenceNumber = 1L,
        elapsedStartMillis = 0L,
        elapsedEndMillis = 1L,
        sessionNonce = lease.identity.nonce,
        protocolVersion = lease.identity.protocolVersion,
        generation = lease.generation
    )
}
