package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.data.repository.AssessmentRepository
import com.stressdetector.mobile.domain.assessment.AssessmentResult
import com.stressdetector.mobile.domain.assessment.AssessmentScore
import com.stressdetector.mobile.domain.assessment.AssessmentValidityStatus
import com.stressdetector.mobile.domain.assessment.InstrumentImplementationStatus
import com.stressdetector.mobile.domain.assessment.PsychometricInstrumentRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ClinicalCounselingContextAssemblerTest {

    @Test
    fun `assembles named assessment plus verbatim transcript prosody and session notes`() = runBlocking {
        val assessmentRepo = mock(AssessmentRepository::class.java)
        val tiu5 = sampleAssessment("tiu_5", "TIU-5", "Kurang Sekali (KS)", "Kesulitan analogi figural cepat.")
        val cfit = sampleAssessment("cfit", "CFIT Skala 3", "Rata-rata", "Penalaran non-verbal cukup.")
        `when`(assessmentRepo.resultForDiscussion("Bahas hasil TIU-5 dan saran REBT")).thenReturn(tiu5)
        `when`(assessmentRepo.getAllResults()).thenReturn(listOf(tiu5, cfit))

        val sessionSummary = VerbatimSessionSummary(
            sessionId = "sess-1",
            sessionName = "Sesi Konseling Pagi",
            startedAtMillis = 1_700_000_000_000L,
            endedAtMillis = 1_700_000_300_000L,
            durationSeconds = 300L,
            audioFilePath = "",
            artifactFolderPath = "",
            finalizationStatus = "COMPLETED",
            summaryStatus = "COMPLETED"
        )
        val verbatimRepo = FakeVerbatimSessionRepository(
            summary = sessionSummary,
            detail = VerbatimSessionDetail(
                summary = sessionSummary,
                transcript = listOf(
                    SpeechSegment(
                        id = "seg-0",
                        speakerId = "Konselor",
                        startOffsetMs = 0L,
                        endOffsetMs = 2_000L,
                        startTimestampUtc = 1_700_000_000_000L,
                        endTimestampUtc = 1_700_000_002_000L,
                        rawText = "Bagaimana perasaan Anda hari ini?",
                        confidence = 0.95f
                    ),
                    SpeechSegment(
                        id = "seg-1",
                        speakerId = "Klien",
                        startOffsetMs = 2_500L,
                        endOffsetMs = 6_000L,
                        startTimestampUtc = 1_700_000_002_500L,
                        endTimestampUtc = 1_700_000_006_000L,
                        rawText = "Saya sering cemas saat tugas numpuk dan takut salah.",
                        confidence = 0.92f,
                        prosody = VerbatimProsodyEvidence(
                            features = mapOf(
                                "F0semitoneFrom27.5Hz" to 34.2,
                                "loudness" to 0.72,
                                "jitterLocal" to 0.0124
                            ),
                            frameCount = 40,
                            voicedFrameCount = 32
                        )
                    )
                ),
                divergenceEvents = listOf(
                    DivergenceEventEntity(
                        eventId = "div-1",
                        sessionId = "sess-1",
                        timestampMs = 3_000L,
                        delta = 0.25f,
                        currentProbability = 0.80f,
                        baselineProbability = 0.55f,
                        copyText = "Peningkatan stres fisiologis saat membahas target",
                        markerType = "HIGH_STRESS_WITH_HESITATION"
                    )
                )
            ),
            notes = "Catatan: klien responsif terhadap restrukturisasi kognitif."
        )

        val assembler = ClinicalCounselingContextAssembler(assessmentRepo, verbatimRepo)
        val context = assembler.assemble(
            userQuery = "Bahas hasil TIU-5 dan saran REBT",
            interactionMode = CounselingInteractionMode.COUNSELOR_PLANNING,
            includeBehavioralContext = true
        )

        assertNotNull(context.primaryAssessmentResult)
        assertTrue(context.assessmentSummary.contains("[tiu_5]"))
        assertTrue(context.assessmentSummary.contains("[cfit]"))
        assertTrue(context.verbatimSummary.contains("Sesi Konseling Pagi"))
        assertTrue(context.verbatimSummary.contains("sering cemas saat tugas numpuk"))
        assertFalse(context.verbatimSummary.contains("Bagaimana perasaan Anda hari ini?"))
        assertTrue(context.verbatimSummary.contains("F0="))
        assertTrue(context.verbatimSummary.contains("HIGH_STRESS_WITH_HESITATION"))
        assertTrue(context.verbatimSummary.contains("restrukturisasi kognitif"))
        assertTrue(context.enrichedRetrievalQuery.contains("teknik konseling"))
    }

    @Test
    fun `respects opt-out of behavioral context and keeps general counseling clean without clinical intent`() = runBlocking {
        val assessmentRepo = mock(AssessmentRepository::class.java)
        val tiu5 = sampleAssessment("tiu_5", "TIU-5", "Baik", "Ringkasan")
        `when`(assessmentRepo.resultForDiscussion("halo")).thenReturn(null)
        `when`(assessmentRepo.getAllResults()).thenReturn(listOf(tiu5))

        val verbatimRepo = FakeVerbatimSessionRepository(null, null, null)
        val assembler = ClinicalCounselingContextAssembler(assessmentRepo, verbatimRepo)

        val context = assembler.assemble(
            userQuery = "halo selamat pagi",
            interactionMode = CounselingInteractionMode.GENERAL_COUNSELING,
            includeBehavioralContext = false
        )

        assertNull(context.primaryAssessmentResult)
        assertTrue(context.verbatimSummary.isEmpty())
    }

    @Test
    fun `invalid or unconsented assessments are excluded from valid ai context`() = runBlocking {
        val assessmentRepo = mock(AssessmentRepository::class.java)
        val invalidBdi = sampleAssessment("bdi", "BDI", "Berat", "Invalid").copy(
            validityStatus = AssessmentValidityStatus.INVALID
        )
        val blockedDisc = sampleAssessment("disc", "DISC", "S", "Blocked").copy(
            aiContextAllowed = false
        )
        `when`(assessmentRepo.resultForDiscussion("bahas BDI")).thenReturn(invalidBdi)
        `when`(assessmentRepo.getAllResults()).thenReturn(listOf(invalidBdi, blockedDisc))

        val assembler = ClinicalCounselingContextAssembler(assessmentRepo, null)
        val context = assembler.assemble(
            userQuery = "bahas BDI",
            interactionMode = CounselingInteractionMode.ASSESSMENT_INTERPRETATION,
            includeBehavioralContext = false
        )

        assertNull(context.primaryAssessmentResult)
        assertFalse(context.assessmentSummary.contains("[bdi]"))
        assertFalse(context.assessmentSummary.contains("[disc]"))
        assertTrue(context.assessmentSummary.contains("belum memenuhi syarat"))
    }

    private fun sampleAssessment(id: String, title: String, category: String, interpretation: String) = AssessmentResult(
        resultId = "res-$id",
        instrumentId = id,
        title = title,
        version = "1.0",
        sourceTitle = "Bank Psikotes",
        sourceUrl = "asset://$id",
        sourceLocator = id,
        createdAtMillis = 1_700_000_000_000L,
        answered = 10,
        itemCount = 10,
        scores = listOf(AssessmentScore("Kategori", 10.0, 0.0, 20.0, category)),
        interpretation = interpretation,
        schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
        scoringStatus = InstrumentImplementationStatus.VERIFIED,
        validityStatus = AssessmentValidityStatus.VALID,
        aiContextAllowed = true
    )

    private class FakeVerbatimSessionRepository(
        private val summary: VerbatimSessionSummary?,
        private val detail: VerbatimSessionDetail?,
        private val notes: String?
    ) : VerbatimSessionRepository {
        override fun observeSessions(): Flow<List<VerbatimSessionSummary>> = flowOf(listOfNotNull(summary))
        override fun observeSession(sessionId: String): Flow<VerbatimSessionDetail?> = flowOf(detail)
        override suspend fun getLatestSessionNotes(sessionId: String): String? = notes
        override suspend fun deleteSession(sessionId: String): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAllSessions(): Result<Unit> = Result.success(Unit)
    }
}
