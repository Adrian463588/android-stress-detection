package com.stressdetector.mobile.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "participant_profiles")
data class ParticipantProfileEntity(
    @PrimaryKey val participantId: String,
    val pseudonymCode: String,
    val createdAtMillis: Long,
    val notes: String? = null
)

@Entity(
    tableName = "assessment_sessions",
    indices = [
        Index(value = ["participantId"]),
        Index(value = ["instrumentId"])
    ]
)
data class AssessmentSessionRecordEntity(
    @PrimaryKey val sessionUuid: String,
    val participantId: String,
    val instrumentId: String,
    val instrumentVersion: String,
    val formId: String? = null,
    val scoringSystemId: String? = null,
    val administrationMode: String,
    val answeredCount: Int,
    val totalCount: Int,
    val lifecycleState: String,
    val scoringStatus: String,
    val validityStatus: String,
    val reviewStatus: String,
    val reviewerId: String? = null,
    val aiContextAllowed: Boolean = false,
    val researchExportAllowed: Boolean = false,
    val resultId: String? = null,
    val ppgSessionId: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Entity(
    tableName = "journal_entries",
    indices = [
        Index(value = ["participantId"]),
        Index(value = ["createdAtMillis"])
    ]
)
data class JournalEntryEntity(
    @PrimaryKey val entryId: String,
    val participantId: String,
    val moodScore: Int,
    val moodLabel: String,
    val sleepQuality: Int? = null,
    val stressLevelSelfReport: Int? = null,
    val tagsJson: String = "[]",
    val reflectionText: String,
    val createdAtMillis: Long,
    val aiContextAllowed: Boolean = true,
    val researchExportAllowed: Boolean = false
)

@Entity(
    tableName = "wellbeing_goals",
    indices = [
        Index(value = ["participantId"]),
        Index(value = ["status"])
    ]
)
data class WellbeingGoalEntity(
    @PrimaryKey val goalId: String,
    val participantId: String,
    val title: String,
    val category: String,
    val cadence: String,
    val targetCount: Int,
    val completedCount: Int = 0,
    val status: String = "ACTIVE",
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Entity(
    tableName = "assessment_recommendations",
    indices = [
        Index(value = ["participantId"]),
        Index(value = ["chatSessionId"]),
        Index(value = ["instrumentId"])
    ]
)
data class AssessmentRecommendationEntity(
    @PrimaryKey val recommendationId: String,
    val participantId: String,
    val chatSessionId: String? = null,
    val instrumentId: String,
    val reason: String,
    val route: String,
    val priority: Int = 1,
    val status: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Entity(
    tableName = "ai_interpretations",
    indices = [
        Index(value = ["participantId"]),
        Index(value = ["assessmentResultId"]),
        Index(value = ["instrumentId"])
    ]
)
data class AiInterpretationEntity(
    @PrimaryKey val interpretationId: String,
    val participantId: String,
    val assessmentResultId: String,
    val instrumentId: String,
    val chatMessageId: String? = null,
    val modelVersion: String,
    val kbVersion: String,
    val summaryText: String,
    val createdAtMillis: Long
)

@Entity(
    tableName = "research_export_manifests",
    indices = [
        Index(value = ["participantId"]),
        Index(value = ["exportedAtMillis"])
    ]
)
data class ResearchExportManifestEntity(
    @PrimaryKey val exportId: String,
    val participantId: String,
    val exportedAtMillis: Long,
    val schemaVersion: String,
    val artifactSha256: String,
    val includedModulesJson: String,
    val consentSnapshotJson: String,
    val ethicsApprovalRef: String? = null
)
