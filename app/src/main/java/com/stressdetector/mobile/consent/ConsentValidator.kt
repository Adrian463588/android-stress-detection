package com.stressdetector.mobile.consent

import java.time.Instant

enum class ConsentScope {
    VERBATIM_RECORDING,
    LIVE_MONITORING
}

data class ConsentRecord(
    val consentId: String,
    val userId: String,
    val sessionId: String,
    val scope: ConsentScope,
    val actor: String,
    val timestampUtc: Instant,
    val appVersion: String,
    val policyVersion: String,
    val isMultiParty: Boolean,
    val withdrawalTimestampUtc: Instant? = null
)

data class ConsentToken(
    val consentId: String,
    val userId: String,
    val sessionId: String,
    val scope: ConsentScope,
    val actor: String,
    val issuedAtEpochMillis: Long
)

sealed class ConsentValidationException(message: String) : SecurityException(message) {
    class Missing : ConsentValidationException("Consent record is missing.")
    class Revoked : ConsentValidationException("Consent has been revoked.")
    class WrongUser : ConsentValidationException("Consent belongs to another user.")
    class WrongSession : ConsentValidationException("Consent belongs to another session.")
    class WrongScope : ConsentValidationException("Consent does not cover this operation.")
    class InvalidToken : ConsentValidationException("Consent token does not match its persisted record.")
    class PersistenceFailure : ConsentValidationException("Consent could not be persisted securely.")
}

/** Persists and validates explicit, user/session/scope-bound consent. */
interface ConsentRepository {
    fun grant(record: ConsentRecord): ConsentToken

    @Throws(ConsentValidationException::class)
    fun require(token: ConsentToken, expectedUserId: String, expectedSessionId: String, expectedScope: ConsentScope): ConsentRecord

    fun revokeSession(userId: String, sessionId: String, withdrawnAtUtc: Instant = Instant.now())
}

/** Deterministic implementation used by domain tests and non-Android callers. */
class InMemoryConsentRepository : ConsentRepository {
    private val records = linkedMapOf<String, ConsentRecord>()

    override fun grant(record: ConsentRecord): ConsentToken {
        require(record.userId.isNotBlank()) { "userId must not be blank" }
        require(record.sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(record.actor.isNotBlank()) { "actor must not be blank" }
        require(record.withdrawalTimestampUtc == null) { "Cannot grant a withdrawn consent record" }
        records[record.consentId] = record
        return record.toToken()
    }

    override fun require(
        token: ConsentToken,
        expectedUserId: String,
        expectedSessionId: String,
        expectedScope: ConsentScope
    ): ConsentRecord = validateConsent(records[token.consentId], token, expectedUserId, expectedSessionId, expectedScope)

    override fun revokeSession(userId: String, sessionId: String, withdrawnAtUtc: Instant) {
        records.replaceAll { _, record ->
            if (record.userId == userId && record.sessionId == sessionId && record.withdrawalTimestampUtc == null) {
                record.copy(withdrawalTimestampUtc = withdrawnAtUtc)
            } else {
                record
            }
        }
    }
}

internal fun ConsentRecord.toToken(): ConsentToken = ConsentToken(
    consentId = consentId,
    userId = userId,
    sessionId = sessionId,
    scope = scope,
    actor = actor,
    issuedAtEpochMillis = timestampUtc.toEpochMilli()
)

internal fun validateConsent(
    record: ConsentRecord?,
    token: ConsentToken,
    expectedUserId: String,
    expectedSessionId: String,
    expectedScope: ConsentScope
): ConsentRecord {
    record ?: throw ConsentValidationException.Missing()
    if (record.withdrawalTimestampUtc != null) throw ConsentValidationException.Revoked()
    if (record.userId != expectedUserId || token.userId != expectedUserId) throw ConsentValidationException.WrongUser()
    if (record.sessionId != expectedSessionId || token.sessionId != expectedSessionId) {
        throw ConsentValidationException.WrongSession()
    }
    if (record.scope != expectedScope || token.scope != expectedScope) throw ConsentValidationException.WrongScope()
    if (
        token.consentId != record.consentId ||
        token.actor != record.actor ||
        token.issuedAtEpochMillis != record.timestampUtc.toEpochMilli()
    ) {
        throw ConsentValidationException.InvalidToken()
    }
    return record
}
