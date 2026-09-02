package com.stressdetector.mobile.ml.llm

import android.annotation.SuppressLint
import android.content.Context

data class PersistedModelDownload(
    val downloadId: Long = -1L,
    val replacesActiveModel: Boolean,
    val contractSha256: String,
    val candidateFileName: String,
    val stage: ModelDownloadStage = ModelDownloadStage.TRANSFERRING,
    val finalizerWorkId: String? = null,
    val bytesProcessed: Long = 0L,
    val totalBytes: Long = ModelManager.MODEL_SIZE_BYTES,
    val errorMessage: String? = null,
    val retryable: Boolean? = null,
    val workId: String? = null
)

enum class ModelDownloadStage {
    TRANSFERRING,
    VERIFYING,
    PREFLIGHTING,
    PUBLISHING,
    FAILED,
    CANCELLED
}

// WorkManager reconciliation requires state to be durable before each transition returns.
@SuppressLint("ApplySharedPref")
class ModelDownloadStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun read(): PersistedModelDownload? {
        val id = preferences.getLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD_ID)
        val workId = preferences.getString(KEY_WORK_ID, null)
        if (id == NO_DOWNLOAD_ID && workId.isNullOrBlank()) return null
        return PersistedModelDownload(
            downloadId = id,
            replacesActiveModel = preferences.getBoolean(KEY_REPLACES_ACTIVE, false),
            contractSha256 = preferences.getString(KEY_CONTRACT_SHA256, null).orEmpty(),
            candidateFileName = preferences.getString(KEY_CANDIDATE_FILE, null).orEmpty(),
            stage = preferences.getString(KEY_STAGE, null)
                ?.let { runCatching { ModelDownloadStage.valueOf(it) }.getOrNull() }
                ?: ModelDownloadStage.TRANSFERRING,
            finalizerWorkId = preferences.getString(KEY_FINALIZER_WORK_ID, null),
            bytesProcessed = preferences.getLong(KEY_BYTES_PROCESSED, 0L),
            totalBytes = preferences.getLong(KEY_TOTAL_BYTES, ModelManager.MODEL_SIZE_BYTES),
            errorMessage = preferences.getString(KEY_ERROR_MESSAGE, null),
            retryable = if (preferences.contains(KEY_RETRYABLE)) {
                preferences.getBoolean(KEY_RETRYABLE, false)
            } else {
                null
            },
            workId = workId
        )
    }

    fun save(downloadId: Long, replacesActiveModel: Boolean) {
        preferences.edit()
            .putLong(KEY_DOWNLOAD_ID, downloadId)
            .putBoolean(KEY_REPLACES_ACTIVE, replacesActiveModel)
            .putString(KEY_CONTRACT_SHA256, ModelManager.MODEL_SHA256)
            .putString(KEY_CANDIDATE_FILE, ModelManager.DOWNLOAD_CANDIDATE_FILE_NAME)
            .putString(KEY_STAGE, ModelDownloadStage.TRANSFERRING.name)
            .putLong(KEY_BYTES_PROCESSED, 0L)
            .putLong(KEY_TOTAL_BYTES, ModelManager.MODEL_SIZE_BYTES)
            .remove(KEY_FINALIZER_WORK_ID)
            .remove(KEY_WORK_ID)
            .remove(KEY_ERROR_MESSAGE)
            .remove(KEY_RETRYABLE)
            .commit()
    }

    fun saveWork(workId: String, replacesActiveModel: Boolean) {
        preferences.edit()
            .putLong(KEY_DOWNLOAD_ID, NO_DOWNLOAD_ID)
            .putBoolean(KEY_REPLACES_ACTIVE, replacesActiveModel)
            .putString(KEY_CONTRACT_SHA256, ModelManager.MODEL_SHA256)
            .putString(KEY_CANDIDATE_FILE, ModelManager.DOWNLOAD_CANDIDATE_FILE_NAME)
            .putString(KEY_STAGE, ModelDownloadStage.TRANSFERRING.name)
            .putString(KEY_WORK_ID, workId)
            .putLong(KEY_BYTES_PROCESSED, 0L)
            .putLong(KEY_TOTAL_BYTES, ModelManager.MODEL_SIZE_BYTES)
            .remove(KEY_FINALIZER_WORK_ID)
            .remove(KEY_ERROR_MESSAGE)
            .remove(KEY_RETRYABLE)
            .commit()
    }

    fun update(
        stage: ModelDownloadStage,
        finalizerWorkId: String? = read()?.finalizerWorkId,
        bytesProcessed: Long = read()?.bytesProcessed ?: 0L,
        totalBytes: Long = read()?.totalBytes ?: ModelManager.MODEL_SIZE_BYTES,
        errorMessage: String? = null
    ) {
        preferences.edit()
            .putString(KEY_STAGE, stage.name)
            .putLong(KEY_BYTES_PROCESSED, bytesProcessed)
            .putLong(KEY_TOTAL_BYTES, totalBytes)
            .apply {
                if (finalizerWorkId == null) {
                    remove(KEY_FINALIZER_WORK_ID)
                } else {
                    putString(KEY_FINALIZER_WORK_ID, finalizerWorkId)
                }
                if (errorMessage == null) {
                    remove(KEY_ERROR_MESSAGE)
                } else {
                    putString(KEY_ERROR_MESSAGE, errorMessage)
                }
            }
            .commit()
    }

    fun setRetryable(retryable: Boolean) {
        preferences.edit().putBoolean(KEY_RETRYABLE, retryable).commit()
    }

    fun setFinalizerWorkId(workId: String) {
        preferences.edit().putString(KEY_FINALIZER_WORK_ID, workId).commit()
    }

    fun setWorkId(workId: String) {
        preferences.edit().putString(KEY_WORK_ID, workId).commit()
    }

    fun clear() {
        preferences.edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_REPLACES_ACTIVE)
            .remove(KEY_CONTRACT_SHA256)
            .remove(KEY_CANDIDATE_FILE)
            .remove(KEY_STAGE)
            .remove(KEY_FINALIZER_WORK_ID)
            .remove(KEY_BYTES_PROCESSED)
            .remove(KEY_TOTAL_BYTES)
            .remove(KEY_ERROR_MESSAGE)
            .remove(KEY_RETRYABLE)
            .remove(KEY_WORK_ID)
            .commit()
    }

    companion object {
        private const val PREFERENCES_NAME = "gemma_model_download_state"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_REPLACES_ACTIVE = "replaces_active_model"
        private const val KEY_CONTRACT_SHA256 = "contract_sha256"
        private const val KEY_CANDIDATE_FILE = "candidate_file"
        private const val KEY_STAGE = "stage"
        private const val KEY_FINALIZER_WORK_ID = "finalizer_work_id"
        private const val KEY_BYTES_PROCESSED = "bytes_processed"
        private const val KEY_TOTAL_BYTES = "total_bytes"
        private const val KEY_ERROR_MESSAGE = "error_message"
        private const val KEY_RETRYABLE = "retryable"
        private const val KEY_WORK_ID = "work_id"
        private const val NO_DOWNLOAD_ID = -1L
    }
}
