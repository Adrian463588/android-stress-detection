package com.stressdetector.mobile.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StressDatabaseMigration19To20Test {
    @Test
    fun migrationCreatesEncryptedPayloadMetadataTableAndUniqueIndexes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "stress-migration-19-20.db"
        context.deleteDatabase(databaseName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(Legacy19Callback())
                .build()
        )
        try {
            val database = helper.writableDatabase
            StressDatabase.MIGRATION_19_20.migrate(database)
            database.query("PRAGMA table_info(`ai_reminders`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
                assertTrue("payloadKey" in columns)
                assertTrue("lastErrorCode" in columns)
            }
            database.query("PRAGMA index_list(`ai_reminders`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                val indexes = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
                assertTrue("index_ai_reminders_occurrenceKey" in indexes)
                assertTrue("index_ai_reminders_sourceSessionId" in indexes)
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    private class Legacy19Callback : SupportSQLiteOpenHelper.Callback(19) {
        override fun onCreate(database: SupportSQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS legacy_marker (id INTEGER NOT NULL PRIMARY KEY)")
        }

        override fun onUpgrade(database: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
