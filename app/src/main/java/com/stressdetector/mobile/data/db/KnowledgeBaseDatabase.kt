package com.stressdetector.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        KnowledgeBaseGenerationEntity::class,
        KnowledgeBaseDocumentEntity::class,
        KnowledgeBaseChunkEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class KnowledgeBaseDatabase : RoomDatabase() {
    abstract fun knowledgeBaseDao(): KnowledgeBaseDao

    companion object {
        private const val DATABASE_NAME = "knowledge_base_secure.db"

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
