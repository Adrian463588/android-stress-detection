package com.stressdetector.mobile.domain.reminder

import com.stressdetector.mobile.data.db.ConsentDao
import com.stressdetector.mobile.data.db.UserConsentEntity
import com.stressdetector.mobile.data.reminder.ReminderRecordStatus
import com.stressdetector.mobile.domain.engine.FollowUpReminderKind
import com.stressdetector.mobile.domain.stress.StressHistoryContextSettings
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class AiReminderCoordinatorDependencies(
    val repository: AiReminderRepository,
    val scheduler: AiReminderScheduler,
    val settings: AiReminderSettings,
    val secretStore: AiReminderSecretStore,
    val consentDao: ConsentDao,
    val stressHistorySettings: StressHistoryContextSettings,
    val notificationPermissionGranted: () -> Boolean,
    val clock: () -> Instant = Instant::now
)

@Suppress("TooManyFunctions")
class DefaultAiReminderCoordinator(private val dependencies: AiReminderCoordinatorDependencies) : AiReminderCoordinator {
    private val repository get() = dependencies.repository
    private val scheduler get() = dependencies.scheduler
    private val settings get() = dependencies.settings
    private val secretStore get() = dependencies.secretStore
    private val consentDao get() = dependencies.consentDao
    private val stressHistorySettings get() = dependencies.stressHistorySettings
    private val notificationPermissionGranted get() = dependencies.notificationPermissionGranted
    private val clock get() = dependencies.clock

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(
        AiReminderState(
            enabled = settings.enabled.value,
            reminderConsentGranted = false,
            status = if (settings.enabled.value) {
                AiReminderStatus.NeedsConsent("Menunggu pemeriksaan persetujuan")
            } else {
                AiReminderStatus.Disabled
            }
        )
    )

    override val state: StateFlow<AiReminderState> = mutableState.asStateFlow()

    init {
        scope.launch {
            combine(
                repository.observeLatest(),
                consentDao.observeConsent(AI_REMINDER_CONSENT_TYPE),
                consentDao.observeConsent(AI_COUNSELING_CONSENT_TYPE),
                settings.enabled,
                stressHistorySettings.enabled
            ) { latest, reminderConsent, aiConsent, enabled, historyEnabled ->
                val payload = latest?.takeIf { it.policyVersion == AI_MANUAL_FOLLOWUP_POLICY_VERSION }
                    ?.let { runCatching { repository.readPayload(it.reminderId) }.getOrNull() }
                DerivedState(
                    latest = latest,
                    manualPayload = payload,
                    reminderConsent = reminderConsent?.isGranted == true,
                    aiCounselingConsent = aiConsent?.isGranted == true,
                    enabled = enabled,
                    historyEnabled = historyEnabled
                )
            }.collect { derived ->
                mutableState.value = deriveState(derived)
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        if (!enabled) settings.setEnabled(false)
        scope.launch {
            if (enabled && !canEnable()) {
                settings.setEnabled(false)
                return@launch
            }
            settings.setEnabled(enabled)
            if (!enabled) {
                scheduler.cancelAll()
                repository.deletePending()
            } else {
                scheduler.reconcile(clock())
            }
        }
    }

    override fun setReminderConsent(granted: Boolean) {
        if (!granted) settings.setEnabled(false)
        scope.launch {
            consentDao.insertConsent(
                UserConsentEntity(
                    consentType = AI_REMINDER_CONSENT_TYPE,
                    grantedAtMillis = clock().toEpochMilli(),
                    isGranted = granted
                )
            )
            if (!granted) {
                scheduler.cancelAll()
                repository.deletePending()
                settings.setEnabled(false)
            } else if (settings.enabled.value) {
                scheduler.reconcile(clock())
            }
        }
    }

    override fun saveDisplayName(rawName: String?) {
        scope.launch { secretStore.saveDisplayName(rawName) }
    }

    override fun retry() {
        scope.launch {
            scheduler.cancelAll()
            repository.deletePending()
            if (settings.enabled.value) scheduler.enqueuePlanning()
        }
    }

    override fun pause() = setEnabled(false)

    override fun onSessionFinalized(sessionId: String) {
        if (sessionId.isBlank()) return
        scope.launch {
            if (settings.enabled.value) scheduler.enqueuePlanning(sessionId)
        }
    }

    override fun markOpened(reminderId: String) {
        if (reminderId.isBlank()) return
        // Reading the record validates the ID-only deep-link contract. The
        // notification itself is auto-cancelled by Android.
        scope.launch { repository.getById(reminderId) }
    }

    override fun reconcile() {
        scope.launch { scheduler.reconcile(clock()) }
    }

    override suspend fun scheduleFollowUpReminder(
        kind: FollowUpReminderKind,
        delayHours: Long,
        sourceSessionId: String,
        customNote: String?
    ): Result<AiReminderRecord> = runCatching {
        require(delayHours in 1L..AI_REMINDER_MAX_HORIZON_HOURS) { "Waktu tunda pengingat harus antara 1 hingga 48 jam." }
        val safeHours = delayHours.coerceIn(1L, AI_REMINDER_MAX_HORIZON_HOURS)
        val safeSessionId = sourceSessionId.trim().ifBlank { "counselor-manual" }
        val now = clock()
        val scheduledAt = now.plus(Duration.ofHours(safeHours))
        val reminderId = UUID.randomUUID().toString()
        val occurrenceKey = "$safeSessionId:${kind.name}:${scheduledAt.toEpochMilli()}"
        val displayName = secretStore.displayName.value
        val note = customNote?.trim()?.takeIf(String::isNotBlank)?.take(120)
        val safeBody = buildString {
            append(kind.defaultNonClinicalTitle)
            if (note != null) append(" ($note)")
            append(". Ketuk untuk membuka AI+.")
        }.take(AI_REMINDER_MAX_MESSAGE_CODE_POINTS)

        val record = AiReminderRecord(
            reminderId = reminderId,
            occurrenceKey = occurrenceKey,
            sourceSessionId = safeSessionId,
            scheduledAt = scheduledAt,
            timezone = ZoneId.systemDefault(),
            status = ReminderRecordStatus.SCHEDULED,
            createdAt = now,
            generatedAt = now,
            deliveredAt = null,
            modelVersion = "manual-counselor-followup",
            policyVersion = AI_MANUAL_FOLLOWUP_POLICY_VERSION,
            payloadKey = reminderId,
            lastErrorCode = null
        )
        repository.deletePending()
        check(repository.insertIfAbsent(record)) { "Pengingat tindak lanjut gagal disimpan ke basis data lokal." }
        repository.writePayload(
            reminderId,
            ReminderPayload(
                message = safeBody,
                displayNameUsed = displayName,
                sourceSessionId = safeSessionId
            )
        )
        scheduler.enqueueDelivery(reminderId, scheduledAt)
        checkNotNull(repository.getById(reminderId)) { "Pengingat tindak lanjut gagal diverifikasi setelah penyimpanan." }
        record
    }

    override suspend fun cancelPendingReminders(): Result<Unit> = runCatching {
        scheduler.cancelAll()
        repository.deletePending()
    }

    private suspend fun canEnable(): Boolean = consentDao.getConsent(AI_REMINDER_CONSENT_TYPE)?.isGranted == true &&
        consentDao.getConsent(AI_COUNSELING_CONSENT_TYPE)?.isGranted == true &&
        stressHistorySettings.enabled.value

    private fun deriveState(derived: DerivedState): AiReminderState {
        val latest = derived.latest
        val isManualFollowUp = latest?.policyVersion == AI_MANUAL_FOLLOWUP_POLICY_VERSION
        val followUpKind = if (isManualFollowUp && latest != null) parseFollowUpKind(latest.occurrenceKey) else null
        val followUpDelayHours = if (isManualFollowUp && latest != null) {
            Duration.between(latest.createdAt, latest.scheduledAt).toHours().coerceIn(1L, AI_REMINDER_MAX_HORIZON_HOURS)
        } else {
            null
        }
        val followUpNote = if (isManualFollowUp && followUpKind != null) {
            extractFollowUpNote(derived.manualPayload?.message, followUpKind)
        } else {
            null
        }
        val manualActive = isManualFollowUp &&
            latest != null &&
            latest.status in setOf(ReminderRecordStatus.SCHEDULED, ReminderRecordStatus.GENERATING, ReminderRecordStatus.GENERATED)
        val baseStatus = when {
            manualActive && latest != null -> statusForRecord(latest)
            !derived.enabled -> AiReminderStatus.Disabled
            !derived.reminderConsent || !derived.aiCounselingConsent -> AiReminderStatus.NeedsConsent(
                "Aktifkan persetujuan AI Counselor dan AI+ Reminder."
            )
            !derived.historyEnabled -> AiReminderStatus.NeedsConsent(
                "Aktifkan ringkasan riwayat stres untuk grounding lokal."
            )
            latest == null -> AiReminderStatus.NoEligibleSession
            else -> statusForRecord(latest)
        }
        val status = if ((manualActive && !notificationPermissionGranted()) || requiresNotificationPermission(derived, baseStatus)) {
            AiReminderStatus.NeedsNotificationPermission
        } else {
            baseStatus
        }
        return AiReminderState(
            enabled = derived.enabled,
            reminderConsentGranted = derived.reminderConsent,
            canEnable = derived.reminderConsent && derived.aiCounselingConsent && derived.historyEnabled,
            status = status,
            nextReminderAt = latest?.scheduledAt,
            sourceSessionId = latest?.sourceSessionId,
            lastErrorCode = latest?.lastErrorCode,
            activeFollowUpKind = followUpKind,
            activeFollowUpDelayHours = followUpDelayHours,
            activeFollowUpNote = followUpNote,
            isManualFollowUp = manualActive
        )
    }

    private fun parseFollowUpKind(occurrenceKey: String): FollowUpReminderKind? {
        val parts = occurrenceKey.split(':')
        val rawKind = parts.getOrNull(parts.size - 2) ?: return null
        return runCatching { FollowUpReminderKind.valueOf(rawKind) }.getOrNull()
    }

    private fun extractFollowUpNote(message: String?, kind: FollowUpReminderKind): String? {
        val prefix = "${kind.defaultNonClinicalTitle} ("
        if (message == null || !message.startsWith(prefix)) return null
        val endIdx = message.lastIndexOf("). Ketuk")
        return if (endIdx > prefix.length) message.substring(prefix.length, endIdx) else null
    }

    private fun statusForRecord(record: AiReminderRecord): AiReminderStatus = when (record.status) {
        ReminderRecordStatus.SCHEDULED -> AiReminderStatus.Scheduled(record.scheduledAt, record.sourceSessionId)
        ReminderRecordStatus.GENERATING,
        ReminderRecordStatus.GENERATED -> AiReminderStatus.Generating
        ReminderRecordStatus.DELIVERED -> record.deliveredAt?.let {
            AiReminderStatus.Delivered(it, record.sourceSessionId)
        } ?: AiReminderStatus.Failed("REMINDER_DELIVERY_TIMESTAMP_MISSING")
        ReminderRecordStatus.FAILED -> statusFromFailure(record.lastErrorCode)
        else -> AiReminderStatus.Failed("REMINDER_STATE_UNKNOWN")
    }

    private fun requiresNotificationPermission(derived: DerivedState, baseStatus: AiReminderStatus): Boolean = derived.enabled &&
        derived.reminderConsent &&
        derived.aiCounselingConsent &&
        derived.historyEnabled &&
        !notificationPermissionGranted() &&
        baseStatus !is AiReminderStatus.Delivered

    private fun statusFromFailure(errorCode: String?): AiReminderStatus = when (errorCode) {
        "MODEL_UNAVAILABLE" -> AiReminderStatus.ModelUnavailable(errorCode)
        "NO_ELIGIBLE_SESSION" -> AiReminderStatus.NoEligibleSession
        else -> AiReminderStatus.Failed(errorCode ?: "REMINDER_DELIVERY_FAILED")
    }

    private data class DerivedState(
        val latest: AiReminderRecord?,
        val manualPayload: ReminderPayload?,
        val reminderConsent: Boolean,
        val aiCounselingConsent: Boolean,
        val enabled: Boolean,
        val historyEnabled: Boolean
    )
}
