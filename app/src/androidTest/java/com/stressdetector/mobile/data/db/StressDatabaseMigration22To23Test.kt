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
class StressDatabaseMigration22To23Test {

    @Test
    fun migrationPreservesExistingVerbatimAndDivergenceRowsWithSafeDefaults() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "stress-migration-22-23.db"
        context.deleteDatabase(databaseName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(Legacy22Callback())
                .build()
        )
        try {
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO `verbatim_sessions` (`sessionId`,`startedAtMillis`,`endedAtMillis`," +
                    "`audioFilePath`,`consentGranted`,`durationSeconds`,`status`) " +
                    "VALUES ('v22-sess', 1000, 34000, '/data/audio.wav', 1, 33, 'COMPLETED')"
            )
            db.execSQL(
                "INSERT INTO `divergence_events` (`eventId`,`sessionId`,`timestampMs`,`copyText`) " +
                    "VALUES ('v22-ev', 'v22-sess', 15000, 'Pitch change')"
            )

            StressDatabase.MIGRATION_22_23.migrate(db)

            db.query(
                "SELECT `watchWearerRole`, `audioDurationMs`, `languageCode`, `durationSeconds` " +
                    "FROM `verbatim_sessions` WHERE `sessionId`='v22-sess'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("CLIENT", cursor.getString(0))
                assertEquals(0L, cursor.getLong(1))
                assertEquals("id", cursor.getString(2))
                assertEquals(33L, cursor.getLong(3))
            }
            db.query(
                "SELECT `evidenceIdsJson`, `detectedAtElapsedMillis`, `copyText` FROM `divergence_events` WHERE `eventId`='v22-ev'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("[]", cursor.getString(0))
                assertEquals(0L, cursor.getLong(1))
                assertEquals("Pitch change", cursor.getString(2))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun v23InMemoryDatabaseReadsAndWritesNewColumns() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, StressDatabase::class.java).build()
        try {
            db.verbatimDao().insertSession(
                VerbatimSessionEntity(
                    sessionId = "s-23",
                    startedAtMillis = 10_000L,
                    endedAtMillis = 43_000L,
                    audioFilePath = "/tmp/s23.wav",
                    consentGranted = true,
                    durationSeconds = 33L,
                    status = "COMPLETED",
                    watchWearerRole = "CLIENT",
                    audioDurationMs = 33_120L,
                    languageCode = "en"
                )
            )
            val saved = db.verbatimDao().getSessionById("s-23")!!
            assertEquals(33_120L, saved.audioDurationMs)
            assertEquals("en", saved.languageCode)
            assertEquals("CLIENT", saved.watchWearerRole)
            assertEquals(1, db.verbatimDao().getAllSessions().first().size)
        } finally {
            db.close()
        }
    }

    private class Legacy22Callback : SupportSQLiteOpenHelper.Callback(22) {
        override fun onCreate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE `verbatim_sessions` (`sessionId` TEXT NOT NULL PRIMARY KEY, " +
                    "`startedAtMillis` INTEGER NOT NULL, `endedAtMillis` INTEGER NOT NULL, " +
                    "`audioFilePath` TEXT NOT NULL, `consentGranted` INTEGER NOT NULL, " +
                    "`durationSeconds` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                    "`sessionName` TEXT NOT NULL DEFAULT 'Sesi Verbatim', " +
                    "`artifactFolderPath` TEXT NOT NULL DEFAULT '', " +
                    "`finalizationStatus` TEXT NOT NULL DEFAULT 'PENDING', " +
                    "`summaryStatus` TEXT NOT NULL DEFAULT 'NOT_REQUESTED', " +
                    "`audioGapsJson` TEXT NOT NULL DEFAULT '[]')"
            )
            database.execSQL(
                "CREATE TABLE `divergence_events` (`eventId` TEXT NOT NULL PRIMARY KEY, " +
                    "`sessionId` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, " +
                    "`delta` REAL, `currentProbability` REAL, `baselineProbability` REAL, " +
                    "`copyText` TEXT NOT NULL, `channel` TEXT NOT NULL DEFAULT 'GREEN', " +
                    "`markerElapsedMillis` INTEGER NOT NULL DEFAULT 0, " +
                    "`episodeStartElapsedMillis` INTEGER NOT NULL DEFAULT 0, " +
                    "`episodeEndElapsedMillis` INTEGER NOT NULL DEFAULT 0, " +
                    "`contextWindowStartElapsedMillis` INTEGER NOT NULL DEFAULT 0, " +
                    "`contextWindowEndElapsedMillis` INTEGER NOT NULL DEFAULT 0, " +
                    "`confidence` REAL NOT NULL DEFAULT 0, `signalQuality` REAL NOT NULL DEFAULT 0, " +
                    "`sourceWindowId` TEXT, `transcriptSegmentId` TEXT, " +
                    "`markerType` TEXT NOT NULL DEFAULT 'MODEL_AROUSAL', `bpm` REAL, `baselineBpm` REAL, " +
                    "`peakBpm` REAL, `bpmDelta` REAL, `bpmTrend` TEXT, " +
                    "`sourceChannels` TEXT NOT NULL DEFAULT '', " +
                    "`markerConfigVersion` TEXT NOT NULL DEFAULT 'physiological-markers-v1', " +
                    "`audioRmsDb` REAL, `audioBaselineRmsDb` REAL, `audioBaselineDeviationDb` REAL, " +
                    "`audioZeroCrossingRate` REAL, `audioClippingRatio` REAL, `audioFiniteSampleRatio` REAL, " +
                    "`audioAnalysisVersion` TEXT)"
            )
        }

        override fun onUpgrade(database: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
