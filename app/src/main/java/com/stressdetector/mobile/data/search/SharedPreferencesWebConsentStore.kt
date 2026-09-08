package com.stressdetector.mobile.data.search

import android.content.Context
import com.stressdetector.mobile.domain.search.WebConsentStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SharedPreferencesWebConsentStore(context: Context) : WebConsentStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val mutableGranted = MutableStateFlow(preferences.getBoolean(GRANTED_KEY, false))
    override val granted: StateFlow<Boolean> = mutableGranted.asStateFlow()

    override fun setGranted(granted: Boolean) {
        preferences.edit().putBoolean(GRANTED_KEY, granted).apply()
        mutableGranted.value = granted
    }

    private companion object {
        const val PREFERENCES_NAME = "ai_counselor_web_consent"
        const val GRANTED_KEY = "network_consent_granted"
    }
}
