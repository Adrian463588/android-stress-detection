package com.stressdetector.mobile.data.repository

import android.content.Context
import com.stressdetector.mobile.domain.engine.CounselingInteractionMode
import com.stressdetector.mobile.domain.engine.CounselorPreferences
import com.stressdetector.mobile.domain.engine.CounselorPreferencesStore
import com.stressdetector.mobile.domain.engine.ResponseProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SharedPreferencesCounselorPreferencesStore(context: Context) : CounselorPreferencesStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(loadInitial())
    override val state: StateFlow<CounselorPreferences> = mutableState.asStateFlow()

    override fun setInteractionMode(mode: CounselingInteractionMode) {
        val current = mutableState.value
        val nextProfile = when {
            mode == CounselingInteractionMode.COUNSELOR_PLANNING -> ResponseProfile.COUNSELING_PLAN
            current.responseProfile == ResponseProfile.COUNSELING_PLAN -> ResponseProfile.STANDARD
            else -> current.responseProfile
        }
        persist(current.copy(interactionMode = mode, responseProfile = nextProfile))
    }

    override fun setResponseProfile(profile: ResponseProfile) {
        persist(mutableState.value.copy(responseProfile = profile))
    }

    override fun setIncludeBehavioralContext(enabled: Boolean) {
        persist(mutableState.value.copy(includeBehavioralContext = enabled))
    }

    private fun loadInitial(): CounselorPreferences {
        val mode = preferences.getString(KEY_MODE, null)
            ?.let { runCatching { CounselingInteractionMode.valueOf(it) }.getOrNull() }
            ?: CounselingInteractionMode.GENERAL_COUNSELING
        val profile = preferences.getString(KEY_PROFILE, null)
            ?.let { runCatching { ResponseProfile.valueOf(it) }.getOrNull() }
            ?: ResponseProfile.STANDARD
        val behavioral = preferences.getBoolean(KEY_BEHAVIORAL_CONTEXT, true)
        return CounselorPreferences(mode, profile, behavioral)
    }

    private fun persist(next: CounselorPreferences) {
        preferences.edit()
            .putString(KEY_MODE, next.interactionMode.name)
            .putString(KEY_PROFILE, next.responseProfile.name)
            .putBoolean(KEY_BEHAVIORAL_CONTEXT, next.includeBehavioralContext)
            .apply()
        mutableState.value = next
    }

    private companion object {
        const val PREFS_NAME = "ai_counselor_preferences"
        const val KEY_MODE = "interaction_mode"
        const val KEY_PROFILE = "response_profile"
        const val KEY_BEHAVIORAL_CONTEXT = "include_behavioral_context"
    }
}
