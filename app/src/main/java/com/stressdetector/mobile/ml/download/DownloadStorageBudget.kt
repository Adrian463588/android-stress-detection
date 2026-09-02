package com.stressdetector.mobile.ml.download

import android.annotation.SuppressLint
import android.content.Context

private const val DEFAULT_VOLUME_ID = "default"

/** Logical download lanes whose temporary files share the app-private storage volume. */
enum class DownloadLane {
    GEMMA,
    SHARED_SPEECH
}

sealed interface StorageReservationResult {
    data class Reserved(
        val lane: DownloadLane,
        val requiredBytes: Long,
        val availableBytes: Long,
        val competingBytes: Long,
        val volumeId: String,
        val workId: String
    ) : StorageReservationResult

    data class Insufficient(
        val lane: DownloadLane,
        val requiredBytes: Long,
        val availableBytes: Long,
        val competingBytes: Long,
        val volumeId: String,
        val workId: String
    ) : StorageReservationResult
}

internal data class StorageReservationEntry(val lane: DownloadLane, val volumeId: String, val reservedBytes: Long)

internal fun competingReservationBytes(lane: DownloadLane, volumeId: String, reservations: Iterable<StorageReservationEntry>): Long =
    reservations
        .filter { it.lane != lane && it.volumeId == volumeId }
        .sumOf { it.reservedBytes.coerceAtLeast(0L) }

internal fun evaluateStorageReservation(
    lane: DownloadLane,
    requiredBytes: Long,
    availableBytes: Long,
    competingBytes: Long,
    existingReservationBytes: Long
): StorageReservationResult {
    val requested = requiredBytes.coerceAtLeast(0L)
    val competing = competingBytes.coerceAtLeast(0L)
    val alreadyReservedForLane = existingReservationBytes >= requested
    val canReserve = alreadyReservedForLane ||
        availableBytes >= requested + competing + DownloadStorageBudget.SAFETY_MARGIN_BYTES
    return if (canReserve) {
        StorageReservationResult.Reserved(
            lane = lane,
            requiredBytes = requested,
            availableBytes = availableBytes,
            competingBytes = competing,
            volumeId = DEFAULT_VOLUME_ID,
            workId = lane.name
        )
    } else {
        StorageReservationResult.Insufficient(
            lane = lane,
            requiredBytes = requested,
            availableBytes = availableBytes,
            competingBytes = competing,
            volumeId = DEFAULT_VOLUME_ID,
            workId = lane.name
        )
    }
}

/**
 * Reserves temporary space before a lane starts. The reservation is persisted so
 * two independent managers cannot silently overcommit the same app-private volume.
 */
// Reservation ownership must be durable before another download lane observes it.
@SuppressLint("ApplySharedPref")
class DownloadStorageBudget(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun tryReserve(lane: DownloadLane, requiredBytes: Long, availableBytes: Long): StorageReservationResult = tryReserve(
        lane = lane,
        requiredBytes = requiredBytes,
        availableBytes = availableBytes,
        volumeId = DEFAULT_VOLUME_ID,
        workId = lane.name
    )

    fun tryReserve(
        lane: DownloadLane,
        requiredBytes: Long,
        availableBytes: Long,
        volumeId: String,
        workId: String
    ): StorageReservationResult = synchronized(reservationLock) {
        val competingBytes = competingReservationBytes(
            lane = lane,
            volumeId = volumeId,
            reservations = DownloadLane.entries
                .mapNotNull { readRecord(it) }
                .map { StorageReservationEntry(it.lane, it.volumeId, it.reservedBytes) }
        )
        val existing = readRecord(lane)
            ?.takeIf { it.volumeId == volumeId && it.workId == workId }
            ?.reservedBytes
            ?: 0L
        val result = evaluateStorageReservation(
            lane = lane,
            requiredBytes = requiredBytes,
            availableBytes = availableBytes,
            competingBytes = competingBytes,
            existingReservationBytes = existing
        ).withOwnership(volumeId = volumeId, workId = workId)
        if (result is StorageReservationResult.Reserved) {
            writeRecord(
                ReservationRecord(
                    lane = lane,
                    volumeId = volumeId,
                    workId = workId,
                    reservedBytes = result.requiredBytes
                )
            )
        }
        result
    }

    /** Re-associates a reservation with WorkManager/DownloadManager after restart. */
    fun adopt(lane: DownloadLane, volumeId: String, workId: String, reservedBytes: Long) = synchronized(reservationLock) {
        writeRecord(
            ReservationRecord(
                lane = lane,
                volumeId = volumeId,
                workId = workId,
                reservedBytes = reservedBytes.coerceAtLeast(0L)
            )
        )
    }

    fun release(lane: DownloadLane, workId: String? = null) = synchronized(reservationLock) {
        val current = readRecord(lane)
        if (workId == null || current == null || current.workId == workId) {
            preferences.edit()
                .remove(key(lane, KEY_BYTES))
                .remove(key(lane, KEY_VOLUME))
                .remove(key(lane, KEY_WORK_ID))
                // Remove the pre-volume schema as part of the one-time migration.
                .remove(legacyKey(lane))
                .commit()
        }
    }

    private fun readRecord(lane: DownloadLane): ReservationRecord? {
        val bytes = preferences.getLong(key(lane, KEY_BYTES), 0L).coerceAtLeast(0L)
        if (bytes > 0L) {
            return ReservationRecord(
                lane = lane,
                volumeId = preferences.getString(key(lane, KEY_VOLUME), DEFAULT_VOLUME_ID)
                    ?: DEFAULT_VOLUME_ID,
                workId = preferences.getString(key(lane, KEY_WORK_ID), lane.name) ?: lane.name,
                reservedBytes = bytes
            )
        }

        // Older builds stored only one byte count per lane. Keep it readable until
        // the owning manager reconciles the active work and writes the new record.
        val legacyBytes = preferences.getLong(legacyKey(lane), 0L).coerceAtLeast(0L)
        return legacyBytes.takeIf { it > 0L }?.let {
            ReservationRecord(
                lane = lane,
                volumeId = LEGACY_VOLUME_ID,
                workId = LEGACY_WORK_ID,
                reservedBytes = it
            )
        }
    }

    private fun writeRecord(record: ReservationRecord) {
        preferences.edit()
            .putLong(key(record.lane, KEY_BYTES), record.reservedBytes)
            .putString(key(record.lane, KEY_VOLUME), record.volumeId)
            .putString(key(record.lane, KEY_WORK_ID), record.workId)
            .remove(legacyKey(record.lane))
            .commit()
    }

    private fun key(lane: DownloadLane, suffix: String): String = "reservation_${lane.name.lowercase()}_$suffix"

    private fun legacyKey(lane: DownloadLane): String = "reservation_${lane.name.lowercase()}"

    private data class ReservationRecord(val lane: DownloadLane, val volumeId: String, val workId: String, val reservedBytes: Long)

    private fun StorageReservationResult.withOwnership(volumeId: String, workId: String): StorageReservationResult = when (this) {
        is StorageReservationResult.Reserved -> copy(volumeId = volumeId, workId = workId)
        is StorageReservationResult.Insufficient -> copy(volumeId = volumeId, workId = workId)
    }

    internal companion object {
        const val PREFERENCES = "download_storage_budget"
        const val SAFETY_MARGIN_BYTES = 64L * 1024L * 1024L
        private const val LEGACY_VOLUME_ID = "legacy"
        private const val LEGACY_WORK_ID = "legacy"
        private const val KEY_BYTES = "bytes"
        private const val KEY_VOLUME = "volume"
        private const val KEY_WORK_ID = "work_id"
        private val reservationLock = Any()
    }
}
