package com.stressdetector.mobile.data.reminder

import android.content.Context
import com.stressdetector.mobile.domain.reminder.AiReminderSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Non-sensitive opt-in switch; reminder content/name remains in encrypted storage. */
class SharedPreferencesAiReminderSettings(context: Context) : AiReminderSettings {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean(ENABLED_KEY, false))

    override val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        check(preferences.edit().putBoolean(ENABLED_KEY, enabled).commit()) {
            "AI reminder setting could not be persisted."
        }
        mutableEnabled.value = enabled
    }

    private companion object {
        const val PREFERENCES_NAME = "ai_reminder_settings"
        const val ENABLED_KEY = "enabled"
    }
}
