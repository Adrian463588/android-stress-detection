package com.stressdetector.mobile.data.db

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StressDatabaseMigration23To24Test {

    @Test
    fun migration23To24AddsLongitudinalTablesAndPreservesChatMessages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "stress-migration-23-24.db"
        context.deleteDatabase(databaseName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(Legacy23Callback())
                .build()
        )
        try {
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO `chat_messages` (`messageId`,`chatSessionId`,`sender`,`content`,`timestampMs`) " +
                    "VALUES ('m23-1', 'c23-1', 'user', 'Halo konselor', 1000)"
            )

            StressDatabase.MIGRATION_23_24.migrate(db)

            db.query(
                "SELECT `content`, `messageType`, `structuredPayloadJson` FROM `chat_messages` WHERE `messageId`='m23-1'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Halo konselor", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
            }

            listOf(
                "participant_profiles",
                "assessment_sessions",
                "journal_entries",
                "wellbeing_goals",
                "assessment_recommendations",
                "ai_interpretations",
                "research_export_manifests"
            ).forEach { table ->
                db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { cursor ->
                    assertTrue("Missing table $table", cursor.moveToFirst())
                }
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun v24InMemoryDatabaseSupportsJournalAndGoalRoundTrip() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, StressDatabase::class.java).build()
        try {
            val dao = db.longitudinalDao()
            dao.upsertParticipant(
                ParticipantProfileEntity(
                    participantId = "p-01",
                    pseudonymCode = "P-01",
                    createdAtMillis = 500L
                )
            )
            dao.upsertJournalEntry(
                JournalEntryEntity(
                    entryId = "j-01",
                    participantId = "p-01",
                    moodScore = 4,
                    moodLabel = "Tenang",
                    reflectionText = "Hari ini lebih terkendali.",
                    createdAtMillis = 1_000L
                )
            )
            assertEquals(1, dao.getRecentJournalEntries("p-01").size)
        } finally {
            db.close()
        }
    }

    private class Legacy23Callback : SupportSQLiteOpenHelper.Callback(23) {
        override fun onCreate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE `chat_messages` (`messageId` TEXT NOT NULL PRIMARY KEY, " +
                    "`chatSessionId` TEXT NOT NULL, `sender` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                    "`timestampMs` INTEGER NOT NULL, `citationsJson` TEXT, `stressContextJson` TEXT, " +
                    "`promptVersion` TEXT, `retrievalMode` TEXT, `inputMode` TEXT)"
            )
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
