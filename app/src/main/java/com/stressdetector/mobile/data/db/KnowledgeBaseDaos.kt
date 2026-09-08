package com.stressdetector.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

data class KnowledgeBaseFtsResult(
    val chunkId: String,
    val documentId: String,
    val generationId: String,
    val textContent: String,
    val sourcePage: Int?,
    val sourceLocator: String?,
    val rank: Double
)

@Dao
abstract class KnowledgeBaseDao {
    @Query(
        "SELECT g.* FROM kb_generations AS g " +
            "WHERE g.status = 'READY' " +
            "AND EXISTS (SELECT 1 FROM kb_documents AS d WHERE d.generationId = g.generationId) " +
            "AND EXISTS (SELECT 1 FROM kb_chunks AS c WHERE c.generationId = g.generationId) " +
            "ORDER BY g.createdAtMillis DESC LIMIT 1"
    )
    abstract suspend fun activeReadyGeneration(): KnowledgeBaseGenerationEntity?

    @Query("SELECT * FROM kb_generations WHERE generationId = :generationId")
    abstract suspend fun generation(generationId: String): KnowledgeBaseGenerationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertGeneration(generation: KnowledgeBaseGenerationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertDocuments(documents: List<KnowledgeBaseDocumentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertChunks(chunks: List<KnowledgeBaseChunkEntity>)

    @Query("UPDATE kb_generations SET status = :status, failureReason = :reason WHERE generationId = :generationId")
    abstract suspend fun setGenerationStatus(generationId: String, status: String, reason: String? = null)

    @Query("UPDATE kb_generations SET status = 'FAILED', failureReason = :reason WHERE generationId = :generationId")
    abstract suspend fun failGeneration(generationId: String, reason: String)

    @Query("DELETE FROM kb_chunks WHERE generationId = :generationId")
    abstract suspend fun deleteChunks(generationId: String)

    @Query("DELETE FROM kb_documents WHERE generationId = :generationId")
    abstract suspend fun deleteDocuments(generationId: String)

    @Query("DELETE FROM kb_generations WHERE generationId = :generationId")
    abstract suspend fun deleteGeneration(generationId: String)

    @Query("SELECT * FROM kb_chunks WHERE generationId = :generationId ORDER BY documentId, chunkIndex")
    abstract suspend fun chunksForGeneration(generationId: String): List<KnowledgeBaseChunkEntity>

    @Query("SELECT * FROM kb_documents WHERE generationId = :generationId ORDER BY uploadedAtMillis DESC")
    abstract suspend fun documentsForGeneration(generationId: String): List<KnowledgeBaseDocumentEntity>

    /** Executes a parameterized FTS5 statement; callers must not interpolate user text. */
    @RawQuery
    abstract suspend fun execute(query: SupportSQLiteQuery): Int

    @RawQuery
    abstract suspend fun searchFts(query: SupportSQLiteQuery): List<KnowledgeBaseFtsResult>

    @Transaction
    open suspend fun publishReadyGeneration(
        generation: KnowledgeBaseGenerationEntity,
        documents: List<KnowledgeBaseDocumentEntity>,
        chunks: List<KnowledgeBaseChunkEntity>
    ) {
        require(generation.status == KnowledgeBaseGenerationStatus.STAGING)
        require(documents.isNotEmpty()) { "A READY knowledge-base generation requires at least one document." }
        require(chunks.isNotEmpty()) { "A READY knowledge-base generation requires at least one chunk." }
        require(documents.all { it.generationId == generation.generationId })
        require(chunks.all { it.generationId == generation.generationId })
        insertGeneration(generation)
        insertDocuments(documents)
        insertChunks(chunks)
        execute(
            SimpleSQLiteQuery(
                "DELETE FROM kb_chunks_fts WHERE generationId = ?",
                arrayOf(generation.generationId)
            )
        )
        chunks.forEach { chunk ->
            execute(
                SimpleSQLiteQuery(
                    "INSERT INTO kb_chunks_fts(chunkId, documentId, generationId, textContent, sourcePage, sourceLocator) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        chunk.chunkId,
                        chunk.documentId,
                        chunk.generationId,
                        chunk.textContent,
                        chunk.sourcePage,
                        chunk.sourceLocator
                    )
                )
            )
        }
        execute(
            SimpleSQLiteQuery(
                "UPDATE kb_generations SET status = 'READY', failureReason = NULL WHERE generationId = ?",
                arrayOf(generation.generationId)
            )
        )
    }

    suspend fun searchReadyGeneration(matchExpression: String, limit: Int): List<KnowledgeBaseFtsResult> {
        val generation = activeReadyGeneration() ?: return emptyList()
        return searchFts(
            SimpleSQLiteQuery(
                "SELECT chunkId, documentId, generationId, textContent, sourcePage, sourceLocator, rank " +
                    "FROM kb_chunks_fts WHERE kb_chunks_fts MATCH ? AND generationId = ? " +
                    "ORDER BY rank LIMIT ?",
                arrayOf<Any?>(matchExpression, generation.generationId, limit.coerceIn(1, 30))
            )
        )
    }
}

object KnowledgeBaseGenerationStatus {
    const val STAGING = "STAGING"
    const val READY = "READY"
    const val FAILED = "FAILED"
    const val REINDEX_REQUIRED = "REINDEX_REQUIRED"
}
