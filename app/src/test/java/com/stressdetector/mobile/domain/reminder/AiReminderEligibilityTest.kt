package com.stressdetector.mobile.domain.reminder

import com.stressdetector.mobile.data.db.ConsentDao
import com.stressdetector.mobile.data.db.UserConsentEntity
import com.stressdetector.mobile.domain.stress.InMemoryStressHistoryContextSettings
import com.stressdetector.mobile.domain.stress.StressHistoryContextEntry
import com.stressdetector.mobile.domain.stress.StressHistoryContextProvider
import com.stressdetector.mobile.domain.stress.StressHistoryContextSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class AiReminderEligibilityTest {
    @Test
    fun publicationRecheckRejectsRevokedConsentAndDeletedSource() = runTest {
        val consentDao = Mockito.mock(ConsentDao::class.java)
        listOf(AI_REMINDER_CONSENT_TYPE, AI_COUNSELING_CONSENT_TYPE).forEach { type ->
            Mockito.`when`(consentDao.getConsent(type)).thenReturn(UserConsentEntity(type, 1L, true))
        }
        val provider = Mockito.mock(StressHistoryContextProvider::class.java)
        val snapshot = StressHistoryContextSnapshot(
            "test-contract",
            301_000L,
            listOf(StressHistoryContextEntry("source", 301_000L, 300_000L, "Normal", 0.2f, 0.8f, "good", true, null)),
            "one eligible session"
        )
        Mockito.`when`(provider.snapshot()).thenReturn(snapshot)
        val historySettings = InMemoryStressHistoryContextSettings(true)
        val settings = object : AiReminderSettings {
            override val enabled = MutableStateFlow(true)
            override fun setEnabled(enabled: Boolean) {
                this.enabled.value = enabled
            }
        }
        val eligibility = AiReminderEligibility(consentDao, provider, historySettings, settings)
        assertTrue(eligibility.find("source") is ReminderEligibilityResult.Eligible)

        Mockito.`when`(consentDao.getConsent(AI_REMINDER_CONSENT_TYPE))
            .thenReturn(UserConsentEntity(AI_REMINDER_CONSENT_TYPE, 1L, false))
        assertEquals("REMINDER_CONSENT_REQUIRED", (eligibility.find("source") as ReminderEligibilityResult.Rejected).code)
        Mockito.`when`(consentDao.getConsent(AI_REMINDER_CONSENT_TYPE))
            .thenReturn(UserConsentEntity(AI_REMINDER_CONSENT_TYPE, 1L, true))
        historySettings.setEnabled(false)
        assertEquals("STRESS_HISTORY_CONSENT_REQUIRED", (eligibility.find("source") as ReminderEligibilityResult.Rejected).code)
        historySettings.setEnabled(true)
        Mockito.`when`(provider.snapshot()).thenReturn(snapshot.copy(entries = emptyList()))
        assertEquals("NO_ELIGIBLE_SESSION", (eligibility.find("source") as ReminderEligibilityResult.Rejected).code)
        settings.setEnabled(false)
        assertEquals("REMINDER_DISABLED", (eligibility.find("source") as ReminderEligibilityResult.Rejected).code)
    }
}
