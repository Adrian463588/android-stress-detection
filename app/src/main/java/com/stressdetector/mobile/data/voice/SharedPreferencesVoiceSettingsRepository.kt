package com.stressdetector.mobile.data.voice

import android.content.Context
import com.stressdetector.mobile.domain.voice.VoiceLanguage
import com.stressdetector.mobile.domain.voice.VoiceSendMode
import com.stressdetector.mobile.domain.voice.VoiceSettings
import com.stressdetector.mobile.domain.voice.VoiceSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local persistence adapter for the counselor voice preferences. */
class SharedPreferencesVoiceSettingsRepository(context: Context) : VoiceSettingsRepository {
    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    override val settings: StateFlow<VoiceSettings> = _settings.asStateFlow()

    override fun setLanguage(language: VoiceLanguage) = update { copy(language = language) }

    override fun setSendMode(mode: VoiceSendMode) = update { copy(sendMode = mode) }

    override fun setReplyEnabled(enabled: Boolean) = update { copy(replyEnabled = enabled) }

    private fun update(change: VoiceSettings.() -> VoiceSettings) {
        val next = _settings.value.change()
        _settings.value = next
        preferences.edit()
            .putString(KEY_LANGUAGE, next.language.name)
            .putString(KEY_SEND_MODE, next.sendMode.name)
            .putBoolean(KEY_REPLY, next.replyEnabled)
            .apply()
    }

    private fun read(): VoiceSettings = VoiceSettings(
        language = preferences.getString(KEY_LANGUAGE, null)
            ?.let { runCatching { VoiceLanguage.valueOf(it) }.getOrNull() }
            ?: VoiceLanguage.INDONESIAN,
        sendMode = preferences.getString(KEY_SEND_MODE, null)
            ?.let { runCatching { VoiceSendMode.valueOf(it) }.getOrNull() }
            ?: VoiceSendMode.REVIEW,
        replyEnabled = preferences.getBoolean(KEY_REPLY, true)
    )

    private companion object {
        const val NAME = "counselor_voice_settings"
        const val KEY_LANGUAGE = "language"
        const val KEY_SEND_MODE = "send_mode"
        const val KEY_REPLY = "reply_enabled"
    }
}
