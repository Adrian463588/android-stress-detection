package com.stressdetector.mobile.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.stressdetector.mobile.MainActivity
import com.stressdetector.mobile.R
import com.stressdetector.mobile.domain.reminder.AI_REMINDER_CHANNEL_ID
import com.stressdetector.mobile.domain.reminder.ReminderContent
import com.stressdetector.mobile.domain.reminder.ReminderNotificationPublisher
import com.stressdetector.mobile.domain.reminder.ReminderPublishResult
import java.nio.ByteBuffer
import java.security.MessageDigest

class AndroidAiReminderNotificationPublisher(context: Context) : ReminderNotificationPublisher {
    private val applicationContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(applicationContext)

    override fun isPermissionGranted(): Boolean = (
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) &&
        manager.areNotificationsEnabled()

    @SuppressLint("MissingPermission")
    override fun publish(reminderId: String, content: ReminderContent): ReminderPublishResult {
        if (!isPermissionGranted()) return ReminderPublishResult.PermissionDenied
        val notification = buildNotification(reminderId, content)
        return runCatching {
            manager.notify(ReminderNotificationIds.forReminder(reminderId), notification)
        }.fold(
            onSuccess = { ReminderPublishResult.Published },
            onFailure = { error ->
                if (error is SecurityException) {
                    ReminderPublishResult.PermissionDenied
                } else {
                    ReminderPublishResult.Failed(
                        error.message ?: "NOTIFICATION_PUBLISH_FAILED",
                        retryable = true
                    )
                }
            }
        )
    }

    override fun cancel(reminderId: String) {
        manager.cancel(ReminderNotificationIds.forReminder(reminderId))
    }

    private fun buildNotification(reminderId: String, content: ReminderContent): Notification {
        val title = content.displayNameUsed?.let { "AI+ untuk $it" } ?: "AI+ punya pengingat untukmu"
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_AI_PLUS
            putExtra(MainActivity.EXTRA_REMINDER_ID, reminderId)
            setPackage(applicationContext.packageName)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            ReminderNotificationIds.forReminder(reminderId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val publicVersion = NotificationCompat.Builder(applicationContext, AI_REMINDER_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("Ada pengingat pribadi dari AI+.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()
        return NotificationCompat.Builder(applicationContext, AI_REMINDER_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.message))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .build()
    }
}

object ReminderNotificationIds {
    private const val NAMESPACE_BASE = 2_000_000
    private const val NAMESPACE_SIZE = 1_000_000

    fun forReminder(reminderId: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(reminderId.toByteArray(Charsets.UTF_8))
        val positive = (ByteBuffer.wrap(digest, 0, Int.SIZE_BYTES).int and Int.MAX_VALUE) % NAMESPACE_SIZE
        return NAMESPACE_BASE + positive
    }
}
