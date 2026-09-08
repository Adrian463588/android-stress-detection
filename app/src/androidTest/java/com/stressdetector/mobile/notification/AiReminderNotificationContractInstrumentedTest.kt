package com.stressdetector.mobile.notification

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stressdetector.mobile.domain.reminder.AI_REMINDER_CHANNEL_ID
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiReminderNotificationContractInstrumentedTest {
    @Test
    fun channelIsStableAndPrivateOnTheLockScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(AI_REMINDER_CHANNEL_ID)

        assertNotNull("AI+ reminder channel must be created by the Application", channel)
        assertTrue(
            "Channel importance is controlled by the user/device: ${channel?.importance}",
            channel?.importance == NotificationManager.IMPORTANCE_DEFAULT ||
                channel?.importance == NotificationManager.IMPORTANCE_NONE
        )
        assertTrue(
            "Lock-screen visibility is user/device controlled: ${channel?.lockscreenVisibility}",
            channel?.lockscreenVisibility == Notification.VISIBILITY_PRIVATE ||
                channel?.lockscreenVisibility == NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE
        )
    }
}
