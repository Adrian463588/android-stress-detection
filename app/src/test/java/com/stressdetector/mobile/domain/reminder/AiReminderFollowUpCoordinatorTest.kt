package com.stressdetector.mobile.domain.reminder

import com.stressdetector.mobile.data.db.ConsentDao
import com.stressdetector.mobile.data.reminder.ReminderRecordStatus
import com.stressdetector.mobile.domain.engine.FollowUpReminderKind
import com.stressdetector.mobile.domain.stress.StressHistoryContextSettings
import com.stressdetector.mobile.ui.viewmodels.AiReminderViewModel
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class AiReminderFollowUpCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `schedule edit and cancel update reactive state even when passive auto reminder is disabled`() = runBlocking {
        val repo = InMemoryReminderRepository()
        val scheduler = RecordingReminderScheduler()
        val coordinator = createCoordinator(repo, scheduler)

        val scheduled = coordinator.scheduleFollowUpReminder(
            kind = FollowUpReminderKind.HOMEWORK_PRACTICE,
            delayHours = 24L,
            sourceSessionId = "profile-manual",
            customNote = "Latihan ABCDE"
        )
        assertTrue(scheduled.isSuccess)
        val firstRecord = scheduled.getOrThrow()
        assertEquals(AI_MANUAL_FOLLOWUP_POLICY_VERSION, firstRecord.policyVersion)
        assertEquals(ReminderRecordStatus.SCHEDULED, firstRecord.status)
        assertEquals(1, scheduler.enqueuedDeliveries.size)
        assertTrue(repo.payloads[firstRecord.reminderId]?.message?.contains("Latihan ABCDE") == true)

        val activeState = waitUntilState(coordinator) { it.isManualFollowUp }
        assertTrue(activeState.status is AiReminderStatus.Scheduled)
        assertEquals(FollowUpReminderKind.HOMEWORK_PRACTICE, activeState.activeFollowUpKind)
        assertEquals(24L, activeState.activeFollowUpDelayHours)
        assertEquals("Latihan ABCDE", activeState.activeFollowUpNote)

        val edited = coordinator.scheduleFollowUpReminder(
            kind = FollowUpReminderKind.COUNSELING_SESSION,
            delayHours = 48L,
            sourceSessionId = "profile-manual",
            customNote = "Sesi lanjutan"
        )
        assertTrue(edited.isSuccess)
        assertEquals(1, repo.records.size)

        val cancelled = coordinator.cancelPendingReminders()
        assertTrue(cancelled.isSuccess)
        assertTrue(scheduler.cancelled)
        assertTrue(repo.records.isEmpty())
        val clearedState = waitUntilState(coordinator) { !it.isManualFollowUp }
        assertFalse(clearedState.isManualFollowUp)
        assertNull(clearedState.activeFollowUpKind)
    }

    @Test
    fun `viewmodel schedule validates delay rejects failure without false success and cancels cleanly`() = runTest {
        val repo = InMemoryReminderRepository()
        val scheduler = RecordingReminderScheduler()
        val coordinator = createCoordinator(repo, scheduler)
        val secretStore = mock(AiReminderSecretStore::class.java)
        `when`(secretStore.displayName).thenReturn(MutableStateFlow("Klien A"))
        val vm = AiReminderViewModel(coordinator, secretStore, initialNotificationPermissionGranted = true)
        advanceUntilIdle()

        vm.scheduleFollowUpReminder(FollowUpReminderKind.CHECK_IN, delayHours = 0L, note = "Invalid")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.actionError?.contains("1 sampai 48 jam") == true)
        assertNull(vm.uiState.value.actionFeedback)

        vm.scheduleFollowUpReminder(FollowUpReminderKind.CHECK_IN, delayHours = 12L, note = "Refleksi sore")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.actionFeedback?.contains("Check-in Refleksi") == true)
        assertNull(vm.uiState.value.actionError)

        repo.failInsert = true
        vm.scheduleFollowUpReminder(FollowUpReminderKind.PROGRESS_REVIEW, delayHours = 24L, note = "Gagal")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.actionError?.contains("Gagal menyimpan") == true)
        assertNull(vm.uiState.value.actionFeedback)

        repo.failInsert = false
        vm.cancelPendingReminders()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.actionFeedback?.contains("dibatalkan") == true)
    }

    private suspend fun waitUntilState(
        coordinator: DefaultAiReminderCoordinator,
        predicate: (AiReminderState) -> Boolean
    ): AiReminderState {
        repeat(50) {
            val current = coordinator.state.value
            if (predicate(current)) return current
            kotlinx.coroutines.delay(20L)
        }
        return coordinator.state.value
    }

    private fun createCoordinator(repo: InMemoryReminderRepository, scheduler: RecordingReminderScheduler): DefaultAiReminderCoordinator {
        val settings = object : AiReminderSettings {
            private val flow = MutableStateFlow(false)
            override val enabled: StateFlow<Boolean> = flow
            override fun setEnabled(enabled: Boolean) {
                flow.value = enabled
            }
        }
        val secretStore = mock(AiReminderSecretStore::class.java)
        `when`(secretStore.displayName).thenReturn(MutableStateFlow("Klien A"))
        val consentDao = mock(ConsentDao::class.java)
        `when`(consentDao.observeConsent(AI_REMINDER_CONSENT_TYPE)).thenReturn(flowOf(null))
        `when`(consentDao.observeConsent(AI_COUNSELING_CONSENT_TYPE)).thenReturn(flowOf(null))
        val stressSettings = object : StressHistoryContextSettings {
            private val flow = MutableStateFlow(false)
            override val enabled: StateFlow<Boolean> = flow
            override fun setEnabled(enabled: Boolean) {
                flow.value = enabled
            }
        }
        val fixedNow = Instant.parse("2026-11-16T03:00:00Z")
        return DefaultAiReminderCoordinator(
            AiReminderCoordinatorDependencies(
                repository = repo,
                scheduler = scheduler,
                settings = settings,
                secretStore = secretStore,
                consentDao = consentDao,
                stressHistorySettings = stressSettings,
                notificationPermissionGranted = { true },
                clock = { fixedNow }
            )
        )
    }

    private class RecordingReminderScheduler : AiReminderScheduler {
        val enqueuedDeliveries = mutableListOf<Pair<String, Instant>>()
        var cancelled = false
        override fun enqueuePlanning(sourceSessionId: String?) = Unit
        override fun enqueueDelivery(reminderId: String, scheduledAt: Instant) {
            enqueuedDeliveries += reminderId to scheduledAt
        }

        override fun cancelAll() {
            cancelled = true
        }

        override suspend fun reconcile(now: Instant) = Unit
    }

    private class InMemoryReminderRepository : AiReminderRepository {
        val records = linkedMapOf<String, AiReminderRecord>()
        val payloads = linkedMapOf<String, ReminderPayload>()
        private val latestFlow = MutableStateFlow<AiReminderRecord?>(null)
        var failInsert = false

        override fun observeLatest(): Flow<AiReminderRecord?> = latestFlow.asStateFlow()
        override suspend fun getById(reminderId: String): AiReminderRecord? = records[reminderId]
        override suspend fun getBySourceSessionId(sourceSessionId: String): AiReminderRecord? =
            records.values.firstOrNull { it.sourceSessionId == sourceSessionId }
        override suspend fun getPending(): List<AiReminderRecord> = records.values.toList()
        override suspend fun getUndelivered(): List<AiReminderRecord> = records.values.toList()
        override suspend fun getLatestDeliveredSince(since: Instant): AiReminderRecord? = null
        override suspend fun insertIfAbsent(record: AiReminderRecord): Boolean {
            if (failInsert || records.containsKey(record.reminderId)) return false
            records[record.reminderId] = record
            latestFlow.value = record
            return true
        }

        override suspend fun markStatus(reminderId: String, status: String, errorCode: String?) = Unit
        override suspend fun markGenerated(reminderId: String, generatedAt: Instant, modelVersion: String?) = Unit
        override suspend fun markDelivered(reminderId: String, deliveredAt: Instant) = Unit
        override suspend fun publishIfActive(reminderId: String, publish: () -> ReminderPublishResult): ReminderPublishResult = publish()
        override suspend fun deletePendingForSource(sourceSessionId: String) {
            records.entries.removeIf { it.value.sourceSessionId == sourceSessionId }
            latestFlow.value = records.values.lastOrNull()
        }

        override suspend fun deletePending() {
            records.clear()
            latestFlow.value = null
        }

        override suspend fun deleteAll() = deletePending()
        override suspend fun readPayload(reminderId: String): ReminderPayload? = payloads[reminderId]
        override suspend fun writePayload(reminderId: String, payload: ReminderPayload) {
            payloads[reminderId] = payload
        }

        override suspend fun deletePayload(reminderId: String) {
            payloads.remove(reminderId)
        }
    }
}
