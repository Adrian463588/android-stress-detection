package com.stressdetector.mobile.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A published or staging generation of the local document index. */
@Entity(
    tableName = "kb_generations",
    indices = [Index(value = ["status"]), Index(value = ["policyFingerprint"])]
)
data class KnowledgeBaseGenerationEntity(
    @PrimaryKey val generationId: String,
    val status: String,
    val createdAtMillis: Long,
    val policyFingerprint: String,
    val embeddingFingerprint: String,
    val sourceFingerprint: String,
    val failureReason: String? = null
)

@Entity(
    tableName = "kb_documents",
    indices = [Index(value = ["generationId"]), Index(value = ["sourceSha256"])]
)
data class KnowledgeBaseDocumentEntity(
    @PrimaryKey val documentId: String,
    val generationId: String,
    val title: String,
    val sourceFileName: String,
    val encryptedSourceRef: String,
    val sourceSha256: String,
    val uploadedAtMillis: Long,
    val extractionStatus: String,
    val pageExtractionJson: String? = null,
    val ocrProvenance: String? = null
)

@Entity(
    tableName = "kb_chunks",
    indices = [
        Index(value = ["generationId"]),
        Index(value = ["documentId", "chunkIndex"], unique = true)
    ]
)
data class KnowledgeBaseChunkEntity(
    @PrimaryKey val chunkId: String,
    val generationId: String,
    val documentId: String,
    val chunkIndex: Int,
    val textContent: String,
    val embeddingBlob: ByteArray?,
    val sourceTitle: String? = null,
    val sourcePage: Int? = null,
    val sourceLocator: String? = null,
    val charStart: Int? = null,
    val charEnd: Int? = null,
    val tokenStart: Int? = null,
    val tokenEnd: Int? = null,
    val chunkPolicyVersion: String,
    val embeddingVersion: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as KnowledgeBaseChunkEntity
        return chunkId == other.chunkId &&
            generationId == other.generationId &&
            documentId == other.documentId &&
            chunkIndex == other.chunkIndex &&
            textContent == other.textContent &&
            embeddingBlob.contentEqualsNullable(other.embeddingBlob) &&
            sourceTitle == other.sourceTitle &&
            sourcePage == other.sourcePage &&
            sourceLocator == other.sourceLocator &&
            charStart == other.charStart &&
            charEnd == other.charEnd &&
            tokenStart == other.tokenStart &&
            tokenEnd == other.tokenEnd &&
            chunkPolicyVersion == other.chunkPolicyVersion &&
            embeddingVersion == other.embeddingVersion
    }

    override fun hashCode(): Int = listOf(
        chunkId,
        generationId,
        documentId,
        chunkIndex,
        textContent,
        embeddingBlob?.contentHashCode(),
        sourceTitle,
        sourcePage,
        sourceLocator,
        charStart,
        charEnd,
        tokenStart,
        tokenEnd,
        chunkPolicyVersion,
        embeddingVersion
    ).fold(1) { result, value -> 31 * result + (value?.hashCode() ?: 0) }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this == null && other == null -> true
    this == null || other == null -> false
    else -> contentEquals(other)
}
