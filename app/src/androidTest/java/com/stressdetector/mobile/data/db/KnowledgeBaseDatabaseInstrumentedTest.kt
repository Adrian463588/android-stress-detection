package com.stressdetector.mobile.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only proof that the encrypted knowledge database exposes a real FTS5 index. */
@RunWith(AndroidJUnit4::class)
class KnowledgeBaseDatabaseInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "knowledge_base_secure_test_${UUID.randomUUID()}.db"
    private var database: KnowledgeBaseDatabase? = null

    @After
    fun closeDatabase() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun sqlCipherFts5PublishesAndSearchesOnlyTheReadyGeneration() = runBlocking {
        database = KnowledgeBaseDatabase.openForTests(context, PASSPHRASE, databaseName)
        val dao = requireNotNull(database).knowledgeBaseDao()
        val generationId = "instrumented-${UUID.randomUUID()}"
        val documentId = "document-$generationId"
        dao.publishReadyGeneration(
            generation = KnowledgeBaseGenerationEntity(
                generationId = generationId,
                status = KnowledgeBaseGenerationStatus.STAGING,
                createdAtMillis = 1L,
                policyFingerprint = "policy",
                embeddingFingerprint = "embedding",
                sourceFingerprint = "source"
            ),
            documents = listOf(
                KnowledgeBaseDocumentEntity(
                    documentId = documentId,
                    generationId = generationId,
                    title = "Fixture",
                    sourceFileName = "fixture.txt",
                    encryptedSourceRef = "encrypted://fixture",
                    sourceSha256 = "a".repeat(64),
                    uploadedAtMillis = 1L,
                    extractionStatus = "COMPLETE"
                )
            ),
            chunks = listOf(
                KnowledgeBaseChunkEntity(
                    chunkId = "chunk-$generationId",
                    generationId = generationId,
                    documentId = documentId,
                    chunkIndex = 0,
                    textContent = "Breathing exercise supports grounding.",
                    embeddingBlob = null,
                    sourceTitle = "Fixture",
                    sourcePage = 1,
                    sourceLocator = "section-1",
                    chunkPolicyVersion = "e5_wordpiece_224_256_32_v1"
                )
            )
        )

        assertEquals(generationId, dao.activeReadyGeneration()?.generationId)
        val result = dao.searchReadyGeneration("\"breathing\"", limit = 30)

        assertEquals(1, result.size)
        assertEquals("chunk-$generationId", result.single().chunkId)
        assertNotNull(result.single().rank)
    }

    private companion object {
        val PASSPHRASE = ByteArray(32) { (it + 1).toByte() }
    }
}
