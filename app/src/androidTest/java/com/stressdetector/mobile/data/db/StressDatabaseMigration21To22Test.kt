package com.stressdetector.mobile.data.db

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StressDatabaseMigration21To22Test {

    @Test
    fun migrationCreatesSearchHistoryTableWithCompositePrimaryKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "stress-migration-21-22.db"
        context.deleteDatabase(databaseName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(Legacy21Callback())
                .build()
        )
        try {
            val database = helper.writableDatabase
            StressDatabase.MIGRATION_21_22.migrate(database)
            database.execSQL(
                "INSERT INTO `search_history` VALUES ('wartegg', 'Wartegg', 'ASSESSMENT', 1000)"
            )
            database.execSQL(
                "INSERT INTO `search_history` VALUES ('wartegg', 'Wartegg', 'COUNSELING_MATERIAL', 2000)"
            )
            database.query(
                "SELECT `canonicalTitle`, `category` FROM `search_history` ORDER BY `lastSearchedAt` DESC"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("COUNSELING_MATERIAL", cursor.getString(1))
                assertTrue(cursor.moveToNext())
                assertEquals("ASSESSMENT", cursor.getString(1))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun roomSearchHistoryDaoSeparatesCategoriesDeduplicatesAndPersists() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, StressDatabase::class.java).build()
        try {
            val dao = db.searchHistoryDao()
            dao.upsert(SearchHistoryEntity("wartegg", "Wartegg", "ASSESSMENT", 100L))
            dao.upsert(SearchHistoryEntity("mmpi", "MMPI", "ASSESSMENT", 200L))
            dao.upsert(SearchHistoryEntity("bk-01", "Dasar, Asas & Kode Etik BK", "COUNSELING_MATERIAL", 300L))
            // Duplicate updates timestamp
            dao.upsert(SearchHistoryEntity("wartegg", "Wartegg", "ASSESSMENT", 400L))

            val assessments = dao.observeRecent("ASSESSMENT").first()
            assertEquals(listOf("wartegg", "mmpi"), assessments.map { it.canonicalItemId })

            val counseling = dao.observeRecent("COUNSELING_MATERIAL").first()
            assertEquals(listOf("bk-01"), counseling.map { it.canonicalItemId })

            dao.delete("mmpi", "ASSESSMENT")
            assertEquals(listOf("wartegg"), dao.observeRecent("ASSESSMENT").first().map { it.canonicalItemId })

            dao.clearCategory("ASSESSMENT")
            assertTrue(dao.observeRecent("ASSESSMENT").first().isEmpty())
            assertEquals(1, dao.observeRecent("COUNSELING_MATERIAL").first().size)
        } finally {
            db.close()
        }
    }

    private class Legacy21Callback : SupportSQLiteOpenHelper.Callback(21) {
        override fun onCreate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE `stress_sessions` (`sessionId` TEXT NOT NULL PRIMARY KEY, " +
                    "`startedAtMillis` INTEGER NOT NULL, `endedAtMillis` INTEGER NOT NULL, " +
                    "`durationMillis` INTEGER NOT NULL, `primarySensor` TEXT NOT NULL, " +
                    "`finalLabel` TEXT NOT NULL, `confidence` REAL NOT NULL, `qualityTier` TEXT NOT NULL, " +
                    "`stressProbability` REAL, `greenSampleCount` INTEGER NOT NULL, " +
                    "`irSampleCount` INTEGER NOT NULL, `errorReason` TEXT, " +
                    "`transportIncomplete` INTEGER NOT NULL, `transportFailureReason` TEXT)"
            )
        }

        override fun onUpgrade(database: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
