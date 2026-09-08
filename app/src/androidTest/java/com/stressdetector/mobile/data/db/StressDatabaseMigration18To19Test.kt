package com.stressdetector.mobile.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StressDatabaseMigration18To19Test {
    @Test
    fun migrationMarksExistingReportsAsProductionEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "stress-migration-18-19.db"
        context.deleteDatabase(databaseName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(Legacy18Callback())
                .build()
        )
        try {
            val database = helper.writableDatabase
            database.execSQL(
                "INSERT INTO stress_session_reports " +
                    "(sessionId, reportStatus, createdAtMillis) VALUES ('legacy', 'available', 1)"
            )

            StressDatabase.MIGRATION_18_19.migrate(database)

            database.query("PRAGMA table_info(`stress_session_reports`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
                assertTrue("evidenceMode" in columns)
            }
            database.query(
                "SELECT evidenceMode FROM stress_session_reports WHERE sessionId = 'legacy'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("production", cursor.getString(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    private class Legacy18Callback : SupportSQLiteOpenHelper.Callback(18) {
        override fun onCreate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS stress_session_reports (
                    sessionId TEXT NOT NULL PRIMARY KEY,
                    reportStatus TEXT NOT NULL,
                    createdAtMillis INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(database: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
