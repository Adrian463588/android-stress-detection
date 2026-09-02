package com.stressdetector.mobile.ml.rag

import com.stressdetector.mobile.data.db.DocumentChunkEntity
import com.stressdetector.mobile.data.db.DocumentDao
import com.stressdetector.mobile.data.db.KnowledgeBaseChunkEntity
import com.stressdetector.mobile.data.db.KnowledgeBaseDao
import com.stressdetector.mobile.data.db.KnowledgeBaseDocumentEntity
import com.stressdetector.mobile.data.db.KnowledgeBaseGenerationEntity
import com.stressdetector.mobile.data.db.KnowledgeBaseGenerationStatus
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first

/**
 * Publishes a complete immutable snapshot into the encrypted KB database.
 * The legacy Room DB remains a compatibility/indexing source until its rows
 * have been copied and checksum-verified into the encrypted database.
 */
sealed interface KnowledgeBasePublicationResult {
    data class Published(val generationId: String, val documentCount: Int, val chunkCount: Int) : KnowledgeBasePublicationResult
    data class RetainedExisting(val activeGenerationId: String?, val reason: String) : KnowledgeBasePublicationResult
}

class KnowledgeBaseIndexPublisher(private val legacyDao: DocumentDao, private val knowledgeBaseDao: KnowledgeBaseDao) {
    suspend fun publishSnapshot(): KnowledgeBasePublicationResult {
        val readyDocuments = legacyDao.getAllDocuments().first()
            .filter { it.isProcessed && it.processingState in READY_STATES }
        val allChunks = legacyDao.getAllChunks()
        val chunksByDocument = allChunks.groupBy(DocumentChunkEntity::documentId)
        val documents = readyDocuments.filter { document ->
            val chunks = chunksByDocument[document.documentId].orEmpty()
            val eligible = EncryptedSourceRef.fromDatabasePath(
                document.filePath,
                document.sourceSha256.orEmpty()
            ) != null &&
                document.sourceSha256.orEmpty().isNotBlank() &&
                chunks.isNotEmpty() &&
                chunks.all { chunk ->
                    chunk.chunkPolicyVersion == TextChunker.POLICY_VERSION &&
                        (chunk.embeddingBlob == null || chunk.embeddingVersion == LocalEmbeddingModel.VERSION)
                }
            if (!eligible) {
                legacyDao.updateProcessingStateDetailed(
                    documentId = document.documentId,
                    isProcessed = false,
                    processingState = DocumentIndexingLifecycle.PROCESSING_STATE_REINDEX_REQUIRED,
                    processingError = "Dokumen belum memenuhi kontrak chunk/embedding terenkripsi terbaru.",
                    processingErrorCode = DocumentProcessingFailureCode.INTERRUPTED.name,
                    embeddingStatus = "UNAVAILABLE",
                    processingUpdatedAtMillis = System.currentTimeMillis()
                )
            }
            eligible
        }
        val legacyChunks = allChunks.filter { chunk -> documents.any { it.documentId == chunk.documentId } }
        val generationId = UUID.randomUUID().toString()
        val sourceFingerprint = sha256(
            documents.joinToString("\n") { "${it.documentId}:${it.sourceSha256}:${it.filePath}" }
        )
        val generation = KnowledgeBaseGenerationEntity(
            generationId = generationId,
            status = KnowledgeBaseGenerationStatus.STAGING,
            createdAtMillis = System.currentTimeMillis(),
            policyFingerprint = "${TextChunker.POLICY_VERSION}:${DocumentIngestionService.INGESTION_VERSION}",
            embeddingFingerprint = LocalEmbeddingModel.VERSION,
            sourceFingerprint = sourceFingerprint
        )
        val kbDocuments = documents.map { document ->
            KnowledgeBaseDocumentEntity(
                documentId = document.documentId,
                generationId = generationId,
                title = document.title,
                sourceFileName = document.sourceFileName ?: document.title,
                encryptedSourceRef = document.filePath,
                sourceSha256 = document.sourceSha256.orEmpty(),
                uploadedAtMillis = document.uploadedAtMillis,
                extractionStatus = document.extractionStatus,
                pageExtractionJson = document.pageExtractionJson,
                ocrProvenance = document.ocrProvenance
            )
        }
        val documentById = documents.associateBy { it.documentId }
        val kbChunks = legacyChunks.mapNotNull { chunk ->
            val sourceDocument = documentById[chunk.documentId] ?: return@mapNotNull null
            KnowledgeBaseChunkEntity(
                chunkId = chunk.chunkId,
                generationId = generationId,
                documentId = sourceDocument.documentId,
                chunkIndex = chunk.chunkIndex,
                textContent = chunk.textContent,
                embeddingBlob = chunk.embeddingBlob,
                sourceTitle = chunk.sourceTitle,
                sourcePage = chunk.sourcePage,
                sourceLocator = chunk.sourceLocator,
                charStart = chunk.charStart,
                charEnd = chunk.charEnd,
                tokenStart = chunk.tokenStart,
                tokenEnd = chunk.tokenEnd,
                chunkPolicyVersion = TextChunker.POLICY_VERSION,
                embeddingVersion = chunk.embeddingVersion
            )
        }
        if (!KnowledgeBasePublicationPolicy.canPublish(kbDocuments.size, kbChunks.size)) {
            return KnowledgeBasePublicationResult.RetainedExisting(
                activeGenerationId = knowledgeBaseDao.activeReadyGeneration()?.generationId,
                reason = KnowledgeBasePublicationPolicy.retainedReason(kbDocuments.size, kbChunks.size)
            )
        }
        knowledgeBaseDao.publishReadyGeneration(generation, kbDocuments, kbChunks)
        return KnowledgeBasePublicationResult.Published(
            generationId = generationId,
            documentCount = kbDocuments.size,
            chunkCount = kbChunks.size
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val READY_STATES = setOf("READY_SEMANTIC", "READY_LEXICAL")
    }
}
