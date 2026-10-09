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
    private val mutableAutomatic = MutableStateFlow(preferences.getBoolean(AUTOMATIC_KEY, false))
    override val automaticAllowed: StateFlow<Boolean> = mutableAutomatic.asStateFlow()

    override fun setGranted(granted: Boolean) {
        preferences.edit().putBoolean(GRANTED_KEY, granted).apply()
        if (!granted) setAutomaticAllowed(false)
        mutableGranted.value = granted
    }

    override fun setAutomaticAllowed(allowed: Boolean) {
        if (!allowed) {
            mutableGranted.value = false
            mutableAutomatic.value = false
        }
        check(preferences.edit().putBoolean(AUTOMATIC_KEY, allowed).putBoolean(GRANTED_KEY, allowed).commit()) {
            "web_consent_storage_failed"
        }
        mutableGranted.value = allowed
        mutableAutomatic.value = allowed
    }

    private companion object {
        const val PREFERENCES_NAME = "ai_counselor_web_consent"
        const val GRANTED_KEY = "network_consent_granted"
        const val AUTOMATIC_KEY = "public_topic_automatic_consent_v1"
    }
}
