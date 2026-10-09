package com.stressdetector.mobile.domain.reminder

import com.stressdetector.mobile.data.db.AiReminderDao
import com.stressdetector.mobile.data.db.AiReminderEntity
import com.stressdetector.mobile.data.db.ConsentDao
import com.stressdetector.mobile.data.db.UserConsentEntity
import com.stressdetector.mobile.data.reminder.ReminderRecordStatus
import com.stressdetector.mobile.data.reminder.RoomAiReminderRepository
import com.stressdetector.mobile.domain.stress.InMemoryStressHistoryContextSettings
import com.stressdetector.mobile.domain.stress.StressHistoryContextEntry
import com.stressdetector.mobile.domain.stress.StressHistoryContextProvider
import com.stressdetector.mobile.domain.stress.StressHistoryContextSnapshot
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class AiReminderPublicationTest {
    @Test
    fun deletionDuringCooldownCheckPreventsPublicationAndLateWrites() = runTest {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.dao.beforeCooldownCheck = {
            entered.complete(Unit)
            release.await()
        }
        val delivery = async { fixture.publish() }
        entered.await()
        fixture.repository.deletePendingForSource(fixture.record.sourceSessionId)
        release.complete(Unit)

        assertEquals(ReminderPublishResult.Rejected("REMINDER_INACTIVE"), delivery.await())
        fixture.attemptLateWrites()
        fixture.assertNoPublication()
        assertNull(fixture.dao.record)
        assertNull(fixture.secrets.readPayload(fixture.record.reminderId))
    }

    @Test
    fun cancellationDuringEligibilityCheckCannotBeRescheduledOrDelivered() = runTest {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.beforeHistoryCheck = {
            entered.complete(Unit)
            release.await()
        }
        val delivery = async { fixture.publish() }
        entered.await()
        fixture.repository.markStatus(fixture.record.reminderId, ReminderRecordStatus.DISABLED)
        fixture.repository.deletePayload(fixture.record.reminderId)
        release.complete(Unit)

        assertEquals(ReminderPublishResult.Rejected("REMINDER_INACTIVE"), delivery.await())
        fixture.attemptLateWrites()
        fixture.assertNoPublication()
        assertEquals(ReminderRecordStatus.DISABLED, fixture.dao.record?.status)
        assertNull(fixture.secrets.readPayload(fixture.record.reminderId))
    }

    @Test
    fun pendingAndFullDeletionDuringEligibilityCheckPreventPublication() = runTest {
        for (deleteAll in listOf(false, true)) {
            val fixture = fixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.beforeHistoryCheck = {
                entered.complete(Unit)
                release.await()
            }
            val delivery = async { fixture.publish() }
            entered.await()
            if (deleteAll) fixture.repository.deleteAll() else fixture.repository.deletePending()
            release.complete(Unit)

            assertEquals(ReminderPublishResult.Rejected("REMINDER_INACTIVE"), delivery.await())
            fixture.attemptLateWrites()
            fixture.assertNoPublication()
            assertNull(fixture.dao.record)
            assertNull(fixture.secrets.readPayload(fixture.record.reminderId))
        }
    }

    @Test
    fun pauseAndHistoryOptOutDuringEligibilityCheckAreRecheckedAtPublication() = runTest {
        for (pause in listOf(true, false)) {
            val fixture = fixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.beforeHistoryCheck = {
                entered.complete(Unit)
                release.await()
            }
            val delivery = async { fixture.publish() }
            entered.await()
            if (pause) fixture.settings.setEnabled(false) else fixture.historySettings.setEnabled(false)
            release.complete(Unit)

            val code = if (pause) "REMINDER_DISABLED" else "STRESS_HISTORY_CONSENT_REQUIRED"
            assertEquals(ReminderPublishResult.Rejected(code), delivery.await())
            fixture.assertNoPublication()
        }
    }

    @Test
    fun publicationWinningFinalGatePersistsReceiptBeforePendingDeletion() = runTest {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.dao.beforeRecordReadReturns = {
            entered.complete(Unit)
            release.await()
        }
        val delivery = async { fixture.publish() }
        entered.await()
        val deletion = async { fixture.repository.deletePending() }
        runCurrent()
        assertFalse(deletion.isCompleted)
        release.complete(Unit)

        assertEquals(ReminderPublishResult.Published, delivery.await())
        deletion.await()
        assertEquals(listOf("publish", "receipt", "deletePending"), fixture.events)
        assertEquals(ReminderRecordStatus.DELIVERED, fixture.dao.record?.status)
        assertTrue(fixture.dao.record?.deliveredAtMillis != null)
        assertTrue(fixture.secrets.readPayload(fixture.record.reminderId) != null)
    }

    @Test
    fun cancelledWorkerCannotPublishAfterNonCooperativeFinalRead() = runTest {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.dao.beforeRecordReadReturns = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val delivery = async { fixture.publish() }
        entered.await()
        delivery.cancel()
        release.complete(Unit)
        delivery.join()

        assertTrue(delivery.isCancelled)
        fixture.assertNoPublication()
    }

    @Test
    fun cancellationAfterNotifyCannotDropReceiptOrResurrectDeliveredRecord() = runTest {
        val fixture = fixture()
        lateinit var delivery: Deferred<ReminderPublishResult>
        fixture.afterPublication = { delivery.cancel() }
        fixture.dao.beforeReceipt = { yield() }
        delivery = async { fixture.publish() }
        delivery.join()
        fixture.attemptLateWrites()

        assertEquals(listOf("publish", "receipt"), fixture.events)
        assertEquals(ReminderRecordStatus.DELIVERED, fixture.dao.record?.status)
        assertTrue(fixture.dao.record?.deliveredAtMillis != null)
    }

    @Test
    fun deniedPublicationDoesNotRecordSuccessfulDelivery() = runTest {
        val fixture = fixture()
        fixture.publishResult = ReminderPublishResult.PermissionDenied

        assertEquals(ReminderPublishResult.PermissionDenied, fixture.publish())
        assertNull(fixture.dao.record?.deliveredAtMillis)
        assertEquals(ReminderRecordStatus.GENERATED, fixture.dao.record?.status)
    }

    private suspend fun fixture(): Fixture {
        val consentDao = Mockito.mock(ConsentDao::class.java)
        listOf(AI_REMINDER_CONSENT_TYPE, AI_COUNSELING_CONSENT_TYPE).forEach { type ->
            Mockito.`when`(consentDao.getConsent(type)).thenReturn(UserConsentEntity(type, 1L, true))
        }
        return Fixture(consentDao)
    }

    private class Fixture(consentDao: ConsentDao) {
        val record = AiReminderRecord(
            reminderId = "reminder-test",
            occurrenceKey = "source-test:1",
            sourceSessionId = "source-test",
            scheduledAt = Instant.ofEpochMilli(1L),
            timezone = ZoneId.of("UTC"),
            status = ReminderRecordStatus.GENERATED,
            createdAt = Instant.EPOCH,
            generatedAt = Instant.ofEpochMilli(1L),
            deliveredAt = null,
            modelVersion = "test-model",
            policyVersion = AI_REMINDER_POLICY_VERSION,
            payloadKey = "reminder-test",
            lastErrorCode = null
        )
        val events = mutableListOf<String>()
        val dao = ControllableReminderDao(
            AiReminderEntity(
                record.reminderId, record.occurrenceKey, record.sourceSessionId,
                record.scheduledAt.toEpochMilli(), record.timezone.id, record.status,
                record.createdAt.toEpochMilli(), record.generatedAt?.toEpochMilli(),
                policyVersion = record.policyVersion, payloadKey = record.payloadKey
            ),
            events
        )
        val secrets = TestSecretStore()
        val repository = RoomAiReminderRepository(dao, secrets)
        val settings = object : AiReminderSettings {
            override val enabled = MutableStateFlow(true)
            override fun setEnabled(enabled: Boolean) {
                this.enabled.value = enabled
            }
        }
        val historySettings = InMemoryStressHistoryContextSettings(true)
        var beforeHistoryCheck: suspend () -> Unit = {}
        var afterPublication: () -> Unit = {}
        var publishResult: ReminderPublishResult = ReminderPublishResult.Published
        var publishCalls = 0
        private val history = object : StressHistoryContextProvider by Mockito.mock(StressHistoryContextProvider::class.java) {
            override suspend fun snapshot(): StressHistoryContextSnapshot {
                beforeHistoryCheck()
                return StressHistoryContextSnapshot(
                    "test-contract",
                    301_000L,
                    listOf(StressHistoryContextEntry(record.sourceSessionId, 301_000L, 300_000L, "Normal", 0.2f, 0.8f, "good", true, null)),
                    "test eligible source"
                )
            }
        }
        private val publisher = object : ReminderNotificationPublisher {
            override fun isPermissionGranted(): Boolean = true
            override fun publish(reminderId: String, content: ReminderContent): ReminderPublishResult {
                publishCalls++
                if (publishResult == ReminderPublishResult.Published) {
                    events.add("publish")
                    afterPublication()
                }
                return publishResult
            }

            override fun cancel(reminderId: String) = Unit
        }
        private val publication = AiReminderPublication(
            repository,
            publisher,
            AiReminderEligibility(consentDao, history, historySettings, settings)
        )
        private val content =
            ReminderContent("Bagaimana kabarmu hari ini?", null, record.sourceSessionId, "test-model", AI_REMINDER_POLICY_VERSION)

        init {
            secrets.writePayload(record.reminderId, ReminderPayload(content.message, null, record.sourceSessionId))
        }

        suspend fun publish(): ReminderPublishResult = publication.publish(record, content)

        suspend fun attemptLateWrites() {
            val reminderId = record.reminderId
            repository.markStatus(reminderId, ReminderRecordStatus.SCHEDULED, "LATE_RETRY")
            repository.markGenerated(reminderId, Instant.now(), "late-model")
            repository.markDelivered(reminderId, Instant.now())
            repository.writePayload(reminderId, ReminderPayload("late payload", null, record.sourceSessionId))
        }

        fun assertNoPublication() {
            assertEquals(0, publishCalls)
            assertNull(dao.record?.deliveredAtMillis)
        }
    }

    private class ControllableReminderDao(var record: AiReminderEntity?, private val events: MutableList<String>) :
        AiReminderDao by Mockito.mock(AiReminderDao::class.java) {
        var beforeCooldownCheck: suspend () -> Unit = {}
        var beforeRecordReadReturns: suspend () -> Unit = {}
        var beforeReceipt: suspend () -> Unit = {}

        override suspend fun getById(reminderId: String): AiReminderEntity? {
            val snapshot = record?.takeIf { it.reminderId == reminderId }
            beforeRecordReadReturns()
            return snapshot
        }

        override suspend fun latestDeliveredSince(sinceMillis: Long): AiReminderEntity? {
            val snapshot = record?.takeIf { (it.deliveredAtMillis ?: Long.MIN_VALUE) >= sinceMillis }
            beforeCooldownCheck()
            return snapshot
        }

        override suspend fun getUndelivered(): List<AiReminderEntity> = listOfNotNull(record?.takeIf { it.deliveredAtMillis == null })

        // Deliberately permissive updates prove repository guards protect terminal records.
        override suspend fun updateStatus(reminderId: String, status: String, errorCode: String?): Int {
            record = record?.copy(status = status, lastErrorCode = errorCode)
            return if (record == null) 0 else 1
        }

        override suspend fun markGenerated(reminderId: String, status: String, generatedAtMillis: Long, modelVersion: String?): Int {
            val active =
                record?.status in setOf(ReminderRecordStatus.SCHEDULED, ReminderRecordStatus.GENERATING, ReminderRecordStatus.GENERATED)
            if (active) record = record?.copy(status = status, generatedAtMillis = generatedAtMillis, modelVersion = modelVersion)
            return if (active) 1 else 0
        }

        override suspend fun markDelivered(reminderId: String, status: String, deliveredAtMillis: Long): Int {
            beforeReceipt()
            record = record?.copy(status = status, deliveredAtMillis = deliveredAtMillis)
            events.add("receipt")
            return if (record == null) 0 else 1
        }

        override suspend fun deletePendingForSource(sourceSessionId: String): Int {
            val delete = record?.let { it.sourceSessionId == sourceSessionId && it.deliveredAtMillis == null } == true
            if (delete) record = null
            return if (delete) 1 else 0
        }

        override suspend fun deletePending(): Int {
            events.add("deletePending")
            val delete = record?.deliveredAtMillis == null
            if (delete) record = null
            return if (delete) 1 else 0
        }

        override suspend fun deleteAll() {
            record = null
        }
    }

    private class TestSecretStore : AiReminderSecretStore {
        override val displayName = MutableStateFlow<String?>(null)
        private val payloads = mutableMapOf<String, ReminderPayload>()
        override fun saveDisplayName(rawName: String?): String? = rawName.also { displayName.value = it }
        override fun readPayload(reminderId: String): ReminderPayload? = payloads[reminderId]
        override fun writePayload(reminderId: String, payload: ReminderPayload) {
            payloads[reminderId] = payload
        }

        override fun deletePayload(reminderId: String) {
            payloads.remove(reminderId)
        }

        override fun clear() {
            payloads.clear()
        }

        override fun exportJson(): String = error("Not used in publication tests")
    }
}
