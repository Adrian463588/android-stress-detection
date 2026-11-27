package com.stressdetector.mobile.domain.assessment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessmentVisualizationMapperTest {

    private fun baseResult(
        instrumentId: String,
        title: String,
        scores: List<AssessmentScore>,
        fromMarkedSheet: Boolean = false,
        scoringStatus: InstrumentImplementationStatus = InstrumentImplementationStatus.RAW_SCORE_ONLY,
        validityStatus: AssessmentValidityStatus = AssessmentValidityStatus.VALID,
        reviewStatus: ExaminerReviewStatus = ExaminerReviewStatus.NOT_REQUIRED,
        observations: List<AssessmentObservation> = emptyList(),
        warteggDrafts: List<WarteggBoxDraft> = emptyList(),
        rorschachResponses: List<RorschachResponseDraft> = emptyList(),
        schemaVersion: String? = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION
    ) = AssessmentResult(
        resultId = "id-$instrumentId",
        instrumentId = instrumentId,
        title = title,
        version = "v1",
        sourceTitle = "PsikoTest",
        sourceUrl = "",
        sourceLocator = "§1",
        createdAtMillis = 100L,
        answered = 10,
        itemCount = 10,
        scores = scores,
        interpretation = "Ringkasan",
        fromMarkedSheet = fromMarkedSheet,
        schemaVersion = schemaVersion,
        scoringStatus = scoringStatus,
        validityStatus = validityStatus,
        reviewStatus = reviewStatus,
        observations = observations,
        warteggDrafts = warteggDrafts,
        rorschachResponses = rorschachResponses
    )

    @Test
    fun mapsDiscToThreeDistinctProfileLineSeries() {
        val scores = listOf(
            AssessmentScore("Profil A (Most) Dominance", 5.0, 0.0, 24.0, ""),
            AssessmentScore("Profil A (Most) Influence", 7.0, 0.0, 24.0, ""),
            AssessmentScore("Profil A (Most) Steadiness", 18.0, 0.0, 24.0, ""),
            AssessmentScore("Profil A (Most) Compliance", 10.0, 0.0, 24.0, ""),
            AssessmentScore("Profil B (Least) Dominance", 9.0, 0.0, 24.0, ""),
            AssessmentScore("Profil B (Least) Influence", 12.0, 0.0, 24.0, ""),
            AssessmentScore("Profil B (Least) Steadiness", 2.0, 0.0, 24.0, ""),
            AssessmentScore("Profil B (Least) Compliance", 8.0, 0.0, 24.0, ""),
            AssessmentScore("Profil C (Selisih Most-Least) D", -4.0, -24.0, 24.0, ""),
            AssessmentScore("Profil C (Selisih Most-Least) I", -5.0, -24.0, 24.0, ""),
            AssessmentScore("Profil C (Selisih Most-Least) S", 16.0, -24.0, 24.0, ""),
            AssessmentScore("Profil C (Selisih Most-Least) C", 2.0, -24.0, 24.0, "")
        )
        val model = AssessmentVisualizationMapper.map(baseResult("disc", "DISC", scores))
        assertTrue(model is AssessmentVisualizationModel.DiscProfileLineModel)
        val disc = model as AssessmentVisualizationModel.DiscProfileLineModel
        assertEquals(3, disc.series.size)
        assertEquals(listOf(ChartLineStyle.SOLID, ChartLineStyle.DASHED, ChartLineStyle.DOTTED), disc.series.map { it.lineStyle })
        assertTrue(disc.primarySummary.contains("S — Steadiness"))
    }

    @Test
    fun distinguishesClassicMmpiMarkedSheetFromMmpi2RfRawScores() {
        val rfResult = baseResult(
            instrumentId = "mmpi",
            title = "MMPI",
            scores = listOf(
                AssessmentScore("F-r (Infrequent)", 5.0, 0.0, 32.0, ""),
                AssessmentScore("SUI (Ide Bunuh Diri)", 1.0, 0.0, 5.0, ""),
                AssessmentScore("H-O EID (Disfungsi Emosi)", 10.0, 0.0, 22.0, "")
            ),
            scoringStatus = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
            validityStatus = AssessmentValidityStatus.PENDING,
            reviewStatus = ExaminerReviewStatus.PENDING
        )
        val rfModel = AssessmentVisualizationMapper.map(rfResult) as AssessmentVisualizationModel.MmpiClinicalProfileModel
        assertEquals(null, rfModel.referenceLineValue)
        assertTrue(rfModel.editionLabel.contains("MMPI-2-RF"))
        assertFalse(rfModel.validityVerified)

        val classicResult = baseResult(
            instrumentId = "mmpi",
            title = "MMPI",
            fromMarkedSheet = true,
            scores = listOf(
                AssessmentScore("Ringkasan Profil MMPI-2 Klasik", 68.0, 30.0, 120.0, ""),
                AssessmentScore("L (Lie)", 50.0, 30.0, 120.0, ""),
                AssessmentScore("F (Infrequency)", 55.0, 30.0, 120.0, ""),
                AssessmentScore("K (Correction)", 52.0, 30.0, 120.0, ""),
                AssessmentScore("D (Depression)", 72.0, 30.0, 120.0, "")
            ),
            scoringStatus = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
            validityStatus = AssessmentValidityStatus.VALID,
            reviewStatus = ExaminerReviewStatus.APPROVED
        )
        val classicModel = AssessmentVisualizationMapper.map(classicResult) as AssessmentVisualizationModel.MmpiClinicalProfileModel
        assertEquals(65.0, classicModel.referenceLineValue)
        assertTrue(classicModel.validityVerified)
        assertTrue(classicModel.clinicalScales.single().elevated)
    }

    @Test
    fun mapsPauliTimeSeriesRmibRankedAndBdiBands() {
        val pauliObs = (1..5).map {
            AssessmentObservation("pauli_interval_$it", "Interval $it", "${20 + it}", orderIndex = it, tags = listOf("err=1", "skip=0"))
        }
        val pauliModel = AssessmentVisualizationMapper.map(
            baseResult(
                "pauli",
                "Pauli",
                listOf(AssessmentScore("Total Penjumlahan", 115.0, 0.0, 2500.0, "")),
                observations = pauliObs
            )
        ) as AssessmentVisualizationModel.TimeSeriesCurveModel
        assertEquals(5, pauliModel.points.size)
        assertEquals(5, pauliModel.totalErrors)

        val rmibModel = AssessmentVisualizationMapper.map(
            baseResult(
                "rmib",
                "RMIB",
                listOf(
                    AssessmentScore("Medical [Rank 1]", 18.0, 9.0, 108.0, ""),
                    AssessmentScore("Clerical [Rank 2]", 80.0, 9.0, 108.0, "")
                )
            )
        ) as AssessmentVisualizationModel.RankedBarChartModel
        assertTrue(rmibModel.lowerIsHigherInterest)
        assertEquals(18.0, rmibModel.bars.first().value, 0.001)

        val bdiModel = AssessmentVisualizationMapper.map(
            baseResult(
                "bdi",
                "BDI-II",
                listOf(AssessmentScore("Total Skor BDI-II", 15.0, 0.0, 63.0, "Ringan")),
                scoringStatus = InstrumentImplementationStatus.VERIFIED
            )
        ) as AssessmentVisualizationModel.ScoreIndicatorModel
        assertEquals(4, bdiModel.bands.size)
    }

    @Test
    fun mapsWarteggRorschachAndUnresolvedMseaiProperly() {
        val wartegg = AssessmentVisualizationMapper.map(
            baseResult(
                "wartegg",
                "Wartegg",
                emptyList(),
                scoringStatus = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
                reviewStatus = ExaminerReviewStatus.PENDING,
                warteggDrafts = listOf(WarteggBoxDraft(boxNumber = 1, drawOrder = 1, durationSeconds = 12, title = "Bintang"))
            )
        )
        assertTrue(wartegg is AssessmentVisualizationModel.WarteggEightBoxModel)
        assertEquals(12, (wartegg as AssessmentVisualizationModel.WarteggEightBoxModel).totalDurationSeconds)

        val rorschach = AssessmentVisualizationMapper.map(
            baseResult(
                "rorschach",
                "Rorschach",
                emptyList(),
                scoringStatus = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
                validityStatus = AssessmentValidityStatus.INVALID,
                reviewStatus = ExaminerReviewStatus.PENDING,
                rorschachResponses = listOf(RorschachResponseDraft(1, 1, "Kupu-kupu", codingSystem = "EXNER_CS"))
            )
        )
        assertTrue(rorschach is AssessmentVisualizationModel.ExaminerCodedProfileModel)

        val mseai = AssessmentVisualizationMapper.map(
            baseResult(
                "mseai",
                "MSEAI",
                emptyList(),
                scoringStatus = InstrumentImplementationStatus.UNRESOLVED_IDENTITY,
                validityStatus = AssessmentValidityStatus.INVALID
            )
        )
        assertTrue(mseai is AssessmentVisualizationModel.StatusOnlyModel)
    }
}
