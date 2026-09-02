package com.stressdetector.mobile.domain.engine

import android.content.Context
import com.stressdetector.mobile.security.EncryptedFileRef
import com.stressdetector.mobile.security.EncryptedStorageService
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class VerbatimPlaybackCoordinatorTest {
    @Test
    fun `seek delegates to engine and exposes synchronized position`() {
        runBlocking {
            val cacheRoot = Files.createTempDirectory("verbatim-coordinator-test").toFile()
            val context = mock(Context::class.java)
            `when`(context.applicationContext).thenReturn(context)
            `when`(context.cacheDir).thenReturn(cacheRoot)
            val engine = FakePlaybackEngine()
            val coordinator = VerbatimPlaybackCoordinator(
                artifactService = VerbatimArtifactService(context, NoOpStorage()),
                playbackEngine = engine
            )

            val result = coordinator.seekTo(1_500L)

            assertTrue(result.isSuccess)
            assertEquals(1_500L, engine.lastSeekMillis)
            assertEquals(1_500L, coordinator.positionMillis.value)
            assertEquals(10_000L, coordinator.durationMillis.value)
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun `seek and play is atomic after audio is loaded`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("verbatim-seek-play-test").toFile()
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.cacheDir).thenReturn(cacheRoot)
        val engine = FakePlaybackEngine()
        val coordinator = VerbatimPlaybackCoordinator(
            artifactService = VerbatimArtifactService(context, WavStorage()),
            playbackEngine = engine
        )
        coordinator.load(sessionSummary()).getOrThrow()

        val result = coordinator.seekAndPlay(2_500L)

        assertTrue(result.isSuccess)
        assertEquals(2_500L, engine.lastSeekMillis)
        assertEquals(PlaybackState.Playing, coordinator.state.value)
        cacheRoot.deleteRecursively()
        Unit
    }

    @Test
    fun `seek and play waits for asynchronous audio preparation`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("verbatim-pending-prepare-test").toFile()
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.cacheDir).thenReturn(cacheRoot)
        val engine = FakePlaybackEngine(prepareStartsPending = true)
        val coordinator = VerbatimPlaybackCoordinator(
            artifactService = VerbatimArtifactService(context, WavStorage()),
            playbackEngine = engine
        )
        coordinator.load(sessionSummary()).getOrThrow()

        val playback = async { coordinator.seekAndPlay(3_000L) }
        delay(25L)
        engine.completePreparation()

        assertTrue(playback.await().isSuccess)
        assertEquals(3_000L, engine.lastSeekMillis)
        assertEquals(PlaybackState.Playing, coordinator.state.value)
        cacheRoot.deleteRecursively()
        Unit
    }

    private class FakePlaybackEngine(private val prepareStartsPending: Boolean = false) : AudioPlaybackEngine {
        private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Ready)
        private val _positionMillis = MutableStateFlow(0L)
        private val _durationMillis = MutableStateFlow(10_000L)
        var lastSeekMillis: Long? = null

        override val state: StateFlow<PlaybackState> = _state
        override val positionMillis: StateFlow<Long> = _positionMillis
        override val durationMillis: StateFlow<Long> = _durationMillis

        override suspend fun prepare(audioPath: String): Result<Unit> {
            _state.value = if (prepareStartsPending) PlaybackState.Preparing else PlaybackState.Ready
            return Result.success(Unit)
        }

        fun completePreparation() {
            _state.value = PlaybackState.Ready
        }

        override suspend fun play(): Result<Unit> {
            _state.value = PlaybackState.Playing
            return Result.success(Unit)
        }
        override suspend fun pause() = Result.success(Unit)
        override suspend fun stop() = Result.success(Unit)

        override suspend fun seekTo(positionMillis: Long): Result<Unit> {
            lastSeekMillis = positionMillis
            _positionMillis.value = positionMillis
            return Result.success(Unit)
        }

        override fun close() = Unit
    }

    private class NoOpStorage : EncryptedStorageService {
        override fun writeEncryptedFile(userId: String, fileName: String, data: InputStream) = EncryptedFileRef(fileName, 0L)

        override fun readEncryptedFile(userId: String, relativePath: String): InputStream = error("Not used")

        override fun copyEncryptedFile(userId: String, relativePath: String, output: OutputStream) = error("Not used")

        override fun deleteEncryptedFile(userId: String, relativePath: String) = true
        override fun listEncryptedFiles(userId: String) = emptyList<EncryptedFileRef>()
        override fun deleteAllUserFiles(userId: String) = Unit
    }

    private inner class WavStorage : EncryptedStorageService by NoOpStorage() {
        override fun copyEncryptedFile(userId: String, relativePath: String, output: OutputStream) {
            output.write(validWav())
        }
    }

    private fun sessionSummary() = VerbatimSessionSummary(
        sessionId = "session-1",
        sessionName = "Session",
        startedAtMillis = 0L,
        endedAtMillis = 1L,
        durationSeconds = 1L,
        audioFilePath = "verbatim/session-1/audio.wav.enc",
        artifactFolderPath = "verbatim_sessions/session-1",
        finalizationStatus = "COMPLETED",
        summaryStatus = "GENERATED"
    )

    private fun validWav(): ByteArray = ByteBuffer.allocate(46)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            put("RIFF".toByteArray())
            putInt(38)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(16_000)
            putInt(32_000)
            putShort(2.toShort())
            putShort(16.toShort())
            put("data".toByteArray())
            putInt(2)
            putShort(0.toShort())
        }
        .array()
}
