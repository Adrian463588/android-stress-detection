package com.stressdetector.mobile.domain.engine

import com.stressdetector.shared.WearSessionMode
import kotlinx.coroutines.flow.StateFlow

sealed interface WatchSessionState {
    data object Idle : WatchSessionState
    data object Connecting : WatchSessionState
    data object Ready : WatchSessionState
    data object Streaming : WatchSessionState
    data object Stopping : WatchSessionState
    data class Error(val message: String) : WatchSessionState
}

interface WatchSessionCoordinator {
    val state: StateFlow<WatchSessionState>

    suspend fun start(sessionId: String, mode: WearSessionMode): Result<Unit>
    suspend fun stop(sessionId: String, completionExpected: Boolean = false): Result<Unit>
    suspend fun awaitDrain(sessionId: String, timeoutMillis: Long): Result<Unit>
}
