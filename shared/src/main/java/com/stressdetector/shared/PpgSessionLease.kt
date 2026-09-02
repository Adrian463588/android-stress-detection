package com.stressdetector.shared

/**
 * Exclusive ownership token for the physical PPG session shared by Home and
 * Verbatim+. A lease is valid only when its complete key matches.
 */
data class PpgSessionLease(val identity: SessionIdentity, val mode: WearSessionMode, val generation: Long) {
    init {
        require(generation >= 0L) { "generation must be non-negative" }
    }

    fun matches(other: PpgSessionLease): Boolean = generation == other.generation &&
        mode == other.mode &&
        identity.matches(other.identity)
}

/** Result of attempting to acquire the exclusive PPG owner slot. */
sealed interface PpgSessionLeaseAcquireResult {
    data class Acquired(
        val lease: PpgSessionLease,
        /** True only when the same owner acquired the already-held lease again. */
        val reused: Boolean
    ) : PpgSessionLeaseAcquireResult

    data class Busy(val activeLease: PpgSessionLease) : PpgSessionLeaseAcquireResult
}

/** Lease-scoped signal snapshot result. */
sealed interface PpgSessionLeaseSnapshotResult<out T> {
    data class Available<T>(val value: T) : PpgSessionLeaseSnapshotResult<T>
    data object NotActive : PpgSessionLeaseSnapshotResult<Nothing>
    data class NotOwner(val activeLease: PpgSessionLease) : PpgSessionLeaseSnapshotResult<Nothing>
}

/**
 * Releasing a lease returns the last actual snapshot for finalization. It does
 * not fabricate or mutate samples; the next successfully acquired lease owns
 * clearing the old in-memory buffers.
 */
sealed interface PpgSessionLeaseReleaseResult<out T> {
    data class Released<T>(val snapshot: T) : PpgSessionLeaseReleaseResult<T>
    data object NotActive : PpgSessionLeaseReleaseResult<Nothing>
    data class NotOwner(val activeLease: PpgSessionLease) : PpgSessionLeaseReleaseResult<Nothing>
}
