package com.stressdetector.mobile.data.repository

import com.stressdetector.mobile.data.db.AiInterpretationEntity
import com.stressdetector.mobile.data.db.AssessmentRecommendationEntity
import com.stressdetector.mobile.data.db.AssessmentSessionRecordEntity
import com.stressdetector.mobile.data.db.JournalEntryEntity
import com.stressdetector.mobile.data.db.LongitudinalDao
import com.stressdetector.mobile.data.db.ParticipantProfileEntity
import com.stressdetector.mobile.data.db.ResearchExportManifestEntity
import com.stressdetector.mobile.data.db.WellbeingGoalEntity
import com.stressdetector.mobile.domain.engine.AssessmentRecommendationBubblePayload
import com.stressdetector.mobile.domain.engine.GoalSuggestionPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalAndGoalRepositoryTest {

    @Test
    fun `journal entries isolate consent flags and participants cleanly`() = runBlocking {
        val dao = InMemoryLongitudinalDao()
        val repo = RoomJournalAndGoalRepository(dao = dao, clock = { 1_000L })

        val allowed = repo.saveJournalEntry(
            participantId = "p-a",
            moodScore = 4,
            moodLabel = "Tenang",
            reflectionText = "Refleksi p-a diizinkan",
            aiContextAllowed = true
        )
        repo.saveJournalEntry(
            participantId = "p-a",
            moodScore = 2,
            moodLabel = "Berat",
            reflectionText = "Catatan privat tidak untuk AI",
            aiContextAllowed = false
        )
        repo.saveJournalEntry(
            participantId = "p-b",
            moodScore = 1,
            moodLabel = "Cemas",
            reflectionText = "Milik partisipan B",
            aiContextAllowed = true
        )

        val aiForA = repo.getAiConsentedJournals(participantId = "p-a")
        assertEquals(1, aiForA.size)
        assertEquals(allowed.entryId, aiForA.single().entryId)
    }

    @Test
    fun `wellbeing goals progress cleanly to completed`() = runBlocking {
        val dao = InMemoryLongitudinalDao()
        val repo = RoomJournalAndGoalRepository(dao = dao, clock = { 2_000L })

        val saved = repo.saveGoal(
            GoalSuggestionPayload(
                goalId = "goal-1",
                title = "Latihan napas 5 menit",
                category = "Regulasi Emosi",
                targetCount = 2
            )
        )
        assertEquals("ACTIVE", saved.status)

        val step1 = repo.incrementGoalProgress("goal-1")!!
        assertEquals(1, step1.completedCount)
        assertEquals("ACTIVE", step1.status)

        val step2 = repo.incrementGoalProgress("goal-1")!!
        assertEquals(2, step2.completedCount)
        assertEquals("COMPLETED", step2.status)
    }

    @Test
    fun `assessment recommendation tracks defer and decline transitions`() = runBlocking {
        val dao = InMemoryLongitudinalDao()
        val repo = RoomJournalAndGoalRepository(dao = dao, clock = { 3_000L })
        val payload = AssessmentRecommendationBubblePayload(
            recommendationId = "rec-1",
            instrumentId = "bdi",
            instrumentTitle = "BDI",
            purpose = "Skrining gejala depresi",
            rationale = "Keluhan mood rendah",
            eligibilityNote = "Skrining tervalidasi",
            estimatedDuration = "5 menit"
        )

        repo.recordRecommendation(payload, chatSessionId = "chat-1")
        repo.updateRecommendationStatus("rec-1", "DEFERRED")
        val records = repo.getRecommendations()
        assertEquals(1, records.size)
        assertTrue(records.single().status == "DEFERRED")
    }
}

private class InMemoryLongitudinalDao : LongitudinalDao {
    private val participants = mutableMapOf<String, ParticipantProfileEntity>()
    private val sessions = mutableMapOf<String, AssessmentSessionRecordEntity>()
    private val journals = mutableListOf<JournalEntryEntity>()
    private val goals = mutableMapOf<String, WellbeingGoalEntity>()
    private val recs = mutableMapOf<String, AssessmentRecommendationEntity>()
    private val interps = mutableListOf<AiInterpretationEntity>()
    private val exports = mutableListOf<ResearchExportManifestEntity>()

    override suspend fun upsertParticipant(participant: ParticipantProfileEntity) {
        participants[participant.participantId] = participant
    }

    override suspend fun getParticipant(participantId: String) = participants[participantId]

    override suspend fun upsertAssessmentSession(session: AssessmentSessionRecordEntity) {
        sessions[session.sessionUuid] = session
    }

    override suspend fun getAssessmentSessions(participantId: String) =
        sessions.values.filter { it.participantId == participantId }

    override suspend fun getLatestAssessmentSession(participantId: String, instrumentId: String) =
        sessions.values.firstOrNull { it.participantId == participantId && it.instrumentId == instrumentId }

    override suspend fun upsertJournalEntry(entry: JournalEntryEntity) {
        journals.removeAll { it.entryId == entry.entryId }
        journals.add(0, entry)
    }

    override fun observeJournalEntries(participantId: String): Flow<List<JournalEntryEntity>> =
        flowOf(journals.filter { it.participantId == participantId })

    override suspend fun getRecentJournalEntries(participantId: String, limit: Int) =
        journals.filter { it.participantId == participantId }.take(limit)

    override suspend fun getAiConsentedJournals(participantId: String, limit: Int) =
        journals.filter { it.participantId == participantId && it.aiContextAllowed }.take(limit)

    override suspend fun getResearchConsentedJournals(participantId: String) =
        journals.filter { it.participantId == participantId && it.researchExportAllowed }

    override suspend fun upsertWellbeingGoal(goal: WellbeingGoalEntity) {
        goals[goal.goalId] = goal
    }

    override fun observeWellbeingGoals(participantId: String): Flow<List<WellbeingGoalEntity>> =
        flowOf(goals.values.filter { it.participantId == participantId })

    override suspend fun getWellbeingGoals(participantId: String) =
        goals.values.filter { it.participantId == participantId }

    override suspend fun getActiveWellbeingGoals(participantId: String) =
        goals.values.filter { it.participantId == participantId && it.status == "ACTIVE" }

    override suspend fun upsertRecommendation(recommendation: AssessmentRecommendationEntity) {
        recs[recommendation.recommendationId] = recommendation
    }

    override suspend fun updateRecommendationStatus(
        participantId: String,
        recommendationId: String,
        status: String,
        updatedAtMillis: Long
    ) {
        recs[recommendationId]?.let {
            if (it.participantId == participantId) {
                recs[recommendationId] = it.copy(status = status, updatedAtMillis = updatedAtMillis)
            }
        }
    }

    override suspend fun getRecommendations(participantId: String, limit: Int) =
        recs.values.filter { it.participantId == participantId }.take(limit)

    override suspend fun insertAiInterpretation(interpretation: AiInterpretationEntity) {
        interps += interpretation
    }

    override suspend fun getAiInterpretations(participantId: String) =
        interps.filter { it.participantId == participantId }

    override suspend fun insertResearchExportManifest(manifest: ResearchExportManifestEntity) {
        exports += manifest
    }

    override suspend fun getResearchExportManifests(participantId: String) =
        exports.filter { it.participantId == participantId }
}
