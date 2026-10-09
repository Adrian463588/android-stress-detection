package com.stressdetector.mobile.data.reminder

import com.stressdetector.mobile.data.db.AiReminderDao
import com.stressdetector.mobile.data.db.AiReminderEntity
import com.stressdetector.mobile.domain.reminder.AiReminderRecord
import com.stressdetector.mobile.domain.reminder.AiReminderRepository
import com.stressdetector.mobile.domain.reminder.AiReminderSecretStore
import com.stressdetector.mobile.domain.reminder.ReminderPayload
import com.stressdetector.mobile.domain.reminder.ReminderPublishResult
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Suppress("TooManyFunctions")
class RoomAiReminderRepository(private val dao: AiReminderDao, private val secretStore: AiReminderSecretStore) : AiReminderRepository {
    // ponytail: one gate for low-volume reminders; use per-reminder gates only if contention matters.
    private val publicationMutex = Mutex()

    override fun observeLatest(): Flow<AiReminderRecord?> = dao.observeLatest(1).map { records ->
        records.firstOrNull()?.toDomain()
    }

    override suspend fun getById(reminderId: String): AiReminderRecord? = dao.getById(reminderId)?.toDomain()

    override suspend fun getBySourceSessionId(sourceSessionId: String): AiReminderRecord? =
        dao.getBySourceSessionId(sourceSessionId)?.toDomain()

    override suspend fun getPending(): List<AiReminderRecord> = dao.getPending().map(AiReminderEntity::toDomain)

    override suspend fun getUndelivered(): List<AiReminderRecord> = dao.getUndelivered().map(AiReminderEntity::toDomain)

    override suspend fun getLatestDeliveredSince(since: Instant): AiReminderRecord? =
        dao.latestDeliveredSince(since.toEpochMilli())?.toDomain()

    override suspend fun insertIfAbsent(record: AiReminderRecord): Boolean = publicationMutex.withLock {
        dao.insertIfAbsent(record.toEntity()) != 0L
    }

    override suspend fun markStatus(reminderId: String, status: String, errorCode: String?) {
        publicationMutex.withLock {
            if (dao.getById(reminderId)?.isActive() == true) dao.updateStatus(reminderId, status, errorCode)
        }
    }

    override suspend fun markGenerated(reminderId: String, generatedAt: Instant, modelVersion: String?) {
        publicationMutex.withLock {
            dao.markGenerated(
                reminderId = reminderId,
                status = ReminderRecordStatus.GENERATED,
                generatedAtMillis = generatedAt.toEpochMilli(),
                modelVersion = modelVersion
            )
        }
    }

    override suspend fun markDelivered(reminderId: String, deliveredAt: Instant) {
        publicationMutex.withLock {
            if (dao.getById(reminderId)?.isActive() == true) persistDelivered(reminderId, deliveredAt)
        }
    }

    override suspend fun publishIfActive(reminderId: String, publish: () -> ReminderPublishResult): ReminderPublishResult =
        publicationMutex.withLock {
            // AppLocator owns one repository in the app process. A mutation that wins this gate
            // invalidates the final read; publication that wins completes its receipt before deletion.
            if (dao.getById(reminderId)?.isActive() != true) {
                ReminderPublishResult.Rejected("REMINDER_INACTIVE")
            } else {
                currentCoroutineContext().ensureActive()
                val result = publish()
                if (result == ReminderPublishResult.Published) {
                    // Once notify succeeds, worker cancellation must not omit its delivery receipt.
                    withContext(NonCancellable) { persistDelivered(reminderId, Instant.now()) }
                }
                result
            }
        }

    private suspend fun persistDelivered(reminderId: String, deliveredAt: Instant) {
        dao.markDelivered(reminderId, ReminderRecordStatus.DELIVERED, deliveredAt.toEpochMilli())
    }

    override suspend fun deletePendingForSource(sourceSessionId: String) {
        publicationMutex.withLock {
            dao.getUndelivered()
                .filter { it.sourceSessionId == sourceSessionId }
                .forEach { secretStore.deletePayload(it.reminderId) }
            dao.deletePendingForSource(sourceSessionId)
        }
    }

    override suspend fun deletePending() {
        publicationMutex.withLock {
            dao.getUndelivered().forEach { entity -> secretStore.deletePayload(entity.reminderId) }
            dao.deletePending()
        }
    }

    override suspend fun deleteAll() {
        publicationMutex.withLock {
            dao.deleteAll()
            secretStore.clear()
        }
    }

    override suspend fun readPayload(reminderId: String): ReminderPayload? = secretStore.readPayload(reminderId)

    override suspend fun writePayload(reminderId: String, payload: ReminderPayload) {
        publicationMutex.withLock {
            if (dao.getById(reminderId)?.isActive() == true) secretStore.writePayload(reminderId, payload)
        }
    }

    override suspend fun deletePayload(reminderId: String) {
        publicationMutex.withLock { secretStore.deletePayload(reminderId) }
    }
}

private fun AiReminderEntity.isActive(): Boolean = status == ReminderRecordStatus.SCHEDULED ||
    status == ReminderRecordStatus.GENERATING ||
    status == ReminderRecordStatus.GENERATED

object ReminderRecordStatus {
    const val SCHEDULED = "SCHEDULED"
    const val GENERATING = "GENERATING"
    const val GENERATED = "GENERATED"
    const val DELIVERED = "DELIVERED"
    const val FAILED = "FAILED"
    const val DISABLED = "DISABLED"
}

private fun AiReminderEntity.toDomain(): AiReminderRecord = AiReminderRecord(
    reminderId = reminderId,
    occurrenceKey = occurrenceKey,
    sourceSessionId = sourceSessionId,
    scheduledAt = Instant.ofEpochMilli(scheduledAtMillis),
    timezone = ZoneId.of(zoneId),
    status = status,
    createdAt = Instant.ofEpochMilli(createdAtMillis),
    generatedAt = generatedAtMillis?.let(Instant::ofEpochMilli),
    deliveredAt = deliveredAtMillis?.let(Instant::ofEpochMilli),
    modelVersion = modelVersion,
    policyVersion = policyVersion,
    payloadKey = payloadKey,
    lastErrorCode = lastErrorCode
)

private fun AiReminderRecord.toEntity(): AiReminderEntity = AiReminderEntity(
    reminderId = reminderId,
    occurrenceKey = occurrenceKey,
    sourceSessionId = sourceSessionId,
    scheduledAtMillis = scheduledAt.toEpochMilli(),
    zoneId = timezone.id,
    status = status,
    createdAtMillis = createdAt.toEpochMilli(),
    generatedAtMillis = generatedAt?.toEpochMilli(),
    deliveredAtMillis = deliveredAt?.toEpochMilli(),
    modelVersion = modelVersion,
    policyVersion = policyVersion,
    payloadKey = payloadKey,
    lastErrorCode = lastErrorCode
)
