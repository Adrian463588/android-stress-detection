package com.stressdetector.mobile.lifecycle

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.stressdetector.mobile.security.EncryptedStorageService
import java.io.File
import java.security.MessageDigest

internal const val RESTORE_JOURNAL_PATH = "portable_archive_restore/journal.enc"
internal const val MAX_RESTORE_JOURNAL_BYTES = 2 * 1024 * 1024

internal fun lstat(file: File): StructStat? = try {
    Os.lstat(file.path)
} catch (error: ErrnoException) {
    if (error.errno != OsConstants.ENOENT) throw IllegalStateException("Restore file metadata is unavailable", error)
    null
}

internal fun readOwnership(file: File): FileOwnership? {
    val stat = lstat(file) ?: return null
    check(OsConstants.S_ISREG(stat.st_mode)) { "Restore path is not a regular file" }
    return FileOwnership(stat.st_dev, stat.st_ino)
}

internal fun owns(file: File, expected: FileOwnership): Boolean {
    val actual = readOwnership(file) ?: return false
    check(actual == expected) { "Restore artifact ownership mismatch" }
    return true
}

internal fun matchesChart(file: File, chart: PlannedChart): Boolean {
    if (!owns(file, chart.ownership)) return false
    check(sha256(file) == chart.sha256 && owns(file, chart.ownership)) { "Restore chart ownership mismatch" }
    return true
}

/** Check every existing object before deleting anything; missing moved sources are expected. */
internal fun verifyRecoveryOwnership(plan: RestorePlan, encryptedRoot: File, charts: File, removeTargets: Boolean) {
    if (removeTargets) {
        plan.files.forEach { (target, file) -> owns(scopedFile(encryptedRoot, target), file.ownership) }
        plan.charts.forEach { (id, chart) -> matchesChart(scopedFile(charts.canonicalFile, "$id.json"), chart) }
    }
    plan.charts.forEach { (id, chart) -> matchesChart(chartTemporary(charts, id, plan.stagePrefix), chart) }
    plan.staging.forEach { (path, ownership) -> owns(scopedFile(encryptedRoot, path), ownership) }
}

internal fun checkStagingScope(storage: EncryptedStorageService, userId: String, plan: RestorePlan) {
    val current = storage.listEncryptedFiles(userId).filter { it.relativePath.startsWith("${plan.stagePrefix}/") }
    require(current.all { isStagePath(it.relativePath, plan.stagePrefix) && it.relativePath in plan.staging }) {
        "Unexpected restore staging path"
    }
}

internal fun EncryptedStorageService.hasRestoreJournal(userId: String): Boolean =
    listEncryptedFiles(userId).any { it.relativePath == RESTORE_JOURNAL_PATH }

internal fun chartTemporary(directory: File, id: String, stagePrefix: String): File =
    scopedFile(directory.canonicalFile, ".portable-restore/${stagePrefix.substringAfterLast('/')}/$id.json")

internal fun scopedFile(root: File, relativePath: String): File {
    val file = File(root, relativePath).absoluteFile
    require(file.canonicalPath == file.path && file.path.startsWith(root.path + File.separator)) {
        "Restore path escapes scoped storage"
    }
    return file
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
