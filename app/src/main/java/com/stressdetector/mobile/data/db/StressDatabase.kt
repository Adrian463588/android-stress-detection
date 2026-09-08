@file:Suppress("MaxLineLength", "MagicNumber")

package com.stressdetector.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
@Database(
    entities = [
        StressSessionEntity::class,
        VerbatimSessionEntity::class,
        TranscriptSegmentEntity::class,
        PsychologyCodeEntity::class,
        AiAnalysisEntity::class,
        LiveSessionEntity::class,
        RealtimeInferenceRecordEntity::class,
        DivergenceEventEntity::class,
        AnnotationEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        UserConsentEntity::class,
        AiReminderEntity::class,
        ClinicalDocumentEntity::class,
        DocumentChunkEntity::class,
        CounselorPersonaEntity::class,
        StressSessionReportEntity::class,
        StressWindowEvaluationEntity::class
    ],
    version = 20,
    exportSchema = false
)
abstract class StressDatabase : RoomDatabase() {
    abstract fun stressSessionDao(): StressSessionDao
    abstract fun stressSessionReportDao(): StressSessionReportDao
    abstract fun verbatimDao(): VerbatimDao
    abstract fun liveMonitorDao(): LiveMonitorDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun chatDao(): ChatDao
    abstract fun consentDao(): ConsentDao
    abstract fun aiReminderDao(): AiReminderDao
    abstract fun documentDao(): DocumentDao
    abstract fun counselorPersonaDao(): CounselorPersonaDao

    companion object {
        @Volatile private var instance: StressDatabase? = null

        fun get(context: Context): StressDatabase = instance ?: synchronized(this) {
            instance ?: buildDatabase(context).also { instance = it }
        }

        private fun buildDatabase(context: Context): StressDatabase = Room.databaseBuilder(
            context.applicationContext,
            StressDatabase::class.java,
            "stress_sessions.db"
        )
            .addMigrations(
                MIGRATION_1_4,
                MIGRATION_2_4,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
                MIGRATION_19_20
            )
            .build()

        private val MIGRATION_1_4 = object : Migration(1, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                createMissingTables(database)
                addAiPlusColumns(database)
            }
        }

        private val MIGRATION_2_4 = object : Migration(2, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                createMissingTables(database)
                addAiPlusColumns(database)
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                createMissingTables(database)
                addAiPlusColumns(database)
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addColumnIfMissing(database, "clinical_documents", "ingestionVersion", "TEXT NOT NULL DEFAULT 'pdf-page-chunk-v1'")
                addColumnIfMissing(database, "clinical_documents", "embeddingModelVersion", "TEXT")
                addColumnIfMissing(database, "clinical_documents", "embeddingStatus", "TEXT NOT NULL DEFAULT 'UNAVAILABLE'")
                addColumnIfMissing(database, "document_chunks", "embeddingVersion", "TEXT")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `counselor_persona` (`profileId` TEXT NOT NULL, `markdown` TEXT NOT NULL, `version` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`profileId`))"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addColumnIfMissing(database, "chat_messages", "inputMode", "TEXT")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addColumnIfMissing(database, "clinical_documents", "processingState", "TEXT NOT NULL DEFAULT 'PENDING'")
                addColumnIfMissing(database, "clinical_documents", "processingError", "TEXT")
                database.execSQL(
                    """
                    UPDATE clinical_documents
                    SET processingState = CASE
                        WHEN isProcessed = 1 AND embeddingStatus = 'SEMANTIC_READY' THEN 'READY_SEMANTIC'
                        WHEN isProcessed = 1 THEN 'READY_LEXICAL'
                        ELSE 'PENDING'
                    END
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addColumnIfMissing(database, "verbatim_sessions", "sessionName", "TEXT NOT NULL DEFAULT 'Sesi Verbatim'")
                addColumnIfMissing(database, "verbatim_sessions", "artifactFolderPath", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(database, "verbatim_sessions", "finalizationStatus", "TEXT NOT NULL DEFAULT 'PENDING'")
                addColumnIfMissing(database, "verbatim_sessions", "summaryStatus", "TEXT NOT NULL DEFAULT 'NOT_REQUESTED'")
                database.execSQL(
                    "UPDATE verbatim_sessions SET artifactFolderPath = audioFilePath WHERE artifactFolderPath = '' AND audioFilePath <> ''"
                )
                database.execSQL(
                    "UPDATE verbatim_sessions SET finalizationStatus = CASE WHEN status IN ('COMPLETED', 'TRANSCRIBED', 'ANALYZED') THEN 'COMPLETED' ELSE 'LEGACY' END"
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addColumnIfMissing(database, "divergence_events", "channel", "TEXT NOT NULL DEFAULT 'GREEN'")
                addColumnIfMissing(database, "divergence_events", "markerElapsedMillis", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(database, "divergence_events", "episodeStartElapsedMillis", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(database, "divergence_events", "episodeEndElapsedMillis", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(database, "divergence_events", "contextWindowStartElapsedMillis", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(database, "divergence_events", "contextWindowEndElapsedMillis", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(database, "divergence_events", "confidence", "REAL NOT NULL DEFAULT 0")
                addColumnIfMissing(database, "divergence_events", "signalQuality", "REAL NOT NULL DEFAULT 0")
                addColumnIfMissing(database, "divergence_events", "sourceWindowId", "TEXT")
                addColumnIfMissing(database, "divergence_events", "transcriptSegmentId", "TEXT")
                addColumnIfMissing(database, "realtime_inference_records", "channel", "TEXT NOT NULL DEFAULT 'GREEN'")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Physiological markers added by the live Verbatim pipeline.
                addColumnIfMissing(database, "divergence_events", "markerType", "TEXT NOT NULL DEFAULT 'MODEL_AROUSAL'")
                addColumnIfMissing(database, "divergence_events", "bpm", "REAL")
                addColumnIfMissing(database, "divergence_events", "baselineBpm", "REAL")
                addColumnIfMissing(database, "divergence_events", "peakBpm", "REAL")
                addColumnIfMissing(database, "divergence_events", "bpmDelta", "REAL")
                addColumnIfMissing(database, "divergence_events", "bpmTrend", "TEXT")
                addColumnIfMissing(database, "divergence_events", "sourceChannels", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(database, "divergence_events", "markerConfigVersion", "TEXT NOT NULL DEFAULT 'physiological-markers-v1'")

                // PDF text-first/OCR provenance. Existing documents remain readable;
                // only new imports contain full page-level audit records.
                addColumnIfMissing(database, "clinical_documents", "extractionStatus", "TEXT NOT NULL DEFAULT 'PENDING'")
                addColumnIfMissing(database, "clinical_documents", "ocrProvenance", "TEXT")
                addColumnIfMissing(database, "clinical_documents", "pageExtractionJson", "TEXT")
                addColumnIfMissing(database, "clinical_documents", "indexedChunkCount", "INTEGER NOT NULL DEFAULT 0")
                database.execSQL(
                    "UPDATE clinical_documents SET extractionStatus = CASE " +
                        "WHEN isProcessed = 1 THEN 'LEGACY_NO_PAGE_AUDIT' ELSE extractionStatus END"
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addColumnIfMissing(database, "stress_sessions", "transportIncomplete", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(database, "stress_sessions", "transportFailureReason", "TEXT")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addColumnIfMissing(database, "divergence_events", "audioRmsDb", "REAL")
                addColumnIfMissing(database, "divergence_events", "audioBaselineRmsDb", "REAL")
                addColumnIfMissing(database, "divergence_events", "audioBaselineDeviationDb", "REAL")
                addColumnIfMissing(database, "divergence_events", "audioZeroCrossingRate", "REAL")
                addColumnIfMissing(database, "divergence_events", "audioClippingRatio", "REAL")
                addColumnIfMissing(database, "divergence_events", "audioFiniteSampleRatio", "REAL")
                addColumnIfMissing(database, "divergence_events", "audioAnalysisVersion", "TEXT")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stress_session_reports` (
                        `sessionId` TEXT NOT NULL,
                        `sessionDurationSeconds` INTEGER NOT NULL,
                        `coreStartSeconds` INTEGER NOT NULL,
                        `coreEndSeconds` INTEGER NOT NULL,
                        `analysisDurationSeconds` INTEGER NOT NULL,
                        `expectedWindowCount` INTEGER NOT NULL,
                        `greenAttemptedWindows` INTEGER NOT NULL,
                        `greenValidWindows` INTEGER NOT NULL,
                        `irAttemptedWindows` INTEGER NOT NULL,
                        `irValidWindows` INTEGER NOT NULL,
                        `selectedChannel` TEXT,
                        `modelVersion` TEXT,
                        `preprocessingFingerprint` TEXT,
                        `reportStatus` TEXT NOT NULL,
                        `rejectionReason` TEXT,
                        `createdAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stress_window_evaluations` (
                        `sessionId` TEXT NOT NULL,
                        `channel` TEXT NOT NULL,
                        `windowIndex` INTEGER NOT NULL,
                        `windowId` TEXT NOT NULL,
                        `startElapsedMillis` INTEGER,
                        `endElapsedMillis` INTEGER,
                        `status` TEXT NOT NULL,
                        `label` TEXT,
                        `stressProbability` REAL,
                        `confidence` REAL,
                        `qualityTier` TEXT,
                        `qualityScore` REAL,
                        `sampleCount` INTEGER NOT NULL,
                        `interpolatedSampleCount` INTEGER NOT NULL,
                        `rejectionReason` TEXT,
                        `modelVersion` TEXT,
                        `preprocessingFingerprint` TEXT,
                        PRIMARY KEY(`sessionId`, `channel`, `windowIndex`)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_stress_window_evaluations_sessionId` " +
                        "ON `stress_window_evaluations` (`sessionId`)"
                )
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addColumnIfMissing(database, "document_chunks", "charStart", "INTEGER")
                addColumnIfMissing(database, "document_chunks", "charEnd", "INTEGER")
                addColumnIfMissing(database, "document_chunks", "tokenStart", "INTEGER")
                addColumnIfMissing(database, "document_chunks", "tokenEnd", "INTEGER")
                addColumnIfMissing(
                    database,
                    "document_chunks",
                    "chunkPolicyVersion",
                    "TEXT NOT NULL DEFAULT 'legacy'"
                )
            }
        }

        /** Adds observed-only, per-channel BPM evidence to Home reports. */
        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addColumnIfMissing(database, "stress_session_reports", "greenMeanBpm", "REAL")
                addColumnIfMissing(database, "stress_session_reports", "greenMedianBpm", "REAL")
                addColumnIfMissing(database, "stress_session_reports", "greenMinBpm", "REAL")
                addColumnIfMissing(database, "stress_session_reports", "greenMaxBpm", "REAL")
                addColumnIfMissing(
                    database,
                    "stress_session_reports",
                    "greenBpmObservationCount",
                    "INTEGER NOT NULL DEFAULT 0"
                )
                addColumnIfMissing(
                    database,
                    "stress_session_reports",
                    "greenValidPeakCount",
                    "INTEGER NOT NULL DEFAULT 0"
                )
                addColumnIfMissing(database, "stress_session_reports", "irMeanBpm", "REAL")
                addColumnIfMissing(database, "stress_session_reports", "irMedianBpm", "REAL")
                addColumnIfMissing(database, "stress_session_reports", "irMinBpm", "REAL")
                addColumnIfMissing(database, "stress_session_reports", "irMaxBpm", "REAL")
                addColumnIfMissing(
                    database,
                    "stress_session_reports",
                    "irBpmObservationCount",
                    "INTEGER NOT NULL DEFAULT 0"
                )
                addColumnIfMissing(
                    database,
                    "stress_session_reports",
                    "irValidPeakCount",
                    "INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** Adds explicit state provenance so stale recovery is based on processing activity, not upload age. */
        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addColumnIfMissing(database, "clinical_documents", "processingErrorCode", "TEXT")
                addColumnIfMissing(
                    database,
                    "clinical_documents",
                    "processingStartedAtMillis",
                    "INTEGER NOT NULL DEFAULT 0"
                )
                addColumnIfMissing(
                    database,
                    "clinical_documents",
                    "processingUpdatedAtMillis",
                    "INTEGER NOT NULL DEFAULT 0"
                )
                addColumnIfMissing(
                    database,
                    "clinical_documents",
                    "processingRevision",
                    "INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "UPDATE clinical_documents SET processingStartedAtMillis = uploadedAtMillis " +
                        "WHERE processingStartedAtMillis = 0"
                )
                database.execSQL(
                    "UPDATE clinical_documents SET processingUpdatedAtMillis = uploadedAtMillis " +
                        "WHERE processingUpdatedAtMillis = 0"
                )
            }
        }

        /** Makes unavailable inference evidence explicit instead of encoding it as a numeric sentinel. */
        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addColumnIfMissing(database, "verbatim_sessions", "audioGapsJson", "TEXT NOT NULL DEFAULT '[]'")

                database.execSQL("ALTER TABLE `realtime_inference_records` RENAME TO `realtime_inference_records_legacy`")
                database.execSQL(
                    "CREATE TABLE `realtime_inference_records` (" +
                        "`recordId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, " +
                        "`stressProbability` REAL, `label` TEXT NOT NULL, `qualityScore` REAL NOT NULL, " +
                        "`isValidWindow` INTEGER NOT NULL, `channel` TEXT NOT NULL DEFAULT 'GREEN', " +
                        "PRIMARY KEY(`recordId`))"
                )
                database.execSQL(
                    "INSERT INTO `realtime_inference_records` " +
                        "(`recordId`,`sessionId`,`timestampMs`,`stressProbability`,`label`,`qualityScore`,`isValidWindow`,`channel`) " +
                        "SELECT `recordId`,`sessionId`,`timestampMs`,`stressProbability`,`label`,`qualityScore`," +
                        "`isValidWindow`,`channel` " +
                        "FROM `realtime_inference_records_legacy`"
                )
                database.execSQL("DROP TABLE `realtime_inference_records_legacy`")

                database.execSQL("ALTER TABLE `divergence_events` RENAME TO `divergence_events_legacy`")
                database.execSQL(
                    "CREATE TABLE `divergence_events` (" +
                        "`eventId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, " +
                        "`delta` REAL, `currentProbability` REAL, `baselineProbability` REAL, `copyText` TEXT NOT NULL, " +
                        "`channel` TEXT NOT NULL DEFAULT 'GREEN', `markerElapsedMillis` INTEGER NOT NULL DEFAULT 0, " +
                        "`episodeStartElapsedMillis` INTEGER NOT NULL DEFAULT 0, " +
                        "`episodeEndElapsedMillis` INTEGER NOT NULL DEFAULT 0, " +
                        "`contextWindowStartElapsedMillis` INTEGER NOT NULL DEFAULT 0, " +
                        "`contextWindowEndElapsedMillis` INTEGER NOT NULL DEFAULT 0, " +
                        "`confidence` REAL NOT NULL DEFAULT 0, `signalQuality` REAL NOT NULL DEFAULT 0, " +
                        "`sourceWindowId` TEXT, `transcriptSegmentId` TEXT, `markerType` TEXT NOT NULL DEFAULT 'MODEL_AROUSAL', " +
                        "`bpm` REAL, `baselineBpm` REAL, `peakBpm` REAL, `bpmDelta` REAL, `bpmTrend` TEXT, " +
                        "`sourceChannels` TEXT NOT NULL DEFAULT '', " +
                        "`markerConfigVersion` TEXT NOT NULL DEFAULT 'physiological-markers-v1', " +
                        "`audioRmsDb` REAL, `audioBaselineRmsDb` REAL, `audioBaselineDeviationDb` REAL, " +
                        "`audioZeroCrossingRate` REAL, `audioClippingRatio` REAL, `audioFiniteSampleRatio` REAL, " +
                        "`audioAnalysisVersion` TEXT, PRIMARY KEY(`eventId`))"
                )
                database.execSQL(
                    "INSERT INTO `divergence_events` SELECT * FROM `divergence_events_legacy`"
                )
                database.execSQL("DROP TABLE `divergence_events_legacy`")
            }
        }

        /** Separates research-model reports from production evidence. */
        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addColumnIfMissing(
                    database,
                    "stress_session_reports",
                    "evidenceMode",
                    "TEXT NOT NULL DEFAULT 'production'"
                )
            }
        }

        /** Adds idempotent AI+ reminder scheduling metadata; sensitive payloads stay encrypted. */
        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(database: SupportSQLiteDatabase) {
                createAiReminderTable(database)
            }
        }

        private fun createMissingTables(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `verbatim_sessions` (`sessionId` TEXT NOT NULL, `startedAtMillis` INTEGER NOT NULL, `endedAtMillis` INTEGER NOT NULL, `audioFilePath` TEXT NOT NULL, `consentGranted` INTEGER NOT NULL, `durationSeconds` INTEGER NOT NULL, `status` TEXT NOT NULL, `sessionName` TEXT NOT NULL DEFAULT 'Sesi Verbatim', `artifactFolderPath` TEXT NOT NULL DEFAULT '', `finalizationStatus` TEXT NOT NULL DEFAULT 'PENDING', `summaryStatus` TEXT NOT NULL DEFAULT 'NOT_REQUESTED', `audioGapsJson` TEXT NOT NULL DEFAULT '[]', PRIMARY KEY(`sessionId`))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `transcript_segments` (`segmentId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `speakerId` TEXT NOT NULL, `startOffsetMs` INTEGER NOT NULL, `endOffsetMs` INTEGER NOT NULL, `rawText` TEXT NOT NULL, `cleanedText` TEXT, `confidence` REAL NOT NULL, `language` TEXT NOT NULL, PRIMARY KEY(`segmentId`))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `psychology_codes` (`codeId` TEXT NOT NULL, `segmentId` TEXT NOT NULL, `category` TEXT NOT NULL, `codeLabel` TEXT NOT NULL, `confidence` REAL NOT NULL, `isAiSuggestion` INTEGER NOT NULL, `citationId` TEXT, PRIMARY KEY(`codeId`))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `ai_analyses` (`analysisId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `version` INTEGER NOT NULL, `summaryText` TEXT NOT NULL, `keyInsightsJson` TEXT NOT NULL, `generatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`analysisId`))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `live_sessions` (`sessionId` TEXT NOT NULL, `startedAtMillis` INTEGER NOT NULL, `endedAtMillis` INTEGER, `durationSeconds` INTEGER NOT NULL, `consentGranted` INTEGER NOT NULL, `averageStressProbability` REAL NOT NULL, PRIMARY KEY(`sessionId`))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `realtime_inference_records` (`recordId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, `stressProbability` REAL, `label` TEXT NOT NULL, `qualityScore` REAL NOT NULL, `isValidWindow` INTEGER NOT NULL, `channel` TEXT NOT NULL DEFAULT 'GREEN', PRIMARY KEY(`recordId`))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `divergence_events` (`eventId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, `delta` REAL, `currentProbability` REAL, `baselineProbability` REAL, `copyText` TEXT NOT NULL, `channel` TEXT NOT NULL DEFAULT 'GREEN', `markerElapsedMillis` INTEGER NOT NULL DEFAULT 0, `episodeStartElapsedMillis` INTEGER NOT NULL DEFAULT 0, `episodeEndElapsedMillis` INTEGER NOT NULL DEFAULT 0, `contextWindowStartElapsedMillis` INTEGER NOT NULL DEFAULT 0, `contextWindowEndElapsedMillis` INTEGER NOT NULL DEFAULT 0, `confidence` REAL NOT NULL DEFAULT 0, `signalQuality` REAL NOT NULL DEFAULT 0, `sourceWindowId` TEXT, `transcriptSegmentId` TEXT, `markerType` TEXT NOT NULL DEFAULT 'MODEL_AROUSAL', `bpm` REAL, `baselineBpm` REAL, `peakBpm` REAL, `bpmDelta` REAL, `bpmTrend` TEXT, `sourceChannels` TEXT NOT NULL DEFAULT '', `markerConfigVersion` TEXT NOT NULL DEFAULT 'physiological-markers-v1', `audioRmsDb` REAL, `audioBaselineRmsDb` REAL, `audioBaselineDeviationDb` REAL, `audioZeroCrossingRate` REAL, `audioClippingRatio` REAL, `audioFiniteSampleRatio` REAL, `audioAnalysisVersion` TEXT, PRIMARY KEY(`eventId`))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `annotations` (`annotationId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, `text` TEXT NOT NULL, `author` TEXT NOT NULL, `citationId` TEXT, PRIMARY KEY(`annotationId`))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `chat_sessions` (`chatSessionId` TEXT NOT NULL, `title` TEXT NOT NULL, `startedAtMillis` INTEGER NOT NULL, `lastMessageAtMillis` INTEGER NOT NULL, PRIMARY KEY(`chatSessionId`))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `chat_messages` (`messageId` TEXT NOT NULL, `chatSessionId` TEXT NOT NULL, `sender` TEXT NOT NULL, `content` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, `citationsJson` TEXT, `stressContextJson` TEXT, `promptVersion` TEXT, `retrievalMode` TEXT, `inputMode` TEXT, PRIMARY KEY(`messageId`))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `user_consents` (`consentType` TEXT NOT NULL, `grantedAtMillis` INTEGER NOT NULL, `isGranted` INTEGER NOT NULL, PRIMARY KEY(`consentType`))"
            )
            createAiReminderTable(database)
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `clinical_documents` (`documentId` TEXT NOT NULL, `title` TEXT NOT NULL, `filePath` TEXT NOT NULL, `uploadedAtMillis` INTEGER NOT NULL, `isProcessed` INTEGER NOT NULL, `sourceFileName` TEXT, `sourceSha256` TEXT, PRIMARY KEY(`documentId`))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `document_chunks` (`chunkId` TEXT NOT NULL, `documentId` TEXT NOT NULL, `chunkIndex` INTEGER NOT NULL, `textContent` TEXT NOT NULL, `embeddingBlob` BLOB, `sourceTitle` TEXT, `sourcePage` INTEGER, `sourceLocator` TEXT, PRIMARY KEY(`chunkId`))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `counselor_persona` (`profileId` TEXT NOT NULL, `markdown` TEXT NOT NULL, `version` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`profileId`))"
            )
        }

        private fun addAiPlusColumns(database: SupportSQLiteDatabase) {
            addColumnIfMissing(database, "chat_messages", "stressContextJson", "TEXT")
            addColumnIfMissing(database, "chat_messages", "promptVersion", "TEXT")
            addColumnIfMissing(database, "chat_messages", "retrievalMode", "TEXT")
            addColumnIfMissing(database, "chat_messages", "inputMode", "TEXT")
            addColumnIfMissing(database, "clinical_documents", "sourceFileName", "TEXT")
            addColumnIfMissing(database, "clinical_documents", "sourceSha256", "TEXT")
            addColumnIfMissing(database, "document_chunks", "sourceTitle", "TEXT")
            addColumnIfMissing(database, "document_chunks", "sourcePage", "INTEGER")
            addColumnIfMissing(database, "document_chunks", "sourceLocator", "TEXT")
        }

        private fun createAiReminderTable(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `ai_reminders` (" +
                    "`reminderId` TEXT NOT NULL, `occurrenceKey` TEXT NOT NULL, " +
                    "`sourceSessionId` TEXT NOT NULL, `scheduledAtMillis` INTEGER NOT NULL, " +
                    "`zoneId` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, " +
                    "`generatedAtMillis` INTEGER, `deliveredAtMillis` INTEGER, `modelVersion` TEXT, " +
                    "`policyVersion` TEXT NOT NULL, `payloadKey` TEXT, `lastErrorCode` TEXT, " +
                    "PRIMARY KEY(`reminderId`))"
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_reminders_occurrenceKey` " +
                    "ON `ai_reminders` (`occurrenceKey`)"
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_reminders_sourceSessionId` " +
                    "ON `ai_reminders` (`sourceSessionId`)"
            )
        }

        private fun addColumnIfMissing(database: SupportSQLiteDatabase, tableName: String, columnName: String, columnDefinition: String) {
            var present = false
            database.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0 && cursor.getString(nameIndex) == columnName) {
                        present = true
                        break
                    }
                }
            }
            if (!present) database.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $columnDefinition")
        }
    }
}
