package com.stressdetector.mobile.domain.assessment

import com.google.gson.Gson
import com.stressdetector.mobile.domain.engine.ConservativeGemmaTokenCounter
import com.stressdetector.mobile.domain.engine.CounselorGenerationRequest
import com.stressdetector.mobile.domain.engine.CounselorPromptBuilder
import com.stressdetector.mobile.domain.engine.LlmGenerationState
import com.stressdetector.mobile.domain.engine.LlmReadiness
import com.stressdetector.mobile.domain.engine.OnDeviceLlmEngine
import com.stressdetector.mobile.domain.engine.PromptAsset
import com.stressdetector.mobile.domain.engine.PromptAssetProvider
import com.stressdetector.mobile.domain.engine.PromptBudget
import com.stressdetector.mobile.domain.engine.RagMode
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PsychometricAssessmentTest {
    private val protocols = File("src/main/canonicalAssets/ai/psychometrics/protocols.json").reader().use {
        Gson().fromJson(it, Array<AssessmentProtocol>::class.java).associateBy(AssessmentProtocol::instrumentId)
    }
    private val catalog = AssessmentCatalog(
        File("src/main/canonicalAssets/ai/psychometrics/catalog.json").reader().use {
            Gson().fromJson(it, Array<AssessmentInstrument>::class.java).toList()
        }
    )

    @Test
    fun routesGoalsWithoutAssigningAnIqOrTurningEveryComplaintIntoAnAssessment() {
        assertEquals(listOf("spm", "cfit_3a", "ist"), catalog.recommend("Saya ingin tahu IQ saya").map { it.id })
        assertTrue(catalog.recommend("tes IQ").any { it.id == "spm" && it.form != null })
        assertEquals(listOf("big_five", "disc"), catalog.recommend("Saya ingin mengetahui kepribadian saya").map { it.id })
        assertEquals(listOf("disc", "big_five"), catalog.recommend("DISC").map { it.id })
        assertEquals("holland", catalog.recommend("tes minat karier").first().id)
        assertEquals("cbi", catalog.recommend("Saya lelah terus karena pekerjaan").first().id)
        assertTrue(catalog.recommend("Saya tidak mau tes IQ").isEmpty())
        assertTrue(catalog.recommend("Saya cemas tentang presentasi besok").isEmpty())
    }

    @Test
    fun ipipUsesAllFiftyItemsAndPublishedReverseKeys() {
        val form = requireNotNull(catalog.find("big_five")?.form)
        val guideFile = File("../../PsikoTest/Big_Five/IPIP-BFM-50.doc.md")
        if (!guideFile.isFile) return // Verified in monorepo environment
        val guide = guideFile.readLines()
        val sourceItems = guide.mapNotNull { line -> IPIP_ITEM.matchEntire(line)?.groupValues?.get(1)?.trim() }
        assertEquals(sourceItems.map { it.replace("belakang layer", "belakang layar") }, form.items.map { it.text })
        val keyRows = guide.filter { it.startsWith("| ") && it.contains("\\.") && it.count { char -> char == '|' } == 5 }
        assertEquals(5, keyRows.size)
        keyRows.zip(form.scales).forEach { (row, scale) ->
            val columns = row.split('|')
            assertEquals(ipipIndices(columns[3]), form.items.indices.filter { form.items[it].scale == scale.id && !form.items[it].reverse })
            assertEquals(ipipIndices(columns[4]), form.items.indices.filter { form.items[it].scale == scale.id && form.items[it].reverse })
        }
        assertEquals(50, form.items.size)
        assertFalse(AssessmentScoring.canScore(form, (0..48).associateWith { 2 }))
        val allAgree = form.items.indices.associateWith { form.values.lastIndex }
        assertEquals(listOf(30.0, 34.0, 34.0, 18.0, 38.0), AssessmentScoring.score(form, allAgree).map { it.value })
        assertEquals(
            List(5) { 50.0 },
            AssessmentScoring.score(form, form.items.indices.associateWith { if (form.items[it].reverse) 0 else 4 }).map { it.value }
        )
    }

    private fun ipipIndices(column: String) = column.trim().split(',').map { it.trim().toInt() - 1 }.sorted()

    @Test
    fun personalBurnoutUsesOnlyAnsweredItemsAndRequiresThreeValidAnswers() {
        val form = requireNotNull(catalog.find("cbi")?.form)
        assertFalse(AssessmentScoring.canScore(form, mapOf(0 to 0, 1 to 2)))
        assertFalse(AssessmentScoring.canScore(form, mapOf(0 to 0, 1 to 2, 19 to 4)))
        assertFalse(AssessmentScoring.canScore(form, mapOf(0 to 0, 1 to 2, 2 to 5)))
        val score = AssessmentScoring.score(form, mapOf(0 to 0, 1 to 2, 4 to 4)).single()
        assertEquals(50.0, score.value, 0.0)
        assertEquals(0.0, score.minimum, 0.0)
        assertEquals(100.0, score.maximum, 0.0)
    }

    @Test
    fun burnoutKeepsSubscaleThresholdsIndependentAndReversesTheEnergyItem() {
        val form = requireNotNull(catalog.find("cbi")?.form)
        assertEquals(19, form.items.size)
        assertFalse(AssessmentScoring.canScore(form, mapOf(0 to 0, 1 to 0, 6 to 0, 7 to 0)))
        val work = form.items.indices.filter { form.items[it].scale == "WB" }
        val client = form.items.indices.filter { form.items[it].scale == "CB" }
        val reversed = work.single { form.items[it].reverse }
        assertFalse(AssessmentScoring.canScore(form, (work.take(2) + reversed).associateWith { 0 }))
        val workOnly = (work.take(3) + reversed).associateWith { if (it == reversed) 4 else 0 }
        val score = AssessmentScoring.score(form, workOnly).single()
        assertEquals(100.0, score.value, 0.0)
        assertEquals("Kelelahan terkait pekerjaan", score.label)
        assertEquals(2, AssessmentScoring.score(form, workOnly + client.take(3).associateWith { 2 }).size)
        assertEquals(3, work.count { form.items[it].choices != null })
        assertEquals(4, client.count { form.items[it].choices != null })
    }

    @Test
    fun usesTheFrtCorrectionWithAllFortyFiveMarkedItems() {
        val entry = requireNotNull(protocols.getValue("frt").entry)
        assertFalse(entry.accepts(listOf(36, 7, 1)))
        assertTrue(entry.accepts(listOf(36, 7, 2)))
        assertEquals(34.25, entry.score(listOf(36, 7, 2)).first().value, 0.0)
        assertEquals("34,25", entry.score(listOf(36, 7, 2)).first().displayValue())
    }

    @Test
    fun markedSheetRulesMatchFolderExamples() {
        fun entry(id: String) = requireNotNull(protocols.getValue(id).entry)
        assertEquals(46.0, entry("cfit_2a").score(listOf(12, 14, 12, 8)).first().value, 0.0)
        assertEquals(50.0, entry("cfit_3a").score(listOf(13, 14, 13, 10)).first().value, 0.0)
        assertFalse(entry("cfit_3a").accepts(listOf(13, 14, 13, 11)))
        val rmib = List(12) { (12 - it) * 9 }
        assertEquals("Medical", entry("rmib").score(rmib).first().label)
        assertFalse(entry("rmib").accepts(rmib.dropLast(1) + 10))
        val disc = listOf(12, 3, 3, 2, 4, 4, 5, 6, 7, 2)
        assertEquals(listOf(8.0, -2.0, -3.0, -5.0), entry("disc").score(disc).take(4).map { it.value })
        val inconsistentDisc = disc.toMutableList().apply {
            this[0] += 1
            this[5] -= 1
        }
        assertFalse(entry("disc").accepts(inconsistentDisc))
        assertEquals(listOf(50.0, 30.0, 20.0), entry("vak").score(listOf(9, 15, 6)).map { it.value })
        assertEquals(120.0, entry("adversity_quotient").score(listOf(5, 10, 20, 25)).first().value, 0.0)
        assertEquals(3.0, entry("msai").score(listOf(5, 15, 25) + List(9) { 25 }).first().value, 0.0)
        assertTrue(entry("epps").accepts(List(15) { 14 } + 11))
        assertFalse(entry("epps").accepts(List(15) { 14 } + 9))
        assertFalse(entry("epps").accepts(List(15) { 13 } + 15))

        // Validasi instrumen tambahan klinis & kepribadian
        val bdiScores = entry("bdi").score(listOf(1, 0, 1, 0, 1, 0, 1, 0, 2, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1))
        assertEquals(12.0, bdiScores.first().value, 0.0)
        assertTrue(bdiScores.first().meaning.contains("Minimal"))
        assertTrue(bdiScores.first().meaning.contains("Butir 9 bernilai 2"))

        val mbtiScores = entry("mbti").score(listOf(10, 20, 5, 25, 18, 12, 22, 8))
        assertTrue(mbtiScores.first().meaning.contains("INTJ"))
        assertEquals(10.0, mbtiScores[1].value, 0.0) // E vs I selisih 10

        val srqScores = entry("srq-29").score(listOf(8, 0, 1, 2))
        assertEquals(11.0, srqScores.first().value, 0.0)
        assertTrue(srqScores.first().meaning.contains("Indikasi Gangguan Mental Emosional"))
        assertTrue(srqScores.first().meaning.contains("Perlu Rujukan"))

        val mmpiScores = entry("mmpi").score(listOf(50, 60, 50, 70, 80, 55, 68, 50, 55, 60, 50, 50, 50))
        assertTrue(mmpiScores.first().meaning.contains("Hs (Hypochondriasis)"))
        assertTrue(mmpiScores.first().meaning.contains("D (Depression)"))

        val warteggScores = entry("wartegg").score(List(8) { 20 })
        assertEquals(20.0, warteggScores.first().value, 0.0)
        assertEquals(8, warteggScores.size)

        val mitScores = entry("mit").score(listOf(45, 40, 35, 30, 25, 20, 15, 10))
        assertEquals("Linguistik", mitScores.first().label)
        assertEquals(45.0, mitScores.first().value, 0.0)

        val mseaiScores = entry("mseai").score(listOf(20, 20, 20, 20))
        assertEquals(80.0, mseaiScores.first().value, 0.0)
    }

    @Test
    fun kraepelinMatchesThePublishedFrequencyTable() {
        // Manual Tes Kraepelin, halaman 6. Tabel frekuensi tidak memuat urutan lajur.
        val frequencies = listOf(12 to 4, 13 to 4, 14 to 6, 15 to 16, 16 to 7, 17 to 8, 18 to 2, 19 to 1, 20 to 2)
        val correct = frequencies.flatMap { (value, frequency) -> List(frequency) { value } }
        val entry = requireNotNull(protocols.getValue("kraepelin").entry)
        val scores = entry.score(correct + listOf(0, 0)).associateBy { it.label }
        assertEquals(767, correct.sum())
        assertEquals(15.34, scores.getValue("Panker").value, 1e-10)
        assertEquals(1.448, scores.getValue("Janker").value, 1e-10)
        assertEquals(0.0, entry.score(List(50) { 15 } + listOf(0, 0)).single { it.label == "Hanker" }.value, 1e-10)
        val maximumPerColumn = entry.fields.first().maximum
        assertEquals(27, maximumPerColumn)
        assertFalse(entry.accepts(List(50) { maximumPerColumn } + listOf(1, 0)))
    }

    @Test
    fun pauliUsesTwentyIntervalsAndReviewedErrorDenominator() {
        val entry = requireNotNull(protocols.getValue("pauli").entry)
        val intervals = (30..49).toList()
        val scores = entry.score(intervals + listOf(200, 10)).associateBy { it.label }
        assertEquals(790.0, scores.getValue("Total dikerjakan").value, 0.0)
        assertEquals(39.5, scores.getValue("Rerata per interval").value, 0.0)
        assertEquals(60.0, scores.getValue("Puncak pertama (menit)").value, 0.0)
        assertEquals(5.0, scores.getValue("Salah (%)").value, 0.0)
        assertFalse(entry.accepts(intervals + listOf(200, 201)))
        assertFalse(entry.accepts(intervals + listOf(791, 0)))
    }

    @Test
    fun everyCatalogInstrumentHasSourceBoundGuideAndSupportedScoresFitPrompt() = runTest {
        val prompts = File("src/main/canonicalAssets/ai/prompts/counselor_v1.json").reader().use {
            PromptAssetProvider.Static(Gson().fromJson(it, PromptAsset::class.java))
        }
        val engine = RecordingAssessmentEngine()
        val discussion = AssessmentDiscussion(engine, prompts) { true }
        assertEquals(catalog.instruments.map { it.id }.toSet(), protocols.keys)
        assertEquals(40, protocols.size)
        catalog.instruments.forEach { instrument ->
            val protocol = protocols.getValue(instrument.id)
            val guide = assessmentGuideEvidence(protocol)
            assertTrue(guide.joinToString(" ").contains(protocol.limitation))
            assertTrue(guide.all { ConservativeGemmaTokenCounter.count(it) <= PromptBudget().maxCitationTokens })
            discussion.explain(instrument.copy(protocol = protocol))
            assertTrue(engine.systemInstruction.contains(protocol.limitation))
            val entry = protocol.entry ?: return@forEach
            val max = entry.fields.map { it.maximum }
            val values = when {
                entry.operation == ScoreOperation.KRAEPELIN -> max.dropLast(2) + listOf(0, 0)
                entry.operation == ScoreOperation.PAULI -> List(20) { 40 } + listOf(800, 0)
                entry.operation == ScoreOperation.EPPS -> List(15) { 14 } + max.last()
                entry.requiredTotal == null -> max
                else -> entry.fields.chunked(entry.groupSize ?: entry.fields.size).flatMap { group ->
                    var remaining = entry.requiredTotal - group.sumOf { it.minimum }
                    group.map { field ->
                        val extra = remaining.coerceAtMost(field.maximum - field.minimum)
                        remaining -= extra
                        field.minimum + extra
                    }
                }
            }
            val result = AssessmentResult(
                "unit-test", instrument.id, instrument.title, entry.version, instrument.title, "",
                instrument.source, 1L, values.size, values.size, entry.score(values),
                "${protocol.interpretation} ${protocol.limitation}", true
            )
            val evidence = assessmentEvidence(result).joinToString(" ")
            assertTrue(evidence.contains("rekap lembar"))
            assertTrue(evidence.contains(protocol.limitation))
            result.scores.forEach {
                assertTrue(evidence.contains("${it.label} ${it.displayValue()}"))
                assertTrue(evidence.contains("Skala ${it.displayRange()}"))
            }
            assertEquals(result, Gson().fromJson(Gson().toJson(result), AssessmentResult::class.java))
            discussion.explain(result)
            assertTrue(engine.systemInstruction.contains(protocol.limitation))
            assertTrue(engine.systemInstruction.contains("${result.scores.last().label} ${result.scores.last().displayValue()}"))
        }
    }

    @Test
    fun allFiveScoresFitTheGemmaEvidenceBudget() {
        val instrument = requireNotNull(catalog.find("big_five"))
        val form = requireNotNull(instrument.form)
        val scores = AssessmentScoring.score(form, form.items.indices.associateWith { 4 })
        val evidence = assessmentEvidence(
            AssessmentResult(
                "unit-test", instrument.id, form.title, form.version, form.sourceTitle, form.sourceUrl,
                form.sourceLocator, 1L, 50, 50, scores, form.interpretationNote
            )
        ).joinToString(" ")
        scores.forEach { assertTrue(evidence.contains("${it.label} ${it.displayValue()}")) }
        assertTrue(evidence.contains(form.interpretationNote))
    }

    @Test
    fun discInteractiveScoringProducesProfilesABC() {
        val form = requireNotNull(catalog.find("disc")?.form)
        assertEquals(24, form.items.size)
        val answers = mutableMapOf<Int, Int>()
        for (g in 0 until 24) {
            answers[g * 2] = 0
            answers[g * 2 + 1] = 3
        }
        assertTrue(AssessmentScoring.canScore(form, answers))
        val (scores, interpretation) = AssessmentEnrichers.enrich("disc", form, answers, emptyList())
        assertTrue(scores.any { it.label.contains("Profil A (Most)") })
        assertTrue(scores.any { it.label.contains("Profil B (Least)") })
        assertTrue(scores.any { it.label.contains("Profil C (Selisih Most-Least)") })
        assertTrue(interpretation.contains("Skor mentah DISC-24"))
    }

    @Test
    fun mmpiInteractiveScoringDetectsSuiSafetyRiskAndClinicalScales() {
        val form = requireNotNull(catalog.find("mmpi")?.form)
        assertEquals(338, form.items.size)
        val answers = (0 until 338).associateWith { 0 }.toMutableMap()
        answers[92] = 1
        answers[119] = 1
        val outcome = AssessmentEnrichers.enrichDetailed("mmpi", form, answers, emptyList())
        val suiScore = outcome.scores.first { it.label.contains("SUI") }
        assertEquals(2.0, suiScore.value, 0.001)
        assertTrue(outcome.interpretation.contains("PERINGATAN KESELAMATAN SUI"))
        assertEquals(ExaminerReviewStatus.PENDING, outcome.reviewStatus)
    }

    @Test
    fun tiu6SpatialScoringComputesRawScoreAndFamilies() {
        val form = requireNotNull(catalog.find("tiu_6")?.form)
        assertEquals(40, form.items.size)

        form.items.forEachIndexed { idx, item ->
            val expectedImg = "ai/psychometrics/images/tiu6/tiu6_item_${idx + 1}.webp"
            assertEquals("Item ${idx + 1} must reference its authentic spatial stimulus image", expectedImg, item.imageAssetPath)
            assertEquals("Item ${idx + 1} must have 2 binary choices (Benar/Salah)", 2, item.choices?.size)
            assertEquals("Benar (Dapat Dilipat Utuh)", item.choices?.get(0))
            assertEquals("Salah (Tidak Dapat Dilipat)", item.choices?.get(1))
        }

        val keys = listOf(
            0, 1, 1, 1, 0, 1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 0, 1,
            1, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 0, 0, 0, 0, 1, 0, 0, 1, 1
        )
        val answers = keys.mapIndexed { index, k -> index to if (k == 1) 0 else 1 }.toMap()
        val (scores, interpretation) = AssessmentEnrichers.enrich("tiu_6", form, answers, emptyList())
        val rawScore = scores.first { it.label.contains("Total Jawaban Benar") }
        assertEquals(40.0, rawScore.value, 0.001)
        assertFalse(scores.any { it.label.contains("Skor Baku") })
        assertTrue(interpretation.contains("40 dari 40"))

        val (recapScores, recapInterp) = AssessmentEnrichers.enrichTiu6Recap(15, 12)
        val rawRecap = recapScores.first { it.label.contains("Total Jawaban Benar") }
        assertEquals(27.0, rawRecap.value, 0.001)
        assertTrue(recapInterp.contains("27/40"))
    }

    @Test
    fun tiu5FiguralScoringComputesRawScoreAndHalves() {
        val form = requireNotNull(catalog.find("tiu_5")?.form)
        assertEquals(30, form.items.size)
        form.items.forEachIndexed { idx, item ->
            val expectedImg = "ai/psychometrics/images/tiu5/tiu5_item_${idx + 1}.webp"
            assertEquals("Item ${idx + 1} must reference its authentic figural diagram", expectedImg, item.imageAssetPath)
            assertEquals(5, item.choices?.size)
        }

        val keys = listOf(
            2, 5, 4, 4, 5, 4, 5, 1, 5, 3,
            5, 4, 5, 3, 2, 3, 2, 3, 4, 4,
            3, 3, 4, 4, 5, 1, 2, 5, 3, 5
        )
        val fullAnswers = keys.mapIndexed { index, k -> index to k - 1 }.toMap()
        val (scoresFull, interpFull) = AssessmentEnrichers.enrich("tiu_5", form, fullAnswers, emptyList())
        val rawFull = scoresFull.first { it.label.contains("Total Skor Mentah") }
        assertEquals(30.0, rawFull.value, 0.001)
        assertFalse(scoresFull.any { it.label.contains("Weighted Score") })
        assertTrue(interpFull.contains("30/30"))

        val (recapScores, recapInterp) = AssessmentEnrichers.enrichTiu5Recap(12, 11)
        val rawRecap = recapScores.first { it.label.contains("Total Skor Mentah") }
        assertEquals(23.0, rawRecap.value, 0.001)
        assertTrue(recapInterp.contains("23/30"))
    }

    @Test
    fun spmMatrixScoringComputesRawScoreAndSets() {
        val form = requireNotNull(catalog.find("spm")?.form)
        assertEquals(60, form.items.size)
        val spmKeys = listOf(
            4, 5, 1, 2, 6, 3, 6, 2, 1, 3, 4, 5,
            2, 6, 1, 2, 1, 3, 5, 6, 4, 3, 4, 5,
            8, 2, 3, 8, 7, 4, 5, 1, 7, 6, 1, 2,
            3, 4, 3, 7, 8, 6, 5, 4, 1, 2, 5, 6,
            7, 6, 8, 2, 1, 5, 1, 6, 3, 2, 4, 5
        )
        val answers = spmKeys.mapIndexed { index, k -> index to k - 1 }.toMap()
        val (scores, interpretation) = AssessmentEnrichers.enrich("spm", form, answers, emptyList())
        val total = scores.first { it.label.contains("Total Skor Mentah") }
        assertEquals(60.0, total.value, 0.001)
        assertFalse(scores.any { it.label.contains("Grade") })
        assertTrue(interpretation.contains("tabel norma usia"))
    }

    @Test
    fun spmItemsHaveAuthenticImageAssetsAndValidChoices() {
        val form = requireNotNull(catalog.find("spm")?.form)
        assertEquals(60, form.items.size)
        for ((index, item) in form.items.withIndex()) {
            val itemNum = index + 1
            assertEquals("ai/psychometrics/images/spm/spm_item_$itemNum.webp", item.imageAssetPath)
            val file = java.io.File("src/main/canonicalAssets/${item.imageAssetPath}")
            assertTrue("SPM item $itemNum image must exist: ${file.path}", file.exists())
            if (index < 24) {
                assertEquals("Set A and B must have 6 choices (item $itemNum)", 6, item.choices?.size)
            } else {
                assertEquals("Set C, D and E must have 8 choices (item $itemNum)", 8, item.choices?.size)
            }
        }
    }

    @Test
    fun spmRecapScoringComputesRawTotalsAndConsistency() {
        val (scoresGrade1, interpGrade1) = AssessmentEnrichers.enrichSpmRecap(12, 12, 12, 12, 12)
        assertEquals(60.0, scoresGrade1.first { it.label.contains("Total Skor Mentah") }.value, 0.001)
        assertTrue(interpGrade1.contains("Konsistensi antar-set menunjukkan progresi wajar"))

        val (scoresGrade3, _) = AssessmentEnrichers.enrichSpmRecap(10, 9, 8, 7, 6)
        assertEquals(40.0, scoresGrade3.first { it.label.contains("Total Skor Mentah") }.value, 0.001)

        val (scoresAtypical, interpAtypical) = AssessmentEnrichers.enrichSpmRecap(2, 3, 2, 2, 10)
        assertEquals(19.0, scoresAtypical.first { it.label.contains("Total Skor Mentah") }.value, 0.001)
        assertTrue(interpAtypical.contains("Catatan Konsistensi"))
    }

    @Test
    fun mbtiScoringComputes16PersonalityTypes() {
        val form = requireNotNull(catalog.find("mbti")?.form)
        assertEquals(70, form.items.size)
        val answers = (0 until 70).associateWith { 0 }
        val (scores, interpretation) = AssessmentEnrichers.enrich("mbti", form, answers, emptyList())
        val typeScore = scores.first { it.label.contains("Tipe MBTI") }
        assertTrue(typeScore.meaning.contains("ESTJ"))
        assertTrue(interpretation.contains("ESTJ"))
    }

    @Test
    fun sixteenPfInteractiveScoringComputes16PrimaryRawFactorsAndMd() {
        val form = requireNotNull(catalog.find("16pf")?.form)
        assertEquals(105, form.items.size)

        val factorBKeys = mapOf(2 to 1, 19 to 2, 36 to 1, 53 to 2, 70 to 0, 87 to 2, 103 to 0, 104 to 1)
        val answers = (0 until 105).associateWith { idx ->
            factorBKeys[idx] ?: 1
        }

        val (scores, interpretation) = AssessmentEnrichers.enrich("16pf", form, answers, emptyList())
        assertEquals(17, scores.size)

        val factorBScore = scores.first { it.label.contains("Faktor B:") }
        assertEquals(8.0, factorBScore.value, 0.001)
        assertTrue(factorBScore.meaning.contains("8/8"))
        assertTrue(scores.any { it.label.contains("Skala Validitas MD") })
        assertTrue(interpretation.contains("16PF Form C"))
        assertTrue(interpretation.contains("usia & jenis kelamin"))
    }

    @Test
    fun armyAlphaInteractiveScoringEvaluates12InstructionsAndSubtests() {
        val form = requireNotNull(catalog.find("army_alpha")?.form)
        assertEquals(12, form.items.size)

        val perfectAnswers = (0 until 12).associateWith { 0 }
        val (scores, interpretation) = AssessmentEnrichers.enrich("army_alpha", form, perfectAnswers, emptyList())

        val totalScore = scores.first { it.label.contains("Total Benar Army Alpha") }
        assertEquals(12.0, totalScore.value, 0.001)

        val dirScore = scores.first { it.label.contains("Subtes Following Commands") }
        assertEquals(7.0, dirScore.value, 0.001)

        val speedScore = scores.first { it.label.contains("Subtes Speed & Accuracy") }
        assertEquals(5.0, speedScore.value, 0.001)
        assertTrue(interpretation.contains("alat bantu heuristik"))
        assertFalse(scores.any { it.label.contains("Skala Army Alpha") })

        val lowAnswers = (0 until 12).associateWith { if (it < 3) 0 else 1 }
        val (lowScores, lowInterp) = AssessmentEnrichers.enrich("army_alpha", form, lowAnswers, emptyList())
        assertEquals(3.0, lowScores.first { it.label.contains("Total Benar Army Alpha") }.value, 0.001)
        assertTrue(lowInterp.contains("3/12"))
    }

    @Test
    fun frtInteractiveScoringEvaluatesStagesAndPenalty() {
        val form = requireNotNull(catalog.find("frt")?.form)
        assertEquals(45, form.items.size)
        assertTrue(form.items.all { it.imageAssetPath != null && it.imageAssetPath.startsWith("ai/psychometrics/images/frt/") })

        val frtKeys = listOf(
            6, 4, 2, 2, 4, 3, 2, 4, 2, 5, 5, 2, 3, 4, 5,
            4, 5, 1, 5, 2, 6, 1, 3, 6, 3, 3, 4, 6, 2, 3,
            4, 5, 3, 2, 6, 1, 4, 2, 6, 1, 2, 4, 1, 5, 2
        ).map { it - 1 }

        val perfectAnswers = frtKeys.mapIndexed { idx, k -> idx to k }.toMap()
        val (scores, interpretation) = AssessmentEnrichers.enrich("frt", form, perfectAnswers, emptyList())

        val rsScore = scores.first { it.label.contains("Skor Terkoreksi FRT") }
        assertEquals(45.0, rsScore.value, 0.001)
        assertEquals(45.0, scores.first { it.label.contains("Jumlah Benar (R)") }.value, 0.001)
        assertEquals(0.0, scores.first { it.label.contains("Jumlah Salah (W)") }.value, 0.001)
        assertEquals(0.0, scores.first { it.label.contains("Jumlah Kosong (O)") }.value, 0.001)
        assertEquals(15.0, scores.first { it.label.contains("Tahap Asosiatif") }.value, 0.001)
        assertEquals(15.0, scores.first { it.label.contains("Tahap Sintesis") }.value, 0.001)
        assertEquals(15.0, scores.first { it.label.contains("Tahap Analisis") }.value, 0.001)
        assertTrue(interpretation.contains("tabel persentil FRT"))

        val partialAnswers = mutableMapOf<Int, Int>()
        for (i in 0 until 20) partialAnswers[i] = frtKeys[i]
        for (i in 20 until 28) partialAnswers[i] = (frtKeys[i] + 1) % 6

        val (pScores, pInterp) = AssessmentEnrichers.enrich("frt", form, partialAnswers, emptyList())
        assertEquals(18.0, pScores.first { it.label.contains("Skor Terkoreksi FRT") }.value, 0.001)
        assertEquals(20.0, pScores.first { it.label.contains("Jumlah Benar (R)") }.value, 0.001)
        assertEquals(8.0, pScores.first { it.label.contains("Jumlah Salah (W)") }.value, 0.001)
        assertEquals(17.0, pScores.first { it.label.contains("Jumlah Kosong (O)") }.value, 0.001)
        assertTrue(pInterp.contains("18.00/45"))
    }

    @Test
    fun cfit2aInteractiveScoringEvaluatesFourSubtestsRaw() {
        val form = requireNotNull(catalog.find("cfit_2a")?.form)
        assertEquals(46, form.items.size)
        assertTrue(form.items.all { it.imageAssetPath != null && it.imageAssetPath.startsWith("ai/psychometrics/images/cfit2a/") })

        val subtest1Keys = listOf(2, 3, 0, 2, 1, 4, 1, 2, 2, 2, 3, 0)
        val subtest2Keys = listOf(1, 2, 3, 0, 2, 2, 0, 4, 3, 2, 2, 2, 0, 3)
        val subtest3Keys = listOf(0, 2, 1, 4, 2, 0, 1, 3, 4, 0, 1, 1)
        val subtest4Keys = listOf(2, 1, 0, 3, 2, 2, 0, 1)
        val allKeys = subtest1Keys + subtest2Keys + subtest3Keys + subtest4Keys

        val perfectAnswers = allKeys.mapIndexed { idx, k -> idx to k }.toMap()
        val (scores, interpretation) = AssessmentEnrichers.enrich("cfit_2a", form, perfectAnswers, emptyList())

        assertEquals(46.0, scores.first { it.label.contains("Total Skor Mentah CFIT 2A") }.value, 0.001)
        assertFalse(scores.any { it.label.contains("Estimasi IQ Cattell") })
        assertEquals(12.0, scores.first { it.label.contains("Subtes 1: Seriasi") }.value, 0.001)
        assertEquals(14.0, scores.first { it.label.contains("Subtes 2: Klasifikasi") }.value, 0.001)
        assertEquals(12.0, scores.first { it.label.contains("Subtes 3: Matriks") }.value, 0.001)
        assertEquals(8.0, scores.first { it.label.contains("Subtes 4: Persyaratan Titik") }.value, 0.001)
        assertTrue(interpretation.contains("Total Skor Mentah 46/46"))

        val avgAnswers = (0 until 24).associateWith { allKeys[it] }
        val (avgScores, avgInterp) = AssessmentEnrichers.enrich("cfit_2a", form, avgAnswers, emptyList())
        assertEquals(24.0, avgScores.first { it.label.contains("Total Skor Mentah CFIT 2A") }.value, 0.001)
        assertTrue(avgInterp.contains("tabel norma usia resmi"))
    }

    @Test
    fun cfit3aInteractiveScoringEvaluatesFourSubtestsWithoutStigmatizingIq() {
        val form = requireNotNull(catalog.find("cfit_3a")?.form)
        assertEquals(50, form.items.size)
        assertTrue(form.items.all { it.imageAssetPath != null && it.imageAssetPath.startsWith("ai/psychometrics/images/cfit3a/") })
        assertTrue((13..26).all { form.items[it].multiSelect && form.items[it].maxSelections == 2 })

        val subtest1Keys = listOf(1, 2, 1, 3, 4, 1, 3, 1, 5, 2, 1, 1, 4)
        val subtest2Masks = listOf(18, 17, 9, 20, 18, 9, 18, 18, 9, 10, 17, 12, 6, 3)
        val subtest3Keys = listOf(4, 4, 4, 1, 2, 3, 4, 4, 0, 0, 5, 2, 2)
        val subtest4Keys = listOf(1, 0, 3, 3, 0, 1, 2, 3, 0, 3)

        val perfectAnswers = mutableMapOf<Int, Int>()
        subtest1Keys.forEachIndexed { i, k -> perfectAnswers[i] = k }
        subtest2Masks.forEachIndexed { i, m -> perfectAnswers[13 + i] = m }
        subtest3Keys.forEachIndexed { i, k -> perfectAnswers[27 + i] = k }
        subtest4Keys.forEachIndexed { i, k -> perfectAnswers[40 + i] = k }

        val (scores, interpretation) = AssessmentEnrichers.enrich("cfit_3a", form, perfectAnswers, emptyList())
        assertEquals(50.0, scores.first { it.label.contains("Total Skor Mentah CFIT 3A") }.value, 0.001)
        assertFalse(scores.any { it.label.contains("Standard IQ Cattell") })
        assertFalse(interpretation.contains("Mentally Retardation"))
        assertEquals(13.0, scores.first { it.label.contains("Subtes 1: Deret") }.value, 0.001)
        assertEquals(14.0, scores.first { it.label.contains("Subtes 2: Klasifikasi") }.value, 0.001)
        assertEquals(13.0, scores.first { it.label.contains("Subtes 3: Matriks") }.value, 0.001)
        assertEquals(10.0, scores.first { it.label.contains("Subtes 4: Persyaratan Topologi") }.value, 0.001)
        assertTrue(interpretation.contains("50/50"))
    }

    @Test
    fun cfit2aRecapEnrichmentMatchesInteractiveScoring() {
        val (scores, interp) = AssessmentEnrichers.enrichCfit2aRecap(10, 11, 9, 6)
        assertEquals(36.0, scores.first { it.label.contains("Total Skor Mentah CFIT 2A") }.value, 0.001)
        assertTrue(interp.contains("36/46"))
        assertTrue(interp.contains("tabel norma usia resmi"))
    }

    @Test
    fun cfit3aRecapEnrichmentMatchesInteractiveScoring() {
        val (scores, interp) = AssessmentEnrichers.enrichCfit3aRecap(10, 9, 8, 7)
        assertEquals(34.0, scores.first { it.label.contains("Total Skor Mentah CFIT 3A") }.value, 0.001)
        assertTrue(interp.contains("34/50"))
        assertTrue(interp.contains("tabel norma usia dan pendidikan resmi"))
    }

    @Test
    fun assessmentDiscussionAdmitsSavedScoresSeparatelyFromTheChatMessage() {
        val result = AssessmentResult(
            resultId = "res-123",
            instrumentId = "cbi",
            title = "Copenhagen Burnout Inventory",
            version = "CBI Standard",
            sourceTitle = "CBI Manual",
            sourceUrl = "",
            sourceLocator = "§1",
            createdAtMillis = 1000L,
            answered = 19,
            itemCount = 19,
            scores = listOf(
                AssessmentScore("Kelelahan personal", 87.5, 0.0, 100.0, "Tinggi"),
                AssessmentScore("Kelelahan kerja", 85.71, 0.0, 100.0, "Tinggi"),
                AssessmentScore("Kategori kelelahan", 87.5, 0.0, 100.0, "Tinggi")
            ),
            interpretation = "Tingkat keletihan dan kejenuhan kerja berada pada level Tinggi."
        )

        val prompt = result.toCounselorDiscussionPrompt()
        assertTrue(prompt.contains("Copenhagen Burnout Inventory"))
        assertFalse(prompt.contains("87,5"))
        val request = CounselorGenerationRequest(
            promptAsset = PromptAsset("test", "1", "AI Counselor", "{user_message}"),
            userMessage = prompt,
            ragMode = RagMode.GENERAL_SAFE,
            retrievedCitations = emptyList(),
            stressHistoryContext = "",
            assessmentDiscussion = true,
            assessmentResult = result
        )
        val prepared = CounselorPromptBuilder().prepare(request)
        assertTrue(prepared.systemInstruction.contains("Kelelahan personal: 87,5 (0–100)"))
        assertTrue(prepared.systemInstruction.contains("CBI Standard"))
        assertTrue(prepared.systemInstruction.contains(result.interpretation))
        assertTrue(prepared.systemInstruction.contains("Kategori kelelahan: Tinggi"))
        assertFalse(prepared.userPrompt.contains("87,5"))

        val instrument = AssessmentInstrument(
            id = "cbi",
            title = "Copenhagen Burnout Inventory",
            category = "Kelelahan",
            description = "Inventori kejenuhan kerja",
            source = "docs/cbi.md",
            aliases = emptyList(),
            protocol = AssessmentProtocol("cbi", "CBI v1", "§1", "Rata-rata skor", "Tinggi jika > 50", "Bukan diagnosis klinis")
        )
        val guidePrompt = instrument.toGuideDiscussionPrompt()
        assertTrue(guidePrompt.contains("Copenhagen Burnout Inventory"))
        assertTrue(guidePrompt.contains("CBI v1"))
        assertTrue(guidePrompt.contains("Rata-rata skor"))
    }

    @Test
    fun savedResultLookupSelectsOneNamedInstrumentOnly() {
        assertEquals("tiu_5", catalog.findNamedInstrument("Jelaskan skor TIU-5 saya")?.id)
        assertEquals(null, catalog.findNamedInstrument("Bahas skor TIU-5 dan TIU-6"))
        assertEquals(null, catalog.findNamedInstrument("Jelaskan skor saya"))
    }

    @Test
    fun enneagramEnrichmentRanksRawScoresWithoutWingOverclaim() {
        val form = requireNotNull(catalog.find("enneagram")?.form)
        assertEquals(180, form.items.size)
        assertEquals(180, form.minimumAnswers)

        val type3Indices = setOf(0, 5, 12, 20, 50, 55, 66, 72, 74, 85, 89, 111, 113, 134, 138, 142, 150, 162, 168, 173)
        val type2Indices = setOf(10, 11, 18, 25, 33, 37, 43, 59, 60, 73, 75, 80, 83, 88, 93, 96, 98, 133, 160, 167)

        val answers = mutableMapOf<Int, Int>()
        for (i in 0 until 180) {
            answers[i] = when (i) {
                in type3Indices -> 3
                in type2Indices -> 2
                else -> 0
            }
        }

        val (scores, interpretation) = AssessmentEnrichers.enrich("enneagram", form, answers, emptyList())
        assertEquals(9, scores.size)
        assertTrue(scores.first().label.contains("Tipe 3"))
        assertFalse(scores.any { it.label.contains("Tipe Dominan") })
        assertEquals(60.0, scores.first { it.label.contains("Tipe 3") }.value, 0.001)
        assertEquals(40.0, scores.first { it.label.contains("Tipe 2") }.value, 0.001)
        assertTrue(interpretation.contains("Tipe 3 (60/60)"))
        assertFalse(interpretation.contains("3w2"))
    }

    @Test
    fun istEnrichmentCalculatesRawSubtestsWithoutSyntheticIq() {
        val form = requireNotNull(catalog.find("ist")?.form)
        assertEquals(176, form.items.size)
        assertEquals(176, form.minimumAnswers)

        for (i in 116 until 156) {
            val item = form.items[i]
            assertTrue("Item ${i + 1} must have imageAssetPath", !item.imageAssetPath.isNullOrBlank())
            assertTrue(item.imageAssetPath!!.startsWith("ai/psychometrics/images/ist/"))
        }

        val seKeys = listOf(4, 2, 3, 3, 3, 1, 2, 0, 4, 1, 2, 3, 3, 4, 2, 0, 1, 1, 2, 1)
        val waKeys = listOf(1, 1, 3, 2, 2, 2, 2, 3, 3, 0, 4, 0, 0, 1, 2, 0, 3, 4, 1, 2)
        val anKeys = listOf(2, 4, 3, 3, 3, 0, 3, 1, 4, 3, 2, 2, 2, 2, 3, 2, 2, 3, 4, 4)
        val raKeys = listOf(2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 4, 2, 2)
        val zrKeys = listOf(2, 2, 1, 2, 2, 2, 2, 2, 1, 3, 2, 2, 3, 2, 3, 1, 3, 3, 2, 2)
        val faKeys = listOf(0, 2, 1, 0, 3, 1, 2, 4, 4, 3, 4, 1, 3, 2, 1, 0, 1, 3, 2, 0)
        val wuKeys = listOf(0, 2, 3, 4, 0, 2, 3, 2, 4, 0, 1, 3, 4, 1, 3, 1, 0, 4, 1, 2)
        val meKeys = listOf(3, 4, 1, 0, 2, 0, 3, 4, 2, 1, 1, 0, 4, 2, 3, 1, 4, 0, 2, 3)

        val answers = mutableMapOf<Int, Int>()
        seKeys.forEachIndexed { i, k -> answers[i] = k }
        waKeys.forEachIndexed { i, k -> answers[20 + i] = k }
        anKeys.forEachIndexed { i, k -> answers[40 + i] = k }
        for (i in 0 until 16) answers[60 + i] = 0
        raKeys.forEachIndexed { i, k -> answers[76 + i] = k }
        zrKeys.forEachIndexed { i, k -> answers[96 + i] = k }
        faKeys.forEachIndexed { i, k -> answers[116 + i] = k }
        wuKeys.forEachIndexed { i, k -> answers[136 + i] = k }
        meKeys.forEachIndexed { i, k -> answers[156 + i] = k }

        val (scores, interpretation) = AssessmentEnrichers.enrich("ist", form, answers, emptyList())
        assertFalse(scores.any { it.label.contains("Skor IQ Amthauer") })
        assertEquals(180.0, scores.first { it.label.contains("Total Skor Mentah IST") }.value, 0.001)
        assertTrue(interpretation.contains("176/176 butir terisi"))
        assertTrue(interpretation.contains("Subtes GE menggunakan pilihan ganda adaptasi"))
    }

    @Test
    fun kuderEnrichmentCalculatesValidityAndTenRawScales() {
        val form = requireNotNull(catalog.find("kuder")?.form)
        assertEquals(168, form.items.size)
        assertEquals(168, form.minimumAnswers)

        val answers = mutableMapOf<Int, Int>()
        for (g in 0 until 168) {
            answers[g * 2] = 0
            answers[g * 2 + 1] = 2
        }
        assertTrue(AssessmentScoring.canScore(form, answers))

        val (scores, interpretation) = AssessmentEnrichers.enrich("kuder", form, answers, emptyList())
        assertEquals(11, scores.size)
        assertTrue(scores.first { it.label.contains("Validitas") }.value in 0.0..63.0)
        assertTrue(scores.first { it.label.contains("Outdoor") }.value >= 0.0)
        assertTrue(interpretation.contains("Kuder"))
        assertTrue(interpretation.contains("jenis kelamin"))
    }

    @Test
    fun kraepelinEnrichmentCalculatesPankerTiankerJankerHanker() {
        val form = requireNotNull(catalog.find("kraepelin")?.form)
        assertEquals(50, form.items.size)
        assertEquals(10, form.minimumAnswers)

        val answers = mutableMapOf<Int, Int>()
        val mockScores = listOf(14, 15, 14, 16, 16, 17, 16, 18, 17, 19)
        mockScores.forEachIndexed { col, correct ->
            answers[col] = correct
            answers[100 + col] = if (col % 3 == 0) 1 else 0
            answers[200 + col] = if (col % 4 == 0) 1 else 0
        }
        assertTrue(AssessmentScoring.canScore(form, answers))

        val outcome = AssessmentEnrichers.enrichDetailed("kraepelin", form, answers, emptyList())
        assertEquals(4, outcome.scores.size)
        assertEquals(10, outcome.observations.size)
        assertEquals(16.2, outcome.scores.first { it.label.contains("PANKER") }.value, 0.01)
        assertTrue(outcome.scores.first { it.label.contains("TIANKER") }.value > 0.0)
        assertTrue(outcome.scores.first { it.label.contains("JANKER") }.value > 0.0)
        assertTrue(outcome.scores.first { it.label.contains("HANKER") }.value > 0.0)
        assertTrue(outcome.interpretation.contains("Kraepelin"))
    }

    @Test
    fun multipleIntelligencesEnrichmentCalculatesGardnerRawDimensions() {
        val form = requireNotNull(catalog.find("mit")?.form)
        assertEquals(56, form.items.size)
        assertEquals(56, form.minimumAnswers)

        val answers = (0 until 56).associateWith { i -> if (i in 7..13) 3 else 1 }
        assertTrue(AssessmentScoring.canScore(form, answers))

        val (scores, interpretation) = AssessmentEnrichers.enrich("mit", form, answers, emptyList())
        assertEquals(8, scores.size)
        assertEquals(28.0, scores.first { it.label.contains("Logis-Matematis") }.value, 0.01)
        assertEquals(14.0, scores.first { it.label.contains("Linguistik") }.value, 0.01)
        assertTrue(interpretation.contains("Multiple Intelligences"))
        assertTrue(interpretation.contains("refleksi diri deskriptif"))
    }

    @Test
    fun msaiEnrichmentCalculatesCvfQuadrantsAndCompetencies() {
        val form = requireNotNull(catalog.find("msai")?.form)
        assertEquals(60, form.items.size)
        assertEquals(1, form.minimumAnswers)

        val clanItems = setOf(5, 20, 24, 25, 47, 1, 13, 23, 48, 50, 12, 18, 21, 22, 49)
        val answers = (0 until 60).associateWith { i -> if ((i + 1) in clanItems) 4 else 1 }
        assertTrue(AssessmentScoring.canScore(form, answers))

        val (scores, interpretation) = AssessmentEnrichers.enrich("msai", form, answers, emptyList())
        assertEquals(16, scores.size)
        assertTrue(scores.first { it.label.contains("Clan") }.value == 5.0)
        assertTrue(interpretation.contains("Clan"))
        assertTrue(interpretation.contains("Competing Values Framework"))
    }

    @Test
    fun msdtEnrichmentCalculatesReddinMatrixAndLeadershipStyles() {
        val form = requireNotNull(catalog.find("msdt")?.form)
        assertEquals(64, form.items.size)

        val answers = (0 until 64).associateWith { 0 }
        assertTrue(AssessmentScoring.canScore(form, answers))

        val outcome = AssessmentEnrichers.enrichDetailed("msdt", form, answers, emptyList())
        assertEquals(11, outcome.scores.size)
        assertEquals(AssessmentValidityStatus.VALID, outcome.validityStatus)
        assertTrue(outcome.scores.any { it.label.contains("Task Orientation") })
        assertTrue(outcome.scores.any { it.label.contains("Relationship Orientation") })
        assertTrue(outcome.scores.any { it.label.contains("Effectiveness") })
        assertTrue(outcome.interpretation.contains("Reddin"))
    }

    @Test
    fun papiKostickEnrichmentCalculatesRolesAndNeeds() {
        val form = requireNotNull(catalog.find("papikostick")?.form)
        assertEquals(90, form.items.size)

        val answers = (0 until 90).associateWith { 0 }
        assertTrue(AssessmentScoring.canScore(form, answers))

        val (scores, interpretation) = AssessmentEnrichers.enrich("papikostick", form, answers, emptyList())
        assertEquals(20, scores.size)
        assertTrue(scores.any { it.label.contains("Peran Pekerja Keras") })
        assertTrue(interpretation.contains("PAPI Kostick"))
    }

    @Test
    fun pauliEnrichmentCalculatesWorkCurveAndPerformance() {
        val form = requireNotNull(catalog.find("pauli")?.form)
        assertEquals(45, form.items.size)

        val answers = mapOf(0 to 20, 1 to 22, 2 to 25, 3 to 24, 4 to 26)
        assertTrue(AssessmentScoring.canScore(form, answers))

        val outcome = AssessmentEnrichers.enrichDetailed("pauli", form, answers, emptyList())
        assertEquals(5, outcome.scores.size)
        assertEquals(5, outcome.observations.size)
        assertEquals(117.0, outcome.scores.first { it.label.contains("Total Penjumlahan") }.value, 0.01)
        assertTrue(outcome.interpretation.contains("Pauli"))
    }

    @Test
    fun personalityTestEpqrCalculatesRawDimensionsAndLieScale() {
        val form = requireNotNull(catalog.find("personality_test")?.form)
        assertEquals(48, form.items.size)

        val answers = (0 until 48).associateWith { 0 }
        assertTrue(AssessmentScoring.canScore(form, answers))

        val (scores, interpretation) = AssessmentEnrichers.enrich("personality_test", form, answers, emptyList())
        assertEquals(4, scores.size)
        assertFalse(scores.any { it.label.contains("Tipe Temperamen") })
        assertTrue(scores.any { it.label.contains("Extraversion") })
        assertTrue(scores.any { it.label.contains("Neuroticism") })
        assertTrue(scores.any { it.label.contains("Psychoticism") })
        assertTrue(scores.any { it.label.contains("Lie Scale") })
        assertTrue(interpretation.contains("EPQ-RS"))
    }

    @Test
    fun warteggEnrichmentPreservesObservationsWithoutFakeFunctionScores() {
        val form = requireNotNull(catalog.find("wartegg")?.form)
        assertEquals(8, form.items.size)

        val answers = mapOf(
            0 to 10,
            1 to 22,
            2 to 30,
            3 to 41,
            4 to 53,
            5 to 62,
            6 to 70,
            7 to 81
        )
        assertTrue(AssessmentScoring.canScore(form, answers))

        val outcome = AssessmentEnrichers.enrichDetailed("wartegg", form, answers, emptyList())
        assertTrue(outcome.scores.any { it.label == "Kotak Diselesaikan" && it.value == 8.0 })
        assertFalse(outcome.scores.any { it.label.contains("Fungsi", ignoreCase = true) })
        assertEquals(InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED, outcome.scoringStatus)
        assertEquals(ExaminerReviewStatus.PENDING, outcome.reviewStatus)
        assertEquals(11, outcome.observations.size)
        assertTrue(outcome.observations.first { it.key == "wartegg_completed" }.value == "8/8")
        assertTrue(outcome.interpretation.contains("Wartegg"))
    }

    @Test
    fun rmibEnrichmentCalculatesStaircaseMatrixAnd702Invariant() {
        val form = requireNotNull(catalog.find("rmib")?.form)
        assertEquals(9, form.items.size)

        val answers = mutableMapOf<Int, Int>()
        for (g in 0 until 9) {
            for (p in 0 until 12) {
                answers[g * 12 + p] = p + 1
            }
        }
        assertTrue(AssessmentScoring.canScore(form, answers))

        val outcome = AssessmentEnrichers.enrichDetailed("rmib", form, answers, emptyList())
        assertEquals(13, outcome.scores.size)
        assertEquals(AssessmentValidityStatus.VALID, outcome.validityStatus)
        assertEquals(702.0, outcome.scores.first { it.label.contains("Total Skor RMIB") }.value, 0.001)
        assertTrue(outcome.interpretation.contains("Rothwell–Miller"))
        assertTrue(outcome.interpretation.contains("702"))
    }

    @Test
    fun vrtEnrichmentEmitsRawSubtestsWithSourceIncompleteStatus() {
        val form = requireNotNull(catalog.find("vrt")?.form)
        assertEquals(49, form.items.size)

        val answers = (0 until 49).associateWith { 0 }
        assertTrue(AssessmentScoring.canScore(form, answers))

        val outcome = AssessmentEnrichers.enrichDetailed("vrt", form, answers, emptyList())
        assertEquals(8, outcome.scores.size)
        assertEquals(InstrumentImplementationStatus.SOURCE_INCOMPLETE, outcome.scoringStatus)
        assertEquals(AssessmentValidityStatus.QUESTIONABLE, outcome.validityStatus)
        assertEquals(98.0, outcome.scores.first { it.label.contains("Total Skor Penalaran Verbal") }.value, 0.001)
        assertTrue(outcome.interpretation.contains("VRT"))
        assertFalse(outcome.interpretation.contains("Sangat Tinggi"))
    }

    @Test
    fun mseaiEnrichmentComputesExploratoryScoresWithoutBlocking() {
        val form = requireNotNull(catalog.find("mseai")?.form)
        assertEquals(24, form.items.size)

        val answers = (0 until 24).associateWith { 4 }
        val rawScores = AssessmentScoring.score(form, answers)
        val outcome = AssessmentEnrichers.enrichDetailed("mseai", form, answers, rawScores)
        assertTrue(outcome.scores.isNotEmpty())
        assertEquals(InstrumentImplementationStatus.EXPLORATORY, outcome.scoringStatus)
        assertEquals(AssessmentValidityStatus.VALID, outcome.validityStatus)
        assertTrue(outcome.interpretation.contains("MSEAI"))
    }

    @Test
    fun eppsEnrichmentCalculatesFifteenNeedsAndConsistencyWithInvariant210() {
        val form = requireNotNull(catalog.find("epps")?.form)
        assertEquals(225, form.items.size)

        val allA = (0 until 225).associateWith { 0 }
        assertTrue(AssessmentScoring.canScore(form, allA))

        val (scoresA, interpA) = AssessmentEnrichers.enrich("epps", form, allA, emptyList())
        assertEquals(17, scoresA.size)
        assertEquals(210.0, scoresA.first { it.label.contains("Total Raw Score EPPS") }.value, 0.001)
        assertEquals(15.0, scoresA.first { it.label.contains("Konsistensi Respons") }.value, 0.001)
        assertTrue(interpA.contains("EPPS"))
        assertTrue(interpA.contains("210"))

        val alt = (0 until 225).associateWith { it % 2 }
        val (scoresAlt, _) = AssessmentEnrichers.enrich("epps", form, alt, emptyList())
        assertEquals(210.0, scoresAlt.first { it.label.contains("Total Raw Score EPPS") }.value, 0.001)
    }

    @Test
    fun rorschachEnrichmentPreservesObservationsWithoutHeuristicConstellations() {
        val instrument = requireNotNull(catalog.find("rorschach"))
        val form = requireNotNull(instrument.form)
        assertEquals(10, form.items.size)

        val standardAnswers = (0 until 10).associateWith { idx ->
            val det = when (idx) {
                1, 7, 9 -> 4
                2, 6 -> 1
                5 -> 8
                8 -> 5
                else -> 0
            }
            (0 * 100000) + (det * 1000) + (1 * 100) + (1 * 10) + 0
        }
        assertTrue(AssessmentScoring.canScore(form, standardAnswers))

        val outcome = AssessmentEnrichers.enrichDetailed("rorschach", form, standardAnswers, emptyList())
        assertTrue(outcome.scores.any { it.label.contains("Total Respons") && it.value == 10.0 })
        assertEquals(InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED, outcome.scoringStatus)
        assertEquals(AssessmentValidityStatus.INVALID, outcome.validityStatus)
        assertEquals(ExaminerReviewStatus.PENDING, outcome.reviewStatus)
        assertTrue(outcome.observations.any { it.key == "rorschach_r" && it.value == "10" })
        assertTrue(outcome.observations.any { it.key == "rorschach_loc" && it.value.contains("W=10") })
        assertFalse(outcome.interpretation.contains("S-CON"))
        assertFalse(outcome.interpretation.contains("DEPI"))
    }

    @Test
    fun wptLoadsAuthenticItemsAndComputesRawSubscales() {
        val instrument = requireNotNull(catalog.find("wpt"))
        val form = requireNotNull(instrument.form)
        assertEquals(50, form.items.size)
        assertEquals("Bulan lalu pada awal tahun ini adalah...", form.items[0].text)
        assertEquals(5, requireNotNull(form.items[0].choices).size)
        assertEquals("ai/psychometrics/wpt_item_7.webp", form.items[6].imageAssetPath)
        assertEquals("ai/psychometrics/wpt_item_38.webp", form.items[37].imageAssetPath)
        assertEquals("ai/psychometrics/wpt_item_42.webp", form.items[41].imageAssetPath)
        assertEquals("ai/psychometrics/wpt_item_49.webp", form.items[48].imageAssetPath)

        val allCorrectKeys = listOf(
            3, 1, 2, 1, 2, 0, 2, 0, 0, 3,
            2, 2, 0, 1, 1, 1, 0, 1, 2, 0,
            1, 1, 1, 1, 2, 0, 0, 2, 1, 1,
            1, 0, 2, 1, 0, 2, 0, 1, 1, 1,
            0, 1, 3, 1, 2, 1, 2, 2, 1, 2
        )
        val perfectAnswers = allCorrectKeys.mapIndexed { index, ans -> index to ans }.toMap()
        val (scores50, interp50) = AssessmentEnrichers.enrich("wpt", form, perfectAnswers, emptyList())
        assertEquals(50.0, scores50.first { it.label.contains("Total Skor Mentah") }.value, 0.001)
        assertFalse(scores50.any { it.label.contains("Estimasi IQ Wonderlic") })
        assertFalse(scores50.any { it.label.contains("Benchmark Jabatan") })
        assertTrue(interp50.contains("50/50"))
        assertFalse(interp50.contains("Mentally Defective"))

        val avgAnswers = (0 until 50).associateWith { i -> if (i < 21) allCorrectKeys[i] else 4 }
        val (scores21, interp21) = AssessmentEnrichers.enrich("wpt", form, avgAnswers, emptyList())
        assertEquals(21.0, scores21.first { it.label.contains("Total Skor Mentah") }.value, 0.001)
        assertTrue(interp21.contains("21/50"))
        assertTrue(interp21.contains("12 menit"))
    }

    @Test
    fun armyAlphaInteractiveAndRecapScoringParity() {
        val instrument = requireNotNull(catalog.find("army_alpha"))
        val form = requireNotNull(instrument.form)
        assertEquals(12, form.items.size)
        form.items.forEachIndexed { idx, item ->
            assertEquals("ai/psychometrics/images/army_alpha/item_${idx + 1}.png", item.imageAssetPath)
        }

        val perfectAnswers = (0 until 12).associateWith { 0 }
        val (scores12, interp12) = AssessmentEnrichers.enrich("army_alpha", form, perfectAnswers, emptyList())
        assertEquals(12.0, scores12.first { it.label.contains("Total Benar Army Alpha") }.value, 0.001)
        assertEquals(7.0, scores12.first { it.label.contains("Subtes Following Commands") }.value, 0.001)
        assertEquals(5.0, scores12.first { it.label.contains("Subtes Speed & Accuracy") }.value, 0.001)
        assertTrue(interp12.contains("12/12"))

        val (recapScores, recapInterp) = AssessmentEnrichers.enrichArmyAlphaRecap(6, 6)
        assertEquals(12.0, recapScores.first { it.label.contains("Total Benar Army Alpha") }.value, 0.001)
        assertEquals(6.0, recapScores.first { it.label.contains("Bagian 1") }.value, 0.001)
        assertEquals(6.0, recapScores.first { it.label.contains("Bagian 2") }.value, 0.001)
        assertTrue(recapInterp.contains("12/12"))

        val midAnswers = (0 until 12).associateWith { if (it < 7) 0 else 1 }
        val (scores7, _) = AssessmentEnrichers.enrich("army_alpha", form, midAnswers, emptyList())
        assertEquals(7.0, scores7.first { it.label.contains("Total Benar Army Alpha") }.value, 0.001)
    }


    private companion object {
        val IPIP_ITEM = Regex("""\| \d+ \| ([^|]+) \|.*""")
    }
}

private class RecordingAssessmentEngine : OnDeviceLlmEngine {
    var systemInstruction = ""
        private set

    override fun readiness() = LlmReadiness.Ready("jvm-recording")

    override suspend fun generate(request: CounselorGenerationRequest) = CounselorPromptBuilder().prepare(request).let { prompt ->
        systemInstruction = prompt.systemInstruction
        val marker = prompt.citationRegistry.bindings.first().marker
        flowOf(LlmGenerationState.Content("Gunakan hasil untuk refleksi sesuai panduan. $marker"))
    }
}
