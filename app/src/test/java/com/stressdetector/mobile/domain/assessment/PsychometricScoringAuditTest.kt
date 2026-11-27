package com.stressdetector.mobile.domain.assessment

import com.google.gson.Gson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PsychometricScoringAuditTest {
    private val catalog = AssessmentCatalog(
        File("src/main/canonicalAssets/ai/psychometrics/catalog.json").reader().use {
            Gson().fromJson(it, Array<AssessmentInstrument>::class.java).toList()
        }
    )

    @Test
    fun registryCoversAllCatalogAndDeferredInstrumentsWithAuditSpecs() {
        val catalogIds = catalog.instruments.map { it.id }.toSet()
        val extraAuditIds = setOf("esj", "cbi_tbi", "htp", "dap")
        val allIds = PsychometricInstrumentRegistry.allSpecs().map { it.instrumentId }.toSet()
        assertTrue((catalogIds + extraAuditIds).all { it in allIds })
        assertEquals(setOf("bdi", "srq-29", "cbi", "big_five", "disc", "holland", "mseai", "vrt", "esj", "cbi_tbi"), PsychometricInstrumentRegistry.eligibleAiScreeningIds())
        assertEquals(
            InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
            PsychometricInstrumentRegistry.specFor("mmpi").implementationStatus
        )
        assertEquals(
            InstrumentImplementationStatus.EXPLORATORY,
            PsychometricInstrumentRegistry.specFor("mseai").implementationStatus
        )
    }

    @Test
    fun mmpiCompletenessGateDuplicate201FixAndAiReviewGateWork() {
        val form = requireNotNull(catalog.find("mmpi")?.form)
        // Item 201 answered 1 must give K-r = 0 (not 1), and 0 must give K-r = 1
        val item201False = mapOf(201 to 0)
        val item201True = mapOf(201 to 1)
        val incompleteFalse = AssessmentEnrichers.enrichDetailed("mmpi", form, item201False, emptyList())
        val incompleteTrue = AssessmentEnrichers.enrichDetailed("mmpi", form, item201True, emptyList())
        assertEquals(1.0, incompleteFalse.scores.first { it.label.startsWith("K-r") }.value, 0.0)
        assertEquals(0.0, incompleteTrue.scores.first { it.label.startsWith("K-r") }.value, 0.0)
        assertEquals(AssessmentValidityStatus.INVALID, incompleteFalse.validityStatus)

        // Complete protocol (>= 304/338) with uniform 0 endorsement triggers elevated L-r validity warning (QUESTIONABLE)
        // AND PENDING examiner review -> blocks AI until reviewed
        val complete = (0 until 310).associateWith { 0 }
        val completeOutcome = AssessmentEnrichers.enrichDetailed("mmpi", form, complete, emptyList())
        assertEquals(AssessmentValidityStatus.QUESTIONABLE, completeOutcome.validityStatus)
        assertEquals(ExaminerReviewStatus.PENDING, completeOutcome.reviewStatus)

        val unreviewedResult = AssessmentResult(
            resultId = "m1",
            instrumentId = "mmpi",
            title = form.title,
            version = form.version,
            sourceTitle = form.sourceTitle,
            sourceUrl = form.sourceUrl,
            sourceLocator = form.sourceLocator,
            createdAtMillis = 100L,
            answered = 310,
            itemCount = 338,
            scores = completeOutcome.scores,
            interpretation = completeOutcome.interpretation,
            schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
            scoringStatus = completeOutcome.scoringStatus,
            validityStatus = completeOutcome.validityStatus,
            reviewStatus = completeOutcome.reviewStatus
        )
        assertTrue(unreviewedResult.isAiContextEligible())
        assertFalse(unreviewedResult.copy(aiContextAllowed = false).isAiContextEligible())
        assertNotNull(unreviewedResult.copy(aiContextAllowed = false).aiIneligibilityReason())

        val approvedResult = unreviewedResult.copy(
            validityStatus = AssessmentValidityStatus.VALID,
            reviewStatus = ExaminerReviewStatus.APPROVED
        )
        assertTrue(approvedResult.isAiContextEligible())
        assertNull(approvedResult.aiIneligibilityReason())
    }

    @Test
    fun warteggDraftScoringPreservesRealStrokesAndAvoidsFakeScores() {
        val drafts = (1..8).map { box ->
            WarteggBoxDraft(
                boxNumber = box,
                strokes = listOf(listOf(WarteggNormalizedPoint(0.2f, 0.2f, 0.6f), WarteggNormalizedPoint(0.8f, 0.8f, 0.7f))),
                title = "Gambar $box",
                drawOrder = 9 - box,
                durationSeconds = box * 5,
                valence = if (box % 2 == 0) "+" else "-",
                difficulty = if (box <= 4) "M" else "S"
            )
        }
        val outcome = AssessmentEnrichers.scoreWarteggFromDrafts(drafts)
        assertTrue(outcome.scores.any { it.label == "Kotak Diselesaikan" && it.value == 8.0 })
        assertFalse(outcome.scores.any { it.label.contains("Emosi", ignoreCase = true) })
        assertEquals(InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED, outcome.scoringStatus)
        assertEquals(AssessmentValidityStatus.VALID, outcome.validityStatus)
        assertEquals(ExaminerReviewStatus.PENDING, outcome.reviewStatus)
        assertEquals(8, outcome.warteggDrafts.size)
        assertTrue(outcome.observations.first { it.key == "wartegg_sequence" }.value.startsWith("K8 -> K7"))
    }

    @Test
    fun rorschachMultiResponseEnforcesMinimumValidityR14() {
        val lowResponses = (1..10).map { card ->
            RorschachResponseDraft(cardNumber = card, responseIndex = 1, freeAssociation = "Kupu-kupu", location = "W")
        }
        val invalidOutcome = AssessmentEnrichers.scoreRorschachFromDrafts(lowResponses)
        assertTrue(invalidOutcome.scores.any { it.label.contains("Total Respons") && it.value == 10.0 })
        assertEquals(AssessmentValidityStatus.INVALID, invalidOutcome.validityStatus)
        assertTrue(invalidOutcome.validityWarnings.any { it.contains("R >= 14") })

        val sufficientResponses = (1..10).flatMap { card ->
            listOf(
                RorschachResponseDraft(cardNumber = card, responseIndex = 1, freeAssociation = "A", location = "W"),
                RorschachResponseDraft(cardNumber = card, responseIndex = 2, freeAssociation = "B", location = "D")
            )
        }
        val pendingOutcome = AssessmentEnrichers.scoreRorschachFromDrafts(sufficientResponses, "EXNER_CS")
        assertEquals(AssessmentValidityStatus.QUESTIONABLE, pendingOutcome.validityStatus)
        assertTrue(pendingOutcome.observations.any { it.key == "rorschach_r" && it.value == "20" })

        val reviewedResult = AssessmentResult(
            resultId = "r1",
            instrumentId = "rorschach",
            title = "Tes Rorschach",
            version = "Rorschach-10-Kartu",
            sourceTitle = "PsikoTest",
            sourceUrl = "",
            sourceLocator = "",
            createdAtMillis = 100L,
            answered = 10,
            itemCount = 10,
            scores = emptyList(),
            interpretation = pendingOutcome.interpretation,
            schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
            scoringStatus = pendingOutcome.scoringStatus,
            validityStatus = AssessmentValidityStatus.VALID,
            reviewStatus = ExaminerReviewStatus.APPROVED,
            rorschachResponses = sufficientResponses
        )
        assertTrue(reviewedResult.isAiContextEligible())
    }

    @Test
    fun pauliPartitionsErrorAndSkipKeysOutOfIntervals() {
        val form = requireNotNull(catalog.find("pauli")?.form)
        val answers = mutableMapOf<Int, Int>()
        for (i in 0 until 20) {
            answers[i] = 30 + i
            answers[100 + i] = 1
            answers[200 + i] = 2
        }
        val outcome = AssessmentEnrichers.enrichDetailed("pauli", form, answers, emptyList())
        assertEquals(20, outcome.observations.size)
        assertEquals(790.0, outcome.scores.first { it.label == "Total Penjumlahan" }.value, 0.001)
        assertEquals(60.0, outcome.scores.first { it.label == "Total Salah & Lewatan" }.value, 0.001)
    }

    @Test
    fun rmibMsdtMsaiEppsDoNotImputeMissingValues() {
        // RMIB partial -> INVALID and does not fill p + 1
        val rmibForm = requireNotNull(catalog.find("rmib")?.form)
        val partialRmib = (0 until 12).associateWith { it + 1 } // group 0 only
        val rmibOutcome = AssessmentEnrichers.enrichDetailed("rmib", rmibForm, partialRmib, emptyList())
        assertEquals(AssessmentValidityStatus.INVALID, rmibOutcome.validityStatus)
        assertEquals(78.0, rmibOutcome.scores.first { it.label == "Total Skor RMIB" }.value, 0.001)

        // MSDT < 64 -> INVALID
        val msdtForm = requireNotNull(catalog.find("msdt")?.form)
        val msdtOutcome = AssessmentEnrichers.enrichDetailed("msdt", msdtForm, (0 until 40).associateWith { 0 }, emptyList())
        assertEquals(AssessmentValidityStatus.INVALID, msdtOutcome.validityStatus)
        assertTrue(msdtOutcome.validityWarnings.any { it.contains("40/64") })

        // MSAI partial -> omits unanswered competencies instead of defaulting to 3.0
        val msaiForm = requireNotNull(catalog.find("msai")?.form)
        val msaiPartial = listOf(4, 19, 23, 24, 46).associateWith { 4 } // 5 items in 1 competency only (Clan)
        val msaiOutcome = AssessmentEnrichers.enrichDetailed("msai", msaiForm, msaiPartial, emptyList())
        assertEquals(AssessmentValidityStatus.QUESTIONABLE, msaiOutcome.validityStatus)
        assertEquals(2, msaiOutcome.scores.size) // Kuadran Clan + 1 competency only

        // EPPS < 210 -> INVALID and sum < 210
        val eppsForm = requireNotNull(catalog.find("epps")?.form)
        val eppsOutcome = AssessmentEnrichers.enrichDetailed("epps", eppsForm, (0 until 50).associateWith { 0 }, emptyList())
        assertEquals(AssessmentValidityStatus.INVALID, eppsOutcome.validityStatus)
        assertTrue(eppsOutcome.scores.first { it.label.contains("Total Raw Score") }.value < 210.0)
    }

    @Test
    fun srq29ThresholdSixAndItem17WarningAndLegacyResultBlocking() {
        val srqForm = requireNotNull(catalog.find("srq-29")?.form)
        val gmeSixAndItem17 = mapOf(0 to 1, 1 to 1, 2 to 1, 3 to 1, 4 to 1, 16 to 1)
        val srqOutcome = AssessmentEnrichers.enrichDetailed("srq-29", srqForm, gmeSixAndItem17, emptyList())
        assertTrue(srqOutcome.scores.first { it.label == "Gangguan Mental Emosional" }.meaning.contains("GME >= 6"))
        assertTrue(srqOutcome.validityWarnings.any { it.contains("Butir 17") })

        val legacy = AssessmentResult(
            resultId = "old",
            instrumentId = "bdi",
            title = "BDI-II",
            version = "1",
            sourceTitle = "old",
            sourceUrl = "",
            sourceLocator = "",
            createdAtMillis = 1L,
            answered = 21,
            itemCount = 21,
            scores = emptyList(),
            interpretation = "legacy",
            schemaVersion = null
        )
        assertTrue(legacy.isLegacyUnverified())
        assertFalse(legacy.isAiContextEligible())
    }
}
