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
        val guide = File("../../PsikoTest/Big_Five/IPIP-BFM-50.doc.md").readLines()
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
        assertEquals(4, warteggScores.size) // 4 fungsi dasar kepribadian

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
        assertEquals(35, protocols.size)
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
    fun discInteractiveScoringProducesLine123AndArchetype() {
        val form = requireNotNull(catalog.find("disc")?.form)
        assertEquals(24, form.items.size)
        val answers = mutableMapOf<Int, Int>()
        for (g in 0 until 24) {
            answers[g * 2] = 0
            answers[g * 2 + 1] = 3
        }
        assertTrue(AssessmentScoring.canScore(form, answers))
        val (scores, interpretation) = AssessmentEnrichers.enrich("disc", form, answers, emptyList())
        assertTrue(scores.any { it.label.contains("Grafik 1 (Mask)") })
        assertTrue(scores.any { it.label.contains("Grafik 2 (Core)") })
        assertTrue(scores.any { it.label.contains("Grafik 3 (Mirror)") })
        assertTrue(interpretation.contains("Profil DISC menunjukkan tipe dominan"))
    }

    @Test
    fun mmpiInteractiveScoringDetectsSuiSafetyRiskAndClinicalScales() {
        val form = requireNotNull(catalog.find("mmpi")?.form)
        assertEquals(338, form.items.size)
        val answers = (0 until 338).associateWith { 0 }.toMutableMap()
        answers[92] = 1
        answers[119] = 1
        val (scores, interpretation) = AssessmentEnrichers.enrich("mmpi", form, answers, emptyList())
        val suiScore = scores.first { it.label.contains("SUI") }
        assertEquals(2.0, suiScore.value, 0.001)
        assertTrue(interpretation.contains("PERINGATAN KESELAMATAN KLINIS"))
    }

    @Test
    fun tiu6SpatialScoringMapsToWeightedNormScale() {
        val form = requireNotNull(catalog.find("tiu_6")?.form)
        assertEquals(40, form.items.size)

        // Verify all 40 items have authentic imageAssetPath and binary choices
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
        // Test 1: Full correct (40/40) -> Skala 5 -> BAIK SEKALI
        val answers = keys.mapIndexed { index, k -> index to if (k == 1) 0 else 1 }.toMap()
        val (scores, interpretation) = AssessmentEnrichers.enrich("tiu_6", form, answers, emptyList())
        val rawScore = scores.first { it.label.contains("Total Jawaban Benar") }
        assertEquals(40.0, rawScore.value, 0.001)
        val ws = scores.first { it.label.contains("Skor Baku") }
        assertEquals(5.0, ws.value, 0.001)
        assertTrue(interpretation.contains("BAIK SEKALI"))

        // Test 2: Recap marked sheet (15 + 12 = 27/40) -> Skala 4 -> BAIK
        val (recapScores, recapInterp) = AssessmentEnrichers.enrichTiu6Recap(15, 12)
        val rawRecap = recapScores.first { it.label.contains("Total Jawaban Benar") }
        assertEquals(27.0, rawRecap.value, 0.001)
        val wsRecap = recapScores.first { it.label.contains("Skor Baku") }
        assertEquals(4.0, wsRecap.value, 0.001)
        assertTrue(recapInterp.contains("BAIK (Skala 4)"))
    }

    @Test
    fun tiu5FiguralScoringMapsToWeightedScoreAndNorms() {
        val form = requireNotNull(catalog.find("tiu_5")?.form)
        assertEquals(30, form.items.size)
        // Verify all 30 items have non-null imageAssetPath
        form.items.forEachIndexed { idx, item ->
            val expectedImg = "ai/psychometrics/images/tiu5/tiu5_item_${idx + 1}.webp"
            assertEquals("Item ${idx + 1} must reference its authentic figural diagram", expectedImg, item.imageAssetPath)
            assertEquals(5, item.choices?.size)
        }

        // Test 1: Full correct (30/30) -> WS 18 -> BS
        val keys = listOf(
            2, 5, 4, 4, 5, 4, 5, 1, 5, 3,
            5, 4, 5, 3, 2, 3, 2, 3, 4, 4,
            3, 3, 4, 4, 5, 1, 2, 5, 3, 5
        )
        val fullAnswers = keys.mapIndexed { index, k -> index to k - 1 }.toMap()
        val (scoresFull, interpFull) = AssessmentEnrichers.enrich("tiu_5", form, fullAnswers, emptyList())
        val rawFull = scoresFull.first { it.label.contains("Raw Score") }
        assertEquals(30.0, rawFull.value, 0.001)
        val wsFull = scoresFull.first { it.label.contains("Weighted Score") }
        assertEquals(18.0, wsFull.value, 0.001)
        assertTrue(interpFull.contains("Baik Sekali (BS)"))

        // Test 2: Recap marked sheet (12 + 11 = 23/30) -> WS 14 -> B
        val (recapScores, recapInterp) = AssessmentEnrichers.enrichTiu5Recap(12, 11)
        val rawRecap = recapScores.first { it.label.contains("Total Skor Mentah") }
        assertEquals(23.0, rawRecap.value, 0.001)
        val wsRecap = recapScores.first { it.label.contains("Weighted Score") }
        assertEquals(14.0, wsRecap.value, 0.001)
        assertTrue(recapInterp.contains("Baik (B)"))
    }

    @Test
    fun spmMatrixScoringEvaluatesGradeAndSets() {
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
        assertTrue(interpretation.contains("Grade I"))
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
    fun spmRecapScoringComputesAccurateGradesAndConsistency() {
        val (scoresGrade1, interpGrade1) = AssessmentEnrichers.enrichSpmRecap(12, 12, 12, 12, 12)
        assertEquals(60.0, scoresGrade1.first { it.label.contains("Total Skor Mentah") }.value, 0.001)
        assertTrue(interpGrade1.contains("Grade I"))
        assertTrue(interpGrade1.contains("Konsistensi antar-set menunjukkan progresi wajar"))

        val (scoresGrade3, interpGrade3) = AssessmentEnrichers.enrichSpmRecap(10, 9, 8, 7, 6)
        assertEquals(40.0, scoresGrade3.first { it.label.contains("Total Skor Mentah") }.value, 0.001)
        assertTrue(interpGrade3.contains("Grade III"))

        val (scoresAtypical, interpAtypical) = AssessmentEnrichers.enrichSpmRecap(2, 3, 2, 2, 10)
        assertEquals(19.0, scoresAtypical.first { it.label.contains("Total Skor Mentah") }.value, 0.001)
        assertTrue(interpAtypical.contains("Catatan Konsistensi"))
        assertTrue(interpAtypical.contains("Grade V"))
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
    fun sixteenPfInteractiveScoringComputes16FactorsAndStenNorms() {
        val form = requireNotNull(catalog.find("16pf")?.form)
        assertEquals(105, form.items.size)

        // Simulate answers: answer middle (1) for personality, and all correct for factor B
        val factorBKeys = mapOf(2 to 1, 19 to 2, 36 to 1, 53 to 2, 70 to 0, 87 to 2, 103 to 0, 104 to 1)
        val answers = (0 until 105).associateWith { idx ->
            factorBKeys[idx] ?: 1
        }

        val (scores, interpretation) = AssessmentEnrichers.enrich("16pf", form, answers, emptyList())
        assertEquals(22, scores.size) // 16 primary factors + MD + 5 global factors

        val factorBScore = scores.first { it.label.contains("Faktor B:") }
        assertEquals(10.0, factorBScore.value, 0.001) // 8 correct = Sten 10 in norm table
        assertTrue(factorBScore.meaning.contains("Raw: 8"))

        val exScore = scores.first { it.label.contains("Extraversion") }
        assertTrue(exScore.value in 1.0..10.0)

        val axScore = scores.first { it.label.contains("Anxiety") }
        assertTrue(axScore.value in 1.0..10.0)

        assertTrue(interpretation.contains("16PF Form C Cattell"))
    }

    @Test
    fun armyAlphaInteractiveScoringEvaluates12InstructionsAndScale() {
        val form = requireNotNull(catalog.find("army_alpha")?.form)
        assertEquals(12, form.items.size)

        // Perfect score: all 12 items answered with choice 0 ('a')
        val perfectAnswers = (0 until 12).associateWith { 0 }
        val (scores, interpretation) = AssessmentEnrichers.enrich("army_alpha", form, perfectAnswers, emptyList())

        val totalScore = scores.first { it.label.contains("Total Benar Army Alpha") }
        assertEquals(12.0, totalScore.value, 0.001)

        val scaleScore = scores.first { it.label.contains("Skala Army Alpha (Weighted)") }
        assertEquals(5.0, scaleScore.value, 0.001)
        assertTrue(scaleScore.meaning.contains("Baik Sekali (Skala 5)"))

        val dirScore = scores.first { it.label.contains("Subtes Following Commands") }
        assertEquals(7.0, dirScore.value, 0.001)

        val speedScore = scores.first { it.label.contains("Subtes Speed & Accuracy") }
        assertEquals(5.0, speedScore.value, 0.001)

        assertTrue(interpretation.contains("Baik Sekali (Skala 5)"))

        // Low score: only 3 items correct
        val lowAnswers = (0 until 12).associateWith { if (it < 3) 0 else 1 }
        val (lowScores, lowInterp) = AssessmentEnrichers.enrich("army_alpha", form, lowAnswers, emptyList())
        val lowScale = lowScores.first { it.label.contains("Skala Army Alpha (Weighted)") }
        assertEquals(1.0, lowScale.value, 0.001)
        assertTrue(lowInterp.contains("Kurang Sekali (Skala 1)"))
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

        // Test 1: Perfect score (all 45 correct)
        val perfectAnswers = frtKeys.mapIndexed { idx, k -> idx to k }.toMap()
        val (scores, interpretation) = AssessmentEnrichers.enrich("frt", form, perfectAnswers, emptyList())

        val rsScore = scores.first { it.label.contains("Skor Terkoreksi FRT") }
        assertEquals(45.0, rsScore.value, 0.001)
        val rScore = scores.first { it.label.contains("Jumlah Benar (R)") }
        assertEquals(45.0, rScore.value, 0.001)
        val wScore = scores.first { it.label.contains("Jumlah Salah (W)") }
        assertEquals(0.0, wScore.value, 0.001)
        val oScore = scores.first { it.label.contains("Jumlah Kosong (O)") }
        assertEquals(0.0, oScore.value, 0.001)

        val asoScore = scores.first { it.label.contains("Tahap Asosiatif") }
        assertEquals(15.0, asoScore.value, 0.001)
        val sinScore = scores.first { it.label.contains("Tahap Sintesis") }
        assertEquals(15.0, sinScore.value, 0.001)
        val anaScore = scores.first { it.label.contains("Tahap Analisis") }
        assertEquals(15.0, anaScore.value, 0.001)

        assertTrue(interpretation.contains("Very Superior"))

        // Test 2: Partial answers with guessing penalty
        // 20 correct, 8 wrong, 17 omitted -> RS = 20 - 0.25 * 8 = 18.0 (Average)
        val partialAnswers = mutableMapOf<Int, Int>()
        for (i in 0 until 20) partialAnswers[i] = frtKeys[i]
        for (i in 20 until 28) partialAnswers[i] = (frtKeys[i] + 1) % 6
        // items 28..44 not answered (omitted)

        val (pScores, pInterp) = AssessmentEnrichers.enrich("frt", form, partialAnswers, emptyList())
        val pRsScore = pScores.first { it.label.contains("Skor Terkoreksi FRT") }
        assertEquals(18.0, pRsScore.value, 0.001)
        val pR = pScores.first { it.label.contains("Jumlah Benar (R)") }
        assertEquals(20.0, pR.value, 0.001)
        val pW = pScores.first { it.label.contains("Jumlah Salah (W)") }
        assertEquals(8.0, pW.value, 0.001)
        val pO = pScores.first { it.label.contains("Jumlah Kosong (O)") }
        assertEquals(17.0, pO.value, 0.001)
        assertTrue(pInterp.contains("Average (Rata-Rata)"))
    }

    @Test
    fun cfit2aInteractiveScoringEvaluatesFourSubtestsAndIq() {
        val form = requireNotNull(catalog.find("cfit_2a")?.form)
        assertEquals(46, form.items.size)
        assertTrue(form.items.all { it.imageAssetPath != null && it.imageAssetPath.startsWith("ai/psychometrics/images/cfit2a/") })

        val subtest1Keys = listOf(2, 3, 0, 2, 1, 4, 1, 2, 2, 2, 3, 0)
        val subtest2Keys = listOf(1, 2, 3, 0, 2, 2, 0, 4, 3, 2, 2, 2, 0, 3)
        val subtest3Keys = listOf(0, 2, 1, 4, 2, 0, 1, 3, 4, 0, 1, 1)
        val subtest4Keys = listOf(2, 1, 0, 3, 2, 2, 0, 1)
        val allKeys = subtest1Keys + subtest2Keys + subtest3Keys + subtest4Keys

        // Test 1: Perfect score (46/46)
        val perfectAnswers = allKeys.mapIndexed { idx, k -> idx to k }.toMap()
        val (scores, interpretation) = AssessmentEnrichers.enrich("cfit_2a", form, perfectAnswers, emptyList())

        val totalScore = scores.first { it.label.contains("Total Skor Mentah CFIT 2A") }
        assertEquals(46.0, totalScore.value, 0.001)

        val iqScore = scores.first { it.label.contains("Estimasi IQ Cattell") }
        assertEquals(176.0, iqScore.value, 0.001)
        assertTrue(iqScore.meaning.contains("Very Superior"))

        val s1 = scores.first { it.label.contains("Subtes 1: Seriasi") }
        assertEquals(12.0, s1.value, 0.001)
        val s2 = scores.first { it.label.contains("Subtes 2: Klasifikasi") }
        assertEquals(14.0, s2.value, 0.001)
        val s3 = scores.first { it.label.contains("Subtes 3: Matriks") }
        assertEquals(12.0, s3.value, 0.001)
        val s4 = scores.first { it.label.contains("Subtes 4: Persyaratan Titik") }
        assertEquals(8.0, s4.value, 0.001)

        assertTrue(interpretation.contains("Total Skor Mentah 46/46"))

        // Test 2: Average score (24/46 -> IQ 109)
        val avgAnswers = (0 until 24).associateWith { allKeys[it] }
        val (avgScores, avgInterp) = AssessmentEnrichers.enrich("cfit_2a", form, avgAnswers, emptyList())
        val avgIq = avgScores.first { it.label.contains("Estimasi IQ Cattell") }
        assertEquals(109.0, avgIq.value, 0.001)
        assertTrue(avgInterp.contains("Average"))
    }

    @Test
    fun cfit3aInteractiveScoringEvaluatesFourSubtestsIncludingMultiSelectClassification() {
        val form = requireNotNull(catalog.find("cfit_3a")?.form)
        assertEquals(50, form.items.size)
        assertTrue(form.items.all { it.imageAssetPath != null && it.imageAssetPath.startsWith("ai/psychometrics/images/cfit3a/") })

        // Check Subtest 2 is marked multiSelect
        assertTrue((13..26).all { form.items[it].multiSelect && form.items[it].maxSelections == 2 })

        val subtest1Keys = listOf(1, 2, 1, 3, 4, 1, 3, 1, 5, 2, 1, 1, 4)
        val subtest2Masks = listOf(18, 17, 9, 20, 18, 9, 18, 18, 9, 10, 17, 12, 6, 3)
        val subtest3Keys = listOf(4, 4, 4, 1, 2, 3, 4, 4, 0, 0, 5, 2, 2)
        val subtest4Keys = listOf(1, 0, 3, 3, 0, 1, 2, 3, 0, 3)

        // Test 1: Perfect score (50/50 -> IQ 183)
        val perfectAnswers = mutableMapOf<Int, Int>()
        subtest1Keys.forEachIndexed { i, k -> perfectAnswers[i] = k }
        subtest2Masks.forEachIndexed { i, m -> perfectAnswers[13 + i] = m }
        subtest3Keys.forEachIndexed { i, k -> perfectAnswers[27 + i] = k }
        subtest4Keys.forEachIndexed { i, k -> perfectAnswers[40 + i] = k }

        val (scores, interpretation) = AssessmentEnrichers.enrich("cfit_3a", form, perfectAnswers, emptyList())

        val totalScore = scores.first { it.label.contains("Total Skor Mentah CFIT 3A") }
        assertEquals(50.0, totalScore.value, 0.001)

        val iqScore = scores.first { it.label.contains("Standard IQ Cattell") }
        assertEquals(183.0, iqScore.value, 0.001)
        assertTrue(iqScore.meaning.contains("Genius"))

        val s1 = scores.first { it.label.contains("Subtes 1: Deret") }
        assertEquals(13.0, s1.value, 0.001)
        val s2 = scores.first { it.label.contains("Subtes 2: Klasifikasi") }
        assertEquals(14.0, s2.value, 0.001)
        val s3 = scores.first { it.label.contains("Subtes 3: Matriks") }
        assertEquals(13.0, s3.value, 0.001)
        val s4 = scores.first { it.label.contains("Subtes 4: Persyaratan Topologi") }
        assertEquals(10.0, s4.value, 0.001)

        assertTrue(interpretation.contains("50/50"))

        // Test 2: Moderate score (22/50 -> IQ 100)
        val modAnswers = mutableMapOf<Int, Int>()
        // 13 correct in S1, 9 correct in S2
        subtest1Keys.forEachIndexed { i, k -> modAnswers[i] = k }
        subtest2Masks.take(9).forEachIndexed { i, m -> modAnswers[13 + i] = m }

        val (modScores, modInterp) = AssessmentEnrichers.enrich("cfit_3a", form, modAnswers, emptyList())
        val modTotal = modScores.first { it.label.contains("Total Skor Mentah CFIT 3A") }
        assertEquals(22.0, modTotal.value, 0.001)
        val modIq = modScores.first { it.label.contains("Standard IQ Cattell") }
        assertEquals(100.0, modIq.value, 0.001)
        assertTrue(modInterp.contains("Average"))
    }

    @Test
    fun cfit2aRecapEnrichmentMatchesInteractiveScoring() {
        val (scores, interp) = AssessmentEnrichers.enrichCfit2aRecap(10, 11, 9, 6)
        val totalScore = scores.first { it.label.contains("Total Skor Mentah CFIT 2A") }
        assertEquals(36.0, totalScore.value, 0.001)

        val iqScore = scores.first { it.label.contains("Estimasi IQ Cattell") }
        assertEquals(145.0, iqScore.value, 0.001)
        assertTrue(iqScore.meaning.contains("Very Superior"))
        assertTrue(interp.contains("36/46"))
        assertTrue(interp.contains("145"))
    }

    @Test
    fun cfit3aRecapEnrichmentMatchesInteractiveScoring() {
        val (scores, interp) = AssessmentEnrichers.enrichCfit3aRecap(10, 9, 8, 7)
        val totalScore = scores.first { it.label.contains("Total Skor Mentah CFIT 3A") }
        assertEquals(34.0, totalScore.value, 0.001)

        val iqScore = scores.first { it.label.contains("Standard IQ Cattell") }
        assertEquals(137.0, iqScore.value, 0.001)
        assertTrue(iqScore.meaning.contains("Superior"))
        assertTrue(interp.contains("34/50"))
        assertTrue(interp.contains("137"))
    }

    @Test
    fun assessmentDiscussionPromptGeneratesRichCounselingHandoff() {
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
                AssessmentScore("Kelelahan kerja", 85.71, 0.0, 100.0, "Tinggi")
            ),
            interpretation = "Tingkat keletihan dan kejenuhan kerja berada pada level Tinggi."
        )

        val prompt = result.toCounselorDiscussionPrompt()
        assertTrue(prompt.contains("Copenhagen Burnout Inventory"))
        assertTrue(prompt.contains("Kelelahan personal: 87,5 (0–100)") || prompt.contains("Kelelahan personal: 87.5"))
        assertTrue(prompt.contains("Tingkat keletihan dan kejenuhan kerja berada pada level Tinggi."))
        assertTrue(prompt.contains("Berdasarkan hasil asesmen ini dan materi konseling yang relevan"))

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
    fun enneagramEnrichmentAccuratelyIdentifiesDominantTypeAndWing() {
        val form = requireNotNull(catalog.find("enneagram")?.form)
        assertEquals(180, form.items.size)
        assertEquals(180, form.minimumAnswers)

        // Type 3 items: [0, 5, 12, 20, 50, 55, 66, 72, 74, 85, 89, 111, 113, 134, 138, 142, 150, 162, 168, 173]
        // Give Type 3 answers = 3 (score 60/60). Give Type 2 answers = 2 (score 40/60). Others 0.
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
        val dominantScore = scores.first { it.label.contains("Tipe Dominan") }
        assertEquals(3.0, dominantScore.value, 0.001)
        assertTrue(dominantScore.label.contains("3w2"))
        assertTrue(dominantScore.meaning.contains("3w2"))

        val s3 = scores.first { it.label.contains("Tipe 3") }
        assertEquals(60.0, s3.value, 0.001)
        val s2 = scores.first { it.label.contains("Tipe 2") }
        assertEquals(40.0, s2.value, 0.001)

        assertTrue(interpretation.contains("Tipe 3"))
        assertTrue(interpretation.contains("The Achiever"))
        assertTrue(interpretation.contains("3w2"))
    }

    @Test
    fun istEnrichmentCalculatesAmthauerIqAndClustersWithFullCorrectAnswers() {
        val form = requireNotNull(catalog.find("ist")?.form)
        assertEquals(176, form.items.size)
        assertEquals(176, form.minimumAnswers)

        // Verify image assets exist for FA (117-136) and WU (137-156)
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
        for (i in 0 until 16) {
            answers[60 + i] = 0 // Option A gives 2 points -> 32 points total -> RW 20
        }
        raKeys.forEachIndexed { i, k -> answers[76 + i] = k }
        zrKeys.forEachIndexed { i, k -> answers[96 + i] = k }
        faKeys.forEachIndexed { i, k -> answers[116 + i] = k }
        wuKeys.forEachIndexed { i, k -> answers[136 + i] = k }
        meKeys.forEachIndexed { i, k -> answers[156 + i] = k }

        val (scores, interpretation) = AssessmentEnrichers.enrich("ist", form, answers, emptyList())

        val iqScore = scores.first { it.label.contains("Skor IQ Amthauer") }
        assertTrue("IQ must be >= 135 for all correct, was ${iqScore.value}", iqScore.value >= 135.0)
        assertTrue(iqScore.meaning.contains("Superior"))

        val totalSw = scores.first { it.label.contains("Total Standard Score") }
        assertTrue("Total SW must be > 1100, was ${totalSw.value}", totalSw.value > 1100.0)

        val verbal = scores.first { it.label.contains("Klaster Verbal") }
        assertTrue(verbal.value >= 120.0)

        val numerik = scores.first { it.label.contains("Klaster Numerik") }
        assertTrue(numerik.value >= 120.0)

        val spasial = scores.first { it.label.contains("Klaster Figural-Spasial") }
        assertTrue(spasial.value >= 120.0)

        assertTrue(interpretation.contains("Intelligenz-Struktur-Test"))
        assertTrue(interpretation.contains("Superior"))
    }

    @Test
    fun kuderEnrichmentCalculatesValidityAndTenScales() {
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

        val vScore = scores.first { it.label.contains("Validitas") }
        assertTrue(vScore.value in 0.0..63.0)

        val outdoorScore = scores.first { it.label.contains("Outdoor") }
        assertTrue(outdoorScore.value in 0.0..100.0)

        assertTrue(interpretation.contains("Kuder"))
        assertTrue(interpretation.contains("Tiga area minat vokasional"))
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

        val (scores, interpretation) = AssessmentEnrichers.enrich("kraepelin", form, answers, emptyList())
        assertEquals(4, scores.size)

        val panker = scores.first { it.label.contains("PANKER") }
        assertEquals(16.2, panker.value, 0.01)

        val tianker = scores.first { it.label.contains("TIANKER") }
        assertTrue("Tianker should be > 0, was ${tianker.value}", tianker.value > 0.0)

        val janker = scores.first { it.label.contains("JANKER") }
        assertTrue("Janker should be > 0, was ${janker.value}", janker.value > 0.0)

        val hanker = scores.first { it.label.contains("HANKER") }
        assertTrue("Hanker slope should be positive, was ${hanker.value}", hanker.value > 0.0)

        assertTrue(interpretation.contains("Kraepelin"))
        assertTrue(interpretation.contains("Panker"))
    }

    @Test
    fun multipleIntelligencesEnrichmentCalculatesGardnerDimensions() {
        val form = requireNotNull(catalog.find("mit")?.form)
        assertEquals(56, form.items.size)
        assertEquals(56, form.minimumAnswers)

        val answers = mutableMapOf<Int, Int>()
        for (i in 0 until 56) {
            answers[i] = if (i in 7..13) 3 else 1
        }

        assertTrue(AssessmentScoring.canScore(form, answers))

        val (scores, interpretation) = AssessmentEnrichers.enrich("mit", form, answers, emptyList())
        assertEquals(8, scores.size)

        val logicalScore = scores.first { it.label.contains("Logis-Matematis") }
        assertEquals(28.0, logicalScore.value, 0.01)
        assertTrue(logicalScore.meaning.contains("Tinggi"))

        val linguisticScore = scores.first { it.label.contains("Linguistik") }
        assertEquals(14.0, linguisticScore.value, 0.01)

        assertTrue(interpretation.contains("Multiple Intelligences"))
        assertTrue(interpretation.contains("Logis-Matematis"))
    }

    @Test
    fun msaiEnrichmentCalculatesCvfQuadrantsAndCompetencies() {
        val form = requireNotNull(catalog.find("msai")?.form)
        assertEquals(60, form.items.size)
        assertEquals(1, form.minimumAnswers)

        val answers = mutableMapOf<Int, Int>()
        val clanItems = setOf(5, 20, 24, 25, 47, 1, 13, 23, 48, 50, 12, 18, 21, 22, 49)
        for (i in 0 until 60) {
            val qNum = i + 1
            answers[i] = if (qNum in clanItems) 4 else 1
        }

        assertTrue(AssessmentScoring.canScore(form, answers))

        val (scores, interpretation) = AssessmentEnrichers.enrich("msai", form, answers, emptyList())
        assertTrue(scores.size >= 16)
        val dominant = scores.first()
        assertTrue(dominant.label.contains("Clan"))
        assertTrue(interpretation.contains("Clan"))
        assertTrue(interpretation.contains("Competing Values Framework"))
    }

    @Test
    fun msdtEnrichmentCalculatesReddinMatrixAndLeadershipStyles() {
        val form = requireNotNull(catalog.find("msdt")?.form)
        assertEquals(64, form.items.size)

        val answers = (0 until 64).associateWith { 0 }
        assertTrue(AssessmentScoring.canScore(form, answers))

        val (scores, interpretation) = AssessmentEnrichers.enrich("msdt", form, answers, emptyList())
        assertTrue(scores.size >= 10)
        assertTrue(scores.any { it.label.contains("Task Orientation") })
        assertTrue(scores.any { it.label.contains("Relationship Orientation") })
        assertTrue(scores.any { it.label.contains("Effectiveness") })
        assertTrue(interpretation.contains("Reddin"))
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

        val answers = mapOf(
            0 to 20,
            1 to 22,
            2 to 25,
            3 to 24,
            4 to 26
        )
        assertTrue(AssessmentScoring.canScore(form, answers))

        val (scores, interpretation) = AssessmentEnrichers.enrich("pauli", form, answers, emptyList())
        assertTrue(scores.size >= 5)
        val totalScore = scores.first { it.label.contains("Hasil Kerja Total") }
        assertEquals(117.0, totalScore.value, 0.01)
        assertTrue(interpretation.contains("Pauli"))
    }

    @Test
    fun personalityTestEpqrCalculatesDimensionsAndLieScale() {
        val form = requireNotNull(catalog.find("personality_test")?.form)
        assertEquals(48, form.items.size)

        val answers = (0 until 48).associateWith { 0 }
        assertTrue(AssessmentScoring.canScore(form, answers))

        val (scores, interpretation) = AssessmentEnrichers.enrich("personality_test", form, answers, emptyList())
        assertTrue(scores.size >= 5)
        assertTrue(scores.any { it.label.contains("Extraversion") })
        assertTrue(scores.any { it.label.contains("Neuroticism") })
        assertTrue(scores.any { it.label.contains("Psychoticism") })
        assertTrue(scores.any { it.label.contains("Lie Scale") })
        assertTrue(interpretation.contains("EPQ-R"))
    }

    @Test
    fun warteggEnrichmentCalculatesFourFunctionsAndDrawingSequence() {
        val form = requireNotNull(catalog.find("wartegg")?.form)
        assertEquals(8, form.items.size)

        val answers = mapOf(
            0 to 10,
            1 to 22,
            2 to 34,
            3 to 41,
            4 to 53,
            5 to 64,
            6 to 74,
            7 to 84
        )
        assertTrue(AssessmentScoring.canScore(form, answers))

        val (scores, interpretation) = AssessmentEnrichers.enrich("wartegg", form, answers, emptyList())
        assertTrue(scores.size >= 6)
        assertTrue(scores.any { it.label.contains("Fungsi Emosi") })
        assertTrue(scores.any { it.label.contains("Fungsi Imajinasi") })
        assertTrue(scores.any { it.label.contains("Fungsi Intelektual") })
        assertTrue(scores.any { it.label.contains("Fungsi Aktivitas") })
        assertTrue(scores.any { it.label.contains("Keteraturan Urutan") })
        assertTrue(scores.any { it.label.contains("Kotak Diselesaikan") })
        assertTrue(interpretation.contains("Wartegg"))
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

        val (scores, interpretation) = AssessmentEnrichers.enrich("rmib", form, answers, emptyList())
        assertEquals(13, scores.size)
        val invariantScore = scores.first { it.label.contains("Total Invarian RMIB") }
        assertEquals(702.0, invariantScore.value, 0.001)
        assertTrue(interpretation.contains("Rothwell–Miller"))
        assertTrue(interpretation.contains("702"))
    }

    @Test
    fun vrtEnrichmentCalculatesSevenSubtestsAndVerbalReasoningLevel() {
        val form = requireNotNull(catalog.find("vrt")?.form)
        assertEquals(49, form.items.size)

        val answers = (0 until 49).associateWith { 0 }
        assertTrue(AssessmentScoring.canScore(form, answers))

        val (scores, interpretation) = AssessmentEnrichers.enrich("vrt", form, answers, emptyList())
        assertEquals(8, scores.size)
        val totalScore = scores.first { it.label.contains("Total Skor Penalaran Verbal") }
        assertEquals(98.0, totalScore.value, 0.001)
        assertTrue(interpretation.contains("VRT"))
        assertTrue(interpretation.contains("Sangat Tinggi"))
    }

    @Test
    fun mseaiEnrichmentCalculatesFourDomainsAndAdjustmentLevel() {
        val form = requireNotNull(catalog.find("mseai")?.form)
        assertEquals(24, form.items.size)

        val answers = (0 until 24).associateWith { 4 }
        assertTrue(AssessmentScoring.canScore(form, answers))

        val (scores, interpretation) = AssessmentEnrichers.enrich("mseai", form, answers, emptyList())
        assertEquals(5, scores.size)
        val totalScore = scores.first { it.label.contains("Total Skor Adaptasi MSEAI") }
        assertEquals(120.0, totalScore.value, 0.001)
        assertTrue(interpretation.contains("MSEAI"))
        assertTrue(interpretation.contains("Sangat Baik"))
    }

    @Test
    fun eppsEnrichmentCalculatesFifteenNeedsAndConsistencyWithInvariant210() {
        val form = requireNotNull(catalog.find("epps")?.form)
        assertEquals(225, form.items.size)

        val allA = (0 until 225).associateWith { 0 }
        assertTrue(AssessmentScoring.canScore(form, allA))

        val (scoresA, interpA) = AssessmentEnrichers.enrich("epps", form, allA, emptyList())
        assertEquals(17, scoresA.size)
        val totalScoreA = scoresA.first { it.label.contains("Total Raw Score EPPS") }
        assertEquals(210.0, totalScoreA.value, 0.001)
        val consScoreA = scoresA.first { it.label.contains("Konsistensi Respons") }
        assertEquals(15.0, consScoreA.value, 0.001)
        assertTrue(interpA.contains("EPPS"))
        assertTrue(interpA.contains("210"))

        val alt = (0 until 225).associateWith { it % 2 }
        val (scoresAlt, interpAlt) = AssessmentEnrichers.enrich("epps", form, alt, emptyList())
        val totalScoreAlt = scoresAlt.first { it.label.contains("Total Raw Score EPPS") }
        assertEquals(210.0, totalScoreAlt.value, 0.001)
    }

    @Test
    fun rorschachEnrichmentCalculatesExnerStructuralSummaryAndConstellations() {
        val instrument = requireNotNull(catalog.find("rorschach"))
        val form = requireNotNull(instrument.form)
        assertEquals(10, form.items.size)

        val standardAnswers = (0 until 10).associateWith { idx ->
            val det = when (idx) {
                1, 7, 9 -> 4 // FC
                2, 6 -> 1    // M
                5 -> 8       // T
                8 -> 5       // CF
                else -> 0    // F
            }
            (0 * 100000) + (det * 1000) + (1 * 100) + (1 * 10) + 0
        }
        assertTrue(AssessmentScoring.canScore(form, standardAnswers))

        val (scores, interp) = AssessmentEnrichers.enrich("rorschach", form, standardAnswers, emptyList())
        assertEquals(12, scores.size)
        val eb = scores.first { it.label.contains("EB") }
        val ea = scores.first { it.label.contains("EA") }
        val es = scores.first { it.label.contains("es") }
        val dScore = scores.first { it.label.contains("D-Score") }
        val lambda = scores.first { it.label.contains("Lambda") }
        val xPlus = scores.first { it.label.contains("X+%") }
        val sCon = scores.first { it.label.contains("S-CON") }
        val depi = scores.first { it.label.contains("DEPI") }
        val cdi = scores.first { it.label.contains("CDI") }
        val pti = scores.first { it.label.contains("PTI") }

        assertTrue(eb.meaning.contains("EB"))
        assertTrue(xPlus.value >= 70.0)
        assertTrue(interp.contains("Rorschach"))
        assertTrue(interp.contains("Exner Comprehensive System"))
    }

    @Test
    fun wptLoadsAuthenticItemsAndCalculatesIqScores() {
        val instrument = requireNotNull(catalog.find("wpt"))
        val form = requireNotNull(instrument.form)
        assertEquals(50, form.items.size)

        // Item 1 verification
        assertEquals("Bulan lalu pada awal tahun ini adalah...", form.items[0].text)
        assertEquals(5, requireNotNull(form.items[0].choices).size)

        // Diagram items verification
        assertEquals("ai/psychometrics/wpt_item_7.webp", form.items[6].imageAssetPath)
        assertEquals("ai/psychometrics/wpt_item_38.webp", form.items[37].imageAssetPath)
        assertEquals("ai/psychometrics/wpt_item_42.webp", form.items[41].imageAssetPath)
        assertEquals("ai/psychometrics/wpt_item_49.webp", form.items[48].imageAssetPath)

        // Test all correct answers (RS = 50 -> IQ = 146 -> Genius)
        val allCorrectKeys = listOf(
            3, 1, 2, 1, 2, 0, 2, 0, 0, 3,
            2, 2, 0, 1, 1, 1, 0, 1, 2, 0,
            1, 1, 1, 1, 2, 0, 0, 2, 1, 1,
            1, 0, 2, 1, 0, 2, 0, 1, 1, 1,
            0, 1, 3, 1, 2, 1, 2, 2, 1, 2
        )
        val perfectAnswers = allCorrectKeys.mapIndexed { index, ans -> index to ans }.toMap()
        val (scores50, interp50) = AssessmentEnrichers.enrich("wpt", form, perfectAnswers, emptyList())
        val rsScore = scores50.first { it.label.contains("Total Skor Mentah") }
        val iqScore = scores50.first { it.label.contains("Estimasi IQ Wonderlic") }
        val benchScore = scores50.first { it.label.contains("Benchmark Jabatan") }
        assertEquals(50.0, rsScore.value, 0.001)
        assertEquals(146.0, iqScore.value, 0.001)
        assertTrue(iqScore.meaning.contains("Genius"))
        assertTrue(benchScore.meaning.contains("Tinggi / Analitis Kompleks"))
        assertTrue(interp50.contains("Genius"))
        assertTrue(interp50.contains("50/50"))

        // Test average answers (e.g. exactly 21 correct -> IQ 102 -> Average)
        val avgAnswers = mutableMapOf<Int, Int>()
        for (i in 0 until 50) {
            avgAnswers[i] = if (i < 21) allCorrectKeys[i] else 4 // Wrong answer for rest
        }
        val (scores21, interp21) = AssessmentEnrichers.enrich("wpt", form, avgAnswers, emptyList())
        val rs21 = scores21.first { it.label.contains("Total Skor Mentah") }
        val iq21 = scores21.first { it.label.contains("Estimasi IQ Wonderlic") }
        val bench21 = scores21.first { it.label.contains("Benchmark Jabatan") }
        assertEquals(21.0, rs21.value, 0.001)
        assertEquals(102.0, iq21.value, 0.001)
        assertTrue(iq21.meaning.contains("Average"))
        assertTrue(bench21.meaning.contains("Rata-Rata Operasional"))
        assertTrue(interp21.contains("Average"))
        assertTrue(interp21.contains("21/50"))
    }

    @Test
    fun armyAlphaInteractiveAndRecapScoringParity() {
        val instrument = requireNotNull(catalog.find("army_alpha"))
        val form = requireNotNull(instrument.form)
        assertEquals(12, form.items.size)
        form.items.forEachIndexed { idx, item ->
            assertEquals("ai/psychometrics/images/army_alpha/item_${idx + 1}.png", item.imageAssetPath)
        }

        // Test perfect score 12/12
        val perfectAnswers = (0 until 12).associateWith { 0 }
        val (scores12, interp12) = AssessmentEnrichers.enrich("army_alpha", form, perfectAnswers, emptyList())
        val total12 = scores12.first { it.label.contains("Total Benar Army Alpha") }
        val scale12 = scores12.first { it.label.contains("Skala Army Alpha") }
        val dir12 = scores12.first { it.label.contains("Subtes Following Commands") }
        val speed12 = scores12.first { it.label.contains("Subtes Speed & Accuracy") }

        assertEquals(12.0, total12.value, 0.001)
        assertEquals(5.0, scale12.value, 0.001)
        assertEquals(7.0, dir12.value, 0.001)
        assertEquals(5.0, speed12.value, 0.001)
        assertTrue(scale12.meaning.contains("Baik Sekali"))
        assertTrue(interp12.contains("12/12"))

        // Test recap parity: part1=6, part2=6 -> total 12
        val (recapScores, recapInterp) = AssessmentEnrichers.enrichArmyAlphaRecap(6, 6)
        val recapTotal = recapScores.first { it.label.contains("Total Benar Army Alpha") }
        val recapScale = recapScores.first { it.label.contains("Skala Army Alpha") }
        assertEquals(12.0, recapTotal.value, 0.001)
        assertEquals(5.0, recapScale.value, 0.001)
        assertTrue(recapInterp.contains("12/12"))

        // Test average score 7/12 (Scale 3 Rata-Rata)
        val midAnswers = (0 until 12).associateWith { if (it < 7) 0 else 1 }
        val (scores7, interp7) = AssessmentEnrichers.enrich("army_alpha", form, midAnswers, emptyList())
        val scale7 = scores7.first { it.label.contains("Skala Army Alpha") }
        assertEquals(3.0, scale7.value, 0.001)
        assertTrue(scale7.meaning.contains("Rata-Rata"))
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
