package com.stressdetector.mobile.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StressDatabaseMigration15To16Test {
    @Test
    fun migrationAddsNullableBpmEvidenceAndZeroCounts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "stress-migration-15-16.db"
        context.deleteDatabase(databaseName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(Legacy15Callback())
                .build()
        )
        try {
            val database = helper.writableDatabase
            database.execSQL(
                "INSERT INTO stress_session_reports " +
                    "(sessionId, sessionDurationSeconds, coreStartSeconds, coreEndSeconds, analysisDurationSeconds, " +
                    "expectedWindowCount, greenAttemptedWindows, greenValidWindows, irAttemptedWindows, " +
                    "irValidWindows, reportStatus, createdAtMillis) VALUES " +
                    "('legacy', 300, 30, 270, 240, 7, 7, 6, 7, 6, 'available', 1)"
            )

            StressDatabase.MIGRATION_15_16.migrate(database)

            val columns = mutableSetOf<String>()
            database.query("PRAGMA table_info(`stress_session_reports`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertTrue("greenMedianBpm" in columns)
            assertTrue("irMedianBpm" in columns)
            database.query(
                "SELECT greenMeanBpm, irMeanBpm, greenBpmObservationCount, irBpmObservationCount " +
                    "FROM stress_session_reports WHERE sessionId = 'legacy'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertNull(cursor.getString(0))
                assertNull(cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(0, cursor.getInt(3))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    private class Legacy15Callback : SupportSQLiteOpenHelper.Callback(15) {
        override fun onCreate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS stress_session_reports (
                    sessionId TEXT NOT NULL PRIMARY KEY,
                    sessionDurationSeconds INTEGER NOT NULL,
                    coreStartSeconds INTEGER NOT NULL,
                    coreEndSeconds INTEGER NOT NULL,
                    analysisDurationSeconds INTEGER NOT NULL,
                    expectedWindowCount INTEGER NOT NULL,
                    greenAttemptedWindows INTEGER NOT NULL,
                    greenValidWindows INTEGER NOT NULL,
                    irAttemptedWindows INTEGER NOT NULL,
                    irValidWindows INTEGER NOT NULL,
                    selectedChannel TEXT,
                    modelVersion TEXT,
                    preprocessingFingerprint TEXT,
                    reportStatus TEXT NOT NULL,
                    rejectionReason TEXT,
                    createdAtMillis INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(database: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
