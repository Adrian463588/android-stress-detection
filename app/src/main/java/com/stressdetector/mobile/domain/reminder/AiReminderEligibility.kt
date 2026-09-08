package com.stressdetector.mobile.domain.reminder

import com.stressdetector.mobile.data.db.ConsentDao
import com.stressdetector.mobile.domain.stress.StressHistoryContextProvider
import com.stressdetector.mobile.domain.stress.StressHistoryContextSettings
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

sealed interface ReminderEligibilityResult {
    data class Eligible(val context: ReminderGroundingContext) : ReminderEligibilityResult
    data class Rejected(val code: String) : ReminderEligibilityResult
}

class AiReminderEligibility(
    private val consentDao: ConsentDao,
    private val historyProvider: StressHistoryContextProvider,
    private val historySettings: StressHistoryContextSettings,
    private val reminderSettings: AiReminderSettings
) {
    suspend fun find(sourceSessionId: String? = null): ReminderEligibilityResult {
        val prerequisiteFailure = when {
            !reminderSettings.enabled.value -> "REMINDER_DISABLED"
            consentDao.getConsent(AI_REMINDER_CONSENT_TYPE)?.isGranted != true ->
                "REMINDER_CONSENT_REQUIRED"
            consentDao.getConsent(AI_COUNSELING_CONSENT_TYPE)?.isGranted != true ->
                "AI_COUNSELING_CONSENT_REQUIRED"
            !historySettings.enabled.value -> "STRESS_HISTORY_CONSENT_REQUIRED"
            else -> null
        }
        if (prerequisiteFailure != null) return ReminderEligibilityResult.Rejected(prerequisiteFailure)

        val snapshotResult = runCatching { historyProvider.snapshot() }
        snapshotResult.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
        }
        return snapshotResult.fold(
            onSuccess = { snapshot ->
                val entry = snapshot.entries
                    .asSequence()
                    .filter { it.valid }
                    .filter { sourceSessionId == null || it.sessionId == sourceSessionId }
                    .maxByOrNull { it.endedAtMillis }
                entry?.let {
                    ReminderEligibilityResult.Eligible(
                        ReminderGroundingContext(
                            sourceSessionId = it.sessionId,
                            sessionEndedAt = java.time.Instant.ofEpochMilli(it.endedAtMillis),
                            label = it.label,
                            qualityTier = it.qualityTier,
                            stressProbability = it.stressProbability,
                            confidence = it.confidence,
                            explicitGroundingAction = null
                        )
                    )
                } ?: ReminderEligibilityResult.Rejected("NO_ELIGIBLE_SESSION")
            },
            onFailure = { ReminderEligibilityResult.Rejected("STRESS_HISTORY_UNAVAILABLE") }
        )
    }

    fun currentZone(): ZoneId = ZoneId.systemDefault()
}
