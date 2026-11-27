package com.stressdetector.mobile.data.repository

import com.google.gson.Gson
import com.stressdetector.mobile.data.db.AiInterpretationEntity
import com.stressdetector.mobile.data.db.AssessmentRecommendationEntity
import com.stressdetector.mobile.data.db.AssessmentSessionRecordEntity
import com.stressdetector.mobile.data.db.JournalEntryEntity
import com.stressdetector.mobile.data.db.LongitudinalDao
import com.stressdetector.mobile.data.db.ParticipantProfileEntity
import com.stressdetector.mobile.data.db.WellbeingGoalEntity
import com.stressdetector.mobile.domain.assessment.AssessmentResult
import com.stressdetector.mobile.domain.engine.AdaptiveCounselingRoute
import com.stressdetector.mobile.domain.engine.AssessmentRecommendationBubblePayload
import com.stressdetector.mobile.domain.engine.GoalSuggestionPayload
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

interface JournalAndGoalRepository {
    suspend fun ensureParticipant(participantId: String = DEFAULT_PARTICIPANT_ID): ParticipantProfileEntity

    suspend fun saveJournalEntry(
        participantId: String = DEFAULT_PARTICIPANT_ID,
        moodScore: Int,
        moodLabel: String,
        reflectionText: String,
        sleepQuality: Int? = null,
        stressLevelSelfReport: Int? = null,
        tags: List<String> = emptyList(),
        aiContextAllowed: Boolean = true,
        researchExportAllowed: Boolean = false
    ): JournalEntryEntity

    fun observeJournalEntries(participantId: String = DEFAULT_PARTICIPANT_ID): Flow<List<JournalEntryEntity>>

    suspend fun getAiConsentedJournals(
        participantId: String = DEFAULT_PARTICIPANT_ID,
        limit: Int = 5
    ): List<JournalEntryEntity>

    suspend fun saveGoal(
        suggestion: GoalSuggestionPayload,
        participantId: String = DEFAULT_PARTICIPANT_ID
    ): WellbeingGoalEntity

    suspend fun incrementGoalProgress(
        goalId: String,
        participantId: String = DEFAULT_PARTICIPANT_ID
    ): WellbeingGoalEntity?

    fun observeWellbeingGoals(participantId: String = DEFAULT_PARTICIPANT_ID): Flow<List<WellbeingGoalEntity>>

    suspend fun getActiveGoals(participantId: String = DEFAULT_PARTICIPANT_ID): List<WellbeingGoalEntity>

    suspend fun recordRecommendation(
        payload: AssessmentRecommendationBubblePayload,
        chatSessionId: String?,
        participantId: String = DEFAULT_PARTICIPANT_ID
    ): AssessmentRecommendationEntity

    suspend fun updateRecommendationStatus(
        recommendationId: String,
        status: String,
        participantId: String = DEFAULT_PARTICIPANT_ID
    )

    suspend fun getRecommendations(participantId: String = DEFAULT_PARTICIPANT_ID): List<AssessmentRecommendationEntity>

    suspend fun recordAssessmentLifecycle(
        result: AssessmentResult,
        ppgSessionId: String? = null,
        participantId: String = DEFAULT_PARTICIPANT_ID
    )

    suspend fun recordAiInterpretation(
        assessmentResultId: String,
        instrumentId: String,
        chatMessageId: String?,
        modelVersion: String,
        kbVersion: String,
        summaryText: String,
        participantId: String = DEFAULT_PARTICIPANT_ID
    )

    companion object {
        const val DEFAULT_PARTICIPANT_ID = "participant-local-01"
        const val DEFAULT_PSEUDONYM = "P-LOCAL-01"
    }
}

class RoomJournalAndGoalRepository(
    private val dao: LongitudinalDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val gson: Gson = Gson()
) : JournalAndGoalRepository {

    override suspend fun ensureParticipant(participantId: String): ParticipantProfileEntity =
        withContext(Dispatchers.IO) {
            dao.getParticipant(participantId) ?: ParticipantProfileEntity(
                participantId = participantId,
                pseudonymCode = if (participantId == JournalAndGoalRepository.DEFAULT_PARTICIPANT_ID) {
                    JournalAndGoalRepository.DEFAULT_PSEUDONYM
                } else {
                    "P-${participantId.take(8).uppercase()}"
                },
                createdAtMillis = clock()
            ).also { dao.upsertParticipant(it) }
        }

    override suspend fun saveJournalEntry(
        participantId: String,
        moodScore: Int,
        moodLabel: String,
        reflectionText: String,
        sleepQuality: Int?,
        stressLevelSelfReport: Int?,
        tags: List<String>,
        aiContextAllowed: Boolean,
        researchExportAllowed: Boolean
    ): JournalEntryEntity = withContext(Dispatchers.IO) {
        ensureParticipant(participantId)
        val entry = JournalEntryEntity(
            entryId = UUID.randomUUID().toString(),
            participantId = participantId,
            moodScore = moodScore.coerceIn(1, 5),
            moodLabel = moodLabel.ifBlank { "Refleksi" },
            sleepQuality = sleepQuality?.coerceIn(1, 5),
            stressLevelSelfReport = stressLevelSelfReport?.coerceIn(0, 10),
            tagsJson = gson.toJson(tags.distinct()),
            reflectionText = reflectionText.trim(),
            createdAtMillis = clock(),
            aiContextAllowed = aiContextAllowed,
            researchExportAllowed = researchExportAllowed
        )
        dao.upsertJournalEntry(entry)
        entry
    }

    override fun observeJournalEntries(participantId: String): Flow<List<JournalEntryEntity>> =
        dao.observeJournalEntries(participantId)

    override suspend fun getAiConsentedJournals(
        participantId: String,
        limit: Int
    ): List<JournalEntryEntity> = withContext(Dispatchers.IO) {
        dao.getAiConsentedJournals(participantId, limit)
    }

    override suspend fun saveGoal(
        suggestion: GoalSuggestionPayload,
        participantId: String
    ): WellbeingGoalEntity = withContext(Dispatchers.IO) {
        ensureParticipant(participantId)
        val now = clock()
        val goal = WellbeingGoalEntity(
            goalId = suggestion.goalId,
            participantId = participantId,
            title = suggestion.title,
            category = suggestion.category,
            cadence = suggestion.cadence,
            targetCount = suggestion.targetCount.coerceAtLeast(1),
            completedCount = 0,
            status = "ACTIVE",
            createdAtMillis = now,
            updatedAtMillis = now
        )
        dao.upsertWellbeingGoal(goal)
        goal
    }

    override suspend fun incrementGoalProgress(
        goalId: String,
        participantId: String
    ): WellbeingGoalEntity? = withContext(Dispatchers.IO) {
        val current = dao.getWellbeingGoals(participantId).firstOrNull { it.goalId == goalId } ?: return@withContext null
        val nextCount = (current.completedCount + 1).coerceAtMost(current.targetCount)
        val nextStatus = if (nextCount >= current.targetCount) "COMPLETED" else current.status
        val updated = current.copy(
            completedCount = nextCount,
            status = nextStatus,
            updatedAtMillis = clock()
        )
        dao.upsertWellbeingGoal(updated)
        updated
    }

    override fun observeWellbeingGoals(participantId: String): Flow<List<WellbeingGoalEntity>> =
        dao.observeWellbeingGoals(participantId)

    override suspend fun getActiveGoals(participantId: String): List<WellbeingGoalEntity> =
        withContext(Dispatchers.IO) { dao.getActiveWellbeingGoals(participantId) }

    override suspend fun recordRecommendation(
        payload: AssessmentRecommendationBubblePayload,
        chatSessionId: String?,
        participantId: String
    ): AssessmentRecommendationEntity = withContext(Dispatchers.IO) {
        ensureParticipant(participantId)
        val now = clock()
        val entity = AssessmentRecommendationEntity(
            recommendationId = payload.recommendationId,
            participantId = participantId,
            chatSessionId = chatSessionId,
            instrumentId = payload.instrumentId,
            reason = payload.rationale,
            route = AdaptiveCounselingRoute.ASSESSMENT_SUPPORT.name,
            priority = 1,
            status = payload.status,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        dao.upsertRecommendation(entity)
        entity
    }

    override suspend fun updateRecommendationStatus(
        recommendationId: String,
        status: String,
        participantId: String
    ) = withContext(Dispatchers.IO) {
        dao.updateRecommendationStatus(participantId, recommendationId, status, clock())
    }

    override suspend fun getRecommendations(participantId: String): List<AssessmentRecommendationEntity> =
        withContext(Dispatchers.IO) { dao.getRecommendations(participantId) }

    override suspend fun recordAssessmentLifecycle(
        result: AssessmentResult,
        ppgSessionId: String?,
        participantId: String
    ) = withContext(Dispatchers.IO) {
        ensureParticipant(participantId)
        val review = result.resolvedReviewStatus()
        val validity = result.resolvedValidityStatus()
        val scoring = result.resolvedScoringStatus()
        val lifecycle = when {
            result.isAiContextEligible() -> "AI_CONTEXT_ELIGIBLE"
            review.name == "APPROVED" -> "REVIEWED"
            validity.name == "VALID" -> "VALIDATED"
            else -> "SCORED"
        }
        val existing = dao.getLatestAssessmentSession(participantId, result.instrumentId)
        val now = clock()
        dao.upsertAssessmentSession(
            AssessmentSessionRecordEntity(
                sessionUuid = existing?.sessionUuid ?: UUID.randomUUID().toString(),
                participantId = participantId,
                instrumentId = result.instrumentId,
                instrumentVersion = result.version,
                formId = result.version,
                scoringSystemId = result.examinerCodes["scoring_system"],
                administrationMode = result.administrationMode,
                answeredCount = result.answered,
                totalCount = result.itemCount,
                lifecycleState = lifecycle,
                scoringStatus = scoring.name,
                validityStatus = validity.name,
                reviewStatus = review.name,
                reviewerId = result.reviewerId,
                aiContextAllowed = result.aiContextAllowed,
                researchExportAllowed = result.researchExportAllowed,
                resultId = result.resultId,
                ppgSessionId = ppgSessionId ?: existing?.ppgSessionId,
                createdAtMillis = existing?.createdAtMillis ?: result.createdAtMillis,
                updatedAtMillis = now
            )
        )
    }

    override suspend fun recordAiInterpretation(
        assessmentResultId: String,
        instrumentId: String,
        chatMessageId: String?,
        modelVersion: String,
        kbVersion: String,
        summaryText: String,
        participantId: String
    ) = withContext(Dispatchers.IO) {
        ensureParticipant(participantId)
        dao.insertAiInterpretation(
            AiInterpretationEntity(
                interpretationId = UUID.randomUUID().toString(),
                participantId = participantId,
                assessmentResultId = assessmentResultId,
                instrumentId = instrumentId,
                chatMessageId = chatMessageId,
                modelVersion = modelVersion,
                kbVersion = kbVersion,
                summaryText = summaryText,
                createdAtMillis = clock()
            )
        )
    }
}
