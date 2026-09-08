package com.stressdetector.mobile.data.reminder

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.stressdetector.mobile.domain.reminder.AiReminderSecretStore
import com.stressdetector.mobile.domain.reminder.ReminderNamePolicy
import com.stressdetector.mobile.domain.reminder.ReminderPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Keystore-backed storage for the only sensitive AI reminder values.
 * Room keeps metadata and the payload key; this store keeps name/message out
 * of SQLite and out of WorkManager input data.
 */
class EncryptedAiReminderSecretStore(context: Context) : AiReminderSecretStore {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFERENCES_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val mutableDisplayName = MutableStateFlow(
        ReminderNamePolicy.normalize(preferences.getString(DISPLAY_NAME_KEY, null))
    )

    override val displayName: StateFlow<String?> = mutableDisplayName.asStateFlow()

    override fun saveDisplayName(rawName: String?): String? {
        val normalized = ReminderNamePolicy.normalize(rawName)
        val editor = preferences.edit()
        if (normalized == null) editor.remove(DISPLAY_NAME_KEY) else editor.putString(DISPLAY_NAME_KEY, normalized)
        check(editor.commit()) { "Encrypted AI reminder identity could not be persisted." }
        mutableDisplayName.value = normalized
        return normalized
    }

    override fun readPayload(reminderId: String): ReminderPayload? {
        require(reminderId.isNotBlank()) { "reminderId must not be blank" }
        val serialized = preferences.getString(payloadKey(reminderId), null) ?: return null
        val json = JSONObject(serialized)
        return ReminderPayload(
            message = json.getString(JSON_MESSAGE),
            displayNameUsed = json.optString(JSON_DISPLAY_NAME).takeIf(String::isNotBlank),
            sourceSessionId = json.getString(JSON_SOURCE_SESSION_ID)
        )
    }

    override fun writePayload(reminderId: String, payload: ReminderPayload) {
        require(reminderId.isNotBlank()) { "reminderId must not be blank" }
        require(payload.message.isNotBlank()) { "Reminder message must not be blank" }
        require(payload.sourceSessionId.isNotBlank()) { "sourceSessionId must not be blank" }
        val json = JSONObject()
            .put(JSON_MESSAGE, payload.message)
            .put(JSON_DISPLAY_NAME, payload.displayNameUsed.orEmpty())
            .put(JSON_SOURCE_SESSION_ID, payload.sourceSessionId)
        check(preferences.edit().putString(payloadKey(reminderId), json.toString()).commit()) {
            "Encrypted AI reminder payload could not be persisted."
        }
    }

    override fun deletePayload(reminderId: String) {
        require(reminderId.isNotBlank()) { "reminderId must not be blank" }
        check(preferences.edit().remove(payloadKey(reminderId)).commit()) {
            "Encrypted AI reminder payload could not be deleted."
        }
    }

    override fun clear() {
        check(preferences.edit().clear().commit()) { "Encrypted AI reminder secrets could not be deleted." }
        mutableDisplayName.value = null
    }

    /** Decryption is intentionally exposed only for an explicit user export. */
    override fun exportJson(): String {
        val payloads = JSONObject()
        preferences.all
            .filterKeys { it.startsWith(PAYLOAD_PREFIX) }
            .toSortedMap()
            .forEach { (key, value) ->
                val serialized = value as? String ?: error("Encrypted reminder payload has an invalid type.")
                payloads.put(key.removePrefix(PAYLOAD_PREFIX), JSONObject(serialized))
            }
        return JSONObject()
            .put("displayName", mutableDisplayName.value.orEmpty())
            .put("payloads", payloads)
            .toString(2)
    }

    private fun payloadKey(reminderId: String): String = PAYLOAD_PREFIX + reminderId

    private companion object {
        const val PREFERENCES_NAME = "ai_reminder_secrets"
        const val DISPLAY_NAME_KEY = "display_name"
        const val PAYLOAD_PREFIX = "payload_"
        const val JSON_MESSAGE = "message"
        const val JSON_DISPLAY_NAME = "displayNameUsed"
        const val JSON_SOURCE_SESSION_ID = "sourceSessionId"
    }
}
