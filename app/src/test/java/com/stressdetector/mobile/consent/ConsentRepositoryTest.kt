package com.stressdetector.mobile.consent

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConsentRepositoryTest {
    private val repository = InMemoryConsentRepository()
    private val record = ConsentRecord(
        consentId = "consent-1",
        userId = "user-1",
        sessionId = "session-1",
        scope = ConsentScope.VERBATIM_RECORDING,
        actor = "self",
        timestampUtc = Instant.parse("2026-07-29T00:00:00Z"),
        appVersion = "test",
        policyVersion = "policy-1",
        isMultiParty = true
    )

    @Test
    fun `persisted exact-scope consent is accepted`() {
        val token = repository.grant(record)

        assertEquals(
            record,
            repository.require(token, "user-1", "session-1", ConsentScope.VERBATIM_RECORDING)
        )
    }

    @Test
    fun `missing consent is rejected with typed error`() {
        val token = record.toToken().copy(consentId = "missing")

        assertThrows(ConsentValidationException.Missing::class.java) {
            repository.require(token, "user-1", "session-1", ConsentScope.VERBATIM_RECORDING)
        }
    }

    @Test
    fun `consent cannot be reused for another session`() {
        val token = repository.grant(record)

        assertThrows(ConsentValidationException.WrongSession::class.java) {
            repository.require(token, "user-1", "session-2", ConsentScope.VERBATIM_RECORDING)
        }
    }

    @Test
    fun `recording consent cannot authorize monitoring`() {
        val token = repository.grant(record)

        assertThrows(ConsentValidationException.WrongScope::class.java) {
            repository.require(token, "user-1", "session-1", ConsentScope.LIVE_MONITORING)
        }
    }

    @Test
    fun `revoked session consent is rejected`() {
        val token = repository.grant(record)
        repository.revokeSession("user-1", "session-1", Instant.parse("2026-07-29T00:01:00Z"))

        assertThrows(ConsentValidationException.Revoked::class.java) {
            repository.require(token, "user-1", "session-1", ConsentScope.VERBATIM_RECORDING)
        }
    }
}
