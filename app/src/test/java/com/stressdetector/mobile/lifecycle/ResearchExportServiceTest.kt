package com.stressdetector.mobile.lifecycle

import com.stressdetector.mobile.data.db.AiInterpretationEntity
import com.stressdetector.mobile.data.db.AssessmentRecommendationEntity
import com.stressdetector.mobile.data.db.AssessmentSessionRecordEntity
import com.stressdetector.mobile.data.db.JournalEntryEntity
import com.stressdetector.mobile.data.db.LongitudinalDao
import com.stressdetector.mobile.data.db.ParticipantProfileEntity
import com.stressdetector.mobile.data.db.ResearchExportManifestEntity
import com.stressdetector.mobile.data.db.StressSessionDao
import com.stressdetector.mobile.data.db.WellbeingGoalEntity
import com.stressdetector.mobile.data.repository.AssessmentRepository
import com.stressdetector.mobile.data.repository.JournalAndGoalRepository
import com.stressdetector.mobile.domain.assessment.AssessmentResult
import com.stressdetector.mobile.domain.assessment.AssessmentScore
import com.stressdetector.mobile.domain.assessment.AssessmentValidityStatus
import com.stressdetector.mobile.domain.assessment.InstrumentImplementationStatus
import com.stressdetector.mobile.domain.assessment.PsychometricInstrumentRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ResearchExportServiceTest {

    @Test
    fun `research export includes only consented records with pseudonym and manifest hash`() = runBlocking {
        val tempDir = Files.createTempDirectory("research-export-test").toFile()
        try {
            val consentedAssessment = sampleResult("disc", researchExportAllowed = true)
            val privateAssessment = sampleResult("bdi", researchExportAllowed = false)
            val assessmentRepo = mock(AssessmentRepository::class.java)
            `when`(assessmentRepo.getAllResults()).thenReturn(listOf(consentedAssessment, privateAssessment))

            val stressDao = mock(StressSessionDao::class.java)
            `when`(stressDao.latest(200)).thenReturn(flowOf(emptyList()))

            val longitudinalDao = StubExportLongitudinalDao(
                participant = ParticipantProfileEntity(
                    participantId = JournalAndGoalRepository.DEFAULT_PARTICIPANT_ID,
                    pseudonymCode = "P-ETHICS-07",
                    createdAtMillis = 100L
                ),
                journals = listOf(
                    JournalEntryEntity(
                        entryId = "j-ok",
                        participantId = JournalAndGoalRepository.DEFAULT_PARTICIPANT_ID,
                        moodScore = 4,
                        moodLabel = "Tenang",
                        reflectionText = "Refleksi diizinkan riset",
                        createdAtMillis = 200L,
                        aiContextAllowed = true,
                        researchExportAllowed = true
                    ),
                    JournalEntryEntity(
                        entryId = "j-no",
                        participantId = JournalAndGoalRepository.DEFAULT_PARTICIPANT_ID,
                        moodScore = 1,
                        moodLabel = "Berat",
                        reflectionText = "Rahasia pribadi",
                        createdAtMillis = 300L,
                        aiContextAllowed = true,
                        researchExportAllowed = false
                    )
                )
            )

            val service = ResearchExportService(
                longitudinalDao = longitudinalDao,
                assessmentRepository = assessmentRepo,
                stressSessionDao = stressDao,
                exportDirectory = tempDir,
                clock = { 99_999L }
            )

            val outcome = service.exportParticipantBundle(ethicsApprovalRef = "KEPK-2026-01")
            assertEquals(1, outcome.includedAssessmentCount)
            assertEquals(1, outcome.includedJournalCount)
            assertEquals(64, outcome.artifactSha256.length)
            assertTrue(outcome.exportFile.exists())
            val content = outcome.exportFile.readText(Charsets.UTF_8)
            assertTrue(content.contains("P-ETHICS-07"))
            assertTrue(content.contains("disc"))
            assertFalse(content.contains("bdi"))
            assertFalse(content.contains("Rahasia pribadi"))
            assertEquals(1, longitudinalDao.manifests.size)
            assertEquals("KEPK-2026-01", longitudinalDao.manifests.single().ethicsApprovalRef)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun sampleResult(id: String, researchExportAllowed: Boolean) = AssessmentResult(
        resultId = "res-$id",
        instrumentId = id,
        title = id.uppercase(),
        version = "1.0",
        sourceTitle = "Bank Psikotes",
        sourceUrl = "asset://$id",
        sourceLocator = id,
        createdAtMillis = 1_700_000_000_000L,
        answered = 10,
        itemCount = 10,
        scores = listOf(AssessmentScore("Total", 10.0, 0.0, 20.0, "Normal")),
        interpretation = "Interpretasi deskriptif",
        schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
        scoringStatus = InstrumentImplementationStatus.VERIFIED,
        validityStatus = AssessmentValidityStatus.VALID,
        researchExportAllowed = researchExportAllowed
    )
}

private class StubExportLongitudinalDao(
    private val participant: ParticipantProfileEntity,
    private val journals: List<JournalEntryEntity>
) : LongitudinalDao {
    val manifests = mutableListOf<ResearchExportManifestEntity>()
    override suspend fun upsertParticipant(participant: ParticipantProfileEntity) = Unit
    override suspend fun getParticipant(participantId: String) = participant
    override suspend fun upsertAssessmentSession(session: AssessmentSessionRecordEntity) = Unit
    override suspend fun getAssessmentSessions(participantId: String) = emptyList<AssessmentSessionRecordEntity>()
    override suspend fun getLatestAssessmentSession(participantId: String, instrumentId: String) = null
    override suspend fun upsertJournalEntry(entry: JournalEntryEntity) = Unit
    override fun observeJournalEntries(participantId: String): Flow<List<JournalEntryEntity>> = flowOf(journals)
    override suspend fun getRecentJournalEntries(participantId: String, limit: Int) = journals
    override suspend fun getAiConsentedJournals(participantId: String, limit: Int) =
        journals.filter { it.aiContextAllowed }
    override suspend fun getResearchConsentedJournals(participantId: String) =
        journals.filter { it.researchExportAllowed }
    override suspend fun upsertWellbeingGoal(goal: WellbeingGoalEntity) = Unit
    override fun observeWellbeingGoals(participantId: String): Flow<List<WellbeingGoalEntity>> = flowOf(emptyList())
    override suspend fun getWellbeingGoals(participantId: String) = emptyList<WellbeingGoalEntity>()
    override suspend fun getActiveWellbeingGoals(participantId: String) = emptyList<WellbeingGoalEntity>()
    override suspend fun upsertRecommendation(recommendation: AssessmentRecommendationEntity) = Unit
    override suspend fun updateRecommendationStatus(
        participantId: String,
        recommendationId: String,
        status: String,
        updatedAtMillis: Long
    ) = Unit
    override suspend fun getRecommendations(participantId: String, limit: Int) = emptyList<AssessmentRecommendationEntity>()
    override suspend fun insertAiInterpretation(interpretation: AiInterpretationEntity) = Unit
    override suspend fun getAiInterpretations(participantId: String) = emptyList<AiInterpretationEntity>()
    override suspend fun insertResearchExportManifest(manifest: ResearchExportManifestEntity) {
        manifests += manifest
    }
    override suspend fun getResearchExportManifests(participantId: String) = manifests
}
