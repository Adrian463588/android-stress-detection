package com.stressdetector.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        KnowledgeBaseGenerationEntity::class,
        KnowledgeBaseDocumentEntity::class,
        KnowledgeBaseChunkEntity::class,
        AssessmentResultEntity::class,
        AssessmentResultSnapshotEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class KnowledgeBaseDatabase : RoomDatabase() {
    abstract fun knowledgeBaseDao(): KnowledgeBaseDao
    abstract fun assessmentResultDao(): AssessmentResultDao

    companion object {
        private const val DATABASE_NAME = "knowledge_base_secure.db"
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS assessment_results (instrumentId TEXT NOT NULL PRIMARY KEY, payloadJson TEXT NOT NULL)"
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS assessment_result_snapshots (" +
                        "resultId TEXT NOT NULL PRIMARY KEY, " +
                        "instrumentId TEXT NOT NULL, " +
                        "participantId TEXT NOT NULL, " +
                        "createdAtMillis INTEGER NOT NULL, " +
                        "payloadJson TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_assessment_result_snapshots_instrumentId_createdAtMillis " +
                        "ON assessment_result_snapshots(instrumentId, createdAtMillis)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_assessment_result_snapshots_participantId_createdAtMillis " +
                        "ON assessment_result_snapshots(participantId, createdAtMillis)"
                )
                db.query("SELECT instrumentId, payloadJson FROM assessment_results").use { cursor ->
                    while (cursor.moveToNext()) {
                        val instId = cursor.getString(0)
                        val json = cursor.getString(1)
                        val parsed = runCatching { org.json.JSONObject(json) }.getOrNull()
                        val resultId = parsed?.optString("resultId").orEmpty().ifBlank { "legacy-$instId" }
                        val participantId = parsed?.optString("participantId").orEmpty().ifBlank { "local-participant-01" }
                        val createdAt = parsed?.optLong("createdAtMillis", 0L) ?: 0L
                        db.execSQL(
                            "INSERT OR IGNORE INTO assessment_result_snapshots " +
                                "(resultId, instrumentId, participantId, createdAtMillis, payloadJson) " +
                                "VALUES (?, ?, ?, ?, ?)",
                            arrayOf<Any>(resultId, instId, participantId, createdAt, json)
                        )
                    }
                }
            }
        }

        @Volatile private var instance: KnowledgeBaseDatabase? = null

        fun get(context: Context, passphrase: ByteArray): KnowledgeBaseDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext, passphrase).also { instance = it }
        }

        /** Opens an isolated database name for device tests without touching the user's READY index. */
        internal fun openForTests(context: Context, passphrase: ByteArray, databaseName: String): KnowledgeBaseDatabase =
            build(context.applicationContext, passphrase, databaseName)

        private fun build(context: Context, passphrase: ByteArray, databaseName: String = DATABASE_NAME): KnowledgeBaseDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(passphrase.copyOf())
            return Room.databaseBuilder(
                context,
                KnowledgeBaseDatabase::class.java,
                databaseName
            )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(Fts5Callback())
                .build()
        }
    }
}

private class Fts5Callback : RoomDatabase.Callback() {
    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS kb_chunks_fts USING fts5(" +
                "chunkId UNINDEXED, documentId UNINDEXED, generationId UNINDEXED, " +
                "textContent, sourcePage UNINDEXED, sourceLocator UNINDEXED, " +
                "tokenize = 'unicode61')"
        )
    }

    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS kb_chunks_fts USING fts5(" +
                "chunkId UNINDEXED, documentId UNINDEXED, generationId UNINDEXED, " +
                "textContent, sourcePage UNINDEXED, sourceLocator UNINDEXED, " +
                "tokenize = 'unicode61')"
        )
    }
}
