package com.stressdetector.mobile.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StressDatabaseMigration20To21Test {
    @Test
    fun migrationPreservesHistoryAndAllowsUnavailableProbability() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "stress-migration-20-21.db"
        context.deleteDatabase(databaseName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(Legacy20Callback())
                .build()
        )
        try {
            val database = helper.writableDatabase
            StressDatabase.MIGRATION_20_21.migrate(database)
            database.query("PRAGMA table_info(`stress_sessions`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                val notNullIndex = cursor.getColumnIndex("notnull")
                var probabilityNullable = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "stressProbability") {
                        probabilityNullable = cursor.getInt(notNullIndex) == 0
                    }
                }
                assertTrue(probabilityNullable)
            }
            database.query("SELECT `stressProbability` FROM `stress_sessions` WHERE `sessionId`='existing'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0.7f, cursor.getFloat(0))
            }
            database.execSQL(
                "INSERT INTO `stress_sessions` VALUES " +
                    "('unavailable',1,2,1,'ppg_green','Signal unavailable',0,'invalid',NULL,0,0,NULL,0,NULL)"
            )
            database.query("SELECT `stressProbability` FROM `stress_sessions` WHERE `sessionId`='unavailable'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertFalse(cursor.moveToNext())
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    private class Legacy20Callback : SupportSQLiteOpenHelper.Callback(20) {
        override fun onCreate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE `stress_sessions` (" +
                    "`sessionId` TEXT NOT NULL PRIMARY KEY, `startedAtMillis` INTEGER NOT NULL, " +
                    "`endedAtMillis` INTEGER NOT NULL, `durationMillis` INTEGER NOT NULL, " +
                    "`primarySensor` TEXT NOT NULL, `finalLabel` TEXT NOT NULL, " +
                    "`confidence` REAL NOT NULL, `qualityTier` TEXT NOT NULL, " +
                    "`stressProbability` REAL NOT NULL, `greenSampleCount` INTEGER NOT NULL, " +
                    "`irSampleCount` INTEGER NOT NULL, `errorReason` TEXT, " +
                    "`transportIncomplete` INTEGER NOT NULL, `transportFailureReason` TEXT)"
            )
            database.execSQL(
                "INSERT INTO `stress_sessions` VALUES " +
                    "('existing',1,2,1,'ppg_green','Normal',0.8,'good',0.7,100,0,NULL,0,NULL)"
            )
        }

        override fun onUpgrade(database: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
