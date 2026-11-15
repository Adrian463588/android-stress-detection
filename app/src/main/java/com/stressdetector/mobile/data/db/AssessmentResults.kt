package com.stressdetector.mobile.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "assessment_results")
data class AssessmentResultEntity(@PrimaryKey val instrumentId: String, val payloadJson: String)

@Dao
interface AssessmentResultDao {
    @Query("SELECT * FROM assessment_results WHERE instrumentId = :id")
    suspend fun get(id: String): AssessmentResultEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(result: AssessmentResultEntity)

    @Query("SELECT * FROM assessment_results ORDER BY instrumentId")
    suspend fun all(): List<AssessmentResultEntity>

    @Query("DELETE FROM assessment_results WHERE instrumentId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM assessment_results")
    suspend fun deleteAll()
}
