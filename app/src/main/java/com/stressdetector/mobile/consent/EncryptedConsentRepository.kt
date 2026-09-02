package com.stressdetector.mobile.consent

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

/** Keystore-backed persistence for consent records. */
class EncryptedConsentRepository(context: Context) : ConsentRepository {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFERENCES_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun grant(record: ConsentRecord): ConsentToken {
        require(record.userId.isNotBlank()) { "userId must not be blank" }
        require(record.sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(record.actor.isNotBlank()) { "actor must not be blank" }
        require(record.withdrawalTimestampUtc == null) { "Cannot grant a withdrawn consent record" }
        if (!preferences.edit().putString(record.consentId, record.serialize()).commit()) {
            throw ConsentValidationException.PersistenceFailure()
        }
        return record.toToken()
    }

    override fun require(
        token: ConsentToken,
        expectedUserId: String,
        expectedSessionId: String,
        expectedScope: ConsentScope
    ): ConsentRecord = validateConsent(
        preferences.getString(token.consentId, null)?.let(::deserialize),
        token,
        expectedUserId,
        expectedSessionId,
        expectedScope
    )

    override fun revokeSession(userId: String, sessionId: String, withdrawnAtUtc: Instant) {
        val editor = preferences.edit()
        preferences.all.forEach { (consentId, value) ->
            val record = (value as? String)?.let(::deserialize) ?: return@forEach
            if (record.userId == userId && record.sessionId == sessionId && record.withdrawalTimestampUtc == null) {
                editor.putString(consentId, record.copy(withdrawalTimestampUtc = withdrawnAtUtc).serialize())
            }
        }
        if (!editor.commit()) throw ConsentValidationException.PersistenceFailure()
    }

    private fun ConsentRecord.serialize(): String = listOf(
        consentId,
        userId,
        sessionId,
        scope.name,
        actor,
        timestampUtc.toString(),
        appVersion,
        policyVersion,
        isMultiParty.toString(),
        withdrawalTimestampUtc?.toString().orEmpty()
    ).joinToString(SEPARATOR) { encode(it) }

    private fun deserialize(serialized: String): ConsentRecord {
        val fields = serialized.split(SEPARATOR).map(::decode)
        require(fields.size == FIELD_COUNT) { "Invalid persisted consent record" }
        return ConsentRecord(
            consentId = fields[0],
            userId = fields[1],
            sessionId = fields[2],
            scope = ConsentScope.valueOf(fields[3]),
            actor = fields[4],
            timestampUtc = Instant.parse(fields[5]),
            appVersion = fields[6],
            policyVersion = fields[7],
            isMultiParty = fields[8].toBooleanStrict(),
            withdrawalTimestampUtc = fields[9].takeIf(String::isNotEmpty)?.let(Instant::parse)
        )
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

    private companion object {
        const val PREFERENCES_NAME = "scoped_consents"
        const val SEPARATOR = "."
        const val FIELD_COUNT = 10
    }
}
