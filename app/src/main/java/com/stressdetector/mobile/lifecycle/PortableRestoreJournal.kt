package com.stressdetector.mobile.lifecycle

import android.content.Context
import com.stressdetector.mobile.security.EncryptedStorageService
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Durable ownership plan for a restore. Caller begins it inside the Room transaction,
 * after checking scoped tables and every target are empty and preparing chart temporaries.
 * Publication must move the recorded inode into place without replacing an existing target.
 */
internal class PortableRestoreJournal(context: Context, private val encryptedStorage: EncryptedStorageService, private val userId: String) {
    private val filesDir = context.applicationContext.filesDir
    private val chartDirectory = File(filesDir, "history_chart_artifacts")
    private val encryptedRoot by lazy {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(userId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        File(File(filesDir, "sensitive"), hash).canonicalFile
    }

    fun begin(files: Map<String, String>, charts: Map<String, String>, stagePrefix: String) {
        val filePaths = files.toMap()
        val chartDigests = charts.toMap()
        validateRestoreScope(filePaths, chartDigests, stagePrefix)
        check(!encryptedStorage.hasRestoreJournal(userId)) { "Restore journal already exists" }
        val staging = encryptedStorage.listEncryptedFiles(userId)
            .filter { it.relativePath.startsWith("$stagePrefix/") }
            .associate { entry ->
                require(isStagePath(entry.relativePath, stagePrefix)) { "Unexpected restore staging path" }
                entry.relativePath to checkNotNull(readOwnership(encryptedFile(entry.relativePath))) {
                    "Restore staging file is missing"
                }
            }
        val plannedFiles = filePaths.mapValues { (target, source) ->
            check(lstat(encryptedFile(target)) == null) {
                "Encrypted artifact target already exists"
            }
            PlannedFile(source, checkNotNull(staging[source]) { "Restore staging file is missing" })
        }
        val plannedCharts = chartDigests.mapValues { (id, digest) ->
            check(lstat(chartFile(id)) == null) {
                "History chart target already exists"
            }
            val temporary = chartTemporary(chartDirectory, id, stagePrefix)
            val chart = PlannedChart(digest, checkNotNull(readOwnership(temporary)) { "Restore chart staging file is missing" })
            check(matchesChart(temporary, chart)) { "Restore chart staging file is missing" }
            chart
        }
        val plan = RestorePlan(plannedFiles, plannedCharts, staging, stagePrefix).also(::validateRestorePlan)
        val json = encodeJournalPlan(plan)
        require(json.size <= MAX_RESTORE_JOURNAL_BYTES) { "Restore journal exceeds size limit" }
        encryptedStorage.writeEncryptedFile(userId, RESTORE_JOURNAL_PATH, ByteArrayInputStream(json))
    }

    /** Only a receipt committed with the imported rows can establish restore completion. */
    fun recover(isCommitted: (String) -> Boolean) {
        val plan = readPlan() ?: return
        val removeTargets = !isCommitted(plan.stagePrefix)
        checkStagingScope(encryptedStorage, userId, plan)
        verifyRecoveryOwnership(plan, encryptedRoot, chartDirectory, removeTargets)
        if (removeTargets) cleanupFiles(plan)
        cleanupCharts(plan, removeTargets)
        cleanupStaging(plan)
        check(encryptedStorage.deleteEncryptedFile(userId, RESTORE_JOURNAL_PATH)) { "Restore journal cleanup failed" }
    }

    private fun readPlan(): RestorePlan? = if (encryptedStorage.hasRestoreJournal(userId)) {
        decodeRestorePlan(readJournalBytes(encryptedStorage, userId))
    } else {
        null
    }

    private fun cleanupFiles(plan: RestorePlan) {
        plan.files.forEach { (target, file) ->
            if (!owns(encryptedFile(target), file.ownership)) return@forEach
            check(encryptedStorage.deleteEncryptedFile(userId, target)) { "Restore artifact cleanup failed" }
        }
    }

    private fun cleanupCharts(plan: RestorePlan, removeTargets: Boolean) {
        plan.charts.forEach { (id, chart) ->
            val temporary = chartTemporary(chartDirectory, id, plan.stagePrefix)
            if (removeTargets) {
                val target = chartFile(id)
                if (matchesChart(target, chart)) {
                    check(target.delete()) { "Restore chart cleanup failed" }
                }
            }
            if (matchesChart(temporary, chart)) {
                check(temporary.delete()) { "Restore chart staging cleanup failed" }
            }
        }
    }

    private fun cleanupStaging(plan: RestorePlan) {
        plan.staging.forEach { (path, ownership) ->
            if (!owns(encryptedFile(path), ownership)) return@forEach
            check(encryptedStorage.deleteEncryptedFile(userId, path)) { "Restore staging cleanup failed" }
        }
    }

    private fun encryptedFile(relativePath: String): File = scopedFile(encryptedRoot, relativePath)

    private fun chartFile(id: String): File = scopedFile(chartDirectory.canonicalFile, "$id.json")
}
