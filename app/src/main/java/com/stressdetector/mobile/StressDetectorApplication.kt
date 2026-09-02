package com.stressdetector.mobile

import android.app.Application

class StressDetectorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(android.app.NotificationManager::class.java)

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
    }
}
