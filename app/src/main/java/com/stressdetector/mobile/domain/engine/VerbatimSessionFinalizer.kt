package com.stressdetector.mobile.domain.engine

import com.google.gson.Gson
import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.security.EncryptedStorageService
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull

data class VerbatimSessionArtifacts(
    val folderPath: String,
    val audioPath: String,
    val transcriptPath: String,
    val divergencePath: String,
    val summaryPath: String,
    val summaryStatus: String,
    val ppgManifestPath: String? = null,
    val ppgCaptureStatus: String = PpgCaptureStatus.UNAVAILABLE.name,
    val warnings: List<String> = emptyList()
)

/** Publishes encrypted, app-private session artifacts through one finalization boundary. */
class VerbatimSessionFinalizer(
    private val encryptedStorage: EncryptedStorageService,
    private val llmEngine: OnDeviceLlmEngine,
    private val promptAssetProvider: PromptAssetProvider,
    private val ragRetriever: RagRetriever? = null,
    private val gson: Gson = Gson()
) {
    @Suppress(
        "LongParameterList",
        "LongMethod",
        "CyclomaticComplexMethod",
        "NestedBlockDepth",
        "ExceptionRaisedInUnexpectedLocation"
    )
    suspend fun finalize(
        userId: String,
        sessionId: String,
        customSessionName: String,
        recordedAudio: RecordedAudio,
        transcriptSegments: List<SpeechSegment>,
        divergenceEvents: List<DivergenceEventEntity>,
        monitorStatus: String,
        annotations: List<DomainAnnotation> = emptyList(),
        ppgArchive: VerbatimPpgArchiveResult = VerbatimPpgArchiveResult.unavailable("archive_not_provided"),
        audioStartSessionElapsedMillis: Long? = null
    ): VerbatimSessionArtifacts {
        require(recordedAudio.sessionId == sessionId) { "Recorded audio belongs to another session." }
        val folder = "verbatim_sessions/${folderName(customSessionName, recordedAudio.startedAtUtcMillis)}"
        val audioPath = "$folder/audio.wav.enc"
        val transcriptPath = "$folder/live_transcript.jsonl.enc"
        val divergencePath = "$folder/divergence_events.jsonl.enc"
        val summaryPath = "$folder/ai_summary.txt.enc"
        val ppgManifestPath = "$folder/ppg_manifest.json.enc"
        // The clinical recording and transport artifacts are the source of truth.
        // Gemma summary generation is useful, but it must never leave the whole
        // session in a permanent "saving" state when the local runtime stalls.
        val summary = withTimeoutOrNull(SUMMARY_TIMEOUT_MILLIS) {
            generateSummary(sessionId, transcriptSegments)
        } ?: summaryError("AI summary timed out; recording artifacts were saved.")

        val stagingFolder = "$folder.__staging_${UUID.randomUUID()}"
        val coreStaged = listOf(
            "$stagingFolder/audio.wav.enc",
            "$stagingFolder/live_transcript.jsonl.enc",
            "$stagingFolder/divergence_events.jsonl.enc",
            "$stagingFolder/ai_summary.txt.enc"
        )
        val corePublished = listOf(audioPath, transcriptPath, divergencePath, summaryPath)
        var ppgStatus = ppgArchive.status
        var ppgRawPublished = false
        var ppgManifestStaged = false
        var ppgChunksStagedSuccessfully = true
        val ppgStagedChunks = mutableListOf<Pair<String, String>>()
        try {
            encryptedStorage.readEncryptedFile(userId, recordedAudio.encryptedRelativePath).use { input ->
                encryptedStorage.writeEncryptedFile(userId, coreStaged[0], input)
            }
            encryptedStorage.writeEncryptedFile(
                userId,
                coreStaged[1],
                ByteArrayInputStream(transcriptJsonl(sessionId, transcriptSegments).toByteArray(Charsets.UTF_8))
            )
            encryptedStorage.writeEncryptedFile(
                userId,
                coreStaged[2],
                ByteArrayInputStream(
                    divergenceJsonl(sessionId, monitorStatus, divergenceEvents, annotations)
                        .toByteArray(Charsets.UTF_8)
                )
            )
            encryptedStorage.writeEncryptedFile(
                userId,
                coreStaged[3],
                ByteArrayInputStream(summary.first.toByteArray(Charsets.UTF_8))
            )

            runCatching {
                ppgArchive.chunkPaths.zip(ppgArchive.chunkRelativePaths).forEach { (sourcePath, relativePath) ->
                    val stagedPath = "$stagingFolder/$relativePath"
                    encryptedStorage.readEncryptedFile(userId, sourcePath).use { input ->
                        encryptedStorage.writeEncryptedFile(userId, stagedPath, input)
                    }
                    ppgStagedChunks += stagedPath to "$folder/$relativePath"
                }
            }.onFailure {
                ppgChunksStagedSuccessfully = false
                ppgStatus = PpgCaptureStatus.PARTIAL
                ppgStagedChunks.forEach { (stagedPath, _) -> encryptedStorage.deleteEncryptedFile(userId, stagedPath) }
                ppgStagedChunks.clear()
            }
            val ppgManifest = if (ppgChunksStagedSuccessfully &&
                ppgStatus == ppgArchive.status &&
                ppgArchive.manifestJson.isNotBlank()
            ) {
                ppgArchive.manifestJson
            } else {
                gson.toJson(
                    mapOf(
                        "schemaVersion" to 1,
                        "sessionId" to sessionId,
                        "status" to ppgStatus.name,
                        "chunkFiles" to ppgStagedChunks.map { (_, publishedPath) ->
                            publishedPath.removePrefix("$folder/")
                        },
                        "sampleCounts" to ppgArchive.sampleCounts,
                        "firstElapsedMillis" to ppgArchive.firstElapsedMillis,
                        "lastElapsedMillis" to ppgArchive.lastElapsedMillis,
                        "sequenceGapCount" to ppgArchive.sequenceGapCount,
                        "reason" to (ppgArchive.reason ?: "PPG archive unavailable")
                    )
                )
            }
            runCatching {
                encryptedStorage.writeEncryptedFile(
                    userId,
                    "$stagingFolder/ppg_manifest.json.enc",
                    ByteArrayInputStream(ppgManifest.toByteArray(Charsets.UTF_8))
                )
                ppgManifestStaged = true
            }.onFailure {
                ppgStatus = PpgCaptureStatus.UNAVAILABLE
            }
            if (!ppgManifestStaged) {
                ppgStagedChunks.forEach { (stagedPath, _) -> encryptedStorage.deleteEncryptedFile(userId, stagedPath) }
                ppgStagedChunks.clear()
            }

            coreStaged.zip(corePublished).forEach { (stagedPath, finalPath) ->
                encryptedStorage.readEncryptedFile(userId, stagedPath).use { input ->
                    encryptedStorage.writeEncryptedFile(userId, finalPath, input)
                }
            }
            if (ppgManifestStaged) {
                encryptedStorage.readEncryptedFile(userId, "$stagingFolder/ppg_manifest.json.enc").use { input ->
                    encryptedStorage.writeEncryptedFile(userId, ppgManifestPath, input)
                }
                ppgStagedChunks.forEach { (stagedPath, publishedPath) ->
                    encryptedStorage.readEncryptedFile(userId, stagedPath).use { input ->
                        encryptedStorage.writeEncryptedFile(userId, publishedPath, input)
                    }
                }
                ppgRawPublished = true
            }
        } catch (error: Throwable) {
            coreStaged.forEach { encryptedStorage.deleteEncryptedFile(userId, it) }
            encryptedStorage.deleteEncryptedFile(userId, "$stagingFolder/ppg_manifest.json.enc")
            ppgStagedChunks.forEach { (stagedPath, _) -> encryptedStorage.deleteEncryptedFile(userId, stagedPath) }
            corePublished.forEach { encryptedStorage.deleteEncryptedFile(userId, it) }
            if (ppgRawPublished) {
                encryptedStorage.deleteEncryptedFile(userId, ppgManifestPath)
                ppgStagedChunks.forEach { (_, publishedPath) -> encryptedStorage.deleteEncryptedFile(userId, publishedPath) }
            }
            throw error
        }
        // The core is committed. Optional trace and cleanup failures cannot roll it back.
        val tracePublisher = VerbatimSignalTracePublication(encryptedStorage)
        val trace = tracePublisher.publish(userId, recordedAudio, folder, audioStartSessionElapsedMillis)
        val cleanupPaths = buildList {
            addAll(coreStaged)
            if (ppgManifestStaged) add("$stagingFolder/ppg_manifest.json.enc")
            addAll(ppgStagedChunks.map { it.first })
            add(recordedAudio.encryptedRelativePath)
            addAll(trace.sourceFiles)
            if (ppgRawPublished) {
                ppgArchive.manifestPath?.let(::add)
                addAll(ppgArchive.chunkPaths)
            }
        }
        val cleanupWarnings = pendingArtifactCleanup(encryptedStorage, userId, cleanupPaths)
        val warnings = tracePublisher.recordWarnings(userId, folder, trace.warnings + cleanupWarnings)
        return VerbatimSessionArtifacts(
            folderPath = folder,
            audioPath = audioPath,
            transcriptPath = transcriptPath,
            divergencePath = divergencePath,
            summaryPath = summaryPath,
            summaryStatus = summary.second,
            ppgManifestPath = ppgManifestPath.takeIf { ppgRawPublished },
            ppgCaptureStatus = ppgStatus.name,
            warnings = warnings
        )
    }

    private suspend fun generateSummary(sessionId: String, transcriptSegments: List<SpeechSegment>): Pair<String, String> {
        val reviewed = transcriptSegments.filter { it.isFinal && it.recognitionReviewReason == null && it.displayText.isNotBlank() }
        return if (reviewed.isEmpty()) {
            summaryError("No final transcript eligible for summary; review the original audio.")
        } else {
            generateReviewedSummary(sessionId, reviewed, transcriptSegments.size - reviewed.size)
        }
    }

    private suspend fun generateReviewedSummary(sessionId: String, reviewed: List<SpeechSegment>, excluded: Int): Pair<String, String> {
        val promptAsset = runCatching { promptAssetProvider.getCounselorPrompt() }
            .getOrElse { return summaryError("Prompt asset unavailable: ${it.message}") }
        val transcript = reviewed.joinToString("\n") { segment ->
            "[${segment.startTimestampUtc}] ${segment.speakerId}: ${segment.displayText}"
        }.take(MAX_TRANSCRIPT_CHARS)
        val grounding = retrieveGrounding(transcript)
        val request = CounselorGenerationRequest(
            promptAsset = promptAsset,
            userMessage = "Buat ringkasan sesi Verbatim+ secara non-diagnostik. Session: $sessionId\n$transcript",
            ragMode = grounding.first,
            retrievedCitations = grounding.second,
            stressHistoryContext = ""
        )
        return try {
            val assembler = GenerationTextAssembler()
            llmEngine.generate(request).collect { state ->
                appendSummaryContent(state, assembler)
            }
            val text = assembler.currentText().trim()
            if (text.isBlank()) {
                summaryError("Gemma returned an empty response.")
            } else {
                val note = if (excluded > 0) "Catatan: $excluded hasil ASR belum ditinjau dan tidak digunakan dalam ringkasan.\n\n" else ""
                note + renderSummaryWithCitations(text, grounding.second) to "GENERATED"
            }
        } catch (error: SummaryGenerationException) {
            summaryError(error.message ?: "Gemma generation failed.")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            summaryError(error.message ?: "Gemma generation failed.")
        }
    }

    private fun appendSummaryContent(state: LlmGenerationState, assembler: GenerationTextAssembler) {
        val failure = when (state) {
            is LlmGenerationState.Content -> {
                assembler.append(state)
                null
            }
            is LlmGenerationState.Unavailable -> "Local Gemma model is unavailable: ${state.readiness}"
            is LlmGenerationState.Failed -> state.reason
            LlmGenerationState.Cancelled -> "Generation was cancelled."
        }
        if (failure != null) throw SummaryGenerationException(failure)
    }

    private suspend fun retrieveGrounding(transcript: String): Pair<RagMode, List<KnowledgeCitation>> {
        val retriever = ragRetriever ?: return RagMode.GENERAL_SAFE to emptyList()
        val result = withTimeoutOrNull(RAG_TIMEOUT_MILLIS) {
            retriever.retrieveRelevantPassages(
                query = "Ringkasan non-diagnostik sesi konseling:\n$transcript",
                maxResults = MAX_RAG_RESULTS,
                minRelevanceThreshold = MIN_RAG_RELEVANCE
            )
        }
        return when (result) {
            is RagRetrievalResult.Retrieved -> {
                val citations = result.citations.filter { it.relevanceScore >= MIN_RAG_RELEVANCE }
                if (citations.isEmpty()) {
                    RagMode.GENERAL_SAFE to emptyList()
                } else {
                    when (result.sourceTier) {
                        EvidenceSourceTier.BUNDLED_LOCAL -> RagMode.BUNDLED_GROUNDED
                        EvidenceSourceTier.WEB -> RagMode.WEB_GROUNDED
                        EvidenceSourceTier.APP_RAG,
                        EvidenceSourceTier.NONE -> RagMode.GROUNDED
                    } to citations
                }
            }
            else -> RagMode.GENERAL_SAFE to emptyList()
        }
    }

    private fun renderSummaryWithCitations(summary: String, citations: List<KnowledgeCitation>): String {
        if (citations.isEmpty()) return summary
        return buildString {
            append(summary)
            append("\n\nSumber lokal yang diambil:\n")
            citations.forEach { citation ->
                append("- [")
                append(citation.citationId)
                append("] ")
                append(citation.sourceTitle)
                if (citation.section.isNotBlank()) append(" — ").append(citation.section)
                citation.sourceFileName?.let { append("; file=").append(it) }
                citation.sourcePage?.let { append("; page=").append(it) }
                citation.sourceLocator?.let { append("; locator=").append(it) }
                appendLine()
            }
        }.trimEnd()
    }

    private fun transcriptJsonl(sessionId: String, segments: List<SpeechSegment>): String = buildString {
        segments.forEach { segment ->
            appendLine(
                gson.toJson(
                    mapOf(
                        "type" to "transcript_segment",
                        "sessionId" to sessionId,
                        "segmentId" to segment.id,
                        "speakerId" to segment.speakerId,
                        "rawText" to segment.rawText,
                        "editedText" to segment.displayText,
                        "startOffsetMs" to segment.startOffsetMs,
                        "endOffsetMs" to segment.endOffsetMs,
                        "startTimestampUtc" to segment.startTimestampUtc,
                        "endTimestampUtc" to segment.endTimestampUtc,
                        "language" to segment.language,
                        "confidence" to segment.confidence,
                        "isDisfluent" to segment.isDisfluent,
                        "decoderEvidence" to segment.decoderEvidence,
                        "recognitionReviewReason" to segment.recognitionReviewReason,
                        "recognitionEvidenceUnavailableReason" to segment.recognitionEvidenceUnavailableReason,
                        "reviewCueAnalysis" to VerbatimCueAnalyzer().analyze(segment),
                        "prosodyEvidence" to segment.prosody
                    )
                )
            )
        }
    }

    private fun divergenceJsonl(
        sessionId: String,
        monitorStatus: String,
        events: List<DivergenceEventEntity>,
        annotations: List<DomainAnnotation>
    ): String = buildString {
        appendLine(
            gson.toJson(
                mapOf(
                    "type" to "monitor_status",
                    "sessionId" to sessionId,
                    "status" to monitorStatus,
                    "timestampUtc" to System.currentTimeMillis()
                )
            )
        )
        events.forEach { event -> appendLine(gson.toJson(event)) }
        annotations.forEach { annotation ->
            appendLine(
                gson.toJson(
                    mapOf(
                        "type" to "manual_annotation",
                        "markerType" to "MANUAL",
                        "sessionId" to sessionId,
                        "annotationId" to annotation.id,
                        "timestampMs" to annotation.timestampMs,
                        "text" to annotation.text,
                        "author" to annotation.author,
                        "citationId" to annotation.citationId
                    )
                )
            )
        }
    }

    private fun summaryError(reason: String): Pair<String, String> = "AI_SUMMARY_STATUS: ERROR\nREASON: $reason" to "ERROR"

    private fun folderName(name: String, timestamp: Long): String {
        val safeName = name.trim()
            .replace(UNSAFE_FILENAME, "_")
            .replace("..", "_")
            .replace(Regex("\\s+"), " ")
            .take(MAX_NAME_LENGTH)
            .ifBlank { "Session" }
        val time = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.US).format(Date(timestamp))
        return "${safeName}_$time"
    }

    private class SummaryGenerationException(message: String) : Exception(message)

    private companion object {
        const val MAX_NAME_LENGTH = 80
        const val MAX_TRANSCRIPT_CHARS = 8_000
        const val SUMMARY_TIMEOUT_MILLIS = 20_000L
        const val RAG_TIMEOUT_MILLIS = 2_000L
        const val MAX_RAG_RESULTS = 5
        const val MIN_RAG_RELEVANCE = 0.65f
        val UNSAFE_FILENAME = Regex("[\\u0000-\\u001F<>:\"/\\\\|?*]")
    }
}
