package com.stressdetector.mobile.domain.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CounselorPreferences(
    val interactionMode: CounselingInteractionMode = CounselingInteractionMode.GENERAL_COUNSELING,
    val responseProfile: ResponseProfile = ResponseProfile.STANDARD,
    val includeBehavioralContext: Boolean = true
)

interface CounselorPreferencesStore {
    val state: StateFlow<CounselorPreferences>
    fun setInteractionMode(mode: CounselingInteractionMode)
    fun setResponseProfile(profile: ResponseProfile)
    fun setIncludeBehavioralContext(enabled: Boolean)

    class InMemory(initial: CounselorPreferences = CounselorPreferences()) : CounselorPreferencesStore {
        private val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<CounselorPreferences> = mutableState.asStateFlow()

        override fun setInteractionMode(mode: CounselingInteractionMode) {
            val current = mutableState.value
            val nextProfile = when {
                mode == CounselingInteractionMode.COUNSELOR_PLANNING -> ResponseProfile.COUNSELING_PLAN
                current.responseProfile == ResponseProfile.COUNSELING_PLAN -> ResponseProfile.STANDARD
                else -> current.responseProfile
            }
            mutableState.value = current.copy(interactionMode = mode, responseProfile = nextProfile)
        }

        override fun setResponseProfile(profile: ResponseProfile) {
            mutableState.value = mutableState.value.copy(responseProfile = profile)
        }

        override fun setIncludeBehavioralContext(enabled: Boolean) {
            mutableState.value = mutableState.value.copy(includeBehavioralContext = enabled)
        }
    }
}
