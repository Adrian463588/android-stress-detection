package com.stressdetector.mobile.domain.reminder

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

const val AI_REMINDER_CONSENT_TYPE = "AI_REMINDER"
const val AI_COUNSELING_CONSENT_TYPE = "AI_COUNSELING"
const val AI_REMINDER_POLICY_VERSION = "ai-reminder-v1"
const val AI_REMINDER_CHANNEL_ID = "ai_reminder_channel"
const val AI_REMINDER_MAX_HORIZON_HOURS = 48L
const val AI_REMINDER_COOLDOWN_HOURS = 24L
const val AI_REMINDER_MAX_MESSAGE_CODE_POINTS = 280

private const val MAX_SESSION_ID_PROMPT_LENGTH = 40
private const val MAX_LABEL_PROMPT_LENGTH = 32
private const val MAX_QUALITY_PROMPT_LENGTH = 24
private const val MAX_ACTION_PROMPT_LENGTH = 160

/** Prompt-safe summary; raw PPG, audio, transcript, and RAG documents never cross this boundary. */
data class ReminderGroundingContext(
    val sourceSessionId: String,
    val sessionEndedAt: Instant,
    val label: String,
    val qualityTier: String,
    val stressProbability: Float?,
    val confidence: Float?,
    val explicitGroundingAction: String? = null
) {
    fun promptSummary(now: Instant, zoneId: ZoneId): String = buildString {
        append("sessionId=").append(sourceSessionId.take(MAX_SESSION_ID_PROMPT_LENGTH))
        append("; relativeDate=").append(ReminderRelativeDate.describe(sessionEndedAt, now, zoneId))
        append("; label=").append(label.take(MAX_LABEL_PROMPT_LENGTH))
        append("; quality=").append(qualityTier.take(MAX_QUALITY_PROMPT_LENGTH))
        append("; stressProbability=").append(stressProbability?.takeIf(Float::isFinite) ?: "unavailable")
        append("; confidence=").append(confidence?.takeIf(Float::isFinite) ?: "unavailable")
        append("; explicitGroundingAction=")
            .append(
                explicitGroundingAction?.takeIf(String::isNotBlank)?.take(MAX_ACTION_PROMPT_LENGTH)
                    ?: "none_recorded"
            )
    }
}

data class ReminderScheduleDecision(
    val nextReminderAt: Instant,
    val sourceSessionId: String,
    val timezone: ZoneId,
    val modelVersion: String?
)

data class ReminderContent(
    val message: String,
    val displayNameUsed: String?,
    val sourceSessionId: String,
    val modelVersion: String?,
    val policyVersion: String
)

data class ReminderPayload(val message: String, val displayNameUsed: String?, val sourceSessionId: String)

sealed interface AiReminderStatus {
    data object Disabled : AiReminderStatus
    data class NeedsConsent(val reason: String) : AiReminderStatus
    data object NeedsNotificationPermission : AiReminderStatus
    data object NoEligibleSession : AiReminderStatus
    data class ModelUnavailable(val reason: String) : AiReminderStatus
    data class Scheduled(val nextReminderAt: Instant, val sourceSessionId: String) : AiReminderStatus
    data object Generating : AiReminderStatus
    data class Delivered(val deliveredAt: Instant, val sourceSessionId: String) : AiReminderStatus
    data class Failed(val reason: String) : AiReminderStatus
}

data class AiReminderState(
    val enabled: Boolean,
    val reminderConsentGranted: Boolean,
    val canEnable: Boolean = false,
    val status: AiReminderStatus,
    val nextReminderAt: Instant? = null,
    val sourceSessionId: String? = null,
    val lastErrorCode: String? = null
)

data class AiReminderRecord(
    val reminderId: String,
    val occurrenceKey: String,
    val sourceSessionId: String,
    val scheduledAt: Instant,
    val timezone: ZoneId,
    val status: String,
    val createdAt: Instant,
    val generatedAt: Instant?,
    val deliveredAt: Instant?,
    val modelVersion: String?,
    val policyVersion: String,
    val payloadKey: String?,
    val lastErrorCode: String?
)

/** Metadata and encrypted-payload operations are kept atomic at this boundary. */
@Suppress("TooManyFunctions")
interface AiReminderRepository {
    fun observeLatest(): Flow<AiReminderRecord?>
    suspend fun getById(reminderId: String): AiReminderRecord?
    suspend fun getBySourceSessionId(sourceSessionId: String): AiReminderRecord?
    suspend fun getPending(): List<AiReminderRecord>
    suspend fun getUndelivered(): List<AiReminderRecord>
    suspend fun getLatestDeliveredSince(since: Instant): AiReminderRecord?
    suspend fun insertIfAbsent(record: AiReminderRecord): Boolean
    suspend fun markStatus(reminderId: String, status: String, errorCode: String? = null)
    suspend fun markGenerated(reminderId: String, generatedAt: Instant, modelVersion: String?)
    suspend fun markDelivered(reminderId: String, deliveredAt: Instant)
    suspend fun deletePendingForSource(sourceSessionId: String)
    suspend fun deletePending()
    suspend fun deleteAll()
    suspend fun readPayload(reminderId: String): ReminderPayload?
    suspend fun writePayload(reminderId: String, payload: ReminderPayload)
    suspend fun deletePayload(reminderId: String)
}

interface AiReminderScheduler {
    fun enqueuePlanning(sourceSessionId: String? = null)
    fun enqueueDelivery(reminderId: String, scheduledAt: Instant)
    fun cancelAll()
    suspend fun reconcile(now: Instant = Instant.now())
}

interface ReminderSchedulePlanner {
    suspend fun plan(now: Instant, context: ReminderGroundingContext): ReminderScheduleResult
}

sealed interface ReminderScheduleResult {
    data class Planned(val decision: ReminderScheduleDecision) : ReminderScheduleResult
    data class ModelUnavailable(val reason: String) : ReminderScheduleResult
    data class InvalidOutput(val reason: String) : ReminderScheduleResult
    data class Failed(val reason: String, val retryable: Boolean) : ReminderScheduleResult
}

interface ReminderContentGenerator {
    suspend fun generate(now: Instant, context: ReminderGroundingContext, displayName: String?): ReminderContentResult
}

sealed interface ReminderContentResult {
    data class Generated(val content: ReminderContent) : ReminderContentResult
    data class ModelUnavailable(val reason: String) : ReminderContentResult
    data class InvalidOutput(val reason: String) : ReminderContentResult
    data class Failed(val reason: String, val retryable: Boolean) : ReminderContentResult
}

interface ReminderNotificationPublisher {
    fun isPermissionGranted(): Boolean
    fun publish(reminderId: String, content: ReminderContent): ReminderPublishResult
    fun cancel(reminderId: String)
}

sealed interface ReminderPublishResult {
    data object Published : ReminderPublishResult
    data object PermissionDenied : ReminderPublishResult
    data class Failed(val reason: String, val retryable: Boolean) : ReminderPublishResult
}

interface AiReminderSecretStore {
    val displayName: StateFlow<String?>
    fun saveDisplayName(rawName: String?): String?
    fun readPayload(reminderId: String): ReminderPayload?
    fun writePayload(reminderId: String, payload: ReminderPayload)
    fun deletePayload(reminderId: String)
    fun clear()
    fun exportJson(): String
}

interface AiReminderSettings {
    val enabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)
}

interface AiReminderCoordinator {
    val state: StateFlow<AiReminderState>
    fun setEnabled(enabled: Boolean)
    fun setReminderConsent(granted: Boolean)
    fun saveDisplayName(rawName: String?)
    fun retry()
    fun pause()
    fun onSessionFinalized(sessionId: String)
    fun markOpened(reminderId: String)
    fun reconcile()
}

object ReminderRelativeDate {
    fun describe(sessionEndedAt: Instant, now: Instant, zoneId: ZoneId): String {
        val sessionDate = sessionEndedAt.atZone(zoneId).toLocalDate()
        val today = now.atZone(zoneId).toLocalDate()
        return when (java.time.temporal.ChronoUnit.DAYS.between(sessionDate, today)) {
            0L -> "hari_ini"
            1L -> "kemarin"
            else -> "beberapa_hari_lalu"
        }
    }
}

object ReminderNamePolicy {
    const val MAX_CODE_POINTS = 40

    fun normalize(rawName: String?): String? {
        val sanitized = rawName.orEmpty()
            .filterNot(Char::isISOControl)
            .replace(Regex("\\s+"), " ")
            .trim()
        return when {
            sanitized.isBlank() -> null
            sanitized.codePointCount(0, sanitized.length) <= MAX_CODE_POINTS -> sanitized
            else -> sanitized.substring(0, sanitized.offsetByCodePoints(0, MAX_CODE_POINTS)).trimEnd()
        }
    }
}

object ReminderSchedulePolicy {
    fun validate(decision: ReminderScheduleDecision, now: Instant): String? = when {
        decision.sourceSessionId.isBlank() -> "SOURCE_SESSION_MISSING"
        !decision.nextReminderAt.isAfter(now) -> "SCHEDULE_NOT_IN_FUTURE"
        decision.nextReminderAt.isAfter(
            now.plus(java.time.Duration.ofHours(AI_REMINDER_MAX_HORIZON_HOURS))
        ) -> "SCHEDULE_OUTSIDE_48_HOUR_HORIZON"
        else -> null
    }

    fun occurrenceKey(sourceSessionId: String, nextReminderAt: Instant): String = "$sourceSessionId:${nextReminderAt.toEpochMilli()}"
}
