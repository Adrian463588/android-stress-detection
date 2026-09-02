package com.stressdetector.mobile.ml.download

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

/** Snapshot of the storage volume used by a download candidate. */
data class StorageSpaceSnapshot(val availableBytes: Long, val requiredBytes: Long, val volumeId: String, val anchorPath: String)

sealed interface StorageSpaceResult {
    data class Available(val snapshot: StorageSpaceSnapshot) : StorageSpaceResult
    data class Unavailable(val reason: String) : StorageSpaceResult
}

/** Platform boundary for measuring free space without relying on a missing target directory. */
fun interface StorageSpaceProvider {
    fun measure(anchor: File, requiredBytes: Long): StorageSpaceResult
}

/** Android implementation backed by StatFs and the owning StorageVolume. */
class AndroidStorageSpaceProvider(context: Context) : StorageSpaceProvider {
    private val applicationContext = context.applicationContext

    override fun measure(anchor: File, requiredBytes: Long): StorageSpaceResult {
        val existingAnchor = resolveExistingStorageAnchor(anchor, applicationContext.filesDir)
            ?: return StorageSpaceResult.Unavailable(
                "Tidak ada direktori storage yang dapat diakses untuk mengukur ruang kosong."
            )

        return runCatching {
            val stat = StatFs(existingAnchor.absolutePath)
            StorageSpaceResult.Available(
                StorageSpaceSnapshot(
                    availableBytes = stat.availableBytes.coerceAtLeast(0L),
                    requiredBytes = requiredBytes.coerceAtLeast(0L),
                    volumeId = resolveVolumeId(existingAnchor),
                    anchorPath = existingAnchor.absolutePath
                )
            )
        }.getOrElse { error ->
            StorageSpaceResult.Unavailable(
                "Storage volume tidak dapat diperiksa: ${error.message ?: "error tidak diketahui"}"
            )
        }
    }

    private fun resolveVolumeId(anchor: File): String {
        val storageManager = applicationContext.getSystemService(StorageManager::class.java)
        val volume = runCatching { storageManager?.getStorageVolume(anchor) }.getOrNull()
        return when {
            !volume?.uuid.isNullOrBlank() -> "uuid:${volume?.uuid}"
            volume?.isPrimary == true -> PRIMARY_VOLUME_ID
            else -> "path:${anchor.absolutePath}"
        }
    }

    private companion object {
        const val PRIMARY_VOLUME_ID = "primary"
    }
}

/**
 * Walks up to an existing directory so File.freeSpace/StatFs never receives a
 * not-yet-created runtime directory. Kept pure for JVM regression tests.
 */
internal fun resolveExistingStorageAnchor(requested: File, fallback: File): File? {
    fun existingAncestor(start: File): File? {
        var candidate: File? = start
        while (candidate != null && !candidate.exists()) {
            candidate = candidate.parentFile
        }
        return candidate?.takeIf { it.isDirectory }
    }

    return existingAncestor(requested) ?: existingAncestor(fallback)
}
