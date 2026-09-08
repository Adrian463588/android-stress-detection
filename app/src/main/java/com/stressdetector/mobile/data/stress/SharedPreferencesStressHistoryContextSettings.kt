package com.stressdetector.mobile.data.stress

import android.content.Context
import com.stressdetector.mobile.domain.stress.StressHistoryContextSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SharedPreferencesStressHistoryContextSettings(context: Context) : StressHistoryContextSettings {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val mutableEnabled = MutableStateFlow(
        preferences.getBoolean(ENABLED_KEY, DEFAULT_ENABLED)
    )

    override val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(ENABLED_KEY, enabled).apply()
        mutableEnabled.value = enabled
    }

    private companion object {
        const val PREFERENCES_NAME = "ai_counselor_context"
        const val ENABLED_KEY = "use_stress_history"

        // Explicit opt-in prevents physiological history from reaching AI+ by
        // default after install or process recreation.
        const val DEFAULT_ENABLED = false
    }
}
