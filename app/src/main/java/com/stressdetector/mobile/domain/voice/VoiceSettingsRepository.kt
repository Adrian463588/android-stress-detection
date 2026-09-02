package com.stressdetector.mobile.domain.voice

import kotlinx.coroutines.flow.StateFlow

data class VoiceSettings(
    val language: VoiceLanguage = VoiceLanguage.INDONESIAN,
    val sendMode: VoiceSendMode = VoiceSendMode.REVIEW,
    val replyEnabled: Boolean = true
)

interface VoiceSettingsRepository {
    val settings: StateFlow<VoiceSettings>
    fun setLanguage(language: VoiceLanguage)
    fun setSendMode(mode: VoiceSendMode)
    fun setReplyEnabled(enabled: Boolean)
}
