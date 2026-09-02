package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.security.EncryptedFileRef
import com.stressdetector.mobile.security.EncryptedStorageService
import com.stressdetector.shared.PpgBatch
import com.stressdetector.shared.PpgType
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerbatimPpgArchiveTest {
    @Test
    fun storesAcknowledgedRawBatchesInBoundedChunksAndMarksSequenceGap() {
        val storage = MemoryStorage()
        val archive = VerbatimPpgArchive(storage)

        assertTrue(archive.begin("session-a", "nonce-a", "user-a").isSuccess)
        val first = batch(sequence = 1L, elapsed = 0L)
        val gap = batch(sequence = 3L, elapsed = 31_000L)
        archive.append(listOf(first, first, gap))

        val result = archive.drainAndFinalize()

        assertEquals(PpgCaptureStatus.PARTIAL, result.status)
        assertEquals(4L, result.sampleCounts["ppg_green"])
        assertEquals(1L, result.sequenceGapCount)
        assertTrue(result.manifestPath != null)
        assertTrue(result.chunkPaths.size >= 2)
        assertTrue(result.chunkRelativePaths.all { it.startsWith("ppg_raw/") })
        assertEquals(1, storage.readPlain(result.chunkPaths.first()).split("\n").count { it.isNotBlank() })
    }

    private fun batch(sequence: Long, elapsed: Long): PpgBatch = PpgBatch(
        type = PpgType.GREEN,
        timestampsMillis = longArrayOf(elapsed + 1L, elapsed + 2L),
        rawValues = intArrayOf(100, 101),
        sessionId = "session-a",
        elapsedMillis = elapsed + 2L,
        batchId = PpgBatch.batchIdFor("session-a", PpgType.GREEN, sequence),
        sequenceNumber = sequence,
        samplingRateHz = PpgType.GREEN.sourceRateHz,
        elapsedStartMillis = elapsed,
        elapsedEndMillis = elapsed + 2L,
        sessionNonce = "nonce-a"
    )

    private class MemoryStorage : EncryptedStorageService {
        private val files = linkedMapOf<String, ByteArray>()

        override fun writeEncryptedFile(userId: String, fileName: String, data: InputStream): EncryptedFileRef {
            val bytes = data.readBytes()
            files[fileName] = bytes
            return EncryptedFileRef(fileName, bytes.size.toLong())
        }

        override fun readEncryptedFile(userId: String, relativePath: String): InputStream =
            ByteArrayInputStream(files.getValue(relativePath))

        override fun deleteEncryptedFile(userId: String, relativePath: String): Boolean = files.remove(relativePath) != null

        override fun listEncryptedFiles(userId: String): List<EncryptedFileRef> = files.map { (path, bytes) ->
            EncryptedFileRef(path, bytes.size.toLong())
        }

        override fun deleteAllUserFiles(userId: String) = files.clear()

        fun readPlain(path: String): String = files.getValue(path).toString(Charsets.UTF_8)
    }
}
