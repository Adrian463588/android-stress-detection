package com.stressdetector.mobile.ml.llm

import android.content.Context
import java.io.File

/** Caches only the result of a completed canonical verification for fast startup checks. */
class VerifiedModelStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun isCurrent(file: File, contract: OfficialModelContract): Boolean = file.isFile &&
        preferences.getString(KEY_FILE_NAME, null) == contract.fileName &&
        preferences.getLong(KEY_SIZE, -1L) == file.length() &&
        preferences.getLong(KEY_MODIFIED, -1L) == file.lastModified() &&
        preferences.getString(KEY_SHA256, null).equals(contract.sha256, ignoreCase = true)

    fun markVerified(file: File, contract: OfficialModelContract) {
        preferences.edit()
            .putString(KEY_FILE_NAME, file.name)
            .putLong(KEY_SIZE, file.length())
            .putLong(KEY_MODIFIED, file.lastModified())
            .putString(KEY_SHA256, contract.sha256)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "gemma_verified_model_state"
        private const val KEY_FILE_NAME = "file_name"
        private const val KEY_SIZE = "size_bytes"
        private const val KEY_MODIFIED = "last_modified"
        private const val KEY_SHA256 = "sha256"
    }
}
