package com.stressdetector.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StressSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: StressSessionEntity)

    @Query("SELECT * FROM stress_sessions ORDER BY endedAtMillis DESC LIMIT :limit")
    fun latest(limit: Int = 50): Flow<List<StressSessionEntity>>

    @Query("SELECT * FROM stress_sessions WHERE endedAtMillis BETWEEN :startMillis AND :endMillis ORDER BY endedAtMillis DESC")
    fun between(startMillis: Long, endMillis: Long): Flow<List<StressSessionEntity>>

    @Query("SELECT * FROM stress_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getById(sessionId: String): StressSessionEntity?

    @Query("SELECT * FROM stress_sessions WHERE sessionId = :sessionId LIMIT 1")
    fun observeById(sessionId: String): Flow<StressSessionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReport(report: StressSessionReportEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWindowEvaluations(evaluations: List<StressWindowEvaluationEntity>)

    @Query("DELETE FROM stress_window_evaluations WHERE sessionId = :sessionId")
    suspend fun deleteWindowEvaluations(sessionId: String)

    /** Replaces the session evidence graph as one Room transaction. */
    @androidx.room.Transaction
    suspend fun insertWithReport(
        session: StressSessionEntity,
        report: StressSessionReportEntity,
        evaluations: List<StressWindowEvaluationEntity>
    ) {
        insert(session)
        insertReport(report)
        deleteWindowEvaluations(session.sessionId)
        if (evaluations.isNotEmpty()) insertWindowEvaluations(evaluations)
    }
}
