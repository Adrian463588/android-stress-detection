package com.stressdetector.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LongitudinalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParticipant(participant: ParticipantProfileEntity)

    @Query("SELECT * FROM participant_profiles WHERE participantId = :participantId LIMIT 1")
    suspend fun getParticipant(participantId: String): ParticipantProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAssessmentSession(session: AssessmentSessionRecordEntity)

    @Query("SELECT * FROM assessment_sessions WHERE participantId = :participantId ORDER BY updatedAtMillis DESC")
    suspend fun getAssessmentSessions(participantId: String): List<AssessmentSessionRecordEntity>

    @Query("SELECT * FROM assessment_sessions WHERE participantId = :participantId AND instrumentId = :instrumentId LIMIT 1")
    suspend fun getLatestAssessmentSession(participantId: String, instrumentId: String): AssessmentSessionRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJournalEntry(entry: JournalEntryEntity)

    @Query("SELECT * FROM journal_entries WHERE participantId = :participantId ORDER BY createdAtMillis DESC")
    fun observeJournalEntries(participantId: String): Flow<List<JournalEntryEntity>>

    @Query("SELECT * FROM journal_entries WHERE participantId = :participantId ORDER BY createdAtMillis DESC LIMIT :limit")
    suspend fun getRecentJournalEntries(participantId: String, limit: Int = 20): List<JournalEntryEntity>

    @Query(
        "SELECT * FROM journal_entries WHERE participantId = :participantId " +
            "AND aiContextAllowed = 1 ORDER BY createdAtMillis DESC LIMIT :limit"
    )
    suspend fun getAiConsentedJournals(participantId: String, limit: Int = 10): List<JournalEntryEntity>

    @Query("SELECT * FROM journal_entries WHERE participantId = :participantId AND researchExportAllowed = 1 ORDER BY createdAtMillis ASC")
    suspend fun getResearchConsentedJournals(participantId: String): List<JournalEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWellbeingGoal(goal: WellbeingGoalEntity)

    @Query("SELECT * FROM wellbeing_goals WHERE participantId = :participantId ORDER BY updatedAtMillis DESC")
    fun observeWellbeingGoals(participantId: String): Flow<List<WellbeingGoalEntity>>

    @Query("SELECT * FROM wellbeing_goals WHERE participantId = :participantId ORDER BY updatedAtMillis DESC")
    suspend fun getWellbeingGoals(participantId: String): List<WellbeingGoalEntity>

    @Query("SELECT * FROM wellbeing_goals WHERE participantId = :participantId AND status = 'ACTIVE' ORDER BY updatedAtMillis DESC")
    suspend fun getActiveWellbeingGoals(participantId: String): List<WellbeingGoalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecommendation(recommendation: AssessmentRecommendationEntity)

    @Query(
        "UPDATE assessment_recommendations SET status = :status, updatedAtMillis = :updatedAtMillis " +
            "WHERE recommendationId = :recommendationId AND participantId = :participantId"
    )
    suspend fun updateRecommendationStatus(
        participantId: String,
        recommendationId: String,
        status: String,
        updatedAtMillis: Long
    )

    @Query(
        "SELECT * FROM assessment_recommendations WHERE participantId = :participantId " +
            "ORDER BY createdAtMillis DESC LIMIT :limit"
    )
    suspend fun getRecommendations(participantId: String, limit: Int = 30): List<AssessmentRecommendationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAiInterpretation(interpretation: AiInterpretationEntity)

    @Query("SELECT * FROM ai_interpretations WHERE participantId = :participantId ORDER BY createdAtMillis DESC")
    suspend fun getAiInterpretations(participantId: String): List<AiInterpretationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResearchExportManifest(manifest: ResearchExportManifestEntity)

    @Query("SELECT * FROM research_export_manifests WHERE participantId = :participantId ORDER BY exportedAtMillis DESC")
    suspend fun getResearchExportManifests(participantId: String): List<ResearchExportManifestEntity>
}
