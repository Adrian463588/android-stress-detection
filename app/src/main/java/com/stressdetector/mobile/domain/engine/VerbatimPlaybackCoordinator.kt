package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.security.PrivacyIdentity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Coordinates artifact resolution and one lifecycle-safe playback engine. */
class VerbatimPlaybackCoordinator(
    private val artifactService: VerbatimArtifactService,
    private val playbackEngine: AudioPlaybackEngine,
    private val userId: String = PrivacyIdentity.LOCAL_PROFILE_ID
) {
    private val commandMutex = Mutex()
    val state: StateFlow<PlaybackState> = playbackEngine.state
    private val _positionMillis = MutableStateFlow(playbackEngine.positionMillis.value)
    val positionMillis: StateFlow<Long> = _positionMillis.asStateFlow()
    private val _durationMillis = MutableStateFlow(playbackEngine.durationMillis.value)
    val durationMillis: StateFlow<Long> = _durationMillis.asStateFlow()
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loadedSessionId: String? = null
    private var loadedAudioPath: String? = null

    @Volatile
    private var loadedAudioTimeline = AudioTimeline(emptyList())

    init {
        playbackScope.launch {
            playbackEngine.positionMillis.collect { audioPosition ->
                _positionMillis.value = loadedAudioTimeline.audioOffsetToSessionOffset(audioPosition)
            }
        }
        playbackScope.launch {
            playbackEngine.durationMillis.collect { audioDuration ->
                _durationMillis.value = loadedAudioTimeline.sessionDurationForAudio(audioDuration)
            }
        }
    }

    suspend fun load(session: VerbatimSessionSummary): Result<File> = commandMutex.withLock {
        runCatching {
            val file = artifactService.prepareAudioForPlayback(userId, session)
            playbackEngine.prepare(file.absolutePath).getOrThrow()
            loadedSessionId?.takeIf { it != session.sessionId }?.let(artifactService::clearPlaybackCache)
            loadedSessionId = session.sessionId
            loadedAudioPath = file.absolutePath
            loadedAudioTimeline = AudioTimeline(session.audioGaps)
            _positionMillis.value = loadedAudioTimeline.audioOffsetToSessionOffset(playbackEngine.positionMillis.value)
            _durationMillis.value = loadedAudioTimeline.sessionDurationForAudio(playbackEngine.durationMillis.value)
            file
        }
    }

    suspend fun play(): Result<Unit> = commandMutex.withLock {
        if (state.value == PlaybackState.Playing) Result.success(Unit) else playbackEngine.play()
    }

    suspend fun pause(): Result<Unit> = commandMutex.withLock {
        if (state.value == PlaybackState.Paused) Result.success(Unit) else playbackEngine.pause()
    }

    suspend fun stop(): Result<Unit> = commandMutex.withLock {
        if (state.value == PlaybackState.Idle || state.value == PlaybackState.Stopped) {
            Result.success(Unit)
        } else {
            playbackEngine.stop()
        }
    }

    suspend fun seekTo(positionMillis: Long): Result<Unit> = commandMutex.withLock {
        val result = playbackEngine.seekTo(loadedAudioTimeline.sessionOffsetToAudioOffset(positionMillis))
        if (result.isSuccess) _positionMillis.value = positionMillis.coerceAtLeast(0L)
        result
    }

    /** Atomically prepares when needed, seeks to the marker, and starts playback. */
    suspend fun seekAndPlay(positionMillis: Long): Result<Unit> = commandMutex.withLock {
        runCatching {
            require(positionMillis >= 0L) { "Playback position must not be negative" }
            val audioPath = loadedAudioPath ?: error("Audio is not prepared")
            when (state.value) {
                PlaybackState.Idle,
                PlaybackState.Stopped,
                is PlaybackState.Error -> playbackEngine.prepare(audioPath).getOrThrow()
                PlaybackState.Preparing -> Unit
                PlaybackState.Ready,
                PlaybackState.Playing,
                PlaybackState.Paused -> Unit
            }
            awaitPrepared()
            playbackEngine.seekTo(loadedAudioTimeline.sessionOffsetToAudioOffset(positionMillis)).getOrThrow()
            _positionMillis.value = positionMillis
            if (state.value != PlaybackState.Playing) playbackEngine.play().getOrThrow()
        }
    }

    suspend fun closeAndWait() = commandMutex.withLock {
        playbackEngine.closeAndWait()
    }

    fun clearCache(sessionId: String? = null) {
        artifactService.clearPlaybackCache(sessionId)
        if (sessionId == null || loadedSessionId == sessionId) {
            loadedSessionId = null
            loadedAudioPath = null
            loadedAudioTimeline = AudioTimeline(emptyList())
            _positionMillis.value = 0L
            _durationMillis.value = 0L
        }
    }

    fun close() {
        playbackScope.cancel()
        playbackEngine.close()
    }

    private suspend fun awaitPrepared() {
        val preparedState = withTimeout(PREPARE_TIMEOUT_MILLIS) {
            state.first { current -> current != PlaybackState.Preparing }
        }
        check(
            preparedState == PlaybackState.Ready ||
                preparedState == PlaybackState.Playing ||
                preparedState == PlaybackState.Paused
        ) { "Audio preparation failed: $preparedState" }
    }

    private companion object {
        const val PREPARE_TIMEOUT_MILLIS = 5_000L
    }
}
