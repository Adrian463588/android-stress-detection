package com.stressdetector.mobile.domain.engine

import kotlinx.coroutines.flow.StateFlow

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Preparing : PlaybackState
    data object Ready : PlaybackState
    data object Playing : PlaybackState
    data object Paused : PlaybackState
    data object Stopped : PlaybackState
    data class Error(val message: String) : PlaybackState
}

interface AudioPlaybackEngine {
    val state: StateFlow<PlaybackState>
    val positionMillis: StateFlow<Long>
    val durationMillis: StateFlow<Long>
    suspend fun prepare(audioPath: String): Result<Unit>
    suspend fun play(): Result<Unit>
    suspend fun pause(): Result<Unit>
    suspend fun stop(): Result<Unit>
    suspend fun seekTo(positionMillis: Long): Result<Unit>
    suspend fun closeAndWait() {
        close()
    }
    fun close()
}
