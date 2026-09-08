package com.stressdetector.mobile.data.reminder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stressdetector.mobile.domain.reminder.ReminderPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedAiReminderSecretStoreTest {
    @Test
    fun identityAndPayloadRoundTripThroughKeystoreBoundary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedAiReminderSecretStore(context)
        val reminderId = "instrumented-reminder"
        val message = "Halo Alex, bagaimana kabarmu hari ini?"
        try {
            assertEquals("Alex", store.saveDisplayName("  Alex "))
            store.writePayload(reminderId, ReminderPayload(message, "Alex", "session-1"))
            assertEquals(message, store.readPayload(reminderId)?.message)
            val persistedFile = context.getSharedPreferences("ai_reminder_secrets", 0)
            assertFalse(persistedFile.all.values.any { it.toString().contains(message) })
        } finally {
            store.clear()
        }
    }
}
