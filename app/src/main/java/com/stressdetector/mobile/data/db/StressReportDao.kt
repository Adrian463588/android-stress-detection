package com.stressdetector.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class StoredStressSessionReport(
    val session: StressSessionEntity,
    val report: StressSessionReportEntity?,
    val windows: List<StressWindowEvaluationEntity>
)

@Dao
interface StressSessionReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReport(report: StressSessionReportEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWindows(windows: List<StressWindowEvaluationEntity>)

    @Query("SELECT * FROM stress_session_reports WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSessionReport(sessionId: String): StressSessionReportEntity?

    @Query("SELECT * FROM stress_session_reports WHERE sessionId = :sessionId LIMIT 1")
    fun observeSessionReport(sessionId: String): Flow<StressSessionReportEntity?>

    @Query("SELECT * FROM stress_window_evaluations WHERE sessionId = :sessionId ORDER BY channel ASC, windowIndex ASC")
    suspend fun getWindowEvaluations(sessionId: String): List<StressWindowEvaluationEntity>

    @Query("SELECT * FROM stress_window_evaluations WHERE sessionId = :sessionId ORDER BY channel ASC, windowIndex ASC")
    fun observeWindowEvaluations(sessionId: String): Flow<List<StressWindowEvaluationEntity>>

    @Query("DELETE FROM stress_window_evaluations WHERE sessionId = :sessionId")
    suspend fun deleteWindows(sessionId: String)

    @Query("DELETE FROM stress_session_reports WHERE sessionId = :sessionId")
    suspend fun deleteReport(sessionId: String)

    @Transaction
    suspend fun replaceReport(report: StressSessionReportEntity, windows: List<StressWindowEvaluationEntity>) {
        deleteWindows(report.sessionId)
        deleteReport(report.sessionId)
        insertReport(report)
        if (windows.isNotEmpty()) insertWindows(windows)
    }
}
