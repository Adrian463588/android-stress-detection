package com.stressdetector.mobile.domain.reminder

import java.time.Duration
import java.time.Instant

/** Suspended checks precede the repository's publication/cancellation linearization gate. */
internal class AiReminderPublication(
    private val repository: AiReminderRepository,
    private val publisher: ReminderNotificationPublisher,
    private val eligibility: AiReminderEligibility
) {
    suspend fun publish(record: AiReminderRecord, content: ReminderContent): ReminderPublishResult {
        val cooldown = repository.getLatestDeliveredSince(Instant.now().minus(Duration.ofHours(AI_REMINDER_COOLDOWN_HOURS)))
        val eligible = if (cooldown == null) eligibility.find(record.sourceSessionId) else null
        return when {
            cooldown != null -> ReminderPublishResult.Rejected("COOLDOWN_ACTIVE")
            eligible is ReminderEligibilityResult.Rejected -> ReminderPublishResult.Rejected(eligible.code)
            else -> repository.publishIfActive(record.reminderId) {
                eligibility.publicationSettingsRejection()?.let { ReminderPublishResult.Rejected(it) }
                    ?: publisher.publish(record.reminderId, content)
            }
        }
    }
}
