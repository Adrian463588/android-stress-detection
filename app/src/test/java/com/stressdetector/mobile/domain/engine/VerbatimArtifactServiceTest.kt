package com.stressdetector.mobile.domain.engine

import android.content.Context
import com.google.gson.Gson
import com.stressdetector.mobile.security.EncryptedFileRef
import com.stressdetector.mobile.security.EncryptedStorageService
import com.stressdetector.mobile.security.SecureStorageException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun `failed decoder or prosody restoration blocks automatic cues and preserves Room edits`() {
        val archives = listOf(
            null,
            "{invalid",
            archivedRow(decoder = decoderEvidence().copy(status = SpeechRecognitionStatus.PARTIAL)),
            archivedRow(prosody = mapOf("features" to true))
        )
        archives.forEach { archive ->
            val storage = RecordingStorage()
            if (archive != null) storage.files[transcriptPath] = archive.toByteArray(Charsets.UTF_8)
            withService(storage) { service ->
                val original = segment()
                val edited = original.copy(id = "edited", editedText = "Saya tidak yakin.")
                val restored = service.restoreTranscriptEvidence("profile", detail(listOf(original, edited))).transcript

                assertTrue(VerbatimCueAnalyzer().analyze(original).cues.isNotEmpty())
                restored.forEach {
                    assertEquals(original.rawText, it.rawText)
                    assertNotNull(it.recognitionEvidenceUnavailableReason)
                    assertNotNull(it.prosody?.unavailableReason)
                    assertNull(it.decoderEvidence)
                }
                assertTrue(VerbatimCueAnalyzer().analyze(restored.first()).cues.isEmpty())
                assertEquals(edited.editedText, restored.last().editedText)
                assertNull(restored.last().recognitionReviewReason)
                assertTrue(VerbatimCueAnalyzer().analyze(restored.last()).cues.isNotEmpty())
            }
        }
    }

    @Test
    fun `decoder binding requires exact Room raw text offsets and session`() {
        val storage = RecordingStorage()
        storage.files[transcriptPath] = archivedRow().toByteArray(Charsets.UTF_8)
        withService(storage) { service ->
            val original = segment()
            val mismatches = listOf(
                original.copy(rawText = original.rawText + " "),
                original.copy(startOffsetMs = original.startOffsetMs + 1L),
                original.copy(endOffsetMs = original.endOffsetMs + 1L),
                original.copy(sessionId = "other-session")
            )
            mismatches.forEach { mismatch ->
                val restored = service.restoreTranscriptEvidence("profile", detail(listOf(mismatch))).transcript.single()
                assertEquals(mismatch.rawText, restored.rawText)
                assertEquals(mismatch.startOffsetMs, restored.startOffsetMs)
                assertEquals(mismatch.endOffsetMs, restored.endOffsetMs)
                assertEquals(mismatch.sessionId, restored.sessionId)
                assertNull(restored.decoderEvidence)
                assertNotNull(restored.recognitionEvidenceUnavailableReason)
                assertTrue(VerbatimCueAnalyzer().analyze(restored).cues.isEmpty())
            }

            val edited = original.copy(editedText = "Koreksi pengguna berdasarkan audio.")
            val matching = service.restoreTranscriptEvidence("profile", detail(listOf(edited))).transcript.single()
            assertEquals(decoderEvidence(), matching.decoderEvidence)
            assertEquals(prosodyEvidence(), matching.prosody)
            assertEquals(edited.editedText, matching.editedText)
            assertNull(matching.recognitionEvidenceUnavailableReason)
        }
    }

    @Test
    fun `legacy transcript without decoder fields is not a restoration failure`() {
        val storage = RecordingStorage()
        storage.files[transcriptPath] = archivedRow(decoder = null).toByteArray(Charsets.UTF_8)
        withService(storage) { service ->
            val restored = service.restoreTranscriptEvidence("profile", detail(listOf(segment()))).transcript.single()
            assertNull(restored.decoderEvidence)
            assertNull(restored.recognitionEvidenceUnavailableReason)
            assertNull(restored.recognitionReviewReason)
            assertEquals(prosodyEvidence(), restored.prosody)
            assertTrue(VerbatimCueAnalyzer().analyze(restored).cues.isNotEmpty())
        }
    }

    @Test
    fun `restoration propagates cancellation`() {
        val storage = RecordingStorage().apply { readFailure = CancellationException("cancel restore") }
        withService(storage) { service ->
            assertThrows(CancellationException::class.java) {
                service.restoreTranscriptEvidence("profile", detail(listOf(segment())))
            }
        }
    }

    @Test
    fun `missing decoder entries never clear an existing quarantine or accept a partially missing archive`() {
        val storage = RecordingStorage()
        withService(storage) { service ->
            storage.files[transcriptPath] = archivedRow(decoder = null).toByteArray(Charsets.UTF_8)
            val quarantined = listOf(
                segment().copy(recognitionEvidenceUnavailableReason = "native evidence unavailable"),
                segment().copy(
                    rawText = decoderEvidence().wholeUtterance.rawText,
                    decoderEvidence = decoderEvidence().copy(crop = null, status = SpeechRecognitionStatus.SPEAKER_SPANS_UNAVAILABLE)
                )
            )
            quarantined.forEach {
                val restored = service.restoreTranscriptEvidence("profile", detail(listOf(it))).transcript.single()
                assertNotNull(restored.recognitionReviewReason)
                assertTrue(VerbatimCueAnalyzer().analyze(restored).cues.isEmpty())
            }
            storage.files[transcriptPath] = archivedRow().toByteArray(Charsets.UTF_8)
            val missing = segment().copy(id = "missing")
            val restored = service.restoreTranscriptEvidence("profile", detail(listOf(segment(), missing))).transcript.last()
            assertNotNull(restored.recognitionEvidenceUnavailableReason)
        }
    }

    @Test
    fun `delete removes only the exact owned staging nonce folders`() {
        val storage = RecordingStorage()
        val nonce = "12345678-1234-1234-1234-123456789abc"
        val owned = "verbatim_sessions/Session.__staging_$nonce/audio.wav.enc"
        val other = "verbatim_sessions/Session.__staging_${nonce}_other/audio.wav.enc"
        storage.files[owned] = byteArrayOf(1)
        storage.files[other] = byteArrayOf(1)
        withService(storage) { service ->
            assertTrue(service.deleteSession("profile", "verbatim_sessions/Session", "session-1"))
            assertEquals(setOf(other), storage.files.keys)
        }
    }

    @Test
    fun `deletion without artifact paths removes only the owned capture prefix and is idempotent`() {
        val storage = RecordingStorage()
        val owned = listOf(
            "verbatim/session-1/audio_signal/manifest.json.enc",
            "verbatim/session-1/audio_signal/chunk_000000.json.enc",
            "verbatim/session-1/.audio.wav.enc.bak"
        )
        val other = setOf(
            "verbatim/session-10/audio_signal/manifest.json.enc",
            "verbatim/session-2/audio.wav.enc",
            "verbatim_sessions/Other/audio.wav.enc"
        )
        (owned + other).forEach { storage.files[it] = byteArrayOf(1) }
        withService(storage) { service ->
            assertTrue(service.deleteSession("profile", "", "session-1"))
            assertEquals(other, storage.files.keys)
            assertTrue(service.deleteSession("profile", "", "session-1"))
            assertEquals(other, storage.files.keys)
        }
    }

    @Test
    fun `deletion validates identity before touching files and preserves the folder-only API`() {
        val storage = RecordingStorage()
        val archivePath = "verbatim_sessions/Session/audio.wav.enc"
        val capturePath = "verbatim/session-1/audio_signal/manifest.json.enc"
        storage.files[archivePath] = byteArrayOf(1)
        storage.files[capturePath] = byteArrayOf(1)
        withService(storage) { service ->
            listOf(null, "", "../session-1", "session-1/child", "session-1\\child").forEach { sessionId ->
                assertThrows(IllegalArgumentException::class.java) { service.deleteSession("profile", "", sessionId) }
                assertEquals(setOf(archivePath, capturePath), storage.files.keys)
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.deleteSession("profile", "verbatim_sessions/../Other", "session-1")
            }
            assertTrue(service.deleteSession("profile", "verbatim_sessions/Session"))
            assertFalse(archivePath in storage.files)
            assertTrue(capturePath in storage.files)
        }
    }

    private fun withService(storage: RecordingStorage, block: (VerbatimArtifactService) -> Unit) {
        val cacheRoot = Files.createTempDirectory("verbatim-artifact-test").toFile()
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.cacheDir).thenReturn(cacheRoot)
        try {
            block(VerbatimArtifactService(context, storage))
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    private fun segment() = SpeechSegment(
        "segment", "Unknown", 100L, 1_800L, 101L, 1_801L, "Mungkin saya ingat.", 0.8f, sessionId = "session-1"
    )

    private fun detail(transcript: List<SpeechSegment>) = VerbatimSessionDetail(
        VerbatimSessionSummary("session-1", "Session", 1L, 4_001L, 4L, "", "verbatim_sessions/Session", "COMPLETED", "GENERATED"),
        transcript,
        emptyList()
    )

    private fun decoderEvidence(): SpeechDecoderEvidence {
        val whole = SpeechDecoderResult("whisper_tiny_int8", "a".repeat(40), 0L, 4_000L, "Ujaran utuh dari decoder.")
        return SpeechDecoderEvidence(whole, crop = whole.copy(startOffsetMs = 100L, endOffsetMs = 1_800L, rawText = segment().rawText))
    }

    private fun prosodyEvidence() = VerbatimProsodyEvidence(mapOf("F0semitoneFrom27.5Hz" to 30.0), 100, 50)

    private fun archivedRow(decoder: SpeechDecoderEvidence? = decoderEvidence(), prosody: Any = prosodyEvidence()): String {
        val segment = segment()
        val row = mutableMapOf<String, Any>(
            "sessionId" to segment.sessionId,
            "segmentId" to segment.id,
            "rawText" to segment.rawText,
            "language" to segment.language,
            "startOffsetMs" to segment.startOffsetMs,
            "endOffsetMs" to segment.endOffsetMs,
            "prosodyEvidence" to prosody
        )
        if (decoder != null) row["decoderEvidence"] = decoder
        return Gson().toJson(row)
    }

    private val transcriptPath = "verbatim_sessions/Session/live_transcript.jsonl.enc"

    private class RecordingStorage : EncryptedStorageService {
        var copiedPath: String? = null
        val files = linkedMapOf<String, ByteArray>()
        var readFailure: Exception? = null

        override fun writeEncryptedFile(userId: String, fileName: String, data: InputStream) = EncryptedFileRef(fileName, 0L)

        override fun readEncryptedFile(userId: String, relativePath: String): InputStream {
            readFailure?.let { throw it }
            return files[relativePath]?.inputStream() ?: throw SecureStorageException.MissingFile()
        }

        override fun copyEncryptedFile(userId: String, relativePath: String, output: OutputStream) {
            copiedPath = relativePath
            output.write(validWav())
        }

        override fun deleteEncryptedFile(userId: String, relativePath: String): Boolean {
            files.remove(relativePath)
            return true
        }

        override fun listEncryptedFiles(userId: String) = files.map { EncryptedFileRef(it.key, it.value.size.toLong()) }

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
