package com.stressdetector.mobile.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stressdetector.mobile.data.repository.AssessmentRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun markedAssessmentKeepsScoreInterpretationAndSourceAcrossEncryptedDatabaseReopen() = runBlocking {
        database = KnowledgeBaseDatabase.openForTests(context, PASSPHRASE, databaseName)
        val repository = AssessmentRepository(context) { requireNotNull(database).assessmentResultDao() }

        val saved = repository.saveMarkedSheet("frt", listOf(36, 7, 2))
        assertEquals(34.25, saved.scores.first().value, 0.0)
        assertEquals("FRT 45 · koreksi menebak", saved.version)
        assertEquals("PsikoTest/FRT/FRT_Acuan_dan_Skoring.md · §4A, 5–6", saved.sourceLocator)

        database?.close()
        database = KnowledgeBaseDatabase.openForTests(context, PASSPHRASE, databaseName)
        assertEquals(saved, repository.getResult("frt"))
        assertEquals(saved, repository.getResultById(saved.resultId))
        repository.deleteByResultId(saved.resultId)
        assertNull(repository.getResult("frt"))
        assertNull(repository.getResultById(saved.resultId))
    }

    @Test
    fun preservesDistinctResultSnapshotsByResultIdAcrossSameInstrument() = runBlocking {
        database = KnowledgeBaseDatabase.openForTests(context, PASSPHRASE, databaseName)
        val repository = AssessmentRepository(context) { requireNotNull(database).assessmentResultDao() }

        val first = repository.saveMarkedSheet("wpt", listOf(12, 10))
        val second = repository.saveMarkedSheet("wpt", listOf(18, 13))

        val loadedFirst = repository.getResultById(first.resultId)
        val loadedSecond = repository.getResultById(second.resultId)
        assertNotNull(loadedFirst)
        assertNotNull(loadedSecond)
        assertEquals(22.0, loadedFirst!!.scores.first().value, 0.0)
        assertEquals(31.0, loadedSecond!!.scores.first().value, 0.0)
        assertEquals(2, repository.getResultsForInstrument("wpt").size)
    }

    @Test
    fun migration2To3PreservesLegacyResultsAndBackfillsSnapshotTable() {
        val migDbName = "kb_mig_2_3_${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(migDbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS assessment_results (instrumentId TEXT NOT NULL PRIMARY KEY, payloadJson TEXT NOT NULL)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        try {
            val db = helper.writableDatabase
            val legacyJson = """{"resultId":"res-legacy-2","instrumentId":"disc","participantId":"local-participant-01","createdAtMillis":12345,"title":"DISC"}"""
            db.execSQL(
                "INSERT INTO assessment_results (instrumentId, payloadJson) VALUES (?, ?)",
                arrayOf<Any>("disc", legacyJson)
            )
            KnowledgeBaseDatabase.MIGRATION_2_3.migrate(db)
            db.query("SELECT resultId, instrumentId, participantId, createdAtMillis FROM assessment_result_snapshots WHERE resultId='res-legacy-2'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("res-legacy-2", cursor.getString(0))
                    assertEquals("disc", cursor.getString(1))
                    assertEquals("local-participant-01", cursor.getString(2))
                    assertEquals(12345L, cursor.getLong(3))
                }
        } finally {
            helper.close()
            context.deleteDatabase(migDbName)
        }
    }

    private companion object {
        val PASSPHRASE = ByteArray(32) { (it + 1).toByte() }
    }
}
