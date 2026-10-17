package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.security.EncryptedFileRef
import com.stressdetector.mobile.security.EncryptedStorageService
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerbatimSessionFinalizerTest {
    @Test
    fun finalizerWritesFiveEncryptedLogicalArtifactsAndPreservesRawText() = runBlocking {
        val storage = FakeEncryptedStorage()
        storage.files["verbatim/session-1/audio.wav.enc"] = "RIFF".toByteArray()
        val trace = VerbatimAudioSignalTrace(storage).writer("local", "session-1")
        trace.append(LivePitchObservation("session-1", 0, 1_000, 110.0, LivePitchStatus.AVAILABLE, waveform = listOf(0.5f)))
        trace.finish()
        val finalizer = VerbatimSessionFinalizer(
            encryptedStorage = storage,
            llmEngine = FakeLlm(flowOf(LlmGenerationState.Content("ringkasan"))),
            promptAssetProvider = PromptAssetProvider.Static(testPrompt()),
            ragRetriever = FakeRagRetriever()
        )

        val artifacts = finalizer.finalize(
            userId = "local",
            sessionId = "session-1",
            customSessionName = "Pasien/../Sesi",
            recordedAudio = RecordedAudio(
                sessionId = "session-1",
                encryptedRelativePath = "verbatim/session-1/audio.wav.enc",
                startedAtUtcMillis = 1_735_689_600_000L,
                endedAtUtcMillis = 1_735_689_602_000L,
                durationMs = 2_000L,
                sampleRateHz = 16_000,
                signalTraceRoot = trace.root
            ),
            transcriptSegments = listOf(
                SpeechSegment("segment-1", "Patient", 0L, 2_000L, 1L, 2L, "raw text", 0.8f, "id")
            ),
            divergenceEvents = listOf(
                DivergenceEventEntity("event-1", "session-1", 1L, 0.2f, 0.8f, 0.6f, "review")
            ),
            monitorStatus = "UNAVAILABLE: no accepted model",
            audioStartSessionElapsedMillis = 137L,
            annotations = listOf(
                DomainAnnotation("annotation-1", "session-1", 1_500L, "review jawaban", "user")
            )
        )

        assertTrue(artifacts.folderPath.startsWith("verbatim_sessions/"))
        assertFalse(artifacts.folderPath.contains(".."))
        assertEquals("GENERATED", artifacts.summaryStatus)
        assertEquals(7, storage.files.keys.count { it.startsWith("${artifacts.folderPath}/") })
        val signal = VerbatimAudioSignalTrace(storage).readPage("local", "${artifacts.folderPath}/audio_signal", "session-1", 0)
        assertEquals(110.0, signal.observations.single().frequencyHz!!, 0.0)
        assertEquals(137L, signal.manifest!!.audioStartSessionElapsedMs)
        assertFalse(storage.files.keys.any { it.startsWith(trace.root) })
        assertEquals(PpgCaptureStatus.UNAVAILABLE.name, artifacts.ppgCaptureStatus)
        assertTrue(artifacts.ppgManifestPath != null)
        val transcript = storage.files[artifacts.transcriptPath]!!.toString(Charsets.UTF_8)
        assertTrue(transcript.contains("\"rawText\":\"raw text\""))
        assertTrue(transcript.contains("\"editedText\":\"raw text\""))
        val divergence = storage.files[artifacts.divergencePath]!!.toString(Charsets.UTF_8)
        assertTrue(divergence.contains("no accepted model"))
        assertTrue(divergence.contains("\"type\":\"manual_annotation\""))
        assertTrue(divergence.contains("review jawaban"))
        assertEquals("RIFF", storage.files[artifacts.audioPath]!!.toString(Charsets.UTF_8))
        assertFalse(storage.files.containsKey("verbatim/session-1/audio.wav.enc"))
    }

    @Test
    fun summaryUsesOnlyRetrievedLocalCitationsWhenGroundingPasses() = runBlocking {
        val storage = FakeEncryptedStorage()
        storage.files["verbatim/session-1/audio.wav.enc"] = "RIFF".toByteArray()
        var request: CounselorGenerationRequest? = null
        val finalizer = VerbatimSessionFinalizer(
            encryptedStorage = storage,
            llmEngine = object : OnDeviceLlmEngine {
                override fun readiness() = LlmReadiness.Ready("test")
                override suspend fun generate(input: CounselorGenerationRequest): Flow<LlmGenerationState> {
                    request = input
                    return flowOf(LlmGenerationState.Content("ringkasan grounded"))
                }
            },
            promptAssetProvider = PromptAssetProvider.Static(testPrompt()),
            ragRetriever = FakeRagRetriever()
        )

        val artifacts = finalizer.finalize(
            userId = "local",
            sessionId = "session-1",
            customSessionName = "Sesi",
            recordedAudio = RecordedAudio(
                sessionId = "session-1",
                encryptedRelativePath = "verbatim/session-1/audio.wav.enc",
                startedAtUtcMillis = 1_735_689_600_000L,
                endedAtUtcMillis = 1_735_689_602_000L,
                durationMs = 2_000L,
                sampleRateHz = 16_000
            ),
            transcriptSegments = listOf(SpeechSegment("one", "Unknown", 0, 2_000, 1, 2, "Saya ingin bercerita.", 0.8f)),
            divergenceEvents = emptyList(),
            monitorStatus = "READY"
        )

        assertEquals(RagMode.GROUNDED, request?.ragMode)
        assertEquals(listOf("local-1"), request?.retrievedCitations?.map { it.citationId })
        val summary = storage.files[artifacts.summaryPath]!!.toString(Charsets.UTF_8)
        assertTrue(summary.contains("Sumber lokal yang diambil"))
        assertTrue(summary.contains("[local-1] SOP Konseling — Dukungan"))
    }

    @Test
    fun unreviewedRepetitionIsArchivedButCannotProduceASummary() = runBlocking {
        val storage = FakeEncryptedStorage()
        storage.files["verbatim/session/audio.wav.enc"] = "RIFF".toByteArray()
        val raw = "terima kasih terima kasih terima kasih terima kasih"
        val finalizer = VerbatimSessionFinalizer(
            storage,
            object : OnDeviceLlmEngine {
                override fun readiness() = LlmReadiness.Ready("test")
                override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> =
                    error("Unreviewed ASR must not reach Gemma")
            },
            PromptAssetProvider.Static(testPrompt())
        )
        val artifacts = finalizer.finalize(
            "local",
            "session",
            "Review",
            RecordedAudio("session", "verbatim/session/audio.wav.enc", 1, 2_001, 2_000, 16_000),
            listOf(SpeechSegment("one", "Unknown", 0, 2_000, 1, 2_001, raw, 0.8f)),
            emptyList(),
            "UNAVAILABLE"
        )
        assertEquals("ERROR", artifacts.summaryStatus)
        assertTrue(storage.files.getValue(artifacts.transcriptPath).toString(Charsets.UTF_8).contains(raw))
        assertTrue(storage.files.getValue(artifacts.summaryPath).toString(Charsets.UTF_8).contains("No final transcript eligible"))
    }

    @Test
    fun optionalTraceFailuresPreserveCommittedAudioAndNativeDecoderEvidence() = runBlocking {
        listOf(false, true).forEach { corruptManifest ->
            val storage = FakeEncryptedStorage()
            storage.files["verbatim/session/audio.wav.enc"] = "RIFF".toByteArray()
            val trace = VerbatimAudioSignalTrace(storage).writer("local", "session")
            trace.append(LivePitchObservation("session", 0, 1_000, 110.0, LivePitchStatus.AVAILABLE))
            trace.finish()
            if (corruptManifest) {
                storage.files["${trace.root}/manifest.json.enc"] = "invalid JSON".toByteArray()
            } else {
                storage.files.remove("${trace.root}/chunk_000000.json.enc")
            }
            val artifacts = finalizeStored(storage, trace.root)
            assertEquals("RIFF", storage.files.getValue(artifacts.audioPath).toString(Charsets.UTF_8))
            assertTrue(storage.files.getValue(artifacts.transcriptPath).toString(Charsets.UTF_8).contains("whisper_tiny_int8"))
            assertTrue(artifacts.warnings.isNotEmpty())
            assertTrue(storage.files.containsKey("${artifacts.folderPath}/artifact_warnings.json.enc"))
            val page = VerbatimAudioSignalTrace(storage).readPage("local", "${artifacts.folderPath}/audio_signal", "session", 0)
            assertFalse(page.manifest!!.complete)
            assertEquals(120_000L, page.recordingEndOffsetMs)
        }
    }

    @Test
    fun cleanupFailureRetainsPublishedEvidenceAndRecordsExactPendingPath() = runBlocking {
        val storage = FakeEncryptedStorage()
        val source = "verbatim/session/audio.wav.enc"
        storage.files[source] = "RIFF".toByteArray()
        val retainingStorage = object : EncryptedStorageService by storage {
            override fun deleteEncryptedFile(userId: String, relativePath: String): Boolean =
                if (relativePath == source) false else storage.deleteEncryptedFile(userId, relativePath)
        }
        val artifacts = finalizeStored(retainingStorage)
        assertTrue(storage.files.containsKey(artifacts.audioPath))
        assertTrue(storage.files.containsKey(source))
        assertTrue(artifacts.warnings.any { source in it })
        assertTrue(storage.files.getValue("${artifacts.folderPath}/artifact_warnings.json.enc").toString(Charsets.UTF_8).contains(source))
    }

    private suspend fun finalizeStored(storage: EncryptedStorageService, signalRoot: String? = null): VerbatimSessionArtifacts {
        val raw = "Saya ingin bercerita."
        val evidence = SpeechDecoderEvidence(SpeechDecoderResult("whisper_tiny_int8", "a".repeat(40), 0, 2_000, raw))
        return VerbatimSessionFinalizer(
            storage,
            FakeLlm(flowOf(LlmGenerationState.Content("unit response"))),
            PromptAssetProvider.Static(testPrompt())
        ).finalize(
            "local",
            "session",
            "Review",
            RecordedAudio("session", "verbatim/session/audio.wav.enc", 1, 120_001, 120_000, 16_000, signalTraceRoot = signalRoot),
            listOf(SpeechSegment("one", "Unknown", 0, 2_000, 1, 2_001, raw, 0.8f, sessionId = "session", decoderEvidence = evidence)),
            emptyList(),
            "UNAVAILABLE",
            audioStartSessionElapsedMillis = 137L
        )
    }

    private fun testPrompt() = PromptAsset(
        id = "test",
        version = "1",
        systemPrompt = "system",
        userPromptTemplate = "{user_message}"
    )
}

private class FakeRagRetriever : RagRetriever {
    override suspend fun retrieveRelevantPassages(query: String, maxResults: Int, minRelevanceThreshold: Float): RagRetrievalResult =
        RagRetrievalResult.Retrieved(
            listOf(
                KnowledgeCitation(
                    citationId = "local-1",
                    sourceTitle = "SOP Konseling",
                    section = "Dukungan",
                    text = "Dukungan psikologis perlu mempertimbangkan konteks sesi.",
                    relevanceScore = 0.9f
                )
            )
        )
}

private class FakeLlm(private val states: Flow<LlmGenerationState>) : OnDeviceLlmEngine {
    override fun readiness() = LlmReadiness.Ready("test")
    override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> = states
}

private class FakeEncryptedStorage : EncryptedStorageService {
    val files = linkedMapOf<String, ByteArray>()

    override fun writeEncryptedFile(userId: String, fileName: String, data: InputStream): EncryptedFileRef {
        files[fileName] = data.readBytes()
        return EncryptedFileRef(fileName, files[fileName]!!.size.toLong())
    }

    override fun readEncryptedFile(userId: String, relativePath: String): InputStream =
        ByteArrayInputStream(files[relativePath] ?: error("Missing $relativePath"))

    override fun deleteEncryptedFile(userId: String, relativePath: String): Boolean = files.remove(relativePath) != null

    override fun listEncryptedFiles(userId: String): List<EncryptedFileRef> =
        files.map { (path, bytes) -> EncryptedFileRef(path, bytes.size.toLong()) }

    override fun deleteAllUserFiles(userId: String) = files.clear()
}
