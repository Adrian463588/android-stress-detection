package com.stressdetector.mobile.data

import android.os.SystemClock
import com.stressdetector.shared.SessionConfig

/** Phone-side monotonic session clock. Sensor transport must not drive progress. */
class MonotonicSessionClock(
    private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() },
    private val durationMillis: Long = SessionConfig.SESSION_DURATION_MILLIS
) {
    private val lock = Any()
    private var activeSessionId: String? = null
    private var startedAtMillis: Long = 0L

    fun start(sessionId: String) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        synchronized(lock) {
            activeSessionId = sessionId
            startedAtMillis = nowMillis()
        }
    }

    fun elapsed(sessionId: String): Long = synchronized(lock) {
        if (activeSessionId != sessionId) return@synchronized 0L
        (nowMillis() - startedAtMillis).coerceIn(0L, durationMillis)
    }

    fun isComplete(sessionId: String): Boolean = elapsed(sessionId) >= durationMillis

    fun stop(sessionId: String) {
        synchronized(lock) {
            if (activeSessionId == sessionId) {
                activeSessionId = null
                startedAtMillis = 0L
            }
        }
    }
}
