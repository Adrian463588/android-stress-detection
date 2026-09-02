package com.stressdetector.mobile.consent

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * High-level manager for coordinating explicit consent flows for audio recording and live monitoring.
 * Ensures offline-only data boundaries and integrates with ConsentRepository.
 */
class ConsentManager(private val consentRepository: ConsentRepository) {
    private val _activeConsents = MutableStateFlow<Map<ConsentScope, ConsentToken>>(emptyMap())
    val activeConsents: StateFlow<Map<ConsentScope, ConsentToken>> = _activeConsents.asStateFlow()

    fun requestConsent(
        userId: String,
        sessionId: String,
        scope: ConsentScope,
        isMultiParty: Boolean,
        appVersion: String,
        policyVersion: String
    ): ConsentToken {
        val record = ConsentRecord(
            consentId = UUID.randomUUID().toString(),
            userId = userId,
            sessionId = sessionId,
            scope = scope,
            actor = "user",
            timestampUtc = Instant.now(),
            appVersion = appVersion,
            policyVersion = policyVersion,
            isMultiParty = isMultiParty
        )

        val token = consentRepository.grant(record)
        _activeConsents.value = _activeConsents.value.toMutableMap().apply {
            put(scope, token)
        }
        return token
    }

    fun hasValidConsent(userId: String, sessionId: String, scope: ConsentScope): Boolean {
        val token = _activeConsents.value[scope] ?: return false
        return try {
            consentRepository.require(token, userId, sessionId, scope)
            true
        } catch (e: ConsentValidationException) {
            false
        }
    }

    fun revokeConsent(userId: String, sessionId: String, scope: ConsentScope) {
        val token = _activeConsents.value[scope]
        if (token != null && token.userId == userId && token.sessionId == sessionId) {
            consentRepository.revokeSession(userId, sessionId)
            _activeConsents.value = _activeConsents.value.toMutableMap().apply {
                remove(scope)
            }
        }
    }

    fun clearActiveConsents() {
        _activeConsents.value = emptyMap()
    }
}
