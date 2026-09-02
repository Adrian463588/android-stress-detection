package com.stressdetector.mobile.domain.engine

import android.content.Context
import com.stressdetector.mobile.security.EncryptedFileRef
import com.stressdetector.mobile.security.EncryptedStorageService
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class VerbatimArtifactServiceTest {
    @Test
    fun `finalized artifact folder takes precedence over retired staging audio path`() {
        val cacheRoot = Files.createTempDirectory("verbatim-playback-test").toFile()
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.cacheDir).thenReturn(cacheRoot)
        val storage = RecordingStorage()
        val service = VerbatimArtifactService(context, storage)

        service.prepareAudioForPlayback(
            userId = "profile",
            session = VerbatimSessionSummary(
                sessionId = "session-1",
                sessionName = "Session",
                startedAtMillis = 1L,
                endedAtMillis = 2L,
                durationSeconds = 1L,
                audioFilePath = "verbatim/session-1/audio.wav.enc",
                artifactFolderPath = "verbatim_sessions/Session_01-01-2025_00-00-00",
                finalizationStatus = "COMPLETED",
                summaryStatus = "GENERATED"
            )
        )

        assertEquals(
            "verbatim_sessions/Session_01-01-2025_00-00-00/audio.wav.enc",
            storage.copiedPath
        )
        cacheRoot.deleteRecursively()
    }

    private class RecordingStorage : EncryptedStorageService {
        var copiedPath: String? = null

        override fun writeEncryptedFile(userId: String, fileName: String, data: InputStream) = EncryptedFileRef(fileName, 0L)

        override fun readEncryptedFile(userId: String, relativePath: String): InputStream = error("Not used")

        override fun copyEncryptedFile(userId: String, relativePath: String, output: OutputStream) {
            copiedPath = relativePath
            output.write(validWav())
        }

        override fun deleteEncryptedFile(userId: String, relativePath: String) = true

        override fun listEncryptedFiles(userId: String) = emptyList<EncryptedFileRef>()

        override fun deleteAllUserFiles(userId: String) = Unit

        private fun validWav(): ByteArray {
            val bytes = ByteArray(46)
            "RIFF".toByteArray().copyInto(bytes, 0)
            littleEndian(bytes, 4, 38)
            "WAVE".toByteArray().copyInto(bytes, 8)
            "fmt ".toByteArray().copyInto(bytes, 12)
            littleEndian(bytes, 16, 16)
            littleEndian(bytes, 20, 1)
            littleEndian(bytes, 22, 1)
            littleEndian(bytes, 24, 16_000)
            littleEndian(bytes, 28, 32_000)
            littleEndian(bytes, 32, 2)
            littleEndian(bytes, 34, 16)
            "data".toByteArray().copyInto(bytes, 36)
            littleEndian(bytes, 40, 2)
            return bytes
        }

        private fun littleEndian(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value and 0xff).toByte()
            target[offset + 1] = ((value ushr 8) and 0xff).toByte()
            target[offset + 2] = ((value ushr 16) and 0xff).toByte()
            target[offset + 3] = ((value ushr 24) and 0xff).toByte()
        }
    }
}
