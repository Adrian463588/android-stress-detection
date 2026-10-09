package com.stressdetector.mobile.domain.reminder

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stressdetector.mobile.data.reminder.ReminderRecordStatus
import com.stressdetector.mobile.di.AppLocator
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val WORK_BACKOFF_MINUTES = 15L
private const val NO_INITIAL_DELAY_MILLIS = 0L
private val ACTIVE_RECORD_STATUSES = setOf(
    ReminderRecordStatus.SCHEDULED,
    ReminderRecordStatus.GENERATING,
    ReminderRecordStatus.GENERATED
)

class WorkManagerAiReminderScheduler(context: Context) : AiReminderScheduler {
    private val applicationContext = context.applicationContext
    private val workManager = WorkManager.getInstance(applicationContext)

    override fun enqueuePlanning(sourceSessionId: String?) {
        val request = OneTimeWorkRequestBuilder<AiReminderPlanningWorker>()
            .setInputData(
                androidx.work.Data.Builder().apply {
                    sourceSessionId?.takeIf(String::isNotBlank)?.let {
                        putString(AiReminderPlanningWorker.DATA_SOURCE_SESSION_ID, it)
                    }
                }.build()
            )
            .addTag(AI_REMINDER_WORK_TAG)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WORK_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        val key = sourceSessionId?.takeIf(String::isNotBlank) ?: "latest"
        workManager.enqueueUniqueWork(
            "$AI_REMINDER_PLAN_WORK_PREFIX$key",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    override fun enqueueDelivery(reminderId: String, scheduledAt: Instant) {
        if (reminderId.isBlank()) return
        val delayMillis = Duration.between(Instant.now(), scheduledAt).toMillis()
            .coerceAtLeast(NO_INITIAL_DELAY_MILLIS)
        val request = OneTimeWorkRequestBuilder<AiReminderDeliveryWorker>()
            .setInputData(workDataOf(AiReminderDeliveryWorker.DATA_REMINDER_ID to reminderId))
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .addTag(AI_REMINDER_WORK_TAG)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WORK_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(
            "$AI_REMINDER_DELIVERY_WORK_PREFIX$reminderId",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    override fun cancelAll() {
        workManager.cancelAllWorkByTag(AI_REMINDER_WORK_TAG)
    }

    override suspend fun reconcile(now: Instant) {
        val repository = AppLocator.aiReminderRepository(applicationContext)
        val settings = AppLocator.aiReminderSettings(applicationContext)
        if (!settings.enabled.value) {
            cancelAll()
            return
        }
        val pending = repository.getPending()
        pending.forEach { record ->
            if (record.scheduledAt.isAfter(now)) {
                enqueueDelivery(record.reminderId, record.scheduledAt)
            } else {
                workManager.cancelUniqueWork("$AI_REMINDER_DELIVERY_WORK_PREFIX${record.reminderId}")
                repository.deletePendingForSource(record.sourceSessionId)
                enqueuePlanning(record.sourceSessionId)
            }
        }
        if (pending.isEmpty()) enqueuePlanning()
    }

    companion object {
        const val AI_REMINDER_WORK_TAG = "ai_reminder_work"
        const val AI_REMINDER_PLAN_WORK_PREFIX = "ai_reminder_plan_"
        const val AI_REMINDER_DELIVERY_WORK_PREFIX = "ai_reminder_delivery_"
    }
}

class AiReminderPlanningWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val repository = AppLocator.aiReminderRepository(applicationContext)
        val eligibility = AppLocator.aiReminderEligibility(applicationContext)
        val sourceSessionId = inputData.getString(DATA_SOURCE_SESSION_ID)
        val now = Instant.now()
        return when (val eligible = eligibility.find(sourceSessionId)) {
            is ReminderEligibilityResult.Rejected -> Result.success()
            is ReminderEligibilityResult.Eligible -> plan(repository, eligible.context, now)
        }
    }

    private suspend fun plan(repository: AiReminderRepository, context: ReminderGroundingContext, now: Instant): Result {
        val existing = repository.getBySourceSessionId(context.sourceSessionId)
        return existing?.let { record ->
            if (record.status in ACTIVE_RECORD_STATUSES) {
                AppLocator.aiReminderScheduler(applicationContext)
                    .enqueueDelivery(record.reminderId, record.scheduledAt)
            }
            Result.success()
        } ?: if (repository.getLatestDeliveredSince(now.minus(Duration.ofHours(AI_REMINDER_COOLDOWN_HOURS))) != null) {
            Result.success()
        } else {
            when (val planned = AppLocator.aiReminderSchedulePlanner(applicationContext).plan(now, context)) {
                is ReminderScheduleResult.Planned -> persistPlan(repository, planned.decision, now)
                is ReminderScheduleResult.ModelUnavailable -> persistFailure(
                    repository,
                    context,
                    now,
                    "MODEL_UNAVAILABLE"
                )
                is ReminderScheduleResult.InvalidOutput -> persistFailure(
                    repository,
                    context,
                    now,
                    "MODEL_OUTPUT_INVALID_JSON"
                )
                is ReminderScheduleResult.Failed -> if (planned.retryable) {
                    Result.retry()
                } else {
                    persistFailure(repository, context, now, "MODEL_GENERATION_FAILED")
                }
            }
        }
    }

    private suspend fun persistPlan(repository: AiReminderRepository, decision: ReminderScheduleDecision, now: Instant): Result {
        val reminderId = UUID.randomUUID().toString()
        val record = AiReminderRecord(
            reminderId = reminderId,
            occurrenceKey = ReminderSchedulePolicy.occurrenceKey(decision.sourceSessionId, decision.nextReminderAt),
            sourceSessionId = decision.sourceSessionId,
            scheduledAt = decision.nextReminderAt,
            timezone = decision.timezone,
            status = ReminderRecordStatus.SCHEDULED,
            createdAt = now,
            generatedAt = null,
            deliveredAt = null,
            modelVersion = decision.modelVersion,
            policyVersion = AI_REMINDER_POLICY_VERSION,
            payloadKey = reminderId,
            lastErrorCode = null
        )
        val inserted = repository.insertIfAbsent(record)
        val active = if (inserted) record else repository.getBySourceSessionId(decision.sourceSessionId)
        active?.let {
            AppLocator.aiReminderScheduler(applicationContext).enqueueDelivery(it.reminderId, it.scheduledAt)
        }
        return Result.success()
    }

    private suspend fun persistFailure(
        repository: AiReminderRepository,
        context: ReminderGroundingContext,
        now: Instant,
        code: String
    ): Result {
        val existing = repository.getBySourceSessionId(context.sourceSessionId)
        if (existing != null) {
            repository.markStatus(existing.reminderId, ReminderRecordStatus.FAILED, code)
            return Result.success()
        }
        repository.insertIfAbsent(
            AiReminderRecord(
                reminderId = UUID.randomUUID().toString(),
                occurrenceKey = "${context.sourceSessionId}:failure:$code",
                sourceSessionId = context.sourceSessionId,
                scheduledAt = now,
                timezone = java.time.ZoneId.systemDefault(),
                status = ReminderRecordStatus.FAILED,
                createdAt = now,
                generatedAt = null,
                deliveredAt = null,
                modelVersion = null,
                policyVersion = AI_REMINDER_POLICY_VERSION,
                payloadKey = null,
                lastErrorCode = code
            )
        )
        return Result.success()
    }

    companion object {
        const val DATA_SOURCE_SESSION_ID = "source_session_id"
    }
}

class AiReminderDeliveryWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val reminderId = inputData.getString(DATA_REMINDER_ID)
        return if (reminderId == null) Result.failure() else process(reminderId)
    }

    private suspend fun process(reminderId: String): Result {
        val repository = AppLocator.aiReminderRepository(applicationContext)
        val record = repository.getById(reminderId)
        return record?.let { persisted ->
            when {
                persisted.status !in ACTIVE_RECORD_STATUSES -> Result.success()
                persisted.scheduledAt.isAfter(Instant.now()) -> Result.retry()
                else -> deliverIfAllowed(repository, persisted)
            }
        } ?: Result.success()
    }

    private suspend fun deliverIfAllowed(repository: AiReminderRepository, record: AiReminderRecord): Result {
        val publisher = AppLocator.aiReminderNotificationPublisher(applicationContext)
        return when {
            !publisher.isPermissionGranted() -> {
                repository.markStatus(record.reminderId, ReminderRecordStatus.FAILED, "NOTIFICATION_PERMISSION_DENIED")
                repository.deletePayload(record.reminderId)
                Result.success()
            }
            repository.getLatestDeliveredSince(
                Instant.now().minus(Duration.ofHours(AI_REMINDER_COOLDOWN_HOURS))
            ) != null -> {
                repository.markStatus(record.reminderId, ReminderRecordStatus.FAILED, "COOLDOWN_ACTIVE")
                repository.deletePayload(record.reminderId)
                Result.success()
            }
            else -> generateAndDeliver(repository, publisher, record)
        }
    }

    private suspend fun generateAndDeliver(
        repository: AiReminderRepository,
        publisher: ReminderNotificationPublisher,
        record: AiReminderRecord
    ): Result {
        val eligible = AppLocator.aiReminderEligibility(applicationContext).find(record.sourceSessionId)
        val context = (eligible as? ReminderEligibilityResult.Eligible)?.context
        return context?.let { grounding ->
            repository.markStatus(record.reminderId, ReminderRecordStatus.GENERATING)
            val displayName = AppLocator.aiReminderSecretStore(applicationContext).displayName.value
            val generated = runCatching {
                AppLocator.aiReminderContentGenerator(applicationContext)
                    .generate(Instant.now(), grounding, displayName)
            }
            val generationFailure = generated.exceptionOrNull()
            if (generationFailure is CancellationException) {
                withContext(NonCancellable) {
                    repository.markStatus(
                        record.reminderId,
                        ReminderRecordStatus.SCHEDULED,
                        "REMINDER_GENERATION_CANCELLED"
                    )
                }
                throw generationFailure
            }
            generated.fold(
                onSuccess = { contentResult ->
                    when (contentResult) {
                        is ReminderContentResult.Generated -> deliver(repository, publisher, record, contentResult.content)
                        is ReminderContentResult.ModelUnavailable ->
                            fail(repository, record.reminderId, "MODEL_UNAVAILABLE")
                        is ReminderContentResult.InvalidOutput ->
                            fail(repository, record.reminderId, "MODEL_OUTPUT_INVALID_JSON")
                        is ReminderContentResult.Failed -> if (contentResult.retryable) {
                            repository.markStatus(
                                record.reminderId,
                                ReminderRecordStatus.SCHEDULED,
                                "MODEL_GENERATION_RETRY_PENDING"
                            )
                            Result.retry()
                        } else {
                            fail(repository, record.reminderId, "MODEL_GENERATION_FAILED")
                        }
                    }
                },
                onFailure = {
                    repository.markStatus(record.reminderId, ReminderRecordStatus.SCHEDULED, "REMINDER_WORKER_RETRY_PENDING")
                    Result.retry()
                }
            )
        } ?: run {
            repository.markStatus(record.reminderId, ReminderRecordStatus.FAILED, "NO_ELIGIBLE_SESSION")
            repository.deletePayload(record.reminderId)
            Result.success()
        }
    }

    private suspend fun deliver(
        repository: AiReminderRepository,
        publisher: ReminderNotificationPublisher,
        record: AiReminderRecord,
        content: ReminderContent
    ): Result = when {
        content.sourceSessionId != record.sourceSessionId -> fail(repository, record.reminderId, "REMINDER_SOURCE_MISMATCH")
        else -> {
            repository.writePayload(
                record.reminderId,
                ReminderPayload(content.message, content.displayNameUsed, content.sourceSessionId)
            )
            // The DAO cannot revive a record cancelled while generation was running.
            repository.markGenerated(record.reminderId, Instant.now(), content.modelVersion)
            publishValidated(repository, publisher, record, content)
        }
    }

    private suspend fun publishValidated(
        repository: AiReminderRepository,
        publisher: ReminderNotificationPublisher,
        record: AiReminderRecord,
        content: ReminderContent
    ): Result = when (
        val published = AiReminderPublication(
            repository,
            publisher,
            AppLocator.aiReminderEligibility(applicationContext)
        ).publish(record, content)
    ) {
        ReminderPublishResult.Published -> Result.success()
        is ReminderPublishResult.Rejected -> if (published.code == "REMINDER_INACTIVE") {
            repository.deletePayload(record.reminderId)
            Result.success()
        } else {
            fail(repository, record.reminderId, published.code)
        }
        ReminderPublishResult.PermissionDenied -> {
            repository.deletePayload(record.reminderId)
            repository.markStatus(record.reminderId, ReminderRecordStatus.FAILED, "NOTIFICATION_PERMISSION_DENIED")
            Result.success()
        }
        is ReminderPublishResult.Failed -> if (published.retryable) {
            repository.markStatus(record.reminderId, ReminderRecordStatus.SCHEDULED, "NOTIFICATION_RETRY_PENDING")
            Result.retry()
        } else {
            fail(repository, record.reminderId, "NOTIFICATION_PUBLISH_FAILED")
        }
    }

    private suspend fun fail(repository: AiReminderRepository, reminderId: String, code: String): Result {
        repository.deletePayload(reminderId)
        repository.markStatus(reminderId, ReminderRecordStatus.FAILED, code)
        return Result.success()
    }

    companion object {
        const val DATA_REMINDER_ID = "reminder_id"
    }
}
