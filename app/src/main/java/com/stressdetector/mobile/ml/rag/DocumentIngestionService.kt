package com.stressdetector.mobile.ml.rag

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.stressdetector.mobile.data.db.ClinicalDocumentEntity
import com.stressdetector.mobile.data.db.DocumentChunkEntity
import com.stressdetector.mobile.data.db.DocumentDao
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface DocumentIngestionResult {
    data class Indexed(val documentId: String, val chunkCount: Int, val embeddingStatus: String, val extractionStatus: String) :
        DocumentIngestionResult

    data class Partial(val documentId: String, val extractionStatus: String, val reason: String, val failureCode: String) :
        DocumentIngestionResult

    data class AlreadyIndexed(val documentId: String) : DocumentIngestionResult {
        val existingDocumentId: String get() = documentId
    }
}

data class DocumentIngestionProgress(val stage: Stage, val completed: Int = 0, val total: Int = 0) {
    enum class Stage {
        COPYING,
        EXTRACTING,
        CHUNKING,
        EMBEDDING,
        SAVING
    }
}

/**
 * One bounded ingestion boundary. Chunks are staged off-database and become
 * retrievable only in the same Room transaction that publishes the READY
 * document state.
 */
class DocumentIngestionService(
    context: Context,
    private val documentDao: DocumentDao,
    private val parser: DocumentParser,
    private val sourceStore: EncryptedDocumentSourceStore,
    private val legacyMigrator: LegacyKnowledgeBaseMigrator,
    private val knowledgeBasePublisher: KnowledgeBaseIndexPublisher,
    private val chunker: TextChunker,
    private val embedder: LocalEmbeddingModel,
    private val chunkingConfig: TextChunker.Config = TextChunker.Config(),
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val applicationContext = context.applicationContext

    /** Serializes checksum admission so identical imports cannot race the DB lookup/insert. */
    private val admissionMutex = Mutex()

    companion object {
        const val INGESTION_VERSION = "structured-block-atomic-v5+${TextChunker.POLICY_VERSION}"
        const val EMBEDDING_VERSION = LocalEmbeddingModel.VERSION
        private val READY_STATES = setOf("READY_SEMANTIC", "READY_LEXICAL")
    }

    suspend fun ingest(
        uri: Uri,
        requestedTitle: String? = null,
        allowPartialExtraction: Boolean = false,
        onProgress: suspend (DocumentIngestionProgress) -> Unit = {}
    ): DocumentIngestionResult = withContext(Dispatchers.IO) {
        legacyMigrator.migratePlaintextSources()
        recoverStaleIndexing()
        val documentId = UUID.randomUUID().toString()
        val sourceFileName = displayName(uri)
        val title = requestedTitle?.takeIf(String::isNotBlank) ?: sourceFileName
        val format = DocumentFormats.detect(applicationContext.contentResolver.getType(uri), sourceFileName)
        var localFile: File? = null
        var encryptedSource: EncryptedSourceRef? = null
        try {
            onProgress(DocumentIngestionProgress(DocumentIngestionProgress.Stage.COPYING))
            localFile = parser.copyToStagingFile(uri, sourceFileName)
            encryptedSource = sourceStore.put(documentId, localFile.inputStream(), sourceFileName)
            val ingestionStartedAtMillis = nowMillis()
            val result = admissionMutex.withLock {
                indexPreparedFile(
                    documentId = documentId,
                    title = title,
                    sourceFileName = sourceFileName,
                    importedFile = localFile,
                    sourcePath = requireNotNull(encryptedSource).asDatabasePath(),
                    format = format,
                    uploadedAtMillis = ingestionStartedAtMillis,
                    processingStartedAtMillis = ingestionStartedAtMillis,
                    processingRevision = 1,
                    allowPartialExtraction = allowPartialExtraction,
                    onProgress = onProgress
                )
            }
            if (result is DocumentIngestionResult.AlreadyIndexed) {
                runCatching { sourceStore.delete(encryptedSource) }
            }
            result
        } catch (cancelled: CancellationException) {
            if (documentDao.getDocument(documentId) == null) {
                encryptedSource?.let { runCatching { sourceStore.delete(it) } }
            }
            markInterruptedOrDeleteUntracked(documentId, localFile)
            throw cancelled
        } catch (error: Exception) {
            if (documentDao.getDocument(documentId) == null) {
                encryptedSource?.let { runCatching { sourceStore.delete(it) } }
            }
            markFailedOrDeleteUntracked(documentId, localFile, error)
            throw error
        } finally {
            localFile?.delete()
        }
    }

    suspend fun reindex(
        documentId: String,
        allowPartialExtraction: Boolean = false,
        onProgress: suspend (DocumentIngestionProgress) -> Unit = {}
    ): DocumentIngestionResult = withContext(Dispatchers.IO) {
        val document = documentDao.getDocument(documentId)
            ?: throw DocumentParseException("Dokumen yang akan diindeks ulang tidak ditemukan.")
        val sourceFileName = document.sourceFileName ?: "clinical_document"
        val encryptedRef = EncryptedSourceRef.fromDatabasePath(
            path = document.filePath,
            sha256 = document.sourceSha256 ?: ""
        )
        var temporarySource: File? = null
        var replacementSource: EncryptedSourceRef? = null
        val source = if (encryptedRef != null) {
            temporarySource = parser.copyToStagingFile(sourceStore.open(encryptedRef), sourceFileName)
            temporarySource!!
        } else {
            File(document.filePath).also {
                if (!it.isFile) {
                    val failure = DocumentProcessingFailure.sourceMissing()
                    if (!document.hasReadyIndex()) {
                        documentDao.updateProcessingStateDetailed(
                            documentId = documentId,
                            isProcessed = false,
                            processingState = "FAILED",
                            processingError = failure.userMessage,
                            processingErrorCode = failure.code.name,
                            embeddingStatus = "UNAVAILABLE",
                            processingUpdatedAtMillis = nowMillis()
                        )
                    }
                    throw DocumentParseException(
                        message = failure.userMessage,
                        failure = failure
                    )
                }
            }
        }
        val format = DocumentFormats.detect(null, sourceFileName)
        try {
            val sourcePath = if (encryptedRef != null) {
                document.filePath
            } else {
                replacementSource = sourceStore.put(documentId, source.inputStream(), sourceFileName)
                replacementSource!!.asDatabasePath()
            }
            val result = indexPreparedFile(
                documentId = documentId,
                title = document.title,
                sourceFileName = sourceFileName,
                importedFile = source,
                sourcePath = sourcePath,
                format = format,
                uploadedAtMillis = document.uploadedAtMillis,
                processingStartedAtMillis = nowMillis(),
                processingRevision = (document.processingRevision + 1).coerceAtLeast(1),
                allowPartialExtraction = allowPartialExtraction,
                onProgress = onProgress,
                skipDuplicateCheck = true
            )
            if (encryptedRef == null) deleteKnowledgeBaseFile(document.filePath)
            result
        } catch (cancelled: CancellationException) {
            markReindexRequired(documentId, "Indexing dihentikan sebelum publikasi atomik.")
            throw cancelled
        } catch (error: Exception) {
            if (!document.hasReadyIndex()) {
                documentDao.deleteChunksForDocument(documentId)
                val failure = DocumentProcessingFailure.from(error)
                documentDao.updateProcessingStateDetailed(
                    documentId = documentId,
                    isProcessed = false,
                    processingState = "FAILED",
                    processingError = failure.userMessage,
                    processingErrorCode = failure.code.name,
                    embeddingStatus = "UNAVAILABLE",
                    processingUpdatedAtMillis = nowMillis()
                )
            }
            throw error
        } finally {
            temporarySource?.delete()
        }
    }

    suspend fun recoverStaleIndexing(nowMillis: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val stale = documentDao.getAllDocuments().first().filter { document ->
            DocumentIndexingLifecycle.requiresReindex(
                processingState = document.processingState,
                processingUpdatedAtMillis = document.processingUpdatedAtMillis,
                nowMillis = nowMillis
            )
        }
        stale.forEach { document ->
            markReindexRequired(document.documentId, "Indexing sebelumnya terhenti; indeks ulang diperlukan.")
        }
        stale.size
    }

    suspend fun delete(documentId: String) = withContext(Dispatchers.IO) {
        val document = documentDao.getDocument(documentId) ?: return@withContext
        documentDao.deleteDocumentWithChunks(documentId)
        EncryptedSourceRef.fromDatabasePath(document.filePath, document.sourceSha256 ?: "")?.let {
            runCatching { sourceStore.delete(it) }
        } ?: deleteKnowledgeBaseFile(document.filePath)
    }

    private suspend fun indexPreparedFile(
        documentId: String,
        title: String,
        sourceFileName: String,
        importedFile: File,
        sourcePath: String,
        format: DocumentFormat,
        uploadedAtMillis: Long,
        processingStartedAtMillis: Long,
        processingRevision: Int,
        allowPartialExtraction: Boolean,
        onProgress: suspend (DocumentIngestionProgress) -> Unit,
        skipDuplicateCheck: Boolean = false
    ): DocumentIngestionResult {
        DocumentFormats.verifySignature(format, importedFile)
        val checksum = parser.sha256(importedFile)
        if (!skipDuplicateCheck) {
            documentDao.findDocumentBySha256(checksum)?.let { existing ->
                return handleDuplicate(
                    existing = existing,
                    importedFile = importedFile,
                    allowPartialExtraction = allowPartialExtraction,
                    onProgress = onProgress
                )
            }
        }

        val existingReadyIndex = documentDao.getDocument(documentId)?.hasReadyIndex() == true
        if (!existingReadyIndex) {
            documentDao.insertDocument(
                indexingDocument(
                    documentId = documentId,
                    title = title,
                    sourceFileName = sourceFileName,
                    sourcePath = sourcePath,
                    checksum = checksum,
                    uploadedAtMillis = uploadedAtMillis,
                    processingStartedAtMillis = processingStartedAtMillis,
                    processingRevision = processingRevision
                )
            )
        }

        onProgress(DocumentIngestionProgress(DocumentIngestionProgress.Stage.EXTRACTING))
        val extraction = parser.extractDocument(importedFile, documentId, format)
        val publication = DocumentIndexingLifecycle.publicationDecision(
            extractionStatus = extraction.extractionStatus,
            allowPartialExtraction = allowPartialExtraction
        )
        if (!publication.publishChunks) {
            val reason = when (publication.state) {
                DocumentPublicationState.PARTIAL -> {
                    "Ekstraksi dokumen parsial; halaman gagal harus ditinjau sebelum dokumen dapat digunakan."
                }
                DocumentPublicationState.FAILED -> "Dokumen tidak memiliki konten yang dapat diindeks."
                DocumentPublicationState.READY -> error("READY publication must publish chunks")
            }
            val state = publication.state.name
            val failure = when (publication.state) {
                DocumentPublicationState.PARTIAL -> DocumentProcessingFailure.partialExtraction()
                DocumentPublicationState.FAILED -> DocumentProcessingFailure.noIndexableContent()
                DocumentPublicationState.READY -> error("READY publication must publish chunks")
            }
            if (!DocumentIndexingLifecycle.retainsExistingReadyIndex(existingReadyIndex, publication.publishChunks)) {
                documentDao.replaceDocumentWithChunks(
                    document = finalDocument(
                        documentId = documentId,
                        title = title,
                        sourceFileName = sourceFileName,
                        sourcePath = sourcePath,
                        uploadedAtMillis = uploadedAtMillis,
                        processingStartedAtMillis = processingStartedAtMillis,
                        processingRevision = processingRevision,
                        checksum = checksum,
                        extraction = extraction,
                        isProcessed = false,
                        processingState = state,
                        processingFailure = failure,
                        embeddingStatus = "UNAVAILABLE",
                        successfulEmbeddings = 0,
                        chunkCount = 0
                    ),
                    chunks = emptyList()
                )
                knowledgeBasePublisher.publishSnapshot()
            }
            return DocumentIngestionResult.Partial(
                documentId = documentId,
                extractionStatus = extraction.extractionStatus.name,
                reason = reason,
                failureCode = failure.code.name
            )
        }

        val blockChunks = extraction.blocks.flatMap { block ->
            chunker.chunkBlock(block, chunkingConfig).map { chunk -> block to chunk }
        }
        val totalChunks = blockChunks.size
        require(totalChunks in 1..DocumentIngestionLimits.MAX_CHUNKS) {
            "Dokumen menghasilkan jumlah chunk di luar batas indeks lokal."
        }
        onProgress(DocumentIngestionProgress(DocumentIngestionProgress.Stage.CHUNKING, total = totalChunks))

        val stagedChunks = ArrayList<DocumentChunkEntity>(totalChunks)
        var successfulEmbeddings = 0
        var chunkIndex = 0
        blockChunks.forEach { (block, chunk) ->
            currentCoroutineContext().ensureActive()
            val embedding = embedder.embedPassage(chunk.embeddingText)
            val vector = (embedding as? EmbeddingResult.Success)?.vector
            if (vector != null) successfulEmbeddings++
            val currentIndex = chunkIndex++
            stagedChunks += DocumentChunkEntity(
                chunkId = UUID.randomUUID().toString(),
                documentId = documentId,
                chunkIndex = currentIndex,
                textContent = chunk.embeddingText,
                embeddingBlob = vector?.let(LocalEmbeddingModel::floatArrayToByteArray),
                sourceTitle = title,
                sourcePage = block.pageNumber,
                sourceLocator = block.sourceLocator(chunk, currentIndex),
                embeddingVersion = vector?.let { EMBEDDING_VERSION },
                charStart = chunk.charStart,
                charEnd = chunk.charEnd,
                tokenStart = chunk.tokenStart,
                tokenEnd = chunk.tokenEnd,
                chunkPolicyVersion = chunk.policyVersion
            )
            onProgress(
                DocumentIngestionProgress(
                    stage = DocumentIngestionProgress.Stage.EMBEDDING,
                    completed = stagedChunks.size,
                    total = totalChunks
                )
            )
        }
        check(stagedChunks.size == totalChunks) { "Jumlah chunk staging tidak konsisten." }

        val embeddingStatus = when {
            successfulEmbeddings == totalChunks -> "SEMANTIC_READY"
            successfulEmbeddings == 0 -> "LEXICAL_ONLY"
            else -> "PARTIAL_SEMANTIC"
        }
        val readyState = if (successfulEmbeddings > 0) "READY_SEMANTIC" else "READY_LEXICAL"
        val document = finalDocument(
            documentId = documentId,
            title = title,
            sourceFileName = sourceFileName,
            sourcePath = sourcePath,
            uploadedAtMillis = uploadedAtMillis,
            processingStartedAtMillis = processingStartedAtMillis,
            processingRevision = processingRevision,
            checksum = checksum,
            extraction = extraction,
            isProcessed = true,
            processingState = readyState,
            processingFailure = null,
            embeddingStatus = embeddingStatus,
            successfulEmbeddings = successfulEmbeddings,
            chunkCount = totalChunks
        )
        // Keep the determinate bar below 100% until the Room transaction and
        // generation publication have completed. The Worker emits 100% only
        // in terminal Result.success output data.
        onProgress(DocumentIngestionProgress(DocumentIngestionProgress.Stage.SAVING, completed = 0, total = 1))
        documentDao.replaceDocumentWithChunks(document, stagedChunks)
        knowledgeBasePublisher.publishSnapshot()
        return DocumentIngestionResult.Indexed(
            documentId = documentId,
            chunkCount = totalChunks,
            embeddingStatus = embeddingStatus,
            extractionStatus = extraction.extractionStatus.name
        )
    }

    private suspend fun handleDuplicate(
        existing: ClinicalDocumentEntity,
        importedFile: File,
        allowPartialExtraction: Boolean,
        onProgress: suspend (DocumentIngestionProgress) -> Unit
    ): DocumentIngestionResult {
        importedFile.delete()
        val stalePolicy = existing.ingestionVersion != INGESTION_VERSION ||
            existing.embeddingModelVersion?.let { it != EMBEDDING_VERSION } == true
        if (stalePolicy) {
            return reindex(existing.documentId, allowPartialExtraction, onProgress)
        }
        return when (existing.processingState) {
            "PARTIAL" -> if (allowPartialExtraction) {
                reindex(existing.documentId, allowPartialExtraction = true, onProgress = onProgress)
            } else {
                DocumentIngestionResult.Partial(
                    documentId = existing.documentId,
                    extractionStatus = existing.extractionStatus,
                    reason = existing.processingError ?: "Ekstraksi dokumen parsial memerlukan peninjauan.",
                    failureCode = existing.processingErrorCode ?: DocumentProcessingFailureCode.PARTIAL_EXTRACTION.name
                )
            }
            "FAILED", DocumentIndexingLifecycle.PROCESSING_STATE_REINDEX_REQUIRED -> {
                reindex(existing.documentId, allowPartialExtraction, onProgress)
            }
            else -> DocumentIngestionResult.AlreadyIndexed(documentId = existing.documentId)
        }
    }

    private fun indexingDocument(
        documentId: String,
        title: String,
        sourceFileName: String,
        sourcePath: String,
        checksum: String,
        uploadedAtMillis: Long,
        processingStartedAtMillis: Long,
        processingRevision: Int
    ): ClinicalDocumentEntity = ClinicalDocumentEntity(
        documentId = documentId,
        title = title,
        filePath = sourcePath,
        uploadedAtMillis = uploadedAtMillis,
        isProcessed = false,
        sourceFileName = sourceFileName,
        sourceSha256 = checksum,
        ingestionVersion = INGESTION_VERSION,
        processingState = DocumentIndexingLifecycle.PROCESSING_STATE_INDEXING,
        processingStartedAtMillis = processingStartedAtMillis,
        processingUpdatedAtMillis = processingStartedAtMillis,
        processingRevision = processingRevision,
        extractionStatus = "INDEXING"
    )

    @Suppress("LongParameterList")
    private fun finalDocument(
        documentId: String,
        title: String,
        sourceFileName: String,
        sourcePath: String,
        uploadedAtMillis: Long,
        processingStartedAtMillis: Long,
        processingRevision: Int,
        checksum: String,
        extraction: ParsedDocument,
        isProcessed: Boolean,
        processingState: String,
        processingFailure: DocumentProcessingFailure?,
        embeddingStatus: String,
        successfulEmbeddings: Int,
        chunkCount: Int
    ): ClinicalDocumentEntity = ClinicalDocumentEntity(
        documentId = documentId,
        title = title,
        filePath = sourcePath,
        uploadedAtMillis = uploadedAtMillis,
        isProcessed = isProcessed,
        sourceFileName = sourceFileName,
        sourceSha256 = checksum,
        ingestionVersion = INGESTION_VERSION,
        embeddingModelVersion = EMBEDDING_VERSION.takeIf { successfulEmbeddings > 0 },
        embeddingStatus = embeddingStatus,
        processingState = processingState,
        processingError = processingFailure?.userMessage,
        processingErrorCode = processingFailure?.code?.name,
        processingStartedAtMillis = processingStartedAtMillis,
        processingUpdatedAtMillis = nowMillis(),
        processingRevision = processingRevision,
        extractionStatus = extraction.extractionStatus.name,
        ocrProvenance = extraction.ocrProvenance,
        pageExtractionJson = extraction.pageExtractionJson,
        indexedChunkCount = chunkCount
    )

    private suspend fun markInterruptedOrDeleteUntracked(documentId: String, file: File?) {
        if (documentDao.getDocument(documentId) == null) {
            file?.delete()
        } else {
            markReindexRequired(documentId, "Indexing dihentikan sebelum publikasi atomik.")
        }
    }

    private suspend fun markFailedOrDeleteUntracked(documentId: String, file: File?, error: Exception) {
        val existing = documentDao.getDocument(documentId)
        if (existing == null) {
            file?.delete()
            return
        }
        if (existing.hasReadyIndex()) return
        documentDao.deleteChunksForDocument(documentId)
        val failure = DocumentProcessingFailure.from(error)
        documentDao.updateProcessingStateDetailed(
            documentId = documentId,
            isProcessed = false,
            processingState = "FAILED",
            processingError = failure.userMessage,
            processingErrorCode = failure.code.name,
            embeddingStatus = "UNAVAILABLE",
            processingUpdatedAtMillis = nowMillis()
        )
    }

    private suspend fun markReindexRequired(documentId: String, reason: String) {
        if (documentDao.getDocument(documentId)?.hasReadyIndex() == true) return
        documentDao.deleteChunksForDocument(documentId)
        val failure = DocumentProcessingFailure.reindexRequired()
        documentDao.updateProcessingStateDetailed(
            documentId = documentId,
            isProcessed = false,
            processingState = DocumentIndexingLifecycle.PROCESSING_STATE_REINDEX_REQUIRED,
            processingError = reason,
            processingErrorCode = failure.code.name,
            embeddingStatus = "UNAVAILABLE",
            processingUpdatedAtMillis = nowMillis()
        )
    }

    private fun ClinicalDocumentEntity.hasReadyIndex(): Boolean = isProcessed && processingState in READY_STATES

    private fun DocumentBlock.sourceLocator(chunk: TokenAwareChunk, chunkIndex: Int): String = buildList {
        pageNumber?.let { add("Page $it") }
        if (sectionPath.isNotEmpty()) add(sectionPath.joinToString(" > "))
        add(blockType.name)
        tableRowIndex?.let { add("Row ${it + 1}") }
        add("Chars ${chunk.charStart}-${chunk.charEnd}")
        add("Tokens ${chunk.tokenStart}-${chunk.tokenEnd}")
        add("Policy ${chunk.policyVersion}")
        add("Chunk ${chunkIndex + 1}")
    }.joinToString(" · ")

    private fun deleteKnowledgeBaseFile(path: String) {
        runCatching {
            val directory = File(applicationContext.filesDir, "app_knowledge_base").canonicalFile
            val file = File(path).canonicalFile
            if (file.path.startsWith(directory.path + File.separator)) file.delete()
        }
    }

    private fun displayName(uri: Uri): String = applicationContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else "clinical_document"
    } ?: "clinical_document"
}
