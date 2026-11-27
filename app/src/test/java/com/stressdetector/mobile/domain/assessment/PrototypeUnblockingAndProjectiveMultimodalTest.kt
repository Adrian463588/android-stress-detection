package com.stressdetector.mobile.domain.assessment

import com.google.gson.Gson
import com.stressdetector.mobile.data.repository.AssessmentRepository
import com.stressdetector.mobile.domain.engine.ClinicalCounselingContextAssembler
import com.stressdetector.mobile.domain.engine.CounselingInteractionMode
import com.stressdetector.mobile.domain.engine.CounselorGenerationRequest
import com.stressdetector.mobile.domain.engine.LlmGenerationState
import com.stressdetector.mobile.domain.engine.LlmReadiness
import com.stressdetector.mobile.domain.engine.OnDeviceLlmEngine
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class PrototypeUnblockingAndProjectiveMultimodalTest {

    @Test
    fun mseaiComputesDeterministicExploratoryScoresChartsAndAllowsAiPlus() {
        val gson = Gson()
        val instruments = File("src/main/canonicalAssets/ai/psychometrics/catalog.json").reader().use {
            gson.fromJson(it, Array<AssessmentInstrument>::class.java).toList()
        }
        val mseai = instruments.first { it.id == "mseai" }
        val form = requireNotNull(mseai.form)
        val answers = form.items.indices.associateWith { 3 }
        val rawScores = AssessmentScoring.score(form, answers)

        val outcome = AssessmentEnrichers.enrichDetailed("mseai", form, answers, rawScores)
        assertTrue("MSEAI should compute exploratory dimension scores", outcome.scores.isNotEmpty())
        assertTrue(outcome.scores.any { it.label.contains("Total", ignoreCase = true) })
        assertEquals(InstrumentImplementationStatus.EXPLORATORY, outcome.scoringStatus)

        val result = AssessmentResult(
            resultId = "res-mseai-proto",
            instrumentId = "mseai",
            title = mseai.title,
            version = form.version,
            sourceTitle = form.sourceTitle,
            sourceUrl = form.sourceUrl,
            sourceLocator = form.sourceLocator,
            createdAtMillis = 1_700_000_000_000L,
            answered = answers.size,
            itemCount = form.items.size,
            scores = outcome.scores,
            interpretation = outcome.interpretation,
            schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
            scoringStatus = outcome.scoringStatus,
            validityStatus = outcome.validityStatus,
            reviewStatus = outcome.reviewStatus,
            aiContextAllowed = true
        )
        assertTrue(result.isAiContextEligible())
        assertFalse(result.copy(aiContextAllowed = false).isAiContextEligible())

        val viz = AssessmentVisualizationMapper.map(result)
        assertTrue("MSEAI with scores should render bar chart, not StatusOnly", viz is AssessmentVisualizationModel.HorizontalBarChartModel)
        val baseline = AssessmentInterpretationProvider.build(result)
        assertFalse(baseline.resultInterpretation.contains("ditangguhkan", ignoreCase = true))
    }

    @Test
    fun catalogIncludesAll39RegistryInstrumentsIncludingHtpDapCbiTbiAndEsj() {
        val gson = Gson()
        val instruments = File("src/main/canonicalAssets/ai/psychometrics/catalog.json").reader().use {
            gson.fromJson(it, Array<AssessmentInstrument>::class.java).toList()
        }
        val ids = instruments.map { it.id }.toSet()
        assertEquals(40, instruments.size)
        assertTrue(ids.containsAll(listOf("htp", "dap", "baum", "cbi_tbi", "esj", "mseai", "vrt")))
    }

    @Test
    fun warteggAndRorschachProvideDeterministicStructuralScoresTelemetryAndAllowAiPlus() {
        val warteggDrafts = listOf(
            WarteggBoxDraft(
                boxNumber = 1,
                strokes = listOf(
                    listOf(
                        WarteggNormalizedPoint(0.2f, 0.2f, pressure = 0.65f, timeMillis = 1_000L, isStylus = true),
                        WarteggNormalizedPoint(0.8f, 0.2f, pressure = 0.75f, timeMillis = 1_500L, isStylus = true)
                    )
                ),
                title = "Matahari",
                drawOrder = 1,
                durationSeconds = 25,
                valence = "+",
                difficulty = "M"
            ),
            WarteggBoxDraft(
                boxNumber = 2,
                strokes = listOf(
                    listOf(
                        WarteggNormalizedPoint(0.3f, 0.3f, pressure = null, timeMillis = 2_000L, isStylus = false),
                        WarteggNormalizedPoint(0.3f, 0.7f, pressure = null, timeMillis = 2_400L, isStylus = false)
                    )
                ),
                title = "Pohon",
                drawOrder = 2,
                durationSeconds = 30
            )
        )
        val warteggOutcome = AssessmentEnrichers.scoreWarteggFromDrafts(warteggDrafts)
        assertTrue("Wartegg should emit structural execution scores", warteggOutcome.scores.isNotEmpty())
        assertTrue(warteggOutcome.observations.any { it.key == "wartegg_telemetry_summary" })

        val touchTelemetry = ProjectiveTelemetryExtractor.extractMetrics(listOf(warteggDrafts[1].strokes))
        assertNull("Missing pressure must stay null, never 0", touchTelemetry.meanPressure)
        assertFalse(touchTelemetry.stylusUsed)
        assertTrue(touchTelemetry.meanSpeedNormPerSec > 0f)
        assertEquals("Vertikal", touchTelemetry.dominantDirection)

        val stylusTelemetry = ProjectiveTelemetryExtractor.extractMetrics(listOf(warteggDrafts[0].strokes))
        assertEquals(0.70f, stylusTelemetry.meanPressure!!, 0.01f)
        assertTrue(stylusTelemetry.stylusUsed)
        assertEquals("Horizontal", stylusTelemetry.dominantDirection)

        val rorschachOutcome = AssessmentEnrichers.scoreRorschachFromDrafts(
            responses = listOf(
                RorschachResponseDraft(1, 1, "Kelelawar", "Sayap lebar", location = "W", determinant = "F", formQuality = "FQo", popular = true),
                RorschachResponseDraft(2, 1, "Dua beruang", "Bergerak bersama", location = "D", determinant = "FM", formQuality = "FQo", popular = false)
            ),
            systemId = "EXNER_CS"
        )
        assertTrue("Rorschach should emit formal structural count scores", rorschachOutcome.scores.any { it.label.contains("Total Respons") })
        assertTrue(rorschachOutcome.scores.any { it.label.contains("W (Whole)") })
    }

    @Test
    fun clinicalAssemblerAllowsPinnedExploratoryAndPendingResultsWhilePreservingPrivacyAndIsolation() = runBlocking {
        val repo = mock(AssessmentRepository::class.java)
        val pendingWartegg = AssessmentResult(
            resultId = "res-wartegg-pending",
            instrumentId = "wartegg",
            title = "Wartegg",
            version = "Wartegg-8-Kotak",
            sourceTitle = "PsikoTest",
            sourceUrl = "",
            sourceLocator = "Wartegg §4",
            createdAtMillis = 2_000L,
            answered = 8,
            itemCount = 8,
            scores = listOf(AssessmentScore("Kotak Selesai", 8.0, 0.0, 8.0, "Kelengkapan")),
            interpretation = "Observasi 8 kotak tercatat.",
            participantId = "participant-local-01",
            schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
            scoringStatus = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
            validityStatus = AssessmentValidityStatus.VALID,
            reviewStatus = ExaminerReviewStatus.PENDING,
            aiContextAllowed = true
        )
        val unconsented = pendingWartegg.copy(resultId = "res-no-consent", aiContextAllowed = false)
        `when`(repo.getAllResults()).thenReturn(listOf(pendingWartegg, unconsented))
        `when`(repo.getResultById("res-wartegg-pending")).thenReturn(pendingWartegg)
        `when`(repo.getResultById("res-no-consent")).thenReturn(unconsented)

        val assembler = ClinicalCounselingContextAssembler(
            assessmentRepository = repo,
            participantId = "participant-local-01"
        )
        val allowed = assembler.assemble(
            userQuery = "Interpretasikan Wartegg ini",
            interactionMode = CounselingInteractionMode.ASSESSMENT_INTERPRETATION,
            includeBehavioralContext = false,
            pinnedResultId = "res-wartegg-pending"
        )
        assertNotNull(allowed.primaryAssessmentResult)
        assertTrue(allowed.assessmentSummary.contains("wartegg", ignoreCase = true))

        val blockedConsent = assembler.assemble(
            userQuery = "Interpretasikan Wartegg tanpa izin",
            interactionMode = CounselingInteractionMode.ASSESSMENT_INTERPRETATION,
            includeBehavioralContext = false,
            pinnedResultId = "res-no-consent"
        )
        assertNull(blockedConsent.primaryAssessmentResult)
    }

    @Test
    fun projectiveMultimodalAnalyzerUsesVisionWhenAvailableAndFallsBackCleanlyWithoutFaking() = runBlocking {
        val unavailableEngine = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.ModelMissing
            override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> = emptyFlow()
        }
        val samplePanels = listOf(
            ProjectivePanelDraft(
                panelIndex = 0,
                panelLabel = "Rumah",
                strokes = listOf(
                    listOf(
                        WarteggNormalizedPoint(0.1f, 0.8f, 0.5f, 100L, true),
                        WarteggNormalizedPoint(0.5f, 0.2f, 0.6f, 400L, true),
                        WarteggNormalizedPoint(0.9f, 0.8f, 0.5f, 700L, true)
                    )
                ),
                durationSeconds = 18,
                drawOrder = 1,
                title = "Rumah sederhana"
            )
        )
        val fallbackReport = ProjectiveMultimodalAnalyzer(unavailableEngine).analyze(
            instrumentId = "htp",
            instrumentTitle = "House-Tree-Person (HTP)",
            panels = samplePanels,
            verbalNotes = listOf("Rumah tampak kokoh"),
            renderedImagesPng = listOf(byteArrayOf(1, 2, 3))
        )
        assertFalse(fallbackReport.multimodalModelUsed)
        assertTrue(fallbackReport.summary.contains("ekstraksi fitur", ignoreCase = true))
        assertTrue(fallbackReport.summary.contains("stylus", ignoreCase = true))
    }

    @Test
    fun adversityQuotientDigitalScoringMatchesExcelTypologyMatrixAndResolvesTiesAsCamper() {
        val gson = Gson()
        val instruments = File("src/main/canonicalAssets/ai/psychometrics/catalog.json").reader().use {
            gson.fromJson(it, Array<AssessmentInstrument>::class.java).toList()
        }
        val aq = instruments.first { it.id == "adversity_quotient" }
        val aqForm = requireNotNull(aq.form)
        // Q48 check: also verify WPT item 48 has non-blank choices matching index 2 = 175
        val wpt = instruments.first { it.id == "wpt" }
        val wptForm = requireNotNull(wpt.form)
        val wpt48Choices = requireNotNull(wptForm.items[47].choices)
        assertTrue(wpt48Choices.all { it.isNotBlank() })
        assertTrue(wpt48Choices[2].contains("175"))

        // Choose pure Climber option on every AQ item per Adversity Quotient.xlsx Interpretasi sheet:
        val pureClimberAnswers = mapOf(
            0 to 2, 1 to 1, 2 to 0, 3 to 2, 4 to 2,
            5 to 0, 6 to 1, 7 to 2, 8 to 0, 9 to 0,
            10 to 2, 11 to 0, 12 to 2, 13 to 2, 14 to 1
        )
        val climberOutcome = AssessmentEnrichers.enrichDetailed(
            id = "adversity_quotient",
            form = aqForm,
            answers = pureClimberAnswers,
            rawScores = AssessmentScoring.score(aqForm, pureClimberAnswers)
        )
        assertEquals(15.0, climberOutcome.scores.first { it.label == "Climber (Cl)" }.value, 0.0)
        assertEquals(0.0, climberOutcome.scores.first { it.label == "Camper (Ca)" }.value, 0.0)
        assertEquals(0.0, climberOutcome.scores.first { it.label == "Quitter (Q)" }.value, 0.0)
        assertTrue(climberOutcome.interpretation.contains("CLIMBER"))

        // Tie (7 Quitter, 1 Camper, 7 Climber) must resolve to CAMPER per Excel Kode sheet
        val tieAnswers = mapOf(
            0 to 0, 1 to 0, 2 to 2, 3 to 1, 4 to 1, 5 to 1, 6 to 2, // 7 Q
            7 to 1, // 1 Ca
            8 to 0, 9 to 0, 10 to 2, 11 to 0, 12 to 2, 13 to 2, 14 to 1 // 7 Cl
        )
        val tieOutcome = AssessmentEnrichers.enrichDetailed(
            id = "adversity_quotient",
            form = aqForm,
            answers = tieAnswers,
            rawScores = AssessmentScoring.score(aqForm, tieAnswers)
        )
        assertEquals(7.0, tieOutcome.scores.first { it.label == "Climber (Cl)" }.value, 0.0)
        assertEquals(1.0, tieOutcome.scores.first { it.label == "Camper (Ca)" }.value, 0.0)
        assertEquals(7.0, tieOutcome.scores.first { it.label == "Quitter (Q)" }.value, 0.0)
        assertTrue(tieOutcome.interpretation.contains("CAMPER"))
    }

    @Test
    fun projectiveTelemetryExtractorComputesKnownKinematicsPausesRoughnessAndPreservesNulls() {
        // Synthetic straight constant-velocity finger stroke: speed = 0.2/sec, zero pressure/tilt
        val smoothFinger = listOf(
            listOf(
                WarteggNormalizedPoint(0.1f, 0.5f, pressure = null, timeMillis = 1000L, isStylus = false),
                WarteggNormalizedPoint(0.3f, 0.5f, pressure = null, timeMillis = 2000L, isStylus = false),
                WarteggNormalizedPoint(0.5f, 0.5f, pressure = null, timeMillis = 3000L, isStylus = false)
            )
        )
        val smoothMetrics = ProjectiveTelemetryExtractor.extractMetrics(listOf(smoothFinger), revisionCount = 0)
        assertFalse(smoothMetrics.stylusUsed)
        assertNull(smoothMetrics.meanPressure)
        assertNull(smoothMetrics.meanTilt)
        assertEquals(0.4f, smoothMetrics.totalPathLength, 0.01f)
        assertEquals(0.2f, smoothMetrics.meanSpeedNormPerSec, 0.02f)
        assertEquals(0, smoothMetrics.directionChangeCount)
        assertEquals(0, smoothMetrics.pauseCount)
        assertTrue(smoothMetrics.pressureSeries.isEmpty())
        assertTrue(smoothMetrics.speedSeries.size >= 2)

        // Erratic stylus stroke with duplicate timestamps, sharp turns, pause >= 250ms, and varying pressure
        val erraticStylus = listOf(
            listOf(
                WarteggNormalizedPoint(0.1f, 0.1f, pressure = 0.2f, timeMillis = 1000L, isStylus = true, tilt = 0.3f, orientation = 0.5f),
                WarteggNormalizedPoint(0.1f, 0.1f, pressure = 0.2f, timeMillis = 1000L, isStylus = true, tilt = 0.3f), // duplicate
                WarteggNormalizedPoint(0.4f, 0.1f, pressure = 0.8f, timeMillis = 1100L, isStylus = true, tilt = 0.3f),
                WarteggNormalizedPoint(0.2f, 0.5f, pressure = 0.3f, timeMillis = 1300L, isStylus = true, tilt = 0.4f),
                WarteggNormalizedPoint(0.6f, 0.2f, pressure = 0.9f, timeMillis = 1400L, isStylus = true, tilt = 0.4f)
            ),
            listOf(
                WarteggNormalizedPoint(0.7f, 0.7f, pressure = 0.5f, timeMillis = 2000L, isStylus = true, tilt = 0.4f), // 600ms pause
                WarteggNormalizedPoint(0.8f, 0.9f, pressure = 0.6f, timeMillis = 2200L, isStylus = true, tilt = 0.4f)
            )
        )
        val erraticMetrics = ProjectiveTelemetryExtractor.extractMetrics(listOf(erraticStylus), revisionCount = 2)
        assertTrue(erraticMetrics.stylusUsed)
        assertNotNull(erraticMetrics.meanPressure)
        assertNotNull(erraticMetrics.meanJerk)
        assertTrue(erraticMetrics.meanJerk > 0f)
        assertEquals(1, erraticMetrics.pauseCount)
        assertEquals(600L, erraticMetrics.totalPauseMs)
        assertEquals(2, erraticMetrics.revisionCount)
        assertTrue(erraticMetrics.directionChangeCount >= 2)
        assertTrue(erraticMetrics.roughnessIndex > smoothMetrics.roughnessIndex)
        assertTrue(erraticMetrics.pressureSeries.size >= 4)
        assertTrue(erraticMetrics.speedSeries.size >= 3)
    }

    @Test
    fun baumInstrumentScoringVisualizationAndStructuredMultimodalWorkEndToEnd() = runBlocking {
        val gson = Gson()
        val instruments = File("src/main/canonicalAssets/ai/psychometrics/catalog.json").reader().use {
            gson.fromJson(it, Array<AssessmentInstrument>::class.java).toList()
        }
        val baum = instruments.firstOrNull { it.id == "baum" }
        assertNotNull(baum)
        assertEquals(AssessmentVisualizationType.OBSERVATION_MATRIX, PsychometricInstrumentRegistry.specFor("baum").visualizationType)

        var capturedPrompt = ""
        val visionEngine = object : OnDeviceLlmEngine {
            override fun readiness(): LlmReadiness = LlmReadiness.Ready("gemma-4-E2B-it")
            override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> = emptyFlow()
            override suspend fun analyzeMultimodal(prompt: String, imagesPng: List<ByteArray>): Result<String> {
                capturedPrompt = prompt
                return Result.success("1. Observasi Visual\nPohon berakar kuat.\n6. Referensi Materi yang Digunakan\nWartegg & BAUM")
            }
        }
        val panels = listOf(
            ProjectivePanelDraft(
                panelIndex = 0,
                panelLabel = "Pohon",
                strokes = listOf(
                    listOf(
                        WarteggNormalizedPoint(0.45f, 0.85f, 0.4f, 1000L, true),
                        WarteggNormalizedPoint(0.50f, 0.45f, 0.7f, 1500L, true),
                        WarteggNormalizedPoint(0.55f, 0.25f, 0.6f, 2000L, true)
                    )
                ),
                title = "Pohon mangga",
                drawOrder = 1,
                durationSeconds = 25,
                revisionCount = 1
            )
        )
        val report = ProjectiveMultimodalAnalyzer(visionEngine).analyze(
            instrumentId = "baum",
            instrumentTitle = baum!!.title,
            panels = panels,
            verbalNotes = listOf("Pohon berbuah lebat"),
            renderedImagesPng = listOf(byteArrayOf(1, 2, 3))
        )
        assertTrue(report.multimodalModelUsed)
        assertTrue(capturedPrompt.contains("1. Observasi Visual"))
        assertTrue(capturedPrompt.contains("5. Pertanyaan Eksplorasi Lanjutan Psikolog"))

        val outcome = AssessmentEnrichers.scoreProjectivePanels("baum", baum.title, panels, report.summary)
        val result = AssessmentResult(
            resultId = "res-baum-1",
            instrumentId = "baum",
            title = baum.title,
            version = "Baum-1-Panel-Purwarupa",
            sourceTitle = "Bank PsikoTest",
            sourceUrl = "asset://ai/psychometrics/baum",
            sourceLocator = "PsikoTest",
            createdAtMillis = 1_700_000_000_000L,
            answered = 1,
            itemCount = 1,
            scores = outcome.scores,
            interpretation = outcome.interpretation,
            schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
            scoringStatus = outcome.scoringStatus,
            validityStatus = outcome.validityStatus,
            reviewStatus = outcome.reviewStatus,
            observations = outcome.observations,
            projectivePanels = panels,
            multimodalSummary = outcome.multimodalSummary
        )
        val viz = AssessmentVisualizationMapper.map(result) as AssessmentVisualizationModel.ObservationMatrixModel
        assertNotNull(viz.telemetry)
        assertEquals(1, viz.telemetry?.revisionCount)
        assertTrue(viz.telemetry!!.speedSeries.isNotEmpty())
        assertTrue(viz.telemetry!!.pressureSeries.isNotEmpty())
    }
}
