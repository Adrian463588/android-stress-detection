package com.stressdetector.mobile.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "assessment_results")
data class AssessmentResultEntity(@PrimaryKey val instrumentId: String, val payloadJson: String)

@Entity(
    tableName = "assessment_result_snapshots",
    indices = [
        Index(value = ["instrumentId", "createdAtMillis"]),
        Index(value = ["participantId", "createdAtMillis"])
    ]
)
data class AssessmentResultSnapshotEntity(
    @PrimaryKey val resultId: String,
    val instrumentId: String,
    val participantId: String,
    val createdAtMillis: Long,
    val payloadJson: String
)

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
    suspend fun deleteLegacyAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSnapshot(snapshot: AssessmentResultSnapshotEntity)

    @Query("SELECT * FROM assessment_result_snapshots WHERE resultId = :resultId LIMIT 1")
    suspend fun getSnapshot(resultId: String): AssessmentResultSnapshotEntity?

    @Query("SELECT * FROM assessment_result_snapshots ORDER BY createdAtMillis DESC")
    suspend fun allSnapshots(): List<AssessmentResultSnapshotEntity>

    @Query("SELECT * FROM assessment_result_snapshots WHERE instrumentId = :instrumentId ORDER BY createdAtMillis DESC")
    suspend fun snapshotsForInstrument(instrumentId: String): List<AssessmentResultSnapshotEntity>

    @Query("DELETE FROM assessment_result_snapshots WHERE resultId = :resultId")
    suspend fun deleteSnapshot(resultId: String)

    @Query("DELETE FROM assessment_result_snapshots WHERE instrumentId = :instrumentId")
    suspend fun deleteSnapshotsForInstrument(instrumentId: String)

    @Query("DELETE FROM assessment_result_snapshots")
    suspend fun deleteAllSnapshots()

    suspend fun deleteAll() {
        deleteLegacyAll()
        deleteAllSnapshots()
    }
}
