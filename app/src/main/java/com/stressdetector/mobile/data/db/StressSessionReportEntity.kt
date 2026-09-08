package com.stressdetector.mobile.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Persisted report-level evidence for one Home stress session. */
@Entity(tableName = "stress_session_reports")
data class StressSessionReportEntity(
    @PrimaryKey val sessionId: String,
    val sessionDurationSeconds: Int,
    val coreStartSeconds: Int,
    val coreEndSeconds: Int,
    val analysisDurationSeconds: Int,
    val expectedWindowCount: Int,
    val greenAttemptedWindows: Int,
    val greenValidWindows: Int,
    val irAttemptedWindows: Int,
    val irValidWindows: Int,
    val selectedChannel: String?,
    val modelVersion: String?,
    val preprocessingFingerprint: String?,
    val evidenceMode: String = "production",
    val reportStatus: String,
    val rejectionReason: String?,
    val greenMeanBpm: Float? = null,
    val greenMedianBpm: Float? = null,
    val greenMinBpm: Float? = null,
    val greenMaxBpm: Float? = null,
    val greenBpmObservationCount: Int = 0,
    val greenValidPeakCount: Int = 0,
    val irMeanBpm: Float? = null,
    val irMedianBpm: Float? = null,
    val irMinBpm: Float? = null,
    val irMaxBpm: Float? = null,
    val irBpmObservationCount: Int = 0,
    val irValidPeakCount: Int = 0,
    val createdAtMillis: Long
)
