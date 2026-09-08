package com.stressdetector.mobile.domain.reminder

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiReminderWorkManagerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var scheduler: WorkManagerAiReminderScheduler
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        scheduler = WorkManagerAiReminderScheduler(context)
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        scheduler.cancelAll()
    }

    @Test
    fun deliveryIsUniqueAndUsesOnlyReminderIdInput() {
        val reminderId = "instrumented-reminder-unique"
        scheduler.enqueueDelivery(reminderId, Instant.now().plusSeconds(3_600))
        scheduler.enqueueDelivery(reminderId, Instant.now().plusSeconds(7_200))

        val infos = workManager
            .getWorkInfosForUniqueWork(
                WorkManagerAiReminderScheduler.AI_REMINDER_DELIVERY_WORK_PREFIX + reminderId
            )
            .get(5, TimeUnit.SECONDS)

        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertTrue(infos.single().tags.contains(WorkManagerAiReminderScheduler.AI_REMINDER_WORK_TAG))
    }
}
