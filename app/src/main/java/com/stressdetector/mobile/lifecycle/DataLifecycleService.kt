package com.stressdetector.mobile.lifecycle

import android.content.Context
import android.database.Cursor
import android.util.Base64
import com.stressdetector.mobile.data.db.StressDatabase
import com.stressdetector.mobile.domain.reminder.AiReminderScheduler
import com.stressdetector.mobile.domain.reminder.AiReminderSecretStore
import com.stressdetector.mobile.domain.reminder.AiReminderSettings
import com.stressdetector.mobile.security.EncryptedStorageService
import com.stressdetector.mobile.security.KeyManager
import com.stressdetector.mobile.security.PrivacyIdentity
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ExportPayload(val exportFile: File, val checksumSha256: String, val createdAtUtc: Instant)

sealed class DataLifecycleException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class UnsupportedUser : DataLifecycleException("Only the active local profile can be exported or purged.")
    class ExportFailed(cause: Throwable) : DataLifecycleException("Data export failed.", cause)
    class PurgeFailed(cause: Throwable) : DataLifecycleException("Data purge failed.", cause)
}

interface DataLifecycleService {
    suspend fun exportUserData(userId: String): ExportPayload
    suspend fun deleteUserData(userId: String)

    suspend fun exportTranscripts(userId: String): ExportPayload
    suspend fun deleteTranscripts(userId: String)

    suspend fun exportLiveMonitorData(userId: String): ExportPayload
    suspend fun deleteLiveMonitorData(userId: String)
}

data class AiReminderLifecycleDependencies(
    val secretStore: AiReminderSecretStore? = null,
    val scheduler: AiReminderScheduler? = null,
    val settings: AiReminderSettings? = null
)

/**
 * Implements the lifecycle hooks for the app's single local profile.
 * The export is plaintext because it is created only after an explicit user action; callers must
 * hand it to the system share sheet or delete it. Internal sensitive files remain encrypted.
 */
class LocalDataLifecycleService(
    context: Context,
    private val database: StressDatabase,
    private val encryptedStorage: EncryptedStorageService,
    private val keyManager: KeyManager,
    private val aiReminder: AiReminderLifecycleDependencies = AiReminderLifecycleDependencies()
) : DataLifecycleService {
    private val exportDirectory = File(context.applicationContext.cacheDir, "privacy_exports")

    override suspend fun exportUserData(userId: String): ExportPayload = withContext(Dispatchers.IO) {
        requireLocalProfile(userId)
        try {
            exportDirectory.mkdirs()
            val target = File(exportDirectory, "stress-data-${System.currentTimeMillis()}.zip")
            ZipOutputStream(FileOutputStream(target)).use { zip ->
                zip.putNextEntry(ZipEntry("records.json"))
                zip.write(exportDatabase().toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                aiReminder.secretStore?.let { secrets ->
                    zip.putNextEntry(ZipEntry("ai-reminder-sensitive.json"))
                    zip.write(secrets.exportJson().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                encryptedStorage.listEncryptedFiles(userId).forEach { encrypted ->
                    zip.putNextEntry(ZipEntry("files/${encrypted.relativePath}"))
                    encryptedStorage.readEncryptedFile(userId, encrypted.relativePath).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            ExportPayload(target, sha256(target), Instant.now())
        } catch (error: Exception) {
            throw DataLifecycleException.ExportFailed(error)
        }
    }

    override suspend fun deleteUserData(userId: String): Unit = withContext(Dispatchers.IO) {
        requireLocalProfile(userId)
        try {
            aiReminder.settings?.setEnabled(false)
            aiReminder.scheduler?.cancelAll()
            database.clearAllTables()
            aiReminder.secretStore?.clear()
            encryptedStorage.deleteAllUserFiles(userId)
            keyManager.deleteKeys(userId)
            exportDirectory.listFiles()?.forEach(File::delete)
            Unit
        } catch (error: Exception) {
            throw DataLifecycleException.PurgeFailed(error)
        }
    }

    override suspend fun exportTranscripts(userId: String): ExportPayload = withContext(Dispatchers.IO) {
        requireLocalProfile(userId)
        try {
            exportDirectory.mkdirs()
            val target = File(exportDirectory, "transcripts-${System.currentTimeMillis()}.zip")
            ZipOutputStream(FileOutputStream(target)).use { zip ->
                zip.putNextEntry(ZipEntry("records.json"))
                zip.write(exportDatabase(TRANSCRIPT_TABLES).toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            ExportPayload(target, sha256(target), Instant.now())
        } catch (error: Exception) {
            throw DataLifecycleException.ExportFailed(error)
        }
    }

    override suspend fun deleteTranscripts(userId: String): Unit = withContext(Dispatchers.IO) {
        requireLocalProfile(userId)
        try {
            val db = database.openHelper.writableDatabase
            TRANSCRIPT_TABLES.forEach { table ->
                db.execSQL("DELETE FROM $table")
            }
        } catch (error: Exception) {
            throw DataLifecycleException.PurgeFailed(error)
        }
    }

    override suspend fun exportLiveMonitorData(userId: String): ExportPayload = withContext(Dispatchers.IO) {
        requireLocalProfile(userId)
        try {
            exportDirectory.mkdirs()
            val target = File(exportDirectory, "live-monitor-${System.currentTimeMillis()}.zip")
            ZipOutputStream(FileOutputStream(target)).use { zip ->
                zip.putNextEntry(ZipEntry("records.json"))
                zip.write(exportDatabase(LIVE_MONITOR_TABLES).toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            ExportPayload(target, sha256(target), Instant.now())
        } catch (error: Exception) {
            throw DataLifecycleException.ExportFailed(error)
        }
    }

    override suspend fun deleteLiveMonitorData(userId: String): Unit = withContext(Dispatchers.IO) {
        requireLocalProfile(userId)
        try {
            val db = database.openHelper.writableDatabase
            LIVE_MONITOR_TABLES.forEach { table ->
                db.execSQL("DELETE FROM $table")
            }
        } catch (error: Exception) {
            throw DataLifecycleException.PurgeFailed(error)
        }
    }

    private fun requireLocalProfile(userId: String) {
        if (userId != PrivacyIdentity.LOCAL_PROFILE_ID) throw DataLifecycleException.UnsupportedUser()
    }

    private fun exportDatabase(tables: List<String> = TABLES): JSONObject {
        val result = JSONObject()
        tables.forEach { table ->
            database.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor ->
                result.put(table, cursor.toJson())
            }
        }
        return JSONObject()
            .put("schema_version", database.openHelper.readableDatabase.version)
            .put("profile_id", PrivacyIdentity.LOCAL_PROFILE_ID)
            .put("exported_at_utc", Instant.now().toString())
            .put("tables", result)
    }

    private fun Cursor.toJson(): JSONArray = JSONArray().also { rows ->
        while (moveToNext()) {
            val row = JSONObject()
            columnNames.forEachIndexed { index, name ->
                val value: Any = when (getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                    Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                    Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
                    Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(getBlob(index), Base64.NO_WRAP)
                    else -> getString(index)
                }
                row.put(name, value)
            }
            rows.put(row)
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val TABLES = listOf(
            "stress_sessions",
            "verbatim_sessions",
            "transcript_segments",
            "psychology_codes",
            "ai_analyses",
            "live_sessions",
            "realtime_inference_records",
            "divergence_events",
            "annotations",
            "chat_sessions",
            "chat_messages",
            "user_consents",
            "ai_reminders",
            "clinical_documents",
            "document_chunks",
            "counselor_persona"
        )

        val TRANSCRIPT_TABLES = listOf(
            "verbatim_sessions",
            "transcript_segments",
            "psychology_codes"
        )

        val LIVE_MONITOR_TABLES = listOf(
            "live_sessions",
            "realtime_inference_records",
            "divergence_events"
        )
    }
}
