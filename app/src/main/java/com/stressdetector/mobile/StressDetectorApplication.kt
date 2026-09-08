package com.stressdetector.mobile

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import com.stressdetector.mobile.domain.reminder.AI_REMINDER_CHANNEL_ID

class StressDetectorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)

        val ragChannel = android.app.NotificationChannel(
            "rag_channel",
            "RAG Processing",
            android.app.NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(ragChannel)

        val modelDownloadChannel = android.app.NotificationChannel(
            "model_download_channel",
            "Gemma 4 E2B Download",
            android.app.NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows Gemma 4 E2B model download progress"
        }
        manager.createNotificationChannel(modelDownloadChannel)

        val recordingChannel = android.app.NotificationChannel(
            "recording_channel",
            "Recording",
            android.app.NotificationManager.IMPORTANCE_LOW
        )
        recordingChannel.setSound(null, null)
        manager.createNotificationChannel(recordingChannel)

        val sessionChannel = android.app.NotificationChannel(
            "session_channel",
            "Session Service Channel",
            android.app.NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the unified session running in the background"
        }
        manager.createNotificationChannel(sessionChannel)

        val aiReminderChannel = NotificationChannel(
            AI_REMINDER_CHANNEL_ID,
            "AI+ Reminder",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Pengingat reflektif berbasis sesi stress lokal yang valid"
            setLockscreenVisibility(Notification.VISIBILITY_PRIVATE)
            setShowBadge(true)
        }
        manager.createNotificationChannel(aiReminderChannel)
    }
}
