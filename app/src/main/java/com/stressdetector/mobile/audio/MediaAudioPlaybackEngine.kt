package com.stressdetector.mobile.audio

import android.media.MediaPlayer
import com.stressdetector.mobile.domain.engine.AudioPlaybackEngine
import com.stressdetector.mobile.domain.engine.PlaybackState
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns one MediaPlayer; UI only sends lifecycle commands through this API. */
class MediaAudioPlaybackEngine(
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val playerFactory: () -> MediaPlayer = { MediaPlayer() },
    private val audioFocusCoordinator: AudioFocusCoordinator? = null
) : AudioPlaybackEngine {
    private val commandMutex = Mutex()
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    private val _positionMillis = MutableStateFlow(0L)
    private val _durationMillis = MutableStateFlow(0L)
    private val positionScope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private var player: MediaPlayer? = null
    private var prepared = false
    private var closed = false
    private var pendingPrepare: CancellableContinuation<Result<Unit>>? = null
    private var positionJob: Job? = null

    override val state: StateFlow<PlaybackState> = _state.asStateFlow()
    override val positionMillis: StateFlow<Long> = _positionMillis.asStateFlow()
    override val durationMillis: StateFlow<Long> = _durationMillis.asStateFlow()

    override suspend fun prepare(audioPath: String): Result<Unit> = commandMutex.withLock {
        withContext(mainDispatcher) { prepareInternal(audioPath) }
    }

    override suspend fun play(): Result<Unit> = commandMutex.withLock {
        withContext(mainDispatcher) {
            if (closed) return@withContext failure("Playback engine sudah ditutup.")
            val active = player
            if (!prepared || active == null) return@withContext failure("Audio belum siap diputar.")
            if (_state.value == PlaybackState.Playing) return@withContext Result.success(Unit)
            if (audioFocusCoordinator?.requestPlaybackFocus() == false) {
                return@withContext failure("Audio focus tidak tersedia untuk playback.")
            }
            runCatching {
                if (_state.value == PlaybackState.Stopped && active.currentPosition >= active.duration) {
                    active.seekTo(0)
                }
                active.start()
                _state.value = PlaybackState.Playing
                startPositionUpdates(active)
            }.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { failure(it.message ?: "Playback gagal dimulai.") }
            )
        }
    }

    override suspend fun pause(): Result<Unit> = commandMutex.withLock {
        withContext(mainDispatcher) {
            if (closed) return@withContext failure("Playback engine sudah ditutup.")
            val active = player
            if (!prepared || active == null) return@withContext failure("Audio belum siap dijeda.")
            runCatching {
                if (active.isPlaying) active.pause()
                refreshPosition(active)
                stopPositionUpdates()
                audioFocusCoordinator?.abandonPlaybackFocus()
                _state.value = PlaybackState.Paused
            }.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { failure(it.message ?: "Playback gagal dijeda.") }
            )
        }
    }

    override suspend fun stop(): Result<Unit> = commandMutex.withLock {
        withContext(mainDispatcher) {
            if (closed) return@withContext failure("Playback engine sudah ditutup.")
            val active = player
            if (!prepared || active == null) return@withContext failure("Audio belum siap dihentikan.")
            runCatching {
                if (active.isPlaying) active.pause()
                active.seekTo(0)
                _positionMillis.value = 0L
                stopPositionUpdates()
                audioFocusCoordinator?.abandonPlaybackFocus()
                _state.value = PlaybackState.Stopped
            }.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { failure(it.message ?: "Playback gagal dihentikan.") }
            )
        }
    }

    override suspend fun seekTo(positionMillis: Long): Result<Unit> = commandMutex.withLock {
        withContext(mainDispatcher) {
            if (closed) return@withContext failure("Playback engine sudah ditutup.")
            val active = player
            if (!prepared || active == null) return@withContext failure("Audio belum siap untuk seek.")
            runCatching {
                val duration = currentDurationMillis(active)
                val target = positionMillis.coerceIn(0L, duration).coerceAtMost(Int.MAX_VALUE.toLong())
                active.seekTo(target.toInt())
                _positionMillis.value = target
                _durationMillis.value = duration
            }.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { failure(it.message ?: "Audio gagal dipindahkan ke posisi yang diminta.") }
            )
        }
    }

    override suspend fun closeAndWait() = commandMutex.withLock {
        withContext(mainDispatcher) { closeInternal() }
    }

    override fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        if (closed) return
        closed = true
        pendingPrepare?.resume(Result.failure(IllegalStateException("Playback engine ditutup.")))
        pendingPrepare = null
        stopPositionUpdates()
        releasePlayer()
        positionScope.cancel()
        _positionMillis.value = 0L
        _durationMillis.value = 0L
        _state.value = PlaybackState.Idle
    }

    private suspend fun prepareInternal(audioPath: String): Result<Unit> {
        if (closed) return failure("Playback engine sudah ditutup.")
        if (audioPath.isBlank()) return failure("File audio tidak tersedia.")
        releasePlayer()
        _state.value = PlaybackState.Preparing
        val next = runCatching { playerFactory().apply { setDataSource(audioPath) } }
            .getOrElse { return failure(it.message ?: "Audio tidak dapat dibuka.") }
        player = next
        prepared = false
        return suspendCancellableCoroutine { continuation ->
            pendingPrepare = continuation
            next.setOnPreparedListener {
                if (closed || player !== next) return@setOnPreparedListener
                prepared = true
                _positionMillis.value = 0L
                _durationMillis.value = currentDurationMillis(next)
                _state.value = PlaybackState.Ready
                resumePreparation(Result.success(Unit))
            }
            next.setOnCompletionListener {
                if (!closed && player === next) {
                    stopPositionUpdates()
                    _positionMillis.value = _durationMillis.value
                    audioFocusCoordinator?.abandonPlaybackFocus()
                    _state.value = PlaybackState.Stopped
                }
            }
            next.setOnErrorListener { _, what, extra ->
                if (player !== next) return@setOnErrorListener true
                val error = "Audio playback gagal ($what/$extra)."
                prepared = false
                releasePlayer()
                _state.value = PlaybackState.Error(error)
                resumePreparation(Result.failure(IllegalStateException(error)))
                true
            }
            runCatching { next.prepareAsync() }.onFailure { error ->
                prepared = false
                releasePlayer()
                failure(error.message ?: "Audio tidak dapat dipersiapkan.")
                resumePreparation(Result.failure(error))
            }
            continuation.invokeOnCancellation {
                if (pendingPrepare === continuation) pendingPrepare = null
            }
        }
    }

    private fun resumePreparation(result: Result<Unit>) {
        pendingPrepare?.let { continuation ->
            pendingPrepare = null
            if (continuation.isActive) continuation.resume(result)
        }
    }

    private fun failure(message: String): Result<Unit> {
        _state.value = PlaybackState.Error(message)
        return Result.failure(IllegalStateException(message))
    }

    private fun releasePlayer() {
        stopPositionUpdates()
        player?.let { active -> runCatching { active.release() } }
        player = null
        prepared = false
        _positionMillis.value = 0L
        _durationMillis.value = 0L
        audioFocusCoordinator?.abandonPlaybackFocus()
    }

    private fun startPositionUpdates(active: MediaPlayer) {
        stopPositionUpdates()
        positionJob = positionScope.launch {
            while (isActive && !closed && player === active && prepared) {
                refreshPosition(active)
                delay(POSITION_POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun refreshPosition(active: MediaPlayer) {
        if (closed || player !== active || !prepared) return
        runCatching { active.currentPosition.toLong() }
            .onSuccess { _positionMillis.value = it.coerceIn(0L, _durationMillis.value.coerceAtLeast(0L)) }
    }

    private fun currentDurationMillis(active: MediaPlayer): Long = active.duration.toLong().coerceAtLeast(0L)

    private companion object {
        const val POSITION_POLL_INTERVAL_MILLIS = 250L
    }
}
