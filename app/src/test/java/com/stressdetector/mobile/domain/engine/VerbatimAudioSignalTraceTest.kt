package com.stressdetector.mobile.domain.engine

import com.google.gson.Gson
import com.stressdetector.mobile.security.EncryptedFileRef
import com.stressdetector.mobile.security.EncryptedStorageService
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Numerical/storage fixtures are not device accuracy evidence. */
class VerbatimAudioSignalTraceTest {
    @Test
    fun persistsPastLiveTailAndReadsOnlyRequestedPageIncludingFinalPartialChunk() {
        val storage = TraceTestStorage()
        val archive = VerbatimAudioSignalTrace(storage)
        val writer = archive.writer("local", "session")
        repeat(125) { writer.append(observation(it)) }
        writer.finish()
        writer.finish()
        archive.bindSessionOrigin("local", writer.root, "session", 137L)
        storage.reads.clear()

        val page = archive.readPage("local", writer.root, "session", 60_000L)

        assertEquals(60, page.observations.size)
        assertEquals(60_000L, page.observations.first().startOffsetMs)
        assertEquals(listOf(0.1f, 0.5f), page.observations.first().waveform)
        assertEquals(125L, page.manifest!!.observationCount)
        assertEquals(137L, page.manifest.audioStartSessionElapsedMs)
        assertTrue(page.manifest.complete)
        assertEquals(3, storage.reads.size)
        assertFalse(storage.reads.any { "chunk_000000" in it || "chunk_000004" in it })
        assertEquals(5, archive.readPage("local", writer.root, "session", 120_000).observations.size)
        assertEquals(6, archive.files("local", writer.root, "session").size)
    }

    @Test
    fun gapsRemainGapsAndPartialCaptureNeverBecomesComplete() {
        val storage = TraceTestStorage()
        val archive = VerbatimAudioSignalTrace(storage)
        val writer = archive.writer("local", "session")
        repeat(30) { writer.append(observation(it)) }
        writer.markFailure("worker interrupted")
        writer.append(observation(45))
        writer.finish()
        val page = archive.readPage("local", writer.root, "session", 0)
        assertFalse(page.manifest!!.complete)
        assertEquals("worker interrupted", page.error)
        assertEquals(listOf(29_000L, 45_000L), page.observations.takeLast(2).map { it.startOffsetMs })
        assertThrows(IllegalStateException::class.java) { archive.writer("local", "session") }
    }

    @Test
    fun rejectsCrossSessionCorruptChunksNonFiniteValuesAndTraversal() {
        val storage = TraceTestStorage()
        val archive = VerbatimAudioSignalTrace(storage)
        val writer = archive.writer("local", "session")
        assertThrows(IllegalArgumentException::class.java) { writer.append(observation(0).copy(sessionId = "other")) }
        writer.append(observation(0))
        writer.finish()
        assertThrows(IllegalArgumentException::class.java) { archive.readPage("local", writer.root, "other", 0) }
        assertThrows(IllegalArgumentException::class.java) { archive.readPage("local", "verbatim/../audio_signal", "session", 0) }
        val path = "${writer.root}/chunk_000000.json.enc"
        storage.files[path] = String(storage.files.getValue(path)).replace("0.5", "4.5").toByteArray()
        val failedPage = archive.readPage("local", writer.root, "session", 0)
        assertTrue(failedPage.error != null)
        assertTrue(failedPage.manifest != null)
        assertTrue(failedPage.observations.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { observation(0).copy(waveform = listOf(Float.NaN)) }
    }

    @Test
    fun envelopeUsesObservedAbsolutePeaksAndDoesNotFillInvalidPcm() {
        assertEquals(listOf(0.8f, 0.4f), pcmPeakEnvelope(floatArrayOf(-0.8f, 0.1f, 0.4f, -0.2f), 2))
        assertTrue(pcmPeakEnvelope(floatArrayOf(Float.NaN)).isEmpty())
        assertTrue(pcmPeakEnvelope(floatArrayOf(2f)).isEmpty())
        assertTrue(pcmPeakEnvelope(floatArrayOf()).isEmpty())
    }

    @Test
    fun denseManifestIsRejectedBeforeAllocatingAnyChunkPayload() {
        val storage = TraceTestStorage()
        val root = "verbatim/session/audio_signal"
        val chunks = List(18) { index ->
            AudioSignalChunk("chunk_${index.toString().padStart(6, '0')}.json.enc", index.toLong(), index + 1L, 30)
        }
        storage.files["$root/manifest.json.enc"] = Gson().toJson(AudioSignalTraceManifest("session", chunks, 540, true)).toByteArray()
        assertThrows(IllegalArgumentException::class.java) { VerbatimAudioSignalTrace(storage).readPage("local", root, "session", 0) }
        assertEquals(listOf("$root/manifest.json.enc"), storage.reads)
    }

    @Test
    fun finishCanRetryTransientWriteFailureAndAHealthyPageRemainsReachable() {
        val storage = TraceTestStorage()
        var failNextChunk = true
        val transient = object : EncryptedStorageService by storage {
            override fun writeEncryptedFile(userId: String, fileName: String, data: InputStream): EncryptedFileRef {
                if (failNextChunk && "chunk_" in fileName) {
                    failNextChunk = false
                    error("transient test write failure")
                }
                return storage.writeEncryptedFile(userId, fileName, data)
            }
        }
        val archive = VerbatimAudioSignalTrace(transient)
        val writer = archive.writer("local", "session")
        writer.append(observation(0))
        assertThrows(IllegalStateException::class.java) { writer.finish() }
        writer.finish()
        assertTrue(archive.readPage("local", writer.root, "session", 0).manifest!!.complete)
    }

    @Test
    fun missingPageRetainsNavigationToHealthyPagesAndKnownRecordingEnd() {
        val storage = TraceTestStorage()
        val archive = VerbatimAudioSignalTrace(storage)
        val writer = archive.writer("local", "session")
        repeat(90) { writer.append(observation(it)) }
        writer.finish()
        val manifestPath = "${writer.root}/manifest.json.enc"
        val manifest = archive.readManifest("local", writer.root, "session").copy(captureDurationMs = 180_000)
        storage.files[manifestPath] = Gson().toJson(manifest).toByteArray()
        storage.files.remove("${writer.root}/chunk_000002.json.enc")
        val unavailable = archive.readPage("local", writer.root, "session", 60_000)
        assertTrue(unavailable.error != null)
        assertEquals(180_000L, unavailable.recordingEndOffsetMs)
        assertEquals(60, archive.readPage("local", writer.root, "session", 0).observations.size)
        val tail = archive.readPage("local", writer.root, "session", 120_000)
        assertTrue(tail.observations.isEmpty())
        assertEquals(180_000L, tail.recordingEndOffsetMs)
    }

    @Test
    fun rollbackReportsEveryPublishedChunkWhoseDeletionCannotBeConfirmed() {
        val storage = TraceTestStorage()
        val writer = VerbatimAudioSignalTrace(storage).writer("local", "session")
        repeat(60) { writer.append(observation(it)) }
        writer.finish()
        storage.files.remove("${writer.root}/chunk_000001.json.enc")
        val destination = "verbatim_sessions/Review/audio_signal/chunk_000000.json.enc"
        val blockedCleanup = object : EncryptedStorageService by storage {
            override fun deleteEncryptedFile(userId: String, relativePath: String): Boolean =
                if (relativePath == destination) false else storage.deleteEncryptedFile(userId, relativePath)
        }
        val audio = RecordedAudio("session", "verbatim/session/audio.wav.enc", 1, 60_001, 60_000, 16_000, signalTraceRoot = writer.root)
        val result = VerbatimSignalTracePublication(blockedCleanup).publish("local", audio, "verbatim_sessions/Review", 137)
        assertTrue(result.warnings.any { destination in it })
        assertTrue(storage.files.containsKey(destination))
        assertTrue(result.sourceFiles.isEmpty())
    }

    private fun observation(second: Int) = LivePitchObservation(
        "session",
        second * 1_000L,
        (second + 1) * 1_000L,
        110.0,
        LivePitchStatus.AVAILABLE,
        waveform = listOf(0.1f, 0.5f)
    )
}

internal class TraceTestStorage : EncryptedStorageService {
    val files = linkedMapOf<String, ByteArray>()
    val reads = mutableListOf<String>()
    override fun writeEncryptedFile(userId: String, fileName: String, data: InputStream): EncryptedFileRef {
        files[fileName] = data.readBytes()
        return EncryptedFileRef(fileName, files.getValue(fileName).size.toLong())
    }
    override fun readEncryptedFile(userId: String, relativePath: String): InputStream {
        reads += relativePath
        return ByteArrayInputStream(files.getValue(relativePath))
    }
    override fun deleteEncryptedFile(userId: String, relativePath: String): Boolean = files.remove(relativePath) != null
    override fun listEncryptedFiles(userId: String): List<EncryptedFileRef> = files.map { EncryptedFileRef(it.key, it.value.size.toLong()) }
    override fun deleteAllUserFiles(userId: String) = files.clear()
}
