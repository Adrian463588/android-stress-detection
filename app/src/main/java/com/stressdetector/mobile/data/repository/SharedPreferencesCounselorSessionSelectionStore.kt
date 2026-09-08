package com.stressdetector.mobile.data.repository

import android.content.Context
import com.stressdetector.mobile.domain.counselor.CounselorSessionSelectionStore

class SharedPreferencesCounselorSessionSelectionStore(context: Context) : CounselorSessionSelectionStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun read(): String? = preferences.getString(SELECTED_SESSION_KEY, null)

    override fun save(sessionId: String) {
        preferences.edit().putString(SELECTED_SESSION_KEY, sessionId).apply()
    }

    override fun clear() {
        preferences.edit().remove(SELECTED_SESSION_KEY).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "ai_counselor_context"
        const val SELECTED_SESSION_KEY = "selected_chat_session_id"
    }
}
