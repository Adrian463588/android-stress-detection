package com.stressdetector.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.google.android.gms.wearable.Wearable
import com.stressdetector.shared.MessagePath
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SessionCommand
import com.stressdetector.shared.SessionCommandCodec
import com.stressdetector.shared.WearSessionMode
import com.stressdetector.wear.HealthSdkResolutionRegistry
import com.stressdetector.wear.data.PpgSensorRepository
import com.stressdetector.wear.data.WearTrackingState
import com.stressdetector.wear.hasRequiredWearPermissions
import com.stressdetector.wear.sensor.BaseSamsungListener
import com.stressdetector.wear.sensor.PpgIndividualListener
import com.stressdetector.wear.sensor.PpgSensorSample
import com.stressdetector.wear.sensor.PpgTrackerSpec

/** Small synchronized lifecycle guard that makes stop/disconnect handling idempotent. */
internal class PpgServiceStopLifecycle {
    private enum class Phase { RUNNING, STOPPING, COMPLETE }

    private var phase = Phase.RUNNING

    @Synchronized
    fun reset() {
        phase = Phase.RUNNING
    }

    @Synchronized
    fun beginStop(): Boolean {
        if (phase != Phase.RUNNING) return false
        phase = Phase.STOPPING
        return true
    }

    @Synchronized
    fun complete(): Boolean {
        if (phase != Phase.STOPPING) return false
        phase = Phase.COMPLETE
        return true
    }

    @get:Synchronized
    val ignoresConnectionEnded: Boolean
        get() = phase != Phase.RUNNING

    @get:Synchronized
    val isStopping: Boolean
        get() = phase == Phase.STOPPING
}

/**
 * PDSatu-style Samsung acquisition service.
 *
 * Each wavelength owns an individual tracker and listener. Direct batches are
 * sent to the phone; a temporary transport failure never stops the sensor
 * lifecycle. The phone decides final coverage from samples it actually got.
 */
class PpgTrackingService : Service() {
    private lateinit var repository: PpgSensorRepository
    private var healthService: HealthTrackingService? = null
    private var greenListener: BaseSamsungListener? = null
    private var irListener: BaseSamsungListener? = null
    private var redListener: BaseSamsungListener? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sessionState: PpgSessionState? = null
    private var terminalSensorFailure: String? = null
    private val stopLifecycle = PpgServiceStopLifecycle()

    private val progressTicker = object : Runnable {
        override fun run() {
            val state = sessionState ?: return
            val elapsed = state.elapsed()
            WearTrackingState.updateElapsed(elapsed)
            if (state.isComplete()) stopTracking() else handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = PpgSensorRepository(applicationContext)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTracking(intent.getByteArrayExtra(EXTRA_COMMAND))
            ACTION_START -> startTracking(intent.getByteArrayExtra(EXTRA_COMMAND), intent.getStringExtra(EXTRA_REPLY_NODE_ID))
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTracking(commandBytes: ByteArray?, replyNodeId: String?) {
        if (sessionState != null) return
        terminalSensorFailure = null
        startForeground(1, notification("Preparing Green + IR + Red PPG"))
        if (!hasRequiredWearPermissions(this)) {
            WearTrackingState.setHealthSdkPermissionRequired()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val command = runCatching {
            commandBytes?.takeIf { it.isNotEmpty() }?.let(SessionCommandCodec::decode)
                ?: error("missing session command")
        }.getOrElse { error ->
            WearTrackingState.message("SESSION_START ignored: ${error.message ?: "invalid command"}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val phoneNodeId = command.peerNodeId.ifBlank { replyNodeId.orEmpty() }
        if (phoneNodeId.isBlank() || (replyNodeId != null && phoneNodeId != replyNodeId)) {
            WearTrackingState.message("SESSION_START phone identity unavailable")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (command.sessionNonce.isBlank()) {
            WearTrackingState.message("SESSION_START ignored: missing session identity")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val startedAtMillis = System.currentTimeMillis()
        val startedElapsedRealtimeMillis = SystemClock.elapsedRealtime()
        stopLifecycle.reset()
        sessionState = PpgSessionState(
            sessionId = command.sessionId,
            sessionNonce = command.sessionNonce,
            protocolVersion = command.protocolVersion,
            protocolBuildFingerprint = command.protocolBuildFingerprint,
            mode = command.mode,
            peerNodeId = phoneNodeId,
            generation = command.generation,
            startedAtMillis = startedAtMillis,
            startedElapsedRealtimeMillis = startedElapsedRealtimeMillis
        )
        WearTrackingState.message("Preparing individual Samsung PPG trackers")
        HealthSdkResolutionRegistry.clear()
        WearTrackingState.setHealthSdkConnecting()
        connectSamsungService()
    }

    private fun connectSamsungService() {
        val currentSession = sessionState ?: return
        healthService?.disconnectService()
        var setupFailureMessage: String? = null
        healthService = runCatching {
            HealthTrackingService(
                object : ConnectionListener {
                    override fun onConnectionSuccess() {
                        handler.post { registerIndividualTrackers() }
                    }

                    override fun onConnectionEnded() {
                        handler.post {
                            if (stopLifecycle.ignoresConnectionEnded) return@post
                            handleSamsungConnectionLoss("Samsung PPG connection lost during recording")
                        }
                    }

                    override fun onConnectionFailed(error: HealthTrackerException?) {
                        handler.post {
                            val message = "Samsung SDK unavailable: ${error?.message ?: "connection failed"}"
                            val resolutionAvailable = HealthSdkResolutionRegistry.offer(error)
                            handleSamsungConnectionLoss(message)
                            // completeTracking() intentionally clears ordinary
                            // connection state. Re-apply this explicit SDK
                            // resolution state so the user can act on it.
                            WearTrackingState.setHealthSdkFailure(message, resolutionAvailable)
                        }
                    }
                },
                applicationContext
            ).also { it.connectService() }
        }.getOrElse { error ->
            setupFailureMessage = "Samsung SDK setup failed: ${error.message}"
            null
        }
        if (healthService == null && sessionState == currentSession) {
            WearTrackingState.setHealthSdkFailure(
                setupFailureMessage ?: "Samsung PPG capability unavailable"
            )
            failWithoutTracker(setupFailureMessage ?: "Samsung PPG capability unavailable")
        }
    }

    private fun handleSamsungConnectionLoss(message: String) {
        if (stopLifecycle.ignoresConnectionEnded || sessionState == null) return
        if (!WearTrackingState.state.value.isTracking) {
            failWithoutTracker(message)
            return
        }
        terminalSensorFailure = message
        WearTrackingState.onSamsungConnectionEnded(message = message, intentional = false)
        stopTracking()
    }

    private fun registerIndividualTrackers() {
        val service = healthService ?: return
        if (sessionState == null || stopLifecycle.isStopping) return

        var pendingRegistrations = PpgTrackerSpec.individual.size
        var greenRegistered = false
        var irRegistered = false
        fun registrationFinished(type: PpgType, registered: Boolean) {
            when (type) {
                PpgType.GREEN -> greenRegistered = registered
                PpgType.IR -> irRegistered = registered
                PpgType.RED -> Unit
            }
            pendingRegistrations -= 1
            if (pendingRegistrations != 0) return
            if (!greenRegistered || !irRegistered) {
                WearTrackingState.setHealthSdkFailure("Green and IR Samsung PPG trackers are unavailable")
                failWithoutTracker("Green and IR Samsung PPG trackers are unavailable")
                return
            }
            startSessionAfterRegistration()
        }

        greenListener = createListener(
            spec = PpgTrackerSpec.green,
            createTracker = { service.getHealthTracker(PpgTrackerSpec.green.trackerType) },
            onSamples = repository::onGreenBatch,
            onStarted = { registrationFinished(PpgType.GREEN, it) }
        )
        irListener = createListener(
            spec = PpgTrackerSpec.ir,
            createTracker = { service.getHealthTracker(PpgTrackerSpec.ir.trackerType) },
            onSamples = repository::onIrBatch,
            onStarted = { registrationFinished(PpgType.IR, it) }
        )
        redListener = createListener(
            spec = PpgTrackerSpec.red,
            createTracker = { service.getHealthTracker(PpgTrackerSpec.red.trackerType) },
            onSamples = repository::onRedBatch,
            onStarted = { registrationFinished(PpgType.RED, it) }
        )
    }

    private fun startSessionAfterRegistration() {
        if (sessionState == null || stopLifecycle.isStopping) return
        // Start the session clock only after setEventListener has been
        // completed for all individual trackers. The callbacks and this
        // barrier run on the same handler, so the first accepted sample is
        // never measured against a pre-registration clock.
        val trackingStartedAtMillis = System.currentTimeMillis()
        val trackingStartedElapsedRealtimeMillis = SystemClock.elapsedRealtime()
        val active = requireNotNull(sessionState).copy(
            startedAtMillis = trackingStartedAtMillis,
            startedElapsedRealtimeMillis = trackingStartedElapsedRealtimeMillis
        )
        sessionState = active
        repository.startSession(
            identity = active.identity,
            startedElapsedRealtimeMillis = trackingStartedElapsedRealtimeMillis,
            startedAtEpochMillis = trackingStartedAtMillis,
            phoneNodeId = active.peerNodeId,
            generation = active.generation,
            collectMotion = active.mode == WearSessionMode.HOME_STRESS
        )
        WearTrackingState.beginSession(active.sessionId, active.mode, sourceNodeId = active.peerNodeId)
        // The SDK is reported READY only after the service returned usable
        // individual tracker instances and the listener lifecycle was wired.
        WearTrackingState.setHealthSdkReady()
        HealthSdkResolutionRegistry.clear()
        WearTrackingState.setSamsungListenerRegistered(true)
        WearTrackingState.updateElapsed(0L)
        handler.removeCallbacks(progressTicker)
        handler.post(progressTicker)
    }

    private fun failWithoutTracker(message: String) {
        if (!stopLifecycle.beginStop()) return
        WearTrackingState.transportFailed(message)
        WearTrackingState.setTracking(false)
        stopListenersOnce {
            disconnectHealthServiceOnce()
            stopLifecycle.complete()
            sessionState = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createListener(
        spec: PpgTrackerSpec,
        createTracker: () -> com.samsung.android.service.health.tracking.HealthTracker,
        onSamples: (List<PpgSensorSample>) -> Boolean,
        onStarted: (Boolean) -> Unit
    ): BaseSamsungListener? {
        val reportError: (String) -> Unit = { message ->
            WearTrackingState.markChannelFailure(spec.channel, message)
            WearTrackingState.message(message)
        }
        return runCatching {
            PpgIndividualListener(
                spec = spec,
                onSamples = { samples ->
                    if (onSamples(samples)) WearTrackingState.markChannelReady(spec.channel)
                },
                onError = reportError
            ).also { listener ->
                listener.configure(createTracker(), handler, reportError)
                listener.start(onStarted)
            }
        }.onFailure { error ->
            val sdkError = error as? HealthTrackerException
            val resolutionAvailable = HealthSdkResolutionRegistry.offer(sdkError)
            if (resolutionAvailable) {
                WearTrackingState.setHealthSdkFailure(
                    "Samsung ${spec.channel.assetSuffix} tracker requires SDK resolution",
                    resolutionAvailable = true
                )
            }
            reportError("INCOMPATIBLE_SDK: ${spec.channel.assetSuffix} tracker unavailable: ${error.message}")
            onStarted(false)
        }
            .getOrNull()
    }

    private fun stopTracking(commandBytes: ByteArray? = null) {
        val current = sessionState ?: return
        val request = validateStopRequest(commandBytes, current)
        if (request is StopRequestDecision.Rejected) return
        if (!stopLifecycle.beginStop()) return
        if (request is StopRequestDecision.Local && current.mode == WearSessionMode.VERBATIM_AROUSAL) {
            requestPhoneStop(current)
        }
        if (terminalSensorFailure == null) WearTrackingState.setDraining()
        stopListenersOnce { listenersFlushed ->
            repository.flushAll { flushed ->
                handler.post { completeTracking(listenersFlushed && flushed) }
            }
        }
    }

    private fun validateStopRequest(commandBytes: ByteArray?, current: PpgSessionState): StopRequestDecision {
        val bytes = commandBytes
        return if (bytes == null || bytes.isEmpty()) {
            StopRequestDecision.Local
        } else {
            val requested = runCatching { SessionCommandCodec.decode(bytes) }.getOrNull()
            when {
                requested == null -> StopRequestDecision.rejected("SESSION_STOP ignored: invalid command")
                !requested.sessionIdentity.matches(current.identity) ->
                    StopRequestDecision.rejected("SESSION_STOP ignored for stale session")
                requested.generation != current.generation || requested.peerNodeId != current.peerNodeId ->
                    StopRequestDecision.rejected("SESSION_STOP ignored for mismatched session generation")
                else -> StopRequestDecision.Correlated
            }
        }
    }

    private fun requestPhoneStop(state: PpgSessionState) {
        if (state.peerNodeId.isBlank()) {
            WearTrackingState.message("Stop requested locally; phone identity unavailable")
            return
        }
        val command = SessionCommand(
            sessionId = state.sessionId,
            mode = state.mode,
            sessionNonce = state.sessionNonce,
            protocolVersion = state.protocolVersion,
            protocolBuildFingerprint = state.protocolBuildFingerprint,
            completionExpected = false,
            peerNodeId = state.peerNodeId,
            generation = state.generation
        )
        Wearable.getMessageClient(this)
            .sendMessage(
                state.peerNodeId,
                MessagePath.SESSION_STOP_REQUEST,
                SessionCommandCodec.encode(command)
            )
            .addOnSuccessListener { WearTrackingState.message("Stop request sent to phone") }
            .addOnFailureListener {
                WearTrackingState.message("PPG stopped; phone stop request failed")
            }
    }

    private fun completeTracking(flushed: Boolean) {
        val state = sessionState ?: return
        if (!stopLifecycle.complete()) return
        handler.removeCallbacks(progressTicker)
        terminalSensorFailure?.let(WearTrackingState::transportFailed) ?: WearTrackingState.transportComplete(
            if (flushed) {
                "Direct PPG send finished; phone coverage is evidence-based"
            } else {
                "Direct PPG flush ended with transport gaps"
            }
        )
        WearTrackingState.setTracking(false)
        WearTrackingState.updateElapsed(state.elapsed())
        WearTrackingState.setSamsungConnected(false)
        WearTrackingState.setSamsungListenerRegistered(false)
        sessionState = null
        terminalSensorFailure = null
        disconnectHealthServiceOnce()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        repository.stopMotionCollection()
        handler.removeCallbacks(progressTicker)
        val interrupted = sessionState != null && !stopLifecycle.ignoresConnectionEnded
        if (interrupted) {
            WearTrackingState.onSamsungConnectionEnded(
                message = "PPG tracking service ended during recording",
                intentional = false
            )
        }
        stopLifecycle.beginStop()
        stopListenersOnce { disconnectHealthServiceOnce() }
        sessionState = null
        super.onDestroy()
    }

    private fun stopListenersOnce(onComplete: (Boolean) -> Unit = {}) {
        val green = greenListener
        val ir = irListener
        val red = redListener
        greenListener = null
        irListener = null
        redListener = null
        val listeners = listOfNotNull(green, ir, red)
        if (listeners.isEmpty()) {
            onComplete(true)
            return
        }
        var remaining = listeners.size
        var allFlushed = true
        listeners.forEach { listener ->
            listener.stop { flushed ->
                allFlushed = allFlushed && flushed
                remaining -= 1
                if (remaining == 0) onComplete(allFlushed)
            }
        }
    }

    private fun disconnectHealthServiceOnce() {
        val service = healthService ?: return
        healthService = null
        service.disconnectService()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "PPG tracking", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Stress Detector")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_manage)
        .build()

    companion object {
        private const val CHANNEL_ID = "ppg_tracking"
        const val ACTION_START = "com.stressdetector.wear.START"
        const val ACTION_STOP = "com.stressdetector.wear.STOP"
        const val EXTRA_COMMAND = "session_command"
        const val EXTRA_REPLY_NODE_ID = "reply_node_id"
    }
}

private sealed interface StopRequestDecision {
    data object Local : StopRequestDecision

    data object Correlated : StopRequestDecision

    data object Rejected : StopRequestDecision {
        fun reject(message: String): StopRequestDecision {
            WearTrackingState.message(message)
            return this
        }
    }

    companion object {
        fun rejected(message: String): StopRequestDecision = Rejected.reject(message)
    }
}
