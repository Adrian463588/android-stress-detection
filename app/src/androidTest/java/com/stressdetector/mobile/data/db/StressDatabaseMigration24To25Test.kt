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
class StressDatabaseMigration24To25Test {

    @Test
    fun migration24To25AddsSkinTemperatureTableAndDivergenceColumnsPreservingLegacyData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "stress-migration-24-25.db"
        context.deleteDatabase(databaseName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(Legacy24Callback())
                .build()
        )
        try {
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO `verbatim_sessions` (`sessionId`,`startedAtMillis`,`endedAtMillis`," +
                    "`audioFilePath`,`consentGranted`,`durationSeconds`,`status`) " +
                    "VALUES ('v24-1', 1000, 5000, 'verbatim/v24-1/audio.wav.enc', 1, 4, 'COMPLETED')"
            )
            db.execSQL(
                "INSERT INTO `divergence_events` (`eventId`,`sessionId`,`timestampMs`,`copyText`) " +
                    "VALUES ('ev24-1', 'v24-1', 2000, 'Perubahan BPM teramati')"
            )

            StressDatabase.MIGRATION_24_25.migrate(db)

            db.query("SELECT `sessionName`, `status` FROM `verbatim_sessions` WHERE `sessionId`='v24-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Sesi Verbatim", cursor.getString(0))
                assertEquals("COMPLETED", cursor.getString(1))
            }

            db.query(
                "SELECT `copyText`, `thermalDirection`, `ambientTemperatureC`, `ambientDeltaC`, `slopeCelsiusPerMin` " +
                    "FROM `divergence_events` WHERE `eventId`='ev24-1'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Perubahan BPM teramati", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
                assertTrue(cursor.isNull(4))
            }

            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='skin_temperature_samples'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun v25InMemoryDatabaseSupportsSkinTemperatureCrudAndCascadeDelete() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, StressDatabase::class.java).build()
        try {
            val dao = db.skinTemperatureDao()
            dao.insertAll(
                listOf(
                    SkinTemperatureSampleEntity(
                        sampleId = "s25:1",
                        sessionId = "s25",
                        generation = 1L,
                        sequenceNumber = 1L,
                        sensorTimestampMs = 1_760_000_010_000L,
                        receivedTimestampMs = 1_760_000_010_100L,
                        elapsedMillis = 10_000L,
                        clockUncertaintyMs = 250L,
                        skinTemperatureC = 33.4f,
                        ambientTemperatureC = 26.2f,
                        measurementStatus = 0,
                        isValid = true,
                        wearerRole = "CLIENT",
                        source = "WATCH_SDK_SKIN_TEMP"
                    )
                )
            )
            assertEquals(1, dao.getForSessionOnce("s25").size)
            dao.deleteForSession("s25")
            assertEquals(0, dao.getForSessionOnce("s25").size)
        } finally {
            db.close()
        }
    }

    private class Legacy24Callback : SupportSQLiteOpenHelper.Callback(24) {
        override fun onCreate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE `verbatim_sessions` (`sessionId` TEXT NOT NULL PRIMARY KEY, " +
                    "`startedAtMillis` INTEGER NOT NULL, `endedAtMillis` INTEGER NOT NULL, " +
                    "`audioFilePath` TEXT NOT NULL, `consentGranted` INTEGER NOT NULL, " +
                    "`durationSeconds` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                    "`sessionName` TEXT NOT NULL DEFAULT 'Sesi Verbatim')"
            )
            database.execSQL(
                "CREATE TABLE `divergence_events` (`eventId` TEXT NOT NULL PRIMARY KEY, " +
                    "`sessionId` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, `copyText` TEXT NOT NULL)"
            )
        }

        override fun onUpgrade(database: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
