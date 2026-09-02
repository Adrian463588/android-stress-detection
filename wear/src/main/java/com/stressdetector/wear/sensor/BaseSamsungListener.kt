package com.stressdetector.wear.sensor

import android.os.Handler
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint

open class BaseSamsungListener {
    private var handler: Handler? = null
    private var tracker: HealthTracker? = null
    private var listener: HealthTracker.TrackerEventListener? = null
    private var onLifecycleError: ((String) -> Unit)? = null
    private var running = false
    private var stopping = false
    private var stopCallback: ((Boolean) -> Unit)? = null
    private val stopTimeout = Runnable { finishStop(flushed = false) }

    fun configure(tracker: HealthTracker, handler: Handler, onLifecycleError: (String) -> Unit = {}) {
        this.tracker = tracker
        this.handler = handler
        this.onLifecycleError = onLifecycleError
    }

    protected fun setTrackerEventListener(listener: HealthTracker.TrackerEventListener) {
        this.listener = listener
    }

    fun start(onComplete: (Boolean) -> Unit = {}) {
        val localTracker = tracker
        val localHandler = handler
        val localListener = listener
        if (localTracker == null || localHandler == null || localListener == null) {
            onComplete(false)
            return
        }
        if (running) {
            onComplete(true)
            return
        }
        localHandler.post {
            val wrapped = object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) = localListener.onDataReceived(dataPoints)

                override fun onFlushCompleted() {
                    localListener.onFlushCompleted()
                    if (stopping) localHandler.post { finishStop(flushed = true) }
                }

                override fun onError(error: HealthTracker.TrackerError) = localListener.onError(error)
            }
            runCatching { localTracker.setEventListener(wrapped) }
                .onSuccess {
                    running = true
                    stopping = false
                    onComplete(true)
                }
                .onFailure { error ->
                    running = false
                    onLifecycleError?.invoke("Samsung listener start failed: ${error.message}")
                    onComplete(false)
                }
        }
    }

    /** Flushes while the listener is attached and detaches only after completion/timeout. */
    fun stop(timeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MILLIS, onComplete: (Boolean) -> Unit = {}) {
        val localHandler = handler
        val localTracker = tracker
        if (localHandler == null || localTracker == null) {
            onComplete(false)
            return
        }
        localHandler.post {
            if (!running) {
                detachListener(localTracker)
                onComplete(true)
                return@post
            }
            if (stopping) return@post
            stopping = true
            stopCallback = onComplete
            localHandler.postDelayed(stopTimeout, timeoutMillis.coerceAtLeast(0L))
            val accepted = runCatching { localTracker.flush() }
                .onFailure { error ->
                    onLifecycleError?.invoke("FLUSH_UNSUPPORTED: Samsung listener flush failed: ${error.message}")
                }
                .getOrDefault(false)
            if (!accepted) {
                onLifecycleError?.invoke("FLUSH_UNSUPPORTED: Samsung listener flush was rejected")
                finishStop(flushed = false)
            }
        }
    }

    @Suppress("ReturnCount")
    private fun finishStop(flushed: Boolean) {
        val localHandler = handler ?: return
        val localTracker = tracker ?: return
        if (!stopping) return
        stopping = false
        running = false
        localHandler.removeCallbacks(stopTimeout)
        detachListener(localTracker)
        val callback = stopCallback
        stopCallback = null
        callback?.invoke(flushed)
    }

    private fun detachListener(localTracker: HealthTracker) {
        runCatching { localTracker.unsetEventListener() }
            .onFailure { error -> onLifecycleError?.invoke("Samsung listener stop failed: ${error.message}") }
        running = false
    }

    private companion object {
        const val DEFAULT_FLUSH_TIMEOUT_MILLIS = 1_500L
    }
}
