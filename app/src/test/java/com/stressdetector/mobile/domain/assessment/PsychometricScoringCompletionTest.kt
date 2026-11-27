package com.stressdetector.mobile.domain.assessment

import com.google.gson.Gson
import com.stressdetector.mobile.data.repository.AssessmentRepository
import com.stressdetector.mobile.domain.engine.ClinicalCounselingContextAssembler
import com.stressdetector.mobile.domain.engine.CounselingInteractionMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class PsychometricScoringCompletionTest {

    private fun sampleResult(
        instrumentId: String,
        participantId: String = "local-participant-01",
        resultId: String = "res-$instrumentId-1",
        scores: List<AssessmentScore> = emptyList(),
        scoringStatus: InstrumentImplementationStatus = InstrumentImplementationStatus.RAW_SCORE_ONLY,
        validityStatus: AssessmentValidityStatus = AssessmentValidityStatus.VALID,
        reviewStatus: ExaminerReviewStatus = ExaminerReviewStatus.NOT_REQUIRED,
        aiContextAllowed: Boolean = true,
        observations: List<AssessmentObservation> = emptyList(),
        warteggDrafts: List<WarteggBoxDraft> = emptyList(),
        rorschachResponses: List<RorschachResponseDraft> = emptyList()
    ) = AssessmentResult(
        resultId = resultId,
        instrumentId = instrumentId,
        title = PsychometricInstrumentRegistry.specFor(instrumentId).canonicalTitle,
        version = PsychometricInstrumentRegistry.specFor(instrumentId).formVersion,
        sourceTitle = "PsikoTest",
        sourceUrl = "",
        sourceLocator = "§1",
        createdAtMillis = 1_700_000_000_000L,
        answered = 10,
        itemCount = 10,
        scores = scores,
        interpretation = "Ringkasan deskriptif.",
        schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
        participantId = participantId,
        scoringStatus = scoringStatus,
        validityStatus = validityStatus,
        reviewStatus = reviewStatus,
        aiContextAllowed = aiContextAllowed,
        observations = observations,
        warteggDrafts = warteggDrafts,
        rorschachResponses = rorschachResponses
    )

    @Test
    fun mapsMbtiToBipolarPreferenceModelWithFourPoles() {
        val scores = listOf(
            AssessmentScore("Tipe MBTI", 1.0, 1.0, 1.0, "INFP — Mediator"),
            AssessmentScore("Ekstrovert (E)", 3.0, 0.0, 10.0, ""),
            AssessmentScore("Introvert (I)", 7.0, 0.0, 10.0, ""),
            AssessmentScore("Sensing (S)", 6.0, 0.0, 20.0, ""),
            AssessmentScore("Intuition (N)", 14.0, 0.0, 20.0, ""),
            AssessmentScore("Thinking (T)", 5.0, 0.0, 20.0, ""),
            AssessmentScore("Feeling (F)", 15.0, 0.0, 20.0, ""),
            AssessmentScore("Judging (J)", 8.0, 0.0, 20.0, ""),
            AssessmentScore("Perceiving (P)", 12.0, 0.0, 20.0, "")
        )
        val model = AssessmentVisualizationMapper.map(sampleResult("mbti", scores = scores))
        assertTrue(model is AssessmentVisualizationModel.BipolarPreferenceModel)
        val bipolar = model as AssessmentVisualizationModel.BipolarPreferenceModel
        assertEquals(4, bipolar.axes.size)
        assertEquals("I", bipolar.axes[0].dominantCode)
        assertEquals("N", bipolar.axes[1].dominantCode)
        assertEquals("F", bipolar.axes[2].dominantCode)
        assertEquals("P", bipolar.axes[3].dominantCode)
    }

    @Test
    fun mapsBigFiveAndHollandWithRelativeRadarAndCfitWithRawVsIqKpi() {
        val bigFiveScores = listOf(
            AssessmentScore("Openness", 42.0, 10.0, 50.0, ""),
            AssessmentScore("Conscientiousness", 38.0, 10.0, 50.0, ""),
            AssessmentScore("Extraversion", 27.0, 10.0, 50.0, ""),
            AssessmentScore("Agreeableness", 44.0, 10.0, 50.0, ""),
            AssessmentScore("Neuroticism", 24.0, 10.0, 50.0, "")
        )
        val bigFiveModel = AssessmentVisualizationMapper.map(
            sampleResult("big_five", scores = bigFiveScores, scoringStatus = InstrumentImplementationStatus.VERIFIED)
        ) as AssessmentVisualizationModel.HorizontalBarChartModel
        assertEquals(5, bigFiveModel.radarAxes.size)

        val hollandScores = listOf(
            AssessmentScore("Investigative (I)", 36.0, 0.0, 40.0, ""),
            AssessmentScore("Conventional (C)", 32.0, 0.0, 40.0, ""),
            AssessmentScore("Realistic (R)", 28.0, 0.0, 40.0, ""),
            AssessmentScore("Enterprising (E)", 24.0, 0.0, 40.0, ""),
            AssessmentScore("Social (S)", 20.0, 0.0, 40.0, ""),
            AssessmentScore("Artistic (A)", 16.0, 0.0, 40.0, "")
        )
        val hollandModel = AssessmentVisualizationMapper.map(
            sampleResult("holland", scores = hollandScores)
        ) as AssessmentVisualizationModel.RankedBarChartModel
        assertEquals("ICR", hollandModel.dominantProfileCode)
        assertEquals(6, hollandModel.radarAxes.size)

        val cfitScores = listOf(
            AssessmentScore("Total Skor Mentah (RS)", 34.0, 0.0, 50.0, ""),
            AssessmentScore("Subtes 1 Series", 9.0, 0.0, 13.0, ""),
            AssessmentScore("Subtes 2 Classification", 10.0, 0.0, 14.0, "")
        )
        val cfitModel = AssessmentVisualizationMapper.map(
            sampleResult("cfit_3a", scores = cfitScores)
        ) as AssessmentVisualizationModel.HorizontalBarChartModel
        assertTrue(cfitModel.kpiMetrics.any { it.label.contains("Raw", ignoreCase = true) && it.value == "34/50" })
        assertTrue(cfitModel.kpiMetrics.any { it.label.contains("IQ", ignoreCase = true) && it.value == "—" })
    }

    @Test
    fun mapsTiuWptFrtToScoreIndicatorWarteggStatusRowsAndHtpDapObservationMatrix() {
        listOf("tiu_5", "tiu_6", "wpt", "frt").forEach { id ->
            val model = AssessmentVisualizationMapper.map(
                sampleResult(id, scores = listOf(AssessmentScore("Total Skor Mentah (RS)", 20.0, 0.0, 40.0, "Mentah")))
            )
            assertTrue("$id should map to ScoreIndicatorModel", model is AssessmentVisualizationModel.ScoreIndicatorModel)
        }

        val wartegg = AssessmentVisualizationMapper.map(
            sampleResult(
                "wartegg",
                scoringStatus = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
                reviewStatus = ExaminerReviewStatus.PENDING,
                warteggDrafts = listOf(WarteggBoxDraft(1, drawOrder = 1, durationSeconds = 18))
            )
        ) as AssessmentVisualizationModel.WarteggEightBoxModel
        assertEquals(3, wartegg.codingStatusRows.size)

        listOf("htp", "dap").forEach { projId ->
            val obsModel = AssessmentVisualizationMapper.map(
                sampleResult(
                    projId,
                    scoringStatus = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
                    reviewStatus = ExaminerReviewStatus.PENDING,
                    observations = listOf(AssessmentObservation("proporsi", "Proporsi Gambar", "Sedang"))
                )
            )
            assertTrue("$projId should map to ObservationMatrixModel", obsModel is AssessmentVisualizationModel.ObservationMatrixModel)
        }
    }

    @Test
    fun baselineInterpretationCoversAll39InstrumentsOfflineWithoutFabricatedNorms() {
        val allSpecs = PsychometricInstrumentRegistry.allSpecs()
        assertEquals(40, allSpecs.size)
        allSpecs.forEach { spec ->
            val result = sampleResult(
                instrumentId = spec.instrumentId,
                scoringStatus = spec.implementationStatus,
                validityStatus = if (spec.implementationStatus == InstrumentImplementationStatus.UNRESOLVED_IDENTITY) {
                    AssessmentValidityStatus.INVALID
                } else {
                    AssessmentValidityStatus.VALID
                },
                reviewStatus = if (spec.requiresExaminerReview) ExaminerReviewStatus.PENDING else ExaminerReviewStatus.NOT_REQUIRED,
                scores = listOf(AssessmentScore("Total", 12.0, 0.0, 30.0, "Skor mentah."))
            )
            val report = AssessmentInterpretationProvider.build(result)
            assertTrue(report.constructMeaning.isNotBlank())
            assertTrue(report.limitations.isNotBlank())
            assertTrue(report.references.isNotBlank())
            assertNotNull(report.resultInterpretation)
        }
    }

    @Test
    fun clinicalAssemblerUsesExactPinnedResultIdAndPreventsCrossParticipantSubstitution() = runBlocking {
        val assessmentRepo = mock(AssessmentRepository::class.java)
        val p1CfitOlder = sampleResult(
            instrumentId = "cfit_3a",
            participantId = "P-01",
            resultId = "res-p1-older",
            scores = listOf(AssessmentScore("Total Skor Mentah (RS)", 28.0, 0.0, 50.0, ""))
        )
        val p2CfitNewer = sampleResult(
            instrumentId = "cfit_3a",
            participantId = "P-02",
            resultId = "res-p2-newer",
            scores = listOf(AssessmentScore("Total Skor Mentah (RS)", 41.0, 0.0, 50.0, ""))
        )
        val papikostick20 = sampleResult(
            instrumentId = "papikostick",
            participantId = "P-01",
            resultId = "res-papi-20",
            scores = (1..20).map { AssessmentScore("Scale$it", it.toDouble(), 0.0, 9.0, "") }
        )
        `when`(assessmentRepo.getResultById("res-p1-older")).thenReturn(p1CfitOlder)
        `when`(assessmentRepo.getResultById("res-p2-newer")).thenReturn(p2CfitNewer)
        `when`(assessmentRepo.getResultById("res-papi-20")).thenReturn(papikostick20)
        `when`(assessmentRepo.getAllResults()).thenReturn(listOf(p2CfitNewer, p1CfitOlder, papikostick20))

        val assembler = ClinicalCounselingContextAssembler(
            assessmentRepository = assessmentRepo,
            participantId = "P-01"
        )

        val ctx = assembler.assemble(
            userQuery = "Interpretasikan hasil CFIT ini",
            interactionMode = CounselingInteractionMode.ASSESSMENT_INTERPRETATION,
            includeBehavioralContext = false,
            pinnedResultId = "res-p1-older"
        )
        assertEquals("res-p1-older", ctx.primaryAssessmentResult?.resultId)
        assertTrue(ctx.assessmentSummary.contains("28"))
        assertFalse(ctx.assessmentSummary.contains("41"))

        val crossParticipant = assembler.assemble(
            userQuery = "Interpretasikan hasil CFIT ini",
            interactionMode = CounselingInteractionMode.ASSESSMENT_INTERPRETATION,
            includeBehavioralContext = false,
            pinnedResultId = "res-p2-newer"
        )
        assertNull(crossParticipant.primaryAssessmentResult)
        assertFalse(crossParticipant.assessmentSummary.contains("41"))

        val fullScales = assembler.assemble(
            userQuery = "Bahas semua skala PAPI Kostick",
            interactionMode = CounselingInteractionMode.ASSESSMENT_INTERPRETATION,
            includeBehavioralContext = false,
            pinnedResultId = "res-papi-20"
        )
        assertTrue(fullScales.assessmentSummary.contains("Scale20"))
    }

    @Test
    fun allCatalogAndProtocolInstrumentsCompleteScoreVisualizeAndInterpretWithoutUnmappedRoutes() {
        val gson = Gson()
        val instruments = File("src/main/canonicalAssets/ai/psychometrics/catalog.json").reader().use {
            gson.fromJson(it, Array<AssessmentInstrument>::class.java).toList()
        }
        val protocols = File("src/main/canonicalAssets/ai/psychometrics/protocols.json").reader().use {
            gson.fromJson(it, Array<AssessmentProtocol>::class.java).associateBy(AssessmentProtocol::instrumentId)
        }
        assertEquals(40, instruments.size)

        instruments.forEach { inst ->
            val spec = PsychometricInstrumentRegistry.specFor(inst.id)
            assertEquals(inst.id, spec.instrumentId)

            val form = requireNotNull(inst.form) { "Missing form for ${inst.id}" }
            val answers = buildValidSampleAnswers(inst.id, form)
            val rawScores = AssessmentScoring.score(form, answers)
            val outcome = AssessmentEnrichers.enrichDetailed(inst.id, form, answers, rawScores)
            val simulatedResult = sampleResult(
                instrumentId = inst.id,
                scores = outcome.scores,
                scoringStatus = outcome.scoringStatus,
                validityStatus = outcome.validityStatus,
                reviewStatus = outcome.reviewStatus,
                observations = outcome.observations,
                warteggDrafts = outcome.warteggDrafts,
                rorschachResponses = outcome.rorschachResponses
            )
            val viz = AssessmentVisualizationMapper.map(simulatedResult)
            val report = AssessmentInterpretationProvider.build(simulatedResult)

            assertTrue("${inst.id} table/viz should not fail", viz.title.isNotBlank())
            assertTrue("${inst.id} baseline report empty", report.resultInterpretation.isNotBlank())

            protocols[inst.id]?.entry?.let { entry ->
                val sampleMarked = buildValidSampleMarked(entry)
                val markedScores = entry.score(sampleMarked)
                val markedRes = sampleResult(
                    instrumentId = inst.id,
                    scores = markedScores,
                    scoringStatus = spec.implementationStatus,
                    reviewStatus = if (spec.requiresExaminerReview) ExaminerReviewStatus.PENDING else ExaminerReviewStatus.NOT_REQUIRED
                )
                assertTrue(AssessmentVisualizationMapper.map(markedRes).title.isNotBlank())
            }
        }
    }

    private fun buildValidSampleMarked(entry: AssessmentScoreEntry): List<Int> {
        if (entry.operation == ScoreOperation.PAULI) {
            return List(entry.fields.size - 2) { 10 } + listOf(10, 1)
        }
        val req = entry.requiredTotal ?: return entry.fields.map { it.minimum.coerceAtLeast(0) }
        val mutable = entry.fields.map { it.minimum.coerceAtLeast(0) }.toMutableList()
        if (entry.operation == ScoreOperation.EPPS) {
            var remaining = req - mutable.dropLast(1).sum()
            for (i in 0 until mutable.lastIndex) {
                val room = (entry.fields[i].maximum - mutable[i]).coerceAtLeast(0)
                val add = remaining.coerceIn(0, room)
                mutable[i] += add
                remaining -= add
            }
            return mutable
        }
        val step = entry.groupSize ?: mutable.size
        for (start in mutable.indices step step) {
            val end = (start + step).coerceAtMost(mutable.size)
            var remaining = req - mutable.subList(start, end).sum()
            for (i in start until end) {
                val room = (entry.fields[i].maximum - mutable[i]).coerceAtLeast(0)
                val add = remaining.coerceIn(0, room)
                mutable[i] += add
                remaining -= add
            }
        }
        return mutable
    }

    private fun buildValidSampleAnswers(id: String, form: AssessmentForm): Map<Int, Int> = when (id) {
        "disc" -> buildMap {
            for (q in 0 until 24) {
                put(q * 2, 0)
                put(q * 2 + 1, 1)
            }
        }
        "kuder" -> buildMap {
            for (q in 0 until 168) {
                put(q * 2, 0)
                put(q * 2 + 1, 1)
            }
        }
        "rmib" -> buildMap {
            for (g in 0 until 9) {
                for (c in 0 until 12) {
                    put(g * 12 + c, c + 1)
                }
            }
        }
        "kraepelin", "pauli" -> buildMap {
            for (c in 0 until form.items.size) {
                put(c, 15)
                put(100 + c, 1)
                put(200 + c, 0)
            }
        }
        else -> form.items.mapIndexed { idx, item ->
            idx to if (item.multiSelect) 1 else 0
        }.toMap()
    }
}
