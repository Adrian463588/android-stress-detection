package com.stressdetector.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.stressdetector.mobile.MainActivity
import com.stressdetector.mobile.consent.ConsentScope
import com.stressdetector.mobile.consent.ConsentToken
import com.stressdetector.mobile.di.AppLocator
import com.stressdetector.mobile.domain.engine.AudioInputSource
import com.stressdetector.mobile.domain.engine.UnifiedSessionEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UnifiedSessionService : LifecycleService() {

    private lateinit var unifiedSessionEngine: UnifiedSessionEngine
    private var recoveryJob: Job? = null
    private var isServiceStarted = false
    private val commandMutex = Mutex()
    private val processedCommandIds = LinkedHashSet<String>()

    override fun onCreate() {
        super.onCreate()
        unifiedSessionEngine = AppLocator.unifiedSessionEngine(applicationContext)
        if (!unifiedSessionEngine.isSessionActive.value) {
            recoveryJob = lifecycleScope.launch {
                AppLocator.unifiedSessionRepository(applicationContext).recoverInterruptedSessions()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_SESSION -> {
                lifecycleScope.launch {
                    runCommandOnce(intent.commandId()) {
                        recoveryJob?.join()
                        recoveryJob = null
                        startSessionService(intent)
                    }
                }
            }
            ACTION_STOP_SESSION -> {
                lifecycleScope.launch {
                    runCommandOnce(intent.commandId()) { stopSessionService() }
                }
            }
            ACTION_PAUSE_SESSION -> lifecycleScope.launch {
                runCommandOnce(intent.commandId()) {
                    runCatching { unifiedSessionEngine.pauseSession() }
                        .onFailure { unifiedSessionEngine.reportOperationError(it.message ?: "Pause sesi gagal.") }
                }
            }
            ACTION_RESUME_SESSION -> lifecycleScope.launch {
                runCommandOnce(intent.commandId()) {
                    runCatching { unifiedSessionEngine.resumeSession() }
                        .onFailure { unifiedSessionEngine.reportOperationError(it.message ?: "Resume sesi gagal.") }
                }
            }
        }

        return START_STICKY
    }

    private suspend fun startSessionService(intent: Intent) {
        if (isServiceStarted || unifiedSessionEngine.isSessionActive.value) return

        try {
            createNotificationChannel()
            val monitoringEnabled = intent.getBooleanExtra(EXTRA_MONITORING_ENABLED, false)
            val notification = buildNotification(monitoringEnabled)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    foregroundServiceType(intent, monitoringEnabled)
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                ?: throw SecurityException("Missing session ID.")
            val userId = intent.getStringExtra(EXTRA_USER_ID)
                ?: throw SecurityException("Missing user ID.")
            val recordingConsent = getConsentToken(intent, "recording")
            val monitoringConsent = if (monitoringEnabled) getConsentToken(intent, "monitoring") else null
            unifiedSessionEngine.startSession(
                sessionId,
                userId,
                recordingConsent,
                monitoringConsent,
                intent.getStringExtra(EXTRA_SESSION_NAME).orEmpty().ifBlank { "Session" },
                intent.getStringExtra(EXTRA_SESSION_LANGUAGE).orEmpty().ifBlank { "id" },
                audioInput = intent.getStringExtra(EXTRA_AUDIO_URI)
                    ?.takeIf(String::isNotBlank)
                    ?.let(AudioInputSource::Imported)
                    ?: AudioInputSource.Microphone,
                monitoringEnabled = monitoringEnabled
            )
            isServiceStarted = true
        } catch (e: Exception) {
            unifiedSessionEngine.reportOperationError(e.message ?: "Sesi tidak dapat dimulai.")
            isServiceStarted = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun foregroundServiceType(intent: Intent, monitoringEnabled: Boolean): Int = when {
        !intent.getStringExtra(EXTRA_AUDIO_URI).isNullOrBlank() -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        monitoringEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else -> 0
    }

    private suspend fun stopSessionService() {
        if (!isServiceStarted && !unifiedSessionEngine.isSessionActive.value) return

        try {
            unifiedSessionEngine.stopSession()
        } catch (e: Exception) {
            unifiedSessionEngine.reportOperationError(e.message ?: "Sesi tidak dapat dihentikan.")
        } finally {
            isServiceStarted = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Session Service Channel",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the unified session running in the background"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(monitoringEnabled: Boolean): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, UnifiedSessionService::class.java).apply {
            action = ACTION_STOP_SESSION
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Verbatim+ aktif")
            .setContentText(if (monitoringEnabled) "Rekaman dan PPG lokal berjalan" else "Rekaman dan analisis audio lokal berjalan")
            .setSmallIcon(com.stressdetector.mobile.R.drawable.ic_verbatim_notification)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private suspend fun runCommandOnce(commandId: String?, action: suspend () -> Unit) {
        commandMutex.withLock {
            if (commandId.isNullOrBlank()) {
                action()
            } else if (!processedCommandIds.add(commandId)) {
                return@withLock
            } else {
                while (processedCommandIds.size > MAX_PROCESSED_COMMANDS) {
                    processedCommandIds.remove(processedCommandIds.first())
                }
                action()
            }
        }
    }

    private fun Intent?.commandId(): String? = this?.getStringExtra(EXTRA_COMMAND_ID)

    companion object {
        private const val CHANNEL_ID = "session_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_SESSION = "ACTION_START_SESSION"
        const val ACTION_STOP_SESSION = "ACTION_STOP_SESSION"
        const val ACTION_PAUSE_SESSION = "ACTION_PAUSE_SESSION"
        const val ACTION_RESUME_SESSION = "ACTION_RESUME_SESSION"
        const val EXTRA_COMMAND_ID = "EXTRA_COMMAND_ID"
        const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"
        const val EXTRA_USER_ID = "EXTRA_USER_ID"
        const val EXTRA_SESSION_NAME = "EXTRA_SESSION_NAME"
        const val EXTRA_SESSION_LANGUAGE = "EXTRA_SESSION_LANGUAGE"
        const val EXTRA_AUDIO_URI = "EXTRA_AUDIO_URI"
        const val EXTRA_MONITORING_ENABLED = "EXTRA_MONITORING_ENABLED"
        private const val MAX_PROCESSED_COMMANDS = 64

        fun putConsentToken(intent: Intent, prefix: String, token: ConsentToken) {
            intent.putExtra("${prefix}_consent_id", token.consentId)
            intent.putExtra("${prefix}_consent_user_id", token.userId)
            intent.putExtra("${prefix}_consent_session_id", token.sessionId)
            intent.putExtra("${prefix}_consent_scope", token.scope.name)
            intent.putExtra("${prefix}_consent_actor", token.actor)
            intent.putExtra("${prefix}_consent_issued_at", token.issuedAtEpochMillis)
        }

        private fun getConsentToken(intent: Intent, prefix: String): ConsentToken {
            fun required(name: String): String = intent.getStringExtra("${prefix}_$name")
                ?: throw SecurityException("Missing consent token field: $name")
            return ConsentToken(
                consentId = required("consent_id"),
                userId = required("consent_user_id"),
                sessionId = required("consent_session_id"),
                scope = runCatching { ConsentScope.valueOf(required("consent_scope")) }
                    .getOrElse { throw SecurityException("Invalid consent scope.") },
                actor = required("consent_actor"),
                issuedAtEpochMillis = intent.getLongExtra("${prefix}_consent_issued_at", Long.MIN_VALUE)
                    .also { if (it == Long.MIN_VALUE) throw SecurityException("Missing consent issue time.") }
            )
        }
    }
}
