package com.stressdetector.wear.service

import android.os.SystemClock
import com.stressdetector.shared.SessionConfig
import com.stressdetector.shared.SessionIdentity
import com.stressdetector.shared.WearSessionMode

data class PpgSessionState(
    val sessionId: String,
    val sessionNonce: String = "",
    val protocolVersion: Int = SessionIdentity.CURRENT_PROTOCOL_VERSION,
    val protocolBuildFingerprint: String = SessionIdentity.CURRENT_PROTOCOL_BUILD,
    val mode: WearSessionMode = WearSessionMode.HOME_STRESS,
    val peerNodeId: String = "",
    val generation: Long = 0L,
    val startedAtMillis: Long,
    val startedElapsedRealtimeMillis: Long = startedAtMillis
) {
    val identity: SessionIdentity
        get() = SessionIdentity(sessionId, sessionNonce, protocolVersion)

    fun elapsed(nowElapsedRealtimeMillis: Long = SystemClock.elapsedRealtime()): Long {
        val elapsed = (nowElapsedRealtimeMillis - startedElapsedRealtimeMillis).coerceAtLeast(0L)
        return if (mode == WearSessionMode.HOME_STRESS) {
            elapsed.coerceAtMost(SessionConfig.SESSION_DURATION_MILLIS)
        } else {
            elapsed
        }
    }

    fun isComplete(nowElapsedRealtimeMillis: Long = SystemClock.elapsedRealtime()): Boolean = mode == WearSessionMode.HOME_STRESS &&
        elapsed(nowElapsedRealtimeMillis) >= SessionConfig.SESSION_DURATION_MILLIS
}
