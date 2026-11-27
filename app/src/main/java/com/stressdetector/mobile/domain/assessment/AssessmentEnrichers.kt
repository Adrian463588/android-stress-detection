package com.stressdetector.mobile.domain.assessment

import java.util.Locale

@Suppress("MagicNumber", "LongMethod", "CyclomaticComplexMethod", "LargeClass")
object AssessmentEnrichers {

    fun enrich(
        id: String,
        form: AssessmentForm,
        answers: Map<Int, Int>,
        rawScores: List<AssessmentScore>
    ): Pair<List<AssessmentScore>, String> = enrichDetailed(id, form, answers, rawScores).let { it.scores to it.interpretation }

    fun enrichDetailed(
        id: String,
        form: AssessmentForm,
        answers: Map<Int, Int>,
        rawScores: List<AssessmentScore>
    ): EnrichedScoringOutcome = when (id) {
        "bdi" -> enrichBdi(answers)
        "srq-29" -> enrichSrq29(answers)
        "vak" -> outcomeRaw(id, enrichVak(answers))
        "adversity_quotient" -> outcomeRaw(id, enrichAq(answers))
        "big_five" -> outcomeVerified(id, enrichBigFive(rawScores))
        "cbi" -> outcomeRaw(id, enrichCbi(rawScores))
        "disc" -> outcomeRaw(id, enrichDisc(answers))
        "mmpi" -> enrichMmpi(answers, form.items.size)
        "tiu_6" -> outcomeRaw(id, enrichTiu6(answers))
        "spm" -> outcomeRaw(id, enrichSpm(answers))
        "mbti" -> outcomeRaw(id, enrichMbti(answers))
        "tiu_5" -> outcomeRaw(id, enrichTiu5(answers))
        "holland" -> outcomeRaw(id, enrichHolland(answers))
        "wpt" -> outcomeRaw(id, enrichWpt(answers))
        "16pf" -> outcomeRaw(id, enrich16pf(answers))
        "army_alpha" -> outcomeRaw(id, enrichArmyAlpha(answers))
        "frt" -> outcomeRaw(id, enrichFrt(answers))
        "cfit_2a" -> outcomeRaw(id, enrichCfit2a(answers))
        "cfit_3a" -> outcomeRaw(id, enrichCfit3a(answers))
        "enneagram" -> outcomeRaw(id, enrichEnneagram(answers))
        "ist" -> outcomeRaw(id, enrichIst(answers))
        "kuder" -> outcomeRaw(id, enrichKuder(answers))
        "kraepelin" -> enrichKraepelinDetailed(answers)
        "mit" -> outcomeRaw(id, enrichMit(answers))
        "msai" -> enrichMsaiDetailed(answers)
        "msdt" -> enrichMsdtDetailed(answers)
        "papikostick" -> outcomeRaw(id, enrichPapikostick(answers))
        "pauli" -> enrichPauliDetailed(answers)
        "personality_test" -> outcomeRaw(id, enrichPersonalityTest(answers))
        "wartegg" -> enrichWarteggDetailed(answers)
        "rmib" -> enrichRmibDetailed(answers)
        "vrt" -> enrichVrtDetailed(answers)
        "mseai" -> enrichMseaiDetailed(answers, rawScores)
        "epps" -> enrichEppsDetailed(answers)
        "rorschach" -> enrichRorschachDetailed(answers)
        "cbi_tbi" -> EnrichedScoringOutcome(
            scores = rawScores,
            interpretation = "CBI-TBI (Eksploratif Purwarupa): " + rawScores.joinToString("; ") { "${it.label}=${it.displayValue()}" } +
                ". Hasil mencerminkan beban kelelahan yang dilaporkan tanpa klaim normatif resmi.",
            scoringStatus = InstrumentImplementationStatus.EXPLORATORY,
            validityStatus = AssessmentValidityStatus.VALID,
            normReference = PsychometricInstrumentRegistry.specFor("cbi_tbi").normStatus
        )
        "esj" -> EnrichedScoringOutcome(
            scores = rawScores,
            interpretation = "ESJ (Eksploratif Purwarupa): " + rawScores.joinToString("; ") { "${it.label}=${it.displayValue()}" } +
                ". Digunakan sebagai bahan refleksi konseling purwarupa.",
            scoringStatus = InstrumentImplementationStatus.EXPLORATORY,
            validityStatus = AssessmentValidityStatus.VALID,
            normReference = PsychometricInstrumentRegistry.specFor("esj").normStatus
        )
        "htp", "dap", "baum" -> EnrichedScoringOutcome(
            scores = rawScores,
            interpretation = "${form.title} (${answers.size}/${form.items.size} panel dicatat). " + form.interpretationNote,
            scoringStatus = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
            validityStatus = if (answers.isNotEmpty()) AssessmentValidityStatus.VALID else AssessmentValidityStatus.QUESTIONABLE,
            reviewStatus = ExaminerReviewStatus.PENDING,
            normReference = PsychometricInstrumentRegistry.specFor(id).normStatus
        )
        else -> EnrichedScoringOutcome(
            scores = rawScores,
            interpretation = form.interpretationNote,
            scoringStatus = PsychometricInstrumentRegistry.specFor(id).implementationStatus,
            normReference = PsychometricInstrumentRegistry.specFor(id).normStatus
        )
    }

    fun scoreWarteggFromDrafts(
        drafts: List<WarteggBoxDraft>,
        multimodalSummary: String? = null
    ): EnrichedScoringOutcome {
        val completed = drafts.count { it.hasContent() }
        val ordered = drafts.filter { it.drawOrder != null }.sortedBy { it.drawOrder }
        val seqText = if (ordered.isNotEmpty()) {
            ordered.joinToString(" -> ") { "K${it.boxNumber}" }
        } else {
            "Belum ada urutan tercatat"
        }
        val totalRevisions = drafts.sumOf { it.revisionCount.coerceAtLeast(0) }
        val overallTelemetry = ProjectiveTelemetryExtractor.extractMetrics(drafts.map { it.strokes }, totalRevisions)
        val totalDuration = drafts.sumOf { it.durationSeconds.coerceAtLeast(0) }
        val subjectiveMarks = drafts.count { it.valence != null || it.difficulty != null }
        val boxCoverages = drafts.filter { it.strokes.isNotEmpty() }.map { box ->
            ProjectiveTelemetryExtractor.extractMetrics(listOf(box.strokes)).spaceCoveragePercent
        }
        val avgCoverage = boxCoverages.takeIf { it.isNotEmpty() }?.average()?.toDouble() ?: 0.0

        val observations = mutableListOf(
            AssessmentObservation("wartegg_completed", "Kotak Diselesaikan", "$completed/8"),
            AssessmentObservation("wartegg_sequence", "Urutan Pengerjaan", seqText),
            AssessmentObservation(
                key = "wartegg_telemetry_summary",
                label = "Telemetri Goresan & Stylus",
                value = overallTelemetry.toFormattedSummary(),
                pressure = overallTelemetry.meanPressure
            )
        )
        drafts.sortedBy { it.boxNumber }.forEach { box ->
            val boxMetrics = ProjectiveTelemetryExtractor.extractMetrics(listOf(box.strokes))
            val tags = listOfNotNull(
                box.valence?.let { "Valensi: $it" },
                box.difficulty?.let { "Kemudahan: $it" },
                if (boxMetrics.strokeCount > 0) "Arah: ${boxMetrics.dominantDirection}" else null,
                if (boxMetrics.strokeCount > 0) String.format(Locale.US, "Ruang: %.0f%%", boxMetrics.spaceCoveragePercent) else null,
                if (boxMetrics.stylusUsed) "Stylus" else null
            )
            val titlePart = box.title.takeIf(String::isNotBlank)?.let { "Judul: \"$it\"" } ?: "Tanpa judul"
            observations += AssessmentObservation(
                key = "wartegg_box_${box.boxNumber}",
                label = "Kotak ${box.boxNumber}",
                value = titlePart,
                durationSeconds = box.durationSeconds.takeIf { it > 0 },
                orderIndex = box.drawOrder,
                pressure = boxMetrics.meanPressure,
                tags = tags
            )
        }
        val scores = listOf(
            AssessmentScore("Kotak Diselesaikan", completed.toDouble(), 0.0, 8.0, "Kelengkapan kotak terisi dari 8 stimulus."),
            AssessmentScore("Cakupan Ruang Rata-rata (%)", avgCoverage, 0.0, 100.0, "Persentase area bounding-box goresan rata-rata (observasi spasial, bukan skor sifat)."),
            AssessmentScore("Total Durasi Gambar (dtk)", totalDuration.toDouble(), 0.0, 1800.0, "Akumulasi waktu pengerjaan aktif seluruh kotak."),
            AssessmentScore("Kelengkapan Evaluasi Subjektif", subjectiveMarks.toDouble(), 0.0, 8.0, "Kotak yang diberi penanda (+/-/M/S).")
        )
        val warnings = if (completed < 8) {
            listOf("Baru $completed dari 8 kotak Wartegg terisi (analisis bersifat eksploratif parsial).")
        } else {
            emptyList()
        }
        return EnrichedScoringOutcome(
            scores = scores,
            interpretation = "Tes Proyektif Wartegg ($completed/8 kotak selesai; urutan: $seqText; durasi total: ${totalDuration}dtk). " +
                "${overallTelemetry.toFormattedSummary()} " +
                "Metrik eksekusi dan telemetri stylus bersifat observasional terstruktur sebelum coding Kinget & review psikolog.",
            scoringStatus = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
            validityStatus = if (completed == 8) AssessmentValidityStatus.VALID else AssessmentValidityStatus.QUESTIONABLE,
            validityWarnings = warnings,
            reviewStatus = ExaminerReviewStatus.PENDING,
            normReference = PsychometricInstrumentRegistry.specFor("wartegg").normStatus,
            observations = observations,
            warteggDrafts = drafts,
            multimodalSummary = multimodalSummary
        )
    }

    fun scoreRorschachFromDrafts(
        responses: List<RorschachResponseDraft>,
        systemId: String = "EXNER_CS",
        multimodalSummary: String? = null
    ): EnrichedScoringOutcome {
        val totalR = responses.size
        val warnings = buildList {
            if (totalR < 14) {
                add("Total respons R = $totalR berada di bawah ambang minimum validitas standar (R >= 14 pada $systemId); dibaca sebagai protokol eksploratif parsial.")
            }
            if (responses.any { it.freeAssociation.isBlank() && it.location.isNullOrBlank() }) {
                add("Sebagian respons belum dilengkapi asosiasi bebas atau coding pemeriksa.")
            }
        }
        val validity = if (totalR < 14) AssessmentValidityStatus.INVALID else AssessmentValidityStatus.QUESTIONABLE
        val wCount = responses.count { it.location == "W" }
        val dCount = responses.count { it.location == "D" }
        val ddCount = responses.count { it.location == "Dd" }
        val sCount = responses.count { it.location == "S" }
        val popCount = responses.count { it.popular }
        val mCount = responses.count { it.determinant?.contains("M", ignoreCase = false) == true }
        val colorCount = responses.count { it.determinant?.contains("C", ignoreCase = true) == true }
        val fqoCount = responses.count { it.formQuality in setOf("FQo", "FQ+", "+") }
        val fqmCount = responses.count { it.formQuality in setOf("FQ-", "-") }

        val observations = mutableListOf(
            AssessmentObservation("rorschach_r", "Total Respons (R)", totalR.toString()),
            AssessmentObservation("rorschach_system", "Sistem Coding Tunggal", systemId),
            AssessmentObservation("rorschach_loc", "Distribusi Lokasi", "W=$wCount, D=$dCount, Dd=$ddCount, S=$sCount"),
            AssessmentObservation("rorschach_pop", "Popular Terkode", popCount.toString())
        )
        responses.forEach { resp ->
            val assocText = resp.freeAssociation.ifBlank { "Tanpa teks asosiasi" }
            val inqText = resp.inquiry.takeIf(String::isNotBlank)?.let { " [Inquiry: $it]" }.orEmpty()
            observations += AssessmentObservation(
                key = "card_${resp.cardNumber}_r${resp.responseIndex}",
                label = "Kartu ${resp.cardNumber} (#${resp.responseIndex})",
                value = "$assocText$inqText",
                tags = listOfNotNull(resp.location, resp.determinant, resp.formQuality, if (resp.popular) "P" else null)
            )
        }
        val scores = listOf(
            AssessmentScore("Total Respons (R)", totalR.toDouble(), 0.0, 50.0, "Jumlah total respons di 10 kartu ($systemId)."),
            AssessmentScore("W (Whole)", wCount.toDouble(), 0.0, 30.0, "Respons menyeluruh atas seluruh bercak kartu."),
            AssessmentScore("D (Common Detail)", dCount.toDouble(), 0.0, 40.0, "Respons pada detail lazim."),
            AssessmentScore("Dd (Unusual Detail)", ddCount.toDouble(), 0.0, 30.0, "Respons pada detail kecil/tak lazim."),
            AssessmentScore("S (Space)", sCount.toDouble(), 0.0, 20.0, "Integrasi ruang putih kartu."),
            AssessmentScore("P (Popular)", popCount.toDouble(), 0.0, 13.0, "Respons populer yang telah dikode."),
            AssessmentScore("M (Human Movement)", mCount.toDouble(), 0.0, 20.0, "Determinan gerakan manusia yang telah dikode."),
            AssessmentScore("FC/CF/C (Color)", colorCount.toDouble(), 0.0, 20.0, "Determinan warna yang telah dikode."),
            AssessmentScore("FQo/FQ+ (Kesesuaian Bentuk)", fqoCount.toDouble(), 0.0, 40.0, "Bentuk akurat/lazim terkode (FQ-=$fqmCount).")
        )
        return EnrichedScoringOutcome(
            scores = scores,
            interpretation = "Protokol Rorschach ($systemId, R = $totalR respons; W=$wCount, D=$dCount, Dd=$ddCount, S=$sCount, P=$popCount, M=$mCount). " +
                "Skor hitungan di atas berasal langsung dari protokol dan coding yang tercatat; tetap bersifat eksploratif hingga review psikolog final.",
            scoringStatus = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
            validityStatus = validity,
            validityWarnings = warnings,
            reviewStatus = ExaminerReviewStatus.PENDING,
            normReference = PsychometricInstrumentRegistry.specFor("rorschach").normStatus,
            observations = observations,
            rorschachResponses = responses,
            multimodalSummary = multimodalSummary
        )
    }

    fun scoreProjectivePanels(
        instrumentId: String,
        title: String,
        panels: List<ProjectivePanelDraft>,
        multimodalSummary: String? = null
    ): EnrichedScoringOutcome {
        val completed = panels.count { it.hasContent() }
        val totalRevisions = panels.sumOf { it.revisionCount.coerceAtLeast(0) }
        val overallTelemetry = ProjectiveTelemetryExtractor.extractMetrics(panels.map { it.strokes }, totalRevisions)
        val totalDuration = panels.sumOf { it.durationSeconds.coerceAtLeast(0) }
        val ordered = panels.filter { it.drawOrder != null }.sortedBy { it.drawOrder }
        val seqText = ordered.joinToString(" -> ") { it.panelLabel }.ifBlank { "Belum ada urutan tercatat" }

        val observations = mutableListOf(
            AssessmentObservation("${instrumentId}_completed", "Panel Diselesaikan", "$completed/${panels.size}"),
            AssessmentObservation("${instrumentId}_sequence", "Urutan Pengerjaan", seqText),
            AssessmentObservation(
                key = "${instrumentId}_telemetry_summary",
                label = "Telemetri Goresan & Stylus",
                value = overallTelemetry.toFormattedSummary(),
                pressure = overallTelemetry.meanPressure
            )
        )
        panels.forEach { panel ->
            val metrics = ProjectiveTelemetryExtractor.extractMetrics(listOf(panel.strokes), panel.revisionCount)
            val tags = listOfNotNull(
                if (metrics.strokeCount > 0) "Arah: ${metrics.dominantDirection}" else null,
                if (metrics.strokeCount > 0) String.format(Locale.US, "Ruang: %.0f%%", metrics.spaceCoveragePercent) else null,
                if (metrics.stylusUsed) "Stylus" else null
            )
            val desc = listOfNotNull(
                panel.title.takeIf(String::isNotBlank)?.let { "Judul: \"$it\"" },
                panel.notes.takeIf(String::isNotBlank)
            ).joinToString(" · ").ifBlank { if (panel.strokes.isNotEmpty()) "Digambar (${metrics.strokeCount} goresan)" else "Kosong" }
            observations += AssessmentObservation(
                key = "${instrumentId}_panel_${panel.panelIndex}",
                label = panel.panelLabel,
                value = desc,
                durationSeconds = panel.durationSeconds.takeIf { it > 0 },
                orderIndex = panel.drawOrder,
                pressure = metrics.meanPressure,
                tags = tags
            )
        }
        val scores = listOf(
            AssessmentScore("Panel Diselesaikan", completed.toDouble(), 0.0, panels.size.toDouble().coerceAtLeast(1.0), "Kelengkapan panel gambar proyektif."),
            AssessmentScore("Cakupan Ruang (%)", overallTelemetry.spaceCoveragePercent.toDouble(), 0.0, 100.0, "Cakupan area bounding-box gabungan (observasional, bukan skor kepribadian)."),
            AssessmentScore("Total Goresan", overallTelemetry.strokeCount.toDouble(), 0.0, 500.0, "Jumlah goresan tercatat."),
            AssessmentScore("Total Durasi Gambar (dtk)", totalDuration.toDouble(), 0.0, 1800.0, "Akumulasi waktu pengerjaan aktif.")
        )
        val warnings = if (completed < panels.size) {
            listOf("Baru $completed dari ${panels.size} panel $title diselesaikan.")
        } else {
            emptyList()
        }
        return EnrichedScoringOutcome(
            scores = scores,
            interpretation = "Asesmen Proyektif $title ($completed/${panels.size} panel; urutan: $seqText; durasi ${totalDuration}dtk). " +
                "${overallTelemetry.toFormattedSummary()} Analisis visual dan telemetri bersifat observasional eksploratif sebelum review psikolog.",
            scoringStatus = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
            validityStatus = if (completed > 0) AssessmentValidityStatus.VALID else AssessmentValidityStatus.QUESTIONABLE,
            validityWarnings = warnings,
            reviewStatus = ExaminerReviewStatus.PENDING,
            normReference = PsychometricInstrumentRegistry.specFor(instrumentId).normStatus,
            observations = observations,
            projectivePanels = panels,
            multimodalSummary = multimodalSummary
        )
    }

    private fun outcomeVerified(id: String, pair: Pair<List<AssessmentScore>, String>): EnrichedScoringOutcome =
        EnrichedScoringOutcome(
            scores = pair.first,
            interpretation = pair.second,
            scoringStatus = InstrumentImplementationStatus.VERIFIED,
            normReference = PsychometricInstrumentRegistry.specFor(id).normStatus
        )

    private fun outcomeRaw(
        id: String,
        pair: Pair<List<AssessmentScore>, String>,
        warnings: List<String> = emptyList(),
        validity: AssessmentValidityStatus = AssessmentValidityStatus.VALID,
        observations: List<AssessmentObservation> = emptyList()
    ): EnrichedScoringOutcome = EnrichedScoringOutcome(
        scores = pair.first,
        interpretation = pair.second,
        scoringStatus = InstrumentImplementationStatus.RAW_SCORE_ONLY,
        validityStatus = validity,
        validityWarnings = warnings,
        normReference = PsychometricInstrumentRegistry.specFor(id).normStatus,
        observations = observations
    )

    private fun enrichBdi(answers: Map<Int, Int>): EnrichedScoringOutcome {
        val cognitive = answers.filterKeys { it in 0..13 }.values.sum().toDouble()
        val somatic = answers.filterKeys { it in 14..20 }.values.sum().toDouble()
        val total = cognitive + somatic
        val severity = when {
            total <= 13.0 -> "Minimal (0–13)"
            total <= 19.0 -> "Ringan (14–19)"
            total <= 28.0 -> "Sedang (20–28)"
            else -> "Berat (29–63)"
        }
        val item9 = answers[8] ?: 0
        val warnings = if (item9 > 0) {
            listOf("Butir 9 (pikiran/harapan bunuh diri) bernilai $item9/3 — prioritaskan pemeriksaan keselamatan segera.")
        } else {
            emptyList()
        }
        val alert = if (item9 > 0) {
            " PERINGATAN KESELAMATAN: Butir 9 bernilai $item9/3; evaluasi keselamatan segera diperlukan."
        } else {
            ""
        }
        val scores = listOf(
            AssessmentScore("Total Skor BDI-II", total, 0.0, 63.0, "Band gejala: $severity.$alert"),
            AssessmentScore("Butir 9 Ide Bunuh Diri", item9.toDouble(), 0.0, 3.0, "Item kritis keselamatan."),
            AssessmentScore("Subskala Kognitif-Afektif", cognitive, 0.0, 42.0, "Jumlah butir 1–14."),
            AssessmentScore("Subskala Somatik", somatic, 0.0, 21.0, "Jumlah butir 15–21.")
        )
        val interpretation = "Skrining gejala depresi BDI-II (2 minggu terakhir): $severity " +
            "(Skor Total: ${total.toInt()}/63; butir 1–14=${cognitive.toInt()}, butir 15–21=${somatic.toInt()}).$alert " +
            "Bersifat skrining terukur dan bukan diagnosis klinis tunggal."
        return EnrichedScoringOutcome(
            scores = scores,
            interpretation = interpretation,
            scoringStatus = InstrumentImplementationStatus.VERIFIED,
            validityWarnings = warnings,
            normReference = PsychometricInstrumentRegistry.specFor("bdi").normStatus
        )
    }

    private fun enrichSrq29(answers: Map<Int, Int>): EnrichedScoringOutcome {
        val gme = answers.filterKeys { it in 0..19 }.values.sum()
        val substance = answers[20] ?: 0
        val psychotic = answers.filterKeys { it in 21..23 }.values.sum()
        val ptsd = answers.filterKeys { it in 24..28 }.values.sum()
        val totalYes = answers.values.sum()
        val item17 = answers[16] ?: 0
        val gmeStatus = if (gme >= 6) {
            "Skor domain GME >= 6 (indikasi skrining Kemenkes RI; bergantung versi/populasi)"
        } else {
            "Di bawah ambang >= 6 pada skrining GME Kemenkes RI"
        }
        val subStatus = if (substance >= 1) "Ada indikasi respons Ya pada domain zat" else "Tidak ada"
        val psyStatus = if (psychotic >= 1) "Ada indikasi respons Ya pada domain gejala psikotik (perlu evaluasi profesional)" else "Tidak ada"
        val ptsdStatus = if (ptsd >= 1) "Ada indikasi respons Ya pada domain gejala PTSD" else "Tidak ada"
        val warnings = if (item17 == 1) {
            listOf("Butir 17 (pikiran mengakhiri hidup) dijawab Ya — prioritaskan pemeriksaan keselamatan segera.")
        } else {
            emptyList()
        }
        val alert = if (item17 == 1) {
            " PERINGATAN KESELAMATAN: Jawaban Ya pada Butir 17; wajib pemeriksaan keselamatan/rujukan segera."
        } else {
            ""
        }
        val scores = listOf(
            AssessmentScore("Total Indikasi Ya", totalYes.toDouble(), 0.0, 29.0, "Jumlah total butir dijawab Ya."),
            AssessmentScore("Gangguan Mental Emosional", gme.toDouble(), 0.0, 20.0, gmeStatus),
            AssessmentScore("Penggunaan Zat", substance.toDouble(), 0.0, 1.0, subStatus),
            AssessmentScore("Gejala Psikotik", psychotic.toDouble(), 0.0, 3.0, psyStatus),
            AssessmentScore("Gejala PTSD", ptsd.toDouble(), 0.0, 5.0, ptsdStatus)
        )
        val interpretation = "Hasil Skrining SRQ-29: GME=$gme/20 ($gmeStatus); Zat=$substance/1; Psikotik=$psychotic/3; PTSD=$ptsd/5.$alert " +
            "Bersifat skrining awal dan bukan diagnosis pasti."
        return EnrichedScoringOutcome(
            scores = scores,
            interpretation = interpretation,
            scoringStatus = InstrumentImplementationStatus.VERIFIED,
            validityWarnings = warnings,
            normReference = PsychometricInstrumentRegistry.specFor("srq-29").normStatus
        )
    }

    private fun enrichVak(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val countV = answers.values.count { it == 0 }
        val countA = answers.values.count { it == 1 }
        val countK = answers.values.count { it == 2 }
        val total = (countV + countA + countK).coerceAtLeast(1)
        val pV = countV * 100.0 / total
        val pA = countA * 100.0 / total
        val pK = countK * 100.0 / total
        val scores = listOf(
            AssessmentScore("Preferensi Visual", pV, 0.0, 100.0, "$countV/$total butir (${format1(pV)}%)."),
            AssessmentScore("Preferensi Auditori", pA, 0.0, 100.0, "$countA/$total butir (${format1(pA)}%)."),
            AssessmentScore("Preferensi Kinestetik", pK, 0.0, 100.0, "$countK/$total butir (${format1(pK)}%).")
        )
        val interpretation = "Distribusi refleksi preferensi VAK: Visual ${format1(pV)}% ($countV), Auditori ${format1(pA)}% ($countA), " +
            "Kinestetik ${format1(pK)}% ($countK). Catatan ini merupakan refleksi preferensi laporan diri, bukan penetapan gaya belajar baku."
        return scores to interpretation
    }

    private fun enrichAq(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        // Pemetaan eksak 15 butir (a=0, b=1, c=2) -> Q (Quitter), Ca (Camper), Cl (Climber)
        // sesuai sheet Interpretasi & Kode pada Adversity Quotient.xlsx (Paket 25 Item).
        val aqKey = listOf(
            listOf("Q", "Ca", "Cl"),
            listOf("Q", "Cl", "Ca"),
            listOf("Cl", "Ca", "Q"),
            listOf("Ca", "Q", "Cl"),
            listOf("Ca", "Q", "Cl"),
            listOf("Cl", "Q", "Ca"),
            listOf("Ca", "Cl", "Q"),
            listOf("Q", "Ca", "Cl"),
            listOf("Cl", "Q", "Ca"),
            listOf("Cl", "Q", "Ca"),
            listOf("Q", "Ca", "Cl"),
            listOf("Cl", "Q", "Ca"),
            listOf("Ca", "Q", "Cl"),
            listOf("Q", "Ca", "Cl"),
            listOf("Ca", "Cl", "Q")
        )

        var qCount = 0
        var caCount = 0
        var clCount = 0
        var answered = 0
        for (i in aqKey.indices) {
            val choice = answers[i] ?: continue
            if (choice in 0..2) {
                answered++
                when (aqKey[i][choice]) {
                    "Q" -> qCount++
                    "Ca" -> caCount++
                    "Cl" -> clCount++
                }
            }
        }

        // Aturan Kode matrix (136 kombinasi di sheet Kode): pemenang tunggal menang, seri -> CAMPER.
        val dominantCategory = when {
            answered == 0 -> "Belum Terisi"
            clCount > caCount && clCount > qCount -> "CLIMBER"
            qCount > caCount && qCount > clCount -> "QUITTER"
            else -> "CAMPER"
        }

        val scores = listOf(
            AssessmentScore("Climber (Cl)", clCount.toDouble(), 0.0, 15.0, "Frekuensi respons tipe Climber dari 15 butir."),
            AssessmentScore("Camper (Ca)", caCount.toDouble(), 0.0, 15.0, "Frekuensi respons tipe Camper dari 15 butir."),
            AssessmentScore("Quitter (Q)", qCount.toDouble(), 0.0, 15.0, "Frekuensi respons tipe Quitter dari 15 butir.")
        )
        val interpretation = "Profil AQ 15-butir lokal (Adversity Quotient.xlsx; $answered/15 butir terisi): " +
            "Kategori dominan $dominantCategory (Climber=$clCount, Camper=$caCount, Quitter=$qCount). " +
            "Pemetaan a/b/c per butir mengikuti worksheet lokal (terpisah dari skor 40–200 pada rekap ARP 20-butir)."
        return scores to interpretation
    }

    private fun enrichBigFive(rawScores: List<AssessmentScore>): Pair<List<AssessmentScore>, String> {
        val summary = rawScores.joinToString(", ") { "${it.label}: ${it.displayValue()}" }
        val highest = rawScores.maxByOrNull { it.value }?.label ?: "Seimbang"
        val interpretation = "Profil Big Five IPIP-BFM-50 (skor mentah deskriptif 10–50 per skala) menunjukkan skor tertinggi pada $highest. " +
            "Rincian: $summary."
        return rawScores to interpretation
    }

    private fun enrichCbi(rawScores: List<AssessmentScore>): Pair<List<AssessmentScore>, String> {
        val summary = rawScores.joinToString(", ") { "${it.label}: ${it.displayValue()}/100" }
        val interpretation = "Skor subskala Copenhagen Burnout Inventory (CBI NFA-2004, skala 0–100): $summary. " +
            "Dilaporkan per subskala tanpa cutoff diagnostik universal; interpretasi mempertimbangkan konteks kerja & klinis."
        return rawScores to interpretation
    }

    // =========================================================================
    // 1. DISC Scoring & Profiling (24 Groups Most & Least — Raw Counts)
    // =========================================================================
    private fun enrichDisc(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        var d1 = 0
        var i1 = 0
        var s1 = 0
        var c1 = 0
        var d2 = 0
        var i2 = 0
        var s2 = 0
        var c2 = 0

        for (g in 0 until 24) {
            val mostIdx = answers[g * 2]
            val leastIdx = answers[g * 2 + 1]
            if (mostIdx != null && mostIdx in 0..3) {
                when (DISC_MOST[g][mostIdx]) {
                    "D" -> d1++
                    "I" -> i1++
                    "S" -> s1++
                    "C" -> c1++
                }
            }
            if (leastIdx != null && leastIdx in 0..3) {
                when (DISC_LEAST[g][leastIdx]) {
                    "D" -> d2++
                    "I" -> i2++
                    "S" -> s2++
                    "C" -> c2++
                }
            }
        }

        val d3 = d1 - d2
        val i3 = i1 - i2
        val s3 = s1 - s2
        val c3 = c1 - c2
        val topMirror = listOf("D (Dominance)" to d3, "I (Influence)" to i3, "S (Steadiness)" to s3, "C (Compliance)" to c3)
            .maxByOrNull { it.second }?.first ?: "Seimbang"

        val scores = listOf(
            AssessmentScore("Profil A (Most) Dominance", d1.toDouble(), 0.0, 24.0, "Frekuensi pilihan paling menggambarkan diri (D)."),
            AssessmentScore("Profil A (Most) Influence", i1.toDouble(), 0.0, 24.0, "Frekuensi pilihan paling menggambarkan diri (I)."),
            AssessmentScore("Profil A (Most) Steadiness", s1.toDouble(), 0.0, 24.0, "Frekuensi pilihan paling menggambarkan diri (S)."),
            AssessmentScore("Profil A (Most) Compliance", c1.toDouble(), 0.0, 24.0, "Frekuensi pilihan paling menggambarkan diri (C)."),
            AssessmentScore("Profil B (Least) Dominance", d2.toDouble(), 0.0, 24.0, "Frekuensi pilihan paling tidak menggambarkan diri (D)."),
            AssessmentScore("Profil B (Least) Influence", i2.toDouble(), 0.0, 24.0, "Frekuensi pilihan paling tidak menggambarkan diri (I)."),
            AssessmentScore("Profil B (Least) Steadiness", s2.toDouble(), 0.0, 24.0, "Frekuensi pilihan paling tidak menggambarkan diri (S)."),
            AssessmentScore("Profil B (Least) Compliance", c2.toDouble(), 0.0, 24.0, "Frekuensi pilihan paling tidak menggambarkan diri (C)."),
            AssessmentScore("Profil C (Selisih Most-Least) D", d3.toDouble(), -24.0, 24.0, "Selisih mentah D (Profil A - Profil B)."),
            AssessmentScore("Profil C (Selisih Most-Least) I", i3.toDouble(), -24.0, 24.0, "Selisih mentah I (Profil A - Profil B)."),
            AssessmentScore("Profil C (Selisih Most-Least) S", s3.toDouble(), -24.0, 24.0, "Selisih mentah S (Profil A - Profil B)."),
            AssessmentScore("Profil C (Selisih Most-Least) C", c3.toDouble(), -24.0, 24.0, "Selisih mentah C (Profil A - Profil B).")
        )

        val interpretation = "Skor mentah DISC-24 (dimensi selisih tertinggi: $topMirror): " +
            "Profil A (Most) [D=$d1, I=$i1, S=$s1, C=$c1], Profil B (Least) [D=$d2, I=$i2, S=$s2, C=$c2], " +
            "Profil C (Selisih) [D=$d3, I=$i3, S=$s3, C=$c3]. " +
            "Grafik & klasifikasi normatif lebih lanjut memerlukan tabel norma formulir DISC resmi."
        return scores to interpretation
    }

    // =========================================================================
    // 2. MMPI-2-RF Raw Scale Scoring (Safety SUI, Completeness, Review Required)
    // =========================================================================
    private fun enrichMmpi(answers: Map<Int, Int>, totalItems: Int): EnrichedScoringOutcome {
        val suiItems = listOf(92, 119, 163, 250, 333)
        val suiScore = suiItems.count { answers[it] == 1 }
        val hasSuiRisk = suiScore > 0

        val eidScore = listOf(
            5, 21, 29, 47, 61, 73, 88, 116, 129, 143, 157,
            171, 186, 203, 231, 246, 260, 273, 287, 298, 314, 330
        ).count { answers[it] == 1 }
        val thdScore = listOf(
            11, 13, 45, 70, 91, 109, 121, 128, 138, 149, 167, 178,
            198, 202, 215, 251, 263, 269, 272, 286, 293, 310, 329, 331
        ).count { answers[it] == 1 }
        val bxdScore = listOf(
            20, 48, 65, 83, 95, 106, 130, 155, 192, 204,
            222, 225, 230, 247, 252, 265, 291, 311, 315, 328
        ).count { answers[it] == 1 }

        val frScore = listOf(
            13, 29, 45, 55, 66, 70, 73, 105, 116, 119, 138, 145, 163,
            202, 217, 230, 239, 252, 263, 274, 276, 280, 293, 300, 309, 311, 321
        ).count { answers[it] == 1 } + listOf(58, 82, 101, 173, 226).count { answers[it] == 0 }
        val lrScore = listOf(60, 181, 267).count { answers[it] == 1 } +
            listOf(15, 44, 69, 94, 126, 153, 182, 210, 240, 297, 324).count { answers[it] == 0 }
        // Item 201 appears in ==0 false-keyed K-r adjustment direction only (never both 1 and 0).
        val krScore = listOf(79).count { answers[it] == 1 } +
            listOf(9, 22, 35, 43, 71, 88, 104, 139, 201, 216, 221, 233, 245, 281, 292).count { answers[it] == 0 }

        val rcdScore = listOf(
            5, 21, 29, 47, 61, 73, 88, 116, 129, 143, 157,
            171, 186, 203, 231, 246, 260, 273, 287, 298, 314, 330
        ).count { answers[it] == 1 } + listOf(104, 216).count { answers[it] == 0 }
        val rc1Score = listOf(14, 42, 75, 100, 136, 175, 229, 241, 276, 300, 327).count { answers[it] == 1 }
        val rc2Score = listOf(3, 16, 24, 52, 63, 82, 101, 139, 159, 181, 194, 201, 221, 245, 281, 301, 322).count { answers[it] == 0 }
        val rc4Score = listOf(4, 20, 48, 65, 95, 140, 155, 172, 204, 217, 222, 252, 265, 296, 311, 328).count { answers[it] == 1 }

        val targetCount = totalItems.coerceAtLeast(338)
        val answeredCount = answers.keys.count { it in 0 until targetCount }
        val minRequired = (targetCount * 0.9).toInt()
        val isComplete = answeredCount >= minRequired

        val warnings = buildList {
            if (hasSuiRisk) {
                add("PERINGATAN KESELAMATAN KLINIS: Terdeteksi respons positif pada $suiScore butir Ide Bunuh Diri (SUI). Evaluasi keselamatan segera wajib dilakukan.")
            }
            if (!isComplete) {
                add("Protokol tidak lengkap ($answeredCount/$targetCount butir; minimum 90% [$minRequired butir] diperlukan agar valid).")
            }
            add("Skor berupa raw count MMPI-2-RF (belum dikonversi ke skor T standar); wajib verifikasi validitas & review psikolog.")
        }

        val validityStatus = when {
            !isComplete -> AssessmentValidityStatus.INVALID
            frScore >= 20 || lrScore >= 10 -> AssessmentValidityStatus.QUESTIONABLE
            else -> AssessmentValidityStatus.PENDING
        }

        val scores = listOf(
            AssessmentScore("F-r (Infrequent Responses)", frScore.toDouble(), 0.0, 32.0, "Skor mentah validitas pelaporan gejala berlebih."),
            AssessmentScore("L-r (Uncommon Virtues)", lrScore.toDouble(), 0.0, 14.0, "Skor mentah validitas kebajikan moral."),
            AssessmentScore("K-r (Adjustment Validity)", krScore.toDouble(), 0.0, 16.0, "Skor mentah validitas penyesuaian defensif."),
            AssessmentScore("SUI (Ide Bunuh Diri)", suiScore.toDouble(), 0.0, 5.0, if (hasSuiRisk) "Elevasi kritis keselamatan ($suiScore/5)." else "0/5 respons kritis."),
            AssessmentScore("H-O EID (Disfungsi Emosi)", eidScore.toDouble(), 0.0, 22.0, "Skor mentah Emotional/Internalizing."),
            AssessmentScore("H-O THD (Disfungsi Pikiran)", thdScore.toDouble(), 0.0, 24.0, "Skor mentah Thought Dysfunction."),
            AssessmentScore("H-O BXD (Kendala Perilaku)", bxdScore.toDouble(), 0.0, 20.0, "Skor mentah Behavioral/Externalizing."),
            AssessmentScore("RCd (Demoralisasi)", rcdScore.toDouble(), 0.0, 24.0, "Skor mentah demoralisasi."),
            AssessmentScore("RC1 (Keluhan Somatik)", rc1Score.toDouble(), 0.0, 11.0, "Skor mentah somatik."),
            AssessmentScore("RC2 (Emosi Positif Rendah)", rc2Score.toDouble(), 0.0, 17.0, "Skor mentah emosi positif rendah."),
            AssessmentScore("RC4 (Perilaku Antisosial)", rc4Score.toDouble(), 0.0, 16.0, "Skor mentah perilaku antisosial.")
        )

        val alertText = if (hasSuiRisk) " [PERINGATAN KESELAMATAN SUI=$suiScore/5]" else ""
        val interpretation = "MMPI-2-RF ($answeredCount/$targetCount butir dijawab; skor mentah — bukan T-score MMPI-2 klasik): " +
            "Validitas F-r=$frScore, L-r=$lrScore, K-r=$krScore; Klinis EID=$eidScore, THD=$thdScore, BXD=$bxdScore, " +
            "RCd=$rcdScore, RC1=$rc1Score, RC2=$rc2Score, RC4=$rc4Score.$alertText Menunggu verifikasi skala validitas & review psikolog."

        return EnrichedScoringOutcome(
            scores = scores,
            interpretation = interpretation,
            scoringStatus = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
            validityStatus = validityStatus,
            validityWarnings = warnings,
            reviewStatus = ExaminerReviewStatus.PENDING,
            normReference = PsychometricInstrumentRegistry.specFor("mmpi").normStatus
        )
    }

    // =========================================================================
    // 3. TIU 6 Scoring (40 Spatial 3D Folding Items — Raw Score Only)
    // =========================================================================
    private fun enrichTiu6(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val keys = listOf(
            0, 1, 1, 1, 0, 1, 0, 0, 0, 0,
            1, 0, 1, 1, 0, 1, 0, 0, 0, 1,
            1, 1, 1, 1, 0, 1, 0, 1, 0, 1,
            1, 0, 0, 0, 0, 1, 0, 0, 1, 1
        )

        var correctCount = 0
        val familyCorrect = IntArray(8)

        for (i in 0 until 40) {
            val ans = answers[i]
            // Choice 0 = Benar (key 1), Choice 1 = Salah (key 0)
            if (ans != null) {
                val isCorrect = (ans == 0 && keys[i] == 1) || (ans == 1 && keys[i] == 0)
                if (isCorrect) {
                    correctCount++
                    familyCorrect[i / 5]++
                }
            }
        }

        val scores = listOf(
            AssessmentScore(
                "Total Jawaban Benar",
                correctCount.toDouble(),
                0.0,
                40.0,
                "Skor mentah ($correctCount/40); norma skala memerlukan manual resmi."
            ),
            AssessmentScore("Baris 1 (Kubus)", familyCorrect[0].toDouble(), 0.0, 5.0, "Jaring bangun kubus."),
            AssessmentScore("Baris 2 (Piramida)", familyCorrect[1].toDouble(), 0.0, 5.0, "Jaring piramida persegi."),
            AssessmentScore("Baris 3 (Prisma)", familyCorrect[2].toDouble(), 0.0, 5.0, "Jaring prisma segitiga."),
            AssessmentScore("Baris 4 (Piramida 3)", familyCorrect[3].toDouble(), 0.0, 5.0, "Jaring limas segitiga."),
            AssessmentScore("Baris 5 (Poligon)", familyCorrect[4].toDouble(), 0.0, 5.0, "Jaring ruang poligon."),
            AssessmentScore("Baris 6 (Bentuk Arsir)", familyCorrect[5].toDouble(), 0.0, 5.0, "Jaring bentuk berarsir."),
            AssessmentScore("Baris 7 (Kubus Kompleks)", familyCorrect[6].toDouble(), 0.0, 5.0, "Jaring kubus kompleks."),
            AssessmentScore("Baris 8 (Gabungan)", familyCorrect[7].toDouble(), 0.0, 5.0, "Jaring bentuk gabungan.")
        )

        val interpretation = "Hasil Tes Inteligensi Umum 6 (Penalaran Spasial & Bangun Ruang): " +
            "Skor mentah $correctCount dari 40 butir (${answeredSummary(answers.size, 40)}). " +
            "Konversi skala baku atau kategori norma tidak dihasilkan otomatis tanpa tabel norma resmi."
        return scores to interpretation
    }

    // =========================================================================
    // 4. SPM Scoring (Raven's Standard Progressive Matrices — 60 Items Raw)
    // =========================================================================
    private fun enrichSpm(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val keys = listOf(
            // Set A
            4, 5, 1, 2, 6, 3, 6, 2, 1, 3, 4, 5,
            // Set B
            2, 6, 1, 2, 1, 3, 5, 6, 4, 3, 4, 5,
            // Set C
            8, 2, 3, 8, 7, 4, 5, 1, 7, 6, 1, 2,
            // Set D
            3, 4, 3, 7, 8, 6, 5, 4, 1, 2, 5, 6,
            // Set E
            7, 6, 8, 2, 1, 5, 1, 6, 3, 2, 4, 5
        )

        val setCorrect = IntArray(5)
        for (i in 0 until 60) {
            val ans = answers[i]
            if (ans != null && (ans + 1) == keys[i]) {
                setCorrect[i / 12]++
            }
        }
        return enrichSpmRecap(setCorrect[0], setCorrect[1], setCorrect[2], setCorrect[3], setCorrect[4])
    }

    fun enrichSpmRecap(setA: Int, setB: Int, setC: Int, setD: Int, setE: Int): Pair<List<AssessmentScore>, String> {
        val sA = setA.coerceIn(0, 12)
        val sB = setB.coerceIn(0, 12)
        val sC = setC.coerceIn(0, 12)
        val sD = setD.coerceIn(0, 12)
        val sE = setE.coerceIn(0, 12)
        val totalCorrect = sA + sB + sC + sD + sE

        val hasAtypicalPattern = (sE > sA + 2) || (sE > sB + 3)
        val consistencyNote = if (hasAtypicalPattern) {
            " Catatan Konsistensi: Skor set lanjut (Set E) melampaui set dasar (Set A/B); " +
                "disarankan pemeriksaan lembar jawaban untuk menilai kemungkinan tebakan acak."
        } else {
            " Konsistensi antar-set menunjukkan progresi wajar dari set dasar ke set lanjutan."
        }

        val scores = listOf(
            AssessmentScore(
                "Total Skor Mentah",
                totalCorrect.toDouble(),
                0.0,
                60.0,
                "Total jawaban benar ($totalCorrect/60); grade persentil memerlukan norma usia/populasi."
            ),
            AssessmentScore("Set A (Pola Kontinu)", sA.toDouble(), 0.0, 12.0, "Penyelesaian pola statis."),
            AssessmentScore("Set B (Analogi Figur)", sB.toDouble(), 0.0, 12.0, "Analogi simetri 2x2."),
            AssessmentScore("Set C (Progresi Matriks)", sC.toDouble(), 0.0, 12.0, "Perubahan progresi 3x3."),
            AssessmentScore("Set D (Permutasi Matriks)", sD.toDouble(), 0.0, 12.0, "Permutasi elemen teratur."),
            AssessmentScore("Set E (Dekomposisi Logika)", sE.toDouble(), 0.0, 12.0, "Dekomposisi aturan logika abstrak.")
        )

        val interpretation = "Hasil Raven Standard Progressive Matrices (SPM): Skor Mentah $totalCorrect/60. " +
            "Rincian per set: Set A=$sA/12, Set B=$sB/12, Set C=$sC/12, Set D=$sD/12, Set E=$sE/12. " +
            "Penetapan persentil dan Grade I–V wajib merujuk tabel norma usia dan populasi pada manual SPM.$consistencyNote"
        return scores to interpretation
    }

    // =========================================================================
    // 5. MBTI Scoring (70 Paired Items, 16 Types)
    // =========================================================================
    private fun enrichMbti(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        var e = 0
        var iCount = 0
        var s = 0
        var nCount = 0
        var t = 0
        var f = 0
        var j = 0
        var p = 0

        for (q in 0 until 70) {
            val ans = answers[q] ?: continue
            val mod = q % 7
            when (mod) {
                0 -> if (ans == 0) e++ else iCount++
                1, 2 -> if (ans == 0) s++ else nCount++
                3, 4 -> if (ans == 0) t++ else f++
                5, 6 -> if (ans == 0) j++ else p++
            }
        }

        val dimE = if (e >= iCount) "E" else "I"
        val dimS = if (s >= nCount) "S" else "N"
        val dimT = if (t >= f) "T" else "F"
        val dimJ = if (j >= p) "J" else "P"
        val typeCode = "$dimE$dimS$dimT$dimJ"

        val typeDescription = getMbtiDescription(typeCode)

        val scores = listOf(
            AssessmentScore("Tipe MBTI", 1.0, 1.0, 1.0, "$typeCode — ${typeDescription.first}"),
            AssessmentScore("Ekstrovert (E)", e.toDouble(), 0.0, 10.0, "Orientasi energi luar."),
            AssessmentScore("Introvert (I)", iCount.toDouble(), 0.0, 10.0, "Orientasi energi dalam."),
            AssessmentScore("Sensing (S)", s.toDouble(), 0.0, 20.0, "Fokus fakta konkret dan detail."),
            AssessmentScore("Intuition (N)", nCount.toDouble(), 0.0, 20.0, "Fokus gambaran besar dan kemungkinan."),
            AssessmentScore("Thinking (T)", t.toDouble(), 0.0, 20.0, "Keputusan berbasis logika objektif."),
            AssessmentScore("Feeling (F)", f.toDouble(), 0.0, 20.0, "Keputusan berbasis nilai dan empati."),
            AssessmentScore("Judging (J)", j.toDouble(), 0.0, 20.0, "Gaya hidup terencana dan terstruktur."),
            AssessmentScore("Perceiving (P)", p.toDouble(), 0.0, 20.0, "Gaya hidup fleksibel dan adaptif.")
        )

        val interpretation = "Tipe Kepribadian MBTI Anda adalah $typeCode (${typeDescription.first}). " +
            "Proporsi dimensi: E=$e vs I=$iCount, S=$s vs N=$nCount, T=$t vs F=$f, J=$j vs P=$p. " +
            "Karakteristik: ${typeDescription.second}."
        return scores to interpretation
    }

    private fun getMbtiDescription(code: String): Pair<String, String> = when (code) {
        "INTJ" -> "Mastermind / Arsitek" to "Strategis, independen, visioner analitis, dan memiliki dorongan kuat mewujudkan ide"
        "INTP" -> "Logician / Pemikir" to "Inovatif, haus pengetahuan, analitis mendalam, dan membedah teori secara logis"
        "ENTJ" -> "Commander / Komandan" to "Tegas, berjiwa pemimpin, terencana, dan cakap mengorganisasi strategi berskala besar"
        "ENTP" -> "Debater / Visioner" to "Cerdas, fleksibel, antusias mendiskusikan konsep baru, dan cepat melihat celah solusi"
        "INFJ" -> "Advocate / Penasihat" to "Idealistik, berintegritas tinggi, empatik mendalam, dan memiliki wawasan kemanusiaan kuat"
        "INFP" -> "Mediator / Penyembuh" to "Puitis, setia pada nilai pribadi, altruistik, dan penuh empati terhadap potensi manusia"
        "ENFJ" -> "Protagonist / Pendidik" to "Karismatik, inspiratif, komunikatif, dan mampu memberdayakan kelompok secara harmonis"
        "ENFP" -> "Campaigner / Motivator" to "Kreatif, enerjik, berwawasan luas, dan menyukai eksplorasi kemungkinan baru"
        "ISTJ" -> "Inspector / Pengawas" to "Bertanggung jawab, metodis, setia pada fakta dan prosedur, serta sangat andal"
        "ISFJ" -> "Protector / Pelindung" to "Hangat, teliti, setia melayani, menjaga harmoni, dan berdedikasi tinggi"
        "ESTJ" -> "Executive / Pengarah" to "Praktis, berbasis hasil, terorganisasi rapi, dan cakap menegakkan standar mutu"
        "ESFJ" -> "Consul / Pengayom" to "Ramah, kooperatif, peduli terhadap kebutuhan sosial, dan menjaga kekompakan kelompok"
        "ISTP" -> "Virtuoso / Mekanik" to "Praktis, observan tajam, tenang menghadapi krisis, dan menguasai berbagai alat teknis"
        "ISFP" -> "Adventurer / Seniman" to "Sensitif, artistik, menikmati momen saat ini, dan fleksibel menghadapi perubahan"
        "ESTP" -> "Dynamo / Pelaksana" to "Spontan, berani, enerjik, berorientasi tindakan cepat, dan menyukai dinamika langsung"
        else -> "Performer / Penghibur" to "Antusias, ramah, menikmati interaksi sosial yang menyenangkan, dan mengalir bersama tim"
    }

    // =========================================================================
    // 6. TIU 5 Scoring (30 Figural Pattern Reasoning Items — Raw Score Only)
    // =========================================================================
    private fun enrichTiu5(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val keys = listOf(
            2, 5, 4, 4, 5, 4, 5, 1, 5, 3,
            5, 4, 5, 3, 2, 3, 2, 3, 4, 4,
            3, 3, 4, 4, 5, 1, 2, 5, 3, 5
        )

        var correctCount = 0
        var part1Correct = 0
        var part2Correct = 0
        for (i in 0 until 30) {
            val ans = answers[i]
            // Choice 0..4 corresponds to options 1..5
            if (ans != null && (ans + 1) == keys[i]) {
                correctCount++
                if (i < 15) part1Correct++ else part2Correct++
            }
        }

        val scores = listOf(
            AssessmentScore(
                "Total Skor Mentah (RS)",
                correctCount.toDouble(),
                0.0,
                30.0,
                "Jumlah jawaban benar ($correctCount/30); konversi Weighted Score memerlukan norma resmi."
            ),
            AssessmentScore("Benar butir 1–15", part1Correct.toDouble(), 0.0, 15.0, "Paruh pertama tes figural."),
            AssessmentScore("Benar butir 16–30", part2Correct.toDouble(), 0.0, 15.0, "Paruh kedua tes figural.")
        )

        val interpretation = "Hasil Tes Inteligensi Umum 5 (TIU 5 Figural): Skor Mentah $correctCount/30 " +
            "(Butir 1–15: $part1Correct/15, Butir 16–30: $part2Correct/15; ${answeredSummary(answers.size, 30)}). " +
            "Konversi norma dan kategorisasi tingkat penalaran non-verbal wajib merujuk tabel norma terstandardisasi."
        return scores to interpretation
    }

    // =========================================================================
    // 7. Holland RIASEC Scoring (60 Items)
    // =========================================================================
    private fun enrichHolland(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val rScore = answers.filterKeys { it in 0..9 }.values.sum()
        val iScore = answers.filterKeys { it in 10..19 }.values.sum()
        val aScore = answers.filterKeys { it in 20..29 }.values.sum()
        val sScore = answers.filterKeys { it in 30..39 }.values.sum()
        val eScore = answers.filterKeys { it in 40..49 }.values.sum()
        val cScore = answers.filterKeys { it in 50..59 }.values.sum()

        val list = listOf(
            "R" to rScore,
            "I" to iScore,
            "A" to aScore,
            "S" to sScore,
            "E" to eScore,
            "C" to cScore
        ).sortedByDescending { it.second }

        val top3Code = "${list[0].first}${list[1].first}${list[2].first}"
        val diff = list.first().second - list.last().second
        val hasTies = list[0].second == list[1].second || list[1].second == list[2].second || list[2].second == list[3].second

        val scores = listOf(
            AssessmentScore("Realistic (R)", rScore.toDouble(), 0.0, 40.0, "Minat teknik, mesin, alam, dan fisik."),
            AssessmentScore("Investigative (I)", iScore.toDouble(), 0.0, 40.0, "Minat riset ilmiah, data, dan sains."),
            AssessmentScore("Artistic (A)", aScore.toDouble(), 0.0, 40.0, "Minat desain, seni, tulisan, dan ekspresi."),
            AssessmentScore("Social (S)", sScore.toDouble(), 0.0, 40.0, "Minat konseling, mengajar, dan menolong sesama."),
            AssessmentScore("Enterprising (E)", eScore.toDouble(), 0.0, 40.0, "Minat memimpin, bisnis, dan persuasi."),
            AssessmentScore("Conventional (C)", cScore.toDouble(), 0.0, 40.0, "Minat administrasi, akuntansi, dan prosedur."),
            AssessmentScore("Diferensiasi Minat", diff.toDouble(), 0.0, 40.0, "Selisih skor dimensi tertinggi dan terendah.")
        )

        val tieNote = if (hasTies) {
            " Catatan: Terdapat skor sama pada tiga dimensi teratas sehingga urutan kode dapat dipertimbangkan bersama wawancara minat."
        } else {
            ""
        }
        val interpretation = "Profil Minat Karier Holland (Kode Teratas: $top3Code, Diferensiasi: $diff poin). " +
            "Dimensi tertinggi: 1) ${list[0].first} (${list[0].second}/40), 2) ${list[1].first} (${list[1].second}/40), " +
            "3) ${list[2].first} (${list[2].second}/40). Arah eksplorasi memadukan aktivitas " +
            "${getHollandTitle(list[0].first)}, ${getHollandTitle(list[1].first)}, dan ${getHollandTitle(list[2].first)}.$tieNote"
        return scores to interpretation
    }

    private fun getHollandTitle(code: String): String = when (code) {
        "R" -> "Teknik & Operasional Fisik"
        "I" -> "Analisis Data & Riset Ilmiah"
        "A" -> "Kreatif & Desain Visual"
        "S" -> "Pelayanan Sosial & Edukasi"
        "E" -> "Kepemimpinan & Bisnis"
        else -> "Administrasi & Pengawasan Mutu"
    }

    // =========================================================================
    // 8. WPT Scoring (Wonderlic Personnel Test — 50 Items Raw & Subscale)
    // =========================================================================
    private fun enrichWpt(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val keys = listOf(
            3, 1, 2, 1, 2, 0, 2, 0, 0, 3,
            2, 2, 0, 1, 1, 1, 0, 1, 2, 0,
            1, 1, 1, 1, 2, 0, 0, 2, 1, 1,
            1, 0, 2, 1, 0, 2, 0, 1, 1, 1,
            0, 1, 3, 1, 2, 1, 2, 2, 1, 2
        )

        val verbalItems = setOf(0, 1, 2, 3, 4, 5, 8, 9, 10, 13, 16, 18, 22, 24, 27, 31, 32, 38, 40, 43, 45)
        val numericItems = setOf(7, 11, 14, 17, 20, 23, 26, 28, 29, 30, 33, 34, 35, 36, 42, 44, 47, 49)
        val logicItems = setOf(12, 19, 21, 25, 46)
        val spatialItems = setOf(6, 37, 41, 48)
        val clericalItems = setOf(15, 39)

        var correctCount = 0
        var verbalCorrect = 0
        var numericCorrect = 0
        var logicCorrect = 0
        var spatialCorrect = 0
        var clericalCorrect = 0

        for (i in 0 until 50) {
            val ans = answers[i]
            if (ans != null && ans == keys[i]) {
                correctCount++
                when (i) {
                    in verbalItems -> verbalCorrect++
                    in numericItems -> numericCorrect++
                    in logicItems -> logicCorrect++
                    in spatialItems -> spatialCorrect++
                    in clericalItems -> clericalCorrect++
                }
            }
        }

        val scores = listOf(
            AssessmentScore(
                "Total Skor Mentah (RS)",
                correctCount.toDouble(),
                0.0,
                50.0,
                "Skor mentah dalam batas waktu standar 12 menit."
            ),
            AssessmentScore("Penalaran Verbal", verbalCorrect.toDouble(), 0.0, 21.0, "Kosakata, analogi, pemahaman makna kalimat."),
            AssessmentScore("Penalaran Numerik", numericCorrect.toDouble(), 0.0, 18.0, "Aritmetika praktis, deret angka, persentase."),
            AssessmentScore("Penalaran Logika Deduktif", logicCorrect.toDouble(), 0.0, 5.0, "Silogisme dan konsistensi inferensi logis."),
            AssessmentScore("Penalaran Spasial & Geometri", spatialCorrect.toDouble(), 0.0, 4.0, "Rekonstruksi bidang dan bangun geometri."),
            AssessmentScore("Ketelitian Klerikal", clericalCorrect.toDouble(), 0.0, 2.0, "Kecepatan dan ketelitian deteksi duplikasi.")
        )

        val interpretation = buildString {
            append("Hasil Wonderlic Personnel Test (WPT): Skor Mentah $correctCount/50 (${answeredSummary(answers.size, 50)}).\n\n")
            append(
                "• Profil Subskala Mentah: Verbal ($verbalCorrect/21), Numerik ($numericCorrect/18), " +
                    "Logika Deduktif ($logicCorrect/5), Spasial-Geometri ($spatialCorrect/4), Ketelitian Klerikal ($clericalCorrect/2).\n"
            )
            append(
                "• Catatan Metodologis: WPT adalah instrumen skrining kecepatan kognitif dengan batas waktu 12 menit. " +
                    "Estimasi IQ, persentil, dan pembandingan jabatan tidak dihitung otomatis tanpa tabel norma resmi " +
                    "yang mempertimbangkan usia, latar belakang pendidikan, dan kecepatan membaca."
            )
        }
        return scores to interpretation
    }

    fun enrichWptRecap(part1: Int, part2: Int): Pair<List<AssessmentScore>, String> {
        val p1 = part1.coerceIn(0, 25)
        val p2 = part2.coerceIn(0, 25)
        val correctCount = (p1 + p2).coerceIn(0, 50)

        val scores = listOf(
            AssessmentScore("Total Skor Mentah (RS)", correctCount.toDouble(), 0.0, 50.0, "Jawaban benar pada rekap lembar WPT 50 butir."),
            AssessmentScore("Benar butir 1–25", p1.toDouble(), 0.0, 25.0, "Rekap butir paruh pertama."),
            AssessmentScore("Benar butir 26–50", p2.toDouble(), 0.0, 25.0, "Rekap butir paruh kedua.")
        )

        val interpretation = buildString {
            append("Hasil Rekap Wonderlic Personnel Test (WPT): Skor Mentah $correctCount/50.\n")
            append("• Rekap Paruh Lembar: Butir 1–25 ($p1/25), Butir 26–50 ($p2/25).\n")
            append("• Konversi persentil/IQ dan pembandingan jabatan memerlukan tabel norma resmi sesuai konteks asesmen.")
        }
        return scores to interpretation
    }

    fun enrichTiu5Recap(part1: Int, part2: Int): Pair<List<AssessmentScore>, String> {
        val p1 = part1.coerceIn(0, 15)
        val p2 = part2.coerceIn(0, 15)
        val correctCount = (p1 + p2).coerceIn(0, 30)

        val scores = listOf(
            AssessmentScore(
                "Total Skor Mentah (RS)",
                correctCount.toDouble(),
                0.0,
                30.0,
                "Jumlah benar: Part 1 ($p1) + Part 2 ($p2) / 30."
            ),
            AssessmentScore("Benar butir 1–15", p1.toDouble(), 0.0, 15.0, "Rekap butir paruh pertama."),
            AssessmentScore("Benar butir 16–30", p2.toDouble(), 0.0, 15.0, "Rekap butir paruh kedua.")
        )

        val interpretation = "Hasil Rekap Tes Inteligensi Umum 5 (TIU 5 Figural): Skor Mentah $correctCount/30 " +
            "(Butir 1–15: $p1/15, Butir 16–30: $p2/15). Konversi norma memerlukan manual TIU 5 resmi."
        return scores to interpretation
    }

    fun enrichTiu6Recap(part1: Int, part2: Int): Pair<List<AssessmentScore>, String> {
        val p1 = part1.coerceIn(0, 20)
        val p2 = part2.coerceIn(0, 20)
        val correctCount = (p1 + p2).coerceIn(0, 40)

        val scores = listOf(
            AssessmentScore("Total Jawaban Benar", correctCount.toDouble(), 0.0, 40.0, "Raw Score: $correctCount dari 40 butir."),
            AssessmentScore("Benar butir 1–20", p1.toDouble(), 0.0, 20.0, "Rekap butir 1–20."),
            AssessmentScore("Benar butir 21–40", p2.toDouble(), 0.0, 20.0, "Rekap butir 21–40.")
        )

        val interpretation = "Hasil Rekap Tes Inteligensi Umum 6 (Penalaran Spasial & Bangun Ruang): " +
            "Skor Mentah $correctCount/40 (Butir 1–20: $p1/20, Butir 21–40: $p2/20). " +
            "Konversi norma memerlukan tabel norma resmi TIU 6."
        return scores to interpretation
    }

    private fun format1(value: Double): String = String.format(Locale.forLanguageTag("id-ID"), "%.1f", value)

    // DISC Mapping Tables (24 Groups)
    private val DISC_MOST = listOf(
        mapOf(0 to "S", 1 to "I", 2 to "*", 3 to "C"),
        mapOf(0 to "C", 1 to "D", 2 to "*", 3 to "S"),
        mapOf(0 to "I", 1 to "*", 2 to "*", 3 to "D"),
        mapOf(0 to "C", 1 to "S", 2 to "*", 3 to "D"),
        mapOf(0 to "I", 1 to "D", 2 to "S", 3 to "*"),
        mapOf(0 to "C", 1 to "D", 2 to "I", 3 to "S"),
        mapOf(0 to "S", 1 to "I", 2 to "*", 3 to "*"),
        mapOf(0 to "I", 1 to "S", 2 to "C", 3 to "D"),
        mapOf(0 to "D", 1 to "C", 2 to "*", 3 to "*"),
        mapOf(0 to "*", 1 to "D", 2 to "S", 3 to "I"),
        mapOf(0 to "S", 1 to "*", 2 to "D", 3 to "C"),
        mapOf(0 to "*", 1 to "C", 2 to "I", 3 to "D"),
        mapOf(0 to "D", 1 to "S", 2 to "I", 3 to "*"),
        mapOf(0 to "C", 1 to "I", 2 to "S", 3 to "D"),
        mapOf(0 to "S", 1 to "C", 2 to "I", 3 to "D"),
        mapOf(0 to "*", 1 to "C", 2 to "I", 3 to "S"),
        mapOf(0 to "*", 1 to "D", 2 to "S", 3 to "I"),
        mapOf(0 to "D", 1 to "*", 2 to "*", 3 to "C"),
        mapOf(0 to "D", 1 to "S", 2 to "I", 3 to "*"),
        mapOf(0 to "D", 1 to "S", 2 to "I", 3 to "C"),
        mapOf(0 to "S", 1 to "D", 2 to "I", 3 to "*"),
        mapOf(0 to "S", 1 to "*", 2 to "D", 3 to "C"),
        mapOf(0 to "*", 1 to "I", 2 to "S", 3 to "*"),
        mapOf(0 to "*", 1 to "I", 2 to "D", 3 to "C")
    )

    private val DISC_LEAST = listOf(
        mapOf(0 to "S", 1 to "I", 2 to "D", 3 to "C"),
        mapOf(0 to "*", 1 to "D", 2 to "I", 3 to "S"),
        mapOf(0 to "I", 1 to "C", 2 to "S", 3 to "*"),
        mapOf(0 to "C", 1 to "S", 2 to "I", 3 to "D"),
        mapOf(0 to "*", 1 to "D", 2 to "S", 3 to "C"),
        mapOf(0 to "*", 1 to "D", 2 to "I", 3 to "S"),
        mapOf(0 to "*", 1 to "I", 2 to "C", 3 to "D"),
        mapOf(0 to "I", 1 to "S", 2 to "C", 3 to "D"),
        mapOf(0 to "D", 1 to "C", 2 to "I", 3 to "S"),
        mapOf(0 to "C", 1 to "D", 2 to "S", 3 to "*"),
        mapOf(0 to "*", 1 to "I", 2 to "D", 3 to "C"),
        mapOf(0 to "S", 1 to "*", 2 to "I", 3 to "D"),
        mapOf(0 to "D", 1 to "*", 2 to "*", 3 to "C"),
        mapOf(0 to "C", 1 to "I", 2 to "*", 3 to "D"),
        mapOf(0 to "S", 1 to "*", 2 to "I", 3 to "D"),
        mapOf(0 to "D", 1 to "*", 2 to "I", 3 to "S"),
        mapOf(0 to "C", 1 to "D", 2 to "S", 3 to "*"),
        mapOf(0 to "D", 1 to "I", 2 to "S", 3 to "*"),
        mapOf(0 to "D", 1 to "*", 2 to "I", 3 to "C"),
        mapOf(0 to "*", 1 to "S", 2 to "I", 3 to "*"),
        mapOf(0 to "S", 1 to "D", 2 to "I", 3 to "C"),
        mapOf(0 to "S", 1 to "I", 2 to "D", 3 to "C"),
        mapOf(0 to "D", 1 to "*", 2 to "S", 3 to "C"),
        mapOf(0 to "S", 1 to "I", 2 to "*", 3 to "*")
    )

    @Suppress("LongMethod")
    private fun enrich16pf(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val factorItems = mapOf(
            "MD" to listOf(0, 17, 34, 51, 68, 85, 102),
            "A" to listOf(1, 18, 35, 52, 69, 86),
            "B" to listOf(2, 19, 36, 53, 70, 87, 103, 104),
            "C" to listOf(3, 20, 37, 54, 71, 88),
            "E" to listOf(4, 21, 38, 55, 72, 89),
            "F" to listOf(5, 22, 39, 56, 73, 90),
            "G" to listOf(6, 23, 40, 57, 74, 91),
            "H" to listOf(7, 24, 41, 58, 75, 92),
            "I" to listOf(8, 25, 42, 59, 76, 93),
            "L" to listOf(9, 26, 43, 60, 77, 94),
            "M" to listOf(10, 27, 44, 61, 78, 95),
            "N" to listOf(11, 28, 45, 62, 79, 96),
            "O" to listOf(12, 29, 46, 63, 80, 97),
            "Q1" to listOf(13, 30, 47, 64, 81, 98),
            "Q2" to listOf(14, 31, 48, 65, 82, 99),
            "Q3" to listOf(15, 32, 49, 66, 83, 100),
            "Q4" to listOf(16, 33, 50, 67, 84, 101)
        )

        val factorBKeys = mapOf(2 to 1, 19 to 2, 36 to 1, 53 to 2, 70 to 0, 87 to 2, 103 to 0, 104 to 1)
        val twoPtsAItems = setOf(
            0, 3, 6, 7, 8, 9, 13, 14, 15, 16, 18, 20, 22, 25, 28, 29, 30, 32, 38, 40,
            44, 45, 48, 49, 51, 52, 54, 55, 56, 58, 59, 61, 62, 63, 65, 67, 69, 73, 74, 75,
            77, 78, 89, 94, 97, 98, 101
        )

        val rawScores = factorItems.mapValues { (factor, items) ->
            if (factor == "B") {
                items.sumOf { idx -> if (answers[idx] == factorBKeys[idx]) 1 else 0 }
            } else {
                items.sumOf { idx ->
                    val ans = answers[idx] ?: return@sumOf 0
                    val twoPtsChoice = if (idx in twoPtsAItems) 0 else 2
                    when (ans) {
                        1 -> 1
                        twoPtsChoice -> 2
                        else -> 0
                    }
                }
            }
        }

        val factorNamesIndo = mapOf(
            "A" to "Warmth (Kehangatan Interpersonal)",
            "B" to "Reasoning (Penalaran Abstrak)",
            "C" to "Emotional Stability (Kestabilan Emosi)",
            "E" to "Dominance (Dominansi / Asertivitas)",
            "F" to "Liveliness (Spontanitas / Kegairahan)",
            "G" to "Rule-Consciousness (Kepatuhan Norma)",
            "H" to "Social Boldness (Keberanian Sosial)",
            "I" to "Sensitivity (Kepekaan Perasaan)",
            "L" to "Vigilance (Kewaspadaan / Skeptisisme)",
            "M" to "Abstractedness (Imajinasi / Abstraksi)",
            "N" to "Privateness (Kecerdikan Sosial / Privasi)",
            "O" to "Apprehension (Kecenderungan Cemas)",
            "Q1" to "Openness to Change (Keterbukaan Perubahan)",
            "Q2" to "Self-Reliance (Kemandirian)",
            "Q3" to "Perfectionism (Perfeksionisme / Keteraturan)",
            "Q4" to "Tension (Ketegangan Energi)"
        )

        val factorLowPole = mapOf(
            "A" to "Berhati-hati, menjaga jarak",
            "B" to "Pemecahan masalah konkret",
            "C" to "Reaktif secara emosional",
            "E" to "Akomodatif, cenderung mengalah",
            "F" to "Serius, tenang, berhati-hati",
            "G" to "Fleksibel terhadap aturan",
            "H" to "Berhati-hati dalam situasi sosial",
            "I" to "Objektif, praktis, rasional",
            "L" to "Mudah percaya, menerima",
            "M" to "Praktis, fokus pada fakta kini",
            "N" to "Lugas, berterus terang",
            "O" to "Tenang, tidak mudah cemas",
            "Q1" to "Tradisional, menyukai cara mapan",
            "Q2" to "Berorientasi kelompok/tim",
            "Q3" to "Fleksibel terhadap keteraturan",
            "Q4" to "Santai, sabar, tenang"
        )

        val factorHighPole = mapOf(
            "A" to "Hangat, ramah, partisipatif",
            "B" to "Penangkapan konsep abstrak",
            "C" to "Stabil, tenang menghadapi realitas",
            "E" to "Asertif, dominan, teguh pendirian",
            "F" to "Spontan, antusias, ekspresif",
            "G" to "Tekun, patuh pada aturan",
            "H" to "Berani tampil sosial, tahan tekanan",
            "I" to "Peka rasa, empatik, estetis",
            "L" to "Waspada, kritis terhadap motif",
            "M" to "Imajinatif, berfokus ide",
            "N" to "Diplomatis, menjaga privasi",
            "O" to "Mudah khawatir, peka tekanan",
            "Q1" to "Terbuka pada perubahan/eksperimen",
            "Q2" to "Mandiri mengambil keputusan",
            "Q3" to "Teratur, terencana, disiplin diri",
            "Q4" to "Tegang, dorongan energi tinggi"
        )

        val scores = mutableListOf<AssessmentScore>()
        val factorKeys = listOf("A", "B", "C", "E", "F", "G", "H", "I", "L", "M", "N", "O", "Q1", "Q2", "Q3", "Q4")

        for (fKey in factorKeys) {
            val raw = rawScores[fKey] ?: 0
            val maxRaw = if (fKey == "B") 8 else 12
            val desc = "Skor mentah $raw/$maxRaw (${factorLowPole[fKey]} <-> ${factorHighPole[fKey]}). " +
                "Konversi sten memerlukan norma usia dan jenis kelamin."
            scores.add(AssessmentScore("Faktor $fKey: ${factorNamesIndo[fKey]}", raw.toDouble(), 0.0, maxRaw.toDouble(), desc))
        }

        val mdRaw = rawScores["MD"] ?: 0
        val mdAlert = if (mdRaw >= 8) {
            " Catatan validitas: Skor mentah MD ($mdRaw/14) relatif tinggi; tinjau kemungkinan faking good dalam wawancara."
        } else {
            ""
        }
        scores.add(
            AssessmentScore(
                "Skala Validitas MD (Raw)",
                mdRaw.toDouble(),
                0.0,
                14.0,
                "Skor mentah $mdRaw/14.$mdAlert"
            )
        )

        val interpretation = "Profil mentah 16PF Form C (16 faktor primer + skala MD; ${answeredSummary(answers.size, 105)}). " +
            "Raw score faktor penalaran (B): ${rawScores["B"] ?: 0}/8, kestabilan (C): ${rawScores["C"] ?: 0}/12, " +
            "kecemasan/apprehension (O): ${rawScores["O"] ?: 0}/12, ketegangan (Q4): ${rawScores["Q4"] ?: 0}/12, MD: $mdRaw/14.$mdAlert " +
            "Konversi ke skor Sten (1–10) dan lima faktor global wajib merujuk tabel norma terstratifikasi (usia & jenis kelamin)."

        return scores to interpretation
    }

    fun enrichArmyAlphaRecap(part1: Int, part2: Int): Pair<List<AssessmentScore>, String> {
        val p1 = part1.coerceIn(0, 6)
        val p2 = part2.coerceIn(0, 6)
        val totalCorrect = p1 + p2
        val scores = listOf(
            AssessmentScore(
                "Total Benar Army Alpha",
                totalCorrect.toDouble(),
                0.0,
                12.0,
                "Skor mentah ($totalCorrect/12); penetapan kategori skala memerlukan norma manual."
            ),
            AssessmentScore("Bagian 1 (Butir 1–6)", p1.toDouble(), 0.0, 6.0, "Rekap jawaban benar butir 1–6."),
            AssessmentScore("Bagian 2 (Butir 7–12)", p2.toDouble(), 0.0, 6.0, "Rekap jawaban benar butir 7–12.")
        )
        val interpretation = "Hasil Rekap Army Alpha (12 Butir): Total Skor Mentah $totalCorrect/12 " +
            "(Bagian 1: $p1/6, Bagian 2: $p2/6). Pemisahan subtes per kompetensi tidak diperkirakan dari rekap paruh lembar; " +
            "konversi skala dan interpretasi merujuk manual resmi serta catatan pemeriksa."
        return scores to interpretation
    }

    private fun enrichArmyAlpha(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val followingCommandsItems = setOf(0, 1, 2, 4, 5, 6, 10)
        val speedAccuracyItems = setOf(3, 7, 8, 9, 11)

        val dirCorrect = followingCommandsItems.count { answers[it] == 0 }
        val speedCorrect = speedAccuracyItems.count { answers[it] == 0 }
        val totalCorrect = dirCorrect + speedCorrect

        val scores = listOf(
            AssessmentScore(
                "Total Benar Army Alpha",
                totalCorrect.toDouble(),
                0.0,
                12.0,
                "Skor mentah ($totalCorrect/12); konversi skala memerlukan tabel norma resmi."
            ),
            AssessmentScore(
                "Subtes Following Commands",
                dirCorrect.toDouble(),
                0.0,
                7.0,
                "Ketelitian eksekusi perintah lisan visual (butir 1–3, 5–7, 11)."
            ),
            AssessmentScore(
                "Subtes Speed & Accuracy",
                speedCorrect.toDouble(),
                0.0,
                5.0,
                "Kecepatan pemrosesan logika & angka (butir 4, 8–10, 12)."
            )
        )

        val itemSummaries = (0 until 12).joinToString("\n") { idx ->
            val isCorrect = answers[idx] == 0
            val target = ArmyAlphaRecognitionEngine.TARGET_DESCRIPTIONS.getOrElse(idx) { "Target butir ${idx + 1}" }
            if (isCorrect) "• Butir ${idx + 1}: Benar (1) — $target" else "• Butir ${idx + 1}: Belum Tepat (0) — $target"
        }
        val fullInterpretation = "Hasil Tes Army Alpha: Total Skor Mentah $totalCorrect/12 " +
            "(Perintah Lisan $dirCorrect/7, Kecepatan & Ketelitian $speedCorrect/5). " +
            "Konversi skala memerlukan manual resmi.\n" +
            "Catatan: Evaluasi coretan digital merupakan alat bantu heuristik dan wajib ditinjau ulang oleh pemeriksa.\n\n" +
            "Ringkasan Deteksi per Butir:\n$itemSummaries"
        return scores to fullInterpretation
    }

    private fun enrichFrt(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val frtKeys = listOf(
            6, 4, 2, 2, 4, 3, 2, 4, 2, 5, 5, 2, 3, 4, 5,
            4, 5, 1, 5, 2, 6, 1, 3, 6, 3, 3, 4, 6, 2, 3,
            4, 5, 3, 2, 6, 1, 4, 2, 6, 1, 2, 4, 1, 5, 2
        ).map { it - 1 }

        val r = frtKeys.indices.count { idx -> answers[idx] == frtKeys[idx] }
        val w = frtKeys.indices.count { idx -> answers.containsKey(idx) && answers[idx] != frtKeys[idx] }
        val o = 45 - (r + w)
        val rawScore = (r - 0.25 * w).coerceAtLeast(0.0)

        val asosiatifCorrect = (0..14).count { answers[it] == frtKeys[it] }
        val sintesisCorrect = (15..29).count { answers[it] == frtKeys[it] }
        val analisisCorrect = (30..44).count { answers[it] == frtKeys[it] }

        val scores = listOf(
            AssessmentScore(
                label = "Skor Terkoreksi FRT",
                value = rawScore,
                minimum = 0.0,
                maximum = 45.0,
                meaning = "Rumus R - 0.25W (tanpa kategorisasi persentil otomatis)."
            ),
            AssessmentScore(
                label = "Jumlah Benar (R)",
                value = r.toDouble(),
                minimum = 0.0,
                maximum = 45.0,
                meaning = "Jumlah jawaban tepat dari 45 butir."
            ),
            AssessmentScore(
                label = "Jumlah Salah (W)",
                value = w.toDouble(),
                minimum = 0.0,
                maximum = 45.0,
                meaning = "Jumlah jawaban keliru (penalti 0.25 per butir)."
            ),
            AssessmentScore(
                label = "Jumlah Kosong (O)",
                value = o.toDouble(),
                minimum = 0.0,
                maximum = 45.0,
                meaning = "Jumlah butir tidak dijawab (tanpa penalti)."
            ),
            AssessmentScore(
                label = "Tahap Asosiatif",
                value = asosiatifCorrect.toDouble(),
                minimum = 0.0,
                maximum = 15.0,
                meaning = "Relasi dasar figur geometris (butir 1–15)."
            ),
            AssessmentScore(
                label = "Tahap Sintesis",
                value = sintesisCorrect.toDouble(),
                minimum = 0.0,
                maximum = 15.0,
                meaning = "Integrasi kaidah perubahan bentuk spasial (butir 16–30)."
            ),
            AssessmentScore(
                label = "Tahap Analisis",
                value = analisisCorrect.toDouble(),
                minimum = 0.0,
                maximum = 15.0,
                meaning = "Penalaran matriks kompleks (butir 31–45)."
            )
        )

        val interpretation = "Hasil Figure Reasoning Test (FRT Form A): Skor Terkoreksi " +
            "${String.format(Locale.US, "%.2f", rawScore)}/45 (Benar=$r, Salah=$w, Kosong=$o). " +
            "Capaian tahap: Asosiatif $asosiatifCorrect/15, Sintesis $sintesisCorrect/15, Analisis $analisisCorrect/15. " +
            "Kategorisasi norma memerlukan tabel persentil FRT sesuai usia dan latar belakang pendidikan."

        return scores to interpretation
    }

    fun enrichCfit2aRecap(r1: Int, r2: Int, r3: Int, r4: Int): Pair<List<AssessmentScore>, String> {
        return buildCfit2aScores(
            r1 = r1.coerceIn(0, 12),
            r2 = r2.coerceIn(0, 14),
            r3 = r3.coerceIn(0, 12),
            r4 = r4.coerceIn(0, 8)
        )
    }

    private fun enrichCfit2a(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val subtest1Keys = listOf(2, 3, 0, 2, 1, 4, 1, 2, 2, 2, 3, 0)
        val subtest2Keys = listOf(1, 2, 3, 0, 2, 2, 0, 4, 3, 2, 2, 2, 0, 3)
        val subtest3Keys = listOf(0, 2, 1, 4, 2, 0, 1, 3, 4, 0, 1, 1)
        val subtest4Keys = listOf(2, 1, 0, 3, 2, 2, 0, 1)

        val r1 = subtest1Keys.indices.count { answers[it] == subtest1Keys[it] }
        val r2 = subtest2Keys.indices.count { answers[12 + it] == subtest2Keys[it] }
        val r3 = subtest3Keys.indices.count { answers[26 + it] == subtest3Keys[it] }
        val r4 = subtest4Keys.indices.count { answers[38 + it] == subtest4Keys[it] }
        return buildCfit2aScores(r1, r2, r3, r4)
    }

    private fun buildCfit2aScores(r1: Int, r2: Int, r3: Int, r4: Int): Pair<List<AssessmentScore>, String> {
        val totalRaw = r1 + r2 + r3 + r4

        val scores = listOf(
            AssessmentScore(
                label = "Total Skor Mentah CFIT 2A",
                value = totalRaw.toDouble(),
                minimum = 0.0,
                maximum = 46.0,
                meaning = "Jumlah benar 4 subtes: $totalRaw/46 (konversi IQ memerlukan tabel norma usia resmi)."
            ),
            AssessmentScore(
                label = "Subtes 1: Seriasi",
                value = r1.toDouble(),
                minimum = 0.0,
                maximum = 12.0,
                meaning = "Kelanjutan deret logis berurutan."
            ),
            AssessmentScore(
                label = "Subtes 2: Klasifikasi",
                value = r2.toDouble(),
                minimum = 0.0,
                maximum = 14.0,
                meaning = "Identifikasi figur berbeda sifat."
            ),
            AssessmentScore(
                label = "Subtes 3: Matriks",
                value = r3.toDouble(),
                minimum = 0.0,
                maximum = 12.0,
                meaning = "Analogi dan kelengkapan matriks pola."
            ),
            AssessmentScore(
                label = "Subtes 4: Persyaratan Titik",
                value = r4.toDouble(),
                minimum = 0.0,
                maximum = 8.0,
                meaning = "Kondisi topologi spasial penempatan titik."
            )
        )

        val interpretation = "Profil CFIT Skala 2 Bentuk A menghasilkan Total Skor Mentah $totalRaw/46. " +
            "Capaian subtes: Seriasi $r1/12, Klasifikasi $r2/14, Matriks $r3/12, Persyaratan Titik $r4/8. " +
            "Konversi ke IQ standar dan kategorisasi kognitif wajib merujuk tabel norma usia resmi CFIT Scale 2."

        return scores to interpretation
    }

    private fun enrichCfit3a(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val subtest1Keys = listOf(1, 2, 1, 3, 4, 1, 3, 1, 5, 2, 1, 1, 4)
        val subtest2Masks = listOf(18, 17, 9, 20, 18, 9, 18, 18, 9, 10, 17, 12, 6, 3)
        val subtest3Keys = listOf(4, 4, 4, 1, 2, 3, 4, 4, 0, 0, 5, 2, 2)
        val subtest4Keys = listOf(1, 0, 3, 3, 0, 1, 2, 3, 0, 3)

        val r1 = subtest1Keys.indices.count { answers[it] == subtest1Keys[it] }
        val r2 = subtest2Masks.indices.count { answers[13 + it] == subtest2Masks[it] }
        val r3 = subtest3Keys.indices.count { answers[27 + it] == subtest3Keys[it] }
        val r4 = subtest4Keys.indices.count { answers[40 + it] == subtest4Keys[it] }
        return buildCfit3aScores(r1, r2, r3, r4)
    }

    fun enrichCfit3aRecap(r1: Int, r2: Int, r3: Int, r4: Int): Pair<List<AssessmentScore>, String> {
        return buildCfit3aScores(
            r1 = r1.coerceIn(0, 13),
            r2 = r2.coerceIn(0, 14),
            r3 = r3.coerceIn(0, 13),
            r4 = r4.coerceIn(0, 10)
        )
    }

    private fun buildCfit3aScores(r1: Int, r2: Int, r3: Int, r4: Int): Pair<List<AssessmentScore>, String> {
        val totalRaw = r1 + r2 + r3 + r4

        val scores = listOf(
            AssessmentScore(
                label = "Total Skor Mentah CFIT 3A",
                value = totalRaw.toDouble(),
                minimum = 0.0,
                maximum = 50.0,
                meaning = "Jumlah benar 4 subtes: $totalRaw/50 (konversi IQ memerlukan tabel norma resmi)."
            ),
            AssessmentScore(
                label = "Subtes 1: Deret",
                value = r1.toDouble(),
                minimum = 0.0,
                maximum = 13.0,
                meaning = "Kelanjutan deret figur logis kompleks."
            ),
            AssessmentScore(
                label = "Subtes 2: Klasifikasi",
                value = r2.toDouble(),
                minimum = 0.0,
                maximum = 14.0,
                meaning = "Identifikasi 2 figur berbeda (wajib kedua opsi tepat)."
            ),
            AssessmentScore(
                label = "Subtes 3: Matriks",
                value = r3.toDouble(),
                minimum = 0.0,
                maximum = 13.0,
                meaning = "Penalaran relasi matriks spasial nonverbal."
            ),
            AssessmentScore(
                label = "Subtes 4: Persyaratan Topologi",
                value = r4.toDouble(),
                minimum = 0.0,
                maximum = 10.0,
                meaning = "Kondisi relasi topologi spasial penempatan titik."
            )
        )

        val interpretation = "Profil CFIT Skala 3 Bentuk A (Dewasa & Pendidikan Tinggi) menunjukkan Total Skor Mentah " +
            "$totalRaw/50. Rincian subtes: Subtes 1 Deret $r1/13, Subtes 2 Klasifikasi $r2/14, Subtes 3 Matriks $r3/13, " +
            "Subtes 4 Topologi Titik $r4/10. Konversi skor standar IQ wajib merujuk tabel norma usia dan pendidikan resmi."

        return scores to interpretation
    }

    private fun enrichEnneagram(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val typeIndices = mapOf(
            1 to listOf(26, 38, 44, 46, 54, 65, 84, 87, 91, 102, 109, 117, 119, 124, 136, 137, 149, 155, 164, 169),
            2 to listOf(10, 11, 18, 25, 33, 37, 43, 59, 60, 73, 75, 80, 83, 88, 93, 96, 98, 133, 160, 167),
            3 to listOf(0, 5, 12, 20, 50, 55, 66, 72, 74, 85, 89, 111, 113, 134, 138, 142, 150, 162, 168, 173),
            4 to listOf(2, 7, 16, 21, 29, 40, 41, 42, 68, 79, 82, 92, 101, 110, 115, 141, 148, 156, 158, 177),
            5 to listOf(4, 30, 39, 51, 100, 105, 106, 114, 116, 125, 126, 129, 132, 135, 144, 153, 157, 159, 166, 174),
            6 to listOf(6, 15, 31, 35, 36, 48, 49, 53, 56, 58, 67, 70, 78, 95, 120, 128, 143, 152, 165, 172),
            7 to listOf(3, 17, 19, 52, 57, 69, 77, 90, 94, 97, 103, 112, 140, 146, 147, 151, 154, 171, 175, 179),
            8 to listOf(8, 9, 23, 24, 28, 61, 64, 81, 86, 104, 107, 121, 123, 130, 131, 139, 145, 161, 163, 178),
            9 to listOf(1, 13, 14, 22, 27, 32, 34, 45, 47, 62, 63, 71, 76, 99, 108, 118, 122, 127, 170, 176)
        )

        val typeNames = mapOf(
            1 to "The Reformer",
            2 to "The Helper",
            3 to "The Achiever",
            4 to "The Individualist",
            5 to "The Investigator",
            6 to "The Loyalist",
            7 to "The Enthusiast",
            8 to "The Challenger",
            9 to "The Peacemaker"
        )

        val typeMeanings = mapOf(
            1 to "Standar tinggi, teliti, berorientasi perbaikan.",
            2 to "Hangat, empatik, dorongan membantu orang lain.",
            3 to "Berorientasi pencapaian, adaptif, produktif.",
            4 to "Peka perasaan, ekspresif, menghargai autentisitas.",
            5 to "Analitis, ingin tahu, menjaga kemandirian berpikir.",
            6 to "Waspada, berkomitmen, mengutamakan kepastian dan keamanan.",
            7 to "Antusias, spontan, menyukai eksplorasi kemungkinan.",
            8 to "Tegas, mandiri, protektif, tahan menghadapi tantangan.",
            9 to "Tenang, diplomatis, menjaga keharmonisan."
        )

        val typeScores = typeIndices.entries.map { (typeNum, indices) ->
            typeNum to indices.sumOf { answers[it] ?: 0 }
        }.sortedByDescending { it.second }

        val scores = typeScores.map { (typeNum, score) ->
            AssessmentScore(
                label = "Tipe $typeNum: ${typeNames[typeNum]}",
                value = score.toDouble(),
                minimum = 0.0,
                maximum = 60.0,
                meaning = typeMeanings[typeNum].orEmpty()
            )
        }

        val top3Text = typeScores.take(3).joinToString(", ") { "Tipe ${it.first} (${it.second}/60)" }
        val hasTopTie = typeScores.size > 1 && typeScores[0].second == typeScores[1].second
        val tieNote = if (hasTopTie) " Terdapat skor sama pada posisi puncak sehingga perlu eksplorasi wawancara." else ""
        val interpretation = "Profil mentah Enneagram (180 butir; ${answeredSummary(answers.size, 180)}). " +
            "Tiga kecenderungan skor tertinggi: $top3Text.$tieNote " +
            "Hasil ini merupakan bahan refleksi kecenderungan motivasional dan bukan penetapan tipe klinis maupun sayap mutlak."

        return scores to interpretation
    }

    @Suppress("LongMethod")
    private fun enrichIst(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val seKeys = listOf(4, 2, 3, 3, 3, 1, 2, 0, 4, 1, 2, 3, 3, 4, 2, 0, 1, 1, 2, 1)
        val waKeys = listOf(1, 1, 3, 2, 2, 2, 2, 3, 3, 0, 4, 0, 0, 1, 2, 0, 3, 4, 1, 2)
        val anKeys = listOf(2, 4, 3, 3, 3, 0, 3, 1, 4, 3, 2, 2, 2, 2, 3, 2, 2, 3, 4, 4)
        val raKeys = listOf(2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 4, 2, 2)
        val zrKeys = listOf(2, 2, 1, 2, 2, 2, 2, 2, 1, 3, 2, 2, 3, 2, 3, 1, 3, 3, 2, 2)
        val faKeys = listOf(0, 2, 1, 0, 3, 1, 2, 4, 4, 3, 4, 1, 3, 2, 1, 0, 1, 3, 2, 0)
        val wuKeys = listOf(0, 2, 3, 4, 0, 2, 3, 2, 4, 0, 1, 3, 4, 1, 3, 1, 0, 4, 1, 2)
        val meKeys = listOf(3, 4, 1, 0, 2, 0, 3, 4, 2, 1, 1, 0, 4, 2, 3, 1, 4, 0, 2, 3)

        val rwSe = seKeys.indices.count { answers[it] == seKeys[it] }
        val rwWa = waKeys.indices.count { answers[20 + it] == waKeys[it] }
        val rwAn = anKeys.indices.count { answers[40 + it] == anKeys[it] }

        val geAnswered = (0 until 16).any { answers.containsKey(60 + it) }
        val gePoints = (0 until 16).sumOf { i ->
            when (answers[60 + i]) {
                0 -> 2
                1 -> 1
                else -> 0
            }
        }
        val rwGe = if (!geAnswered) {
            0
        } else {
            when {
                gePoints >= 31 -> 20
                gePoints == 30 -> 19
                gePoints == 29 -> 18
                gePoints == 28 -> 17
                gePoints == 27 -> 16
                gePoints in 25..26 -> 15
                gePoints in 23..24 -> 14
                gePoints in 21..22 -> 13
                gePoints in 19..20 -> 12
                gePoints in 17..18 -> 11
                gePoints in 15..16 -> 10
                gePoints in 13..14 -> 9
                gePoints in 11..12 -> 8
                gePoints in 9..10 -> 7
                gePoints in 7..8 -> 6
                gePoints in 5..6 -> 5
                gePoints == 4 -> 4
                gePoints == 3 -> 3
                gePoints == 2 -> 2
                else -> 1
            }
        }

        val rwRa = raKeys.indices.count { answers[76 + it] == raKeys[it] }
        val rwZr = zrKeys.indices.count { answers[96 + it] == zrKeys[it] }
        val rwFa = faKeys.indices.count { answers[116 + it] == faKeys[it] }
        val rwWu = wuKeys.indices.count { answers[136 + it] == wuKeys[it] }
        val rwMe = meKeys.indices.count { answers[156 + it] == meKeys[it] }

        val totalRaw = rwSe + rwWa + rwAn + rwGe + rwRa + rwZr + rwFa + rwWu + rwMe
        val answeredCount = answers.keys.count { it in 0 until 176 }

        val scores = listOf(
            AssessmentScore(
                label = "Total Skor Mentah IST",
                value = totalRaw.toDouble(),
                minimum = 0.0,
                maximum = 180.0,
                meaning = "Jumlah raw score 9 subtes ($totalRaw/180); konversi SW & IQ memerlukan tabel norma usia resmi."
            ),
            AssessmentScore("Subtes 1: SE (Melengkapi Kalimat)", rwSe.toDouble(), 0.0, 20.0, "Skor mentah: $rwSe/20."),
            AssessmentScore("Subtes 2: WA (Mencari Kata Berbeda)", rwWa.toDouble(), 0.0, 20.0, "Skor mentah: $rwWa/20."),
            AssessmentScore("Subtes 3: AN (Analogi Kata)", rwAn.toDouble(), 0.0, 20.0, "Skor mentah: $rwAn/20."),
            AssessmentScore(
                label = "Subtes 4: GE (Kesamaan Konsep)",
                value = rwGe.toDouble(),
                minimum = 0.0,
                maximum = 20.0,
                meaning = "Poin pilihan: $gePoints/32 (RW: $rwGe/20); adaptasi pilihan ganda (berbeda dari isian terbuka manual)."
            ),
            AssessmentScore("Subtes 5: RA (Hitungan Praktis)", rwRa.toDouble(), 0.0, 20.0, "Skor mentah: $rwRa/20."),
            AssessmentScore("Subtes 6: ZR (Deret Angka)", rwZr.toDouble(), 0.0, 20.0, "Skor mentah: $rwZr/20."),
            AssessmentScore("Subtes 7: FA (Bentuk Potongan 2D)", rwFa.toDouble(), 0.0, 20.0, "Skor mentah: $rwFa/20."),
            AssessmentScore("Subtes 8: WU (Kubus Balok 3D)", rwWu.toDouble(), 0.0, 20.0, "Skor mentah: $rwWu/20."),
            AssessmentScore("Subtes 9: ME (Daya Ingat Kata)", rwMe.toDouble(), 0.0, 20.0, "Skor mentah: $rwMe/20.")
        )

        val interpretation = "Profil Skor Mentah Intelligenz-Struktur-Test (IST; ${answeredSummary(answeredCount, 176)}): " +
            "Total RW = $totalRaw/180 (SE=$rwSe, WA=$rwWa, AN=$rwAn, GE=$rwGe [poin pilihan $gePoints/32], " +
            "RA=$rwRa, ZR=$rwZr, FA=$rwFa, WU=$rwWu, ME=$rwMe). " +
            "Catatan Metodologis: Subtes GE menggunakan pilihan ganda adaptasi (bukan skoring jawaban terbuka oleh pemeriksa), " +
            "dan konversi skor standar (SW) serta estimasi IQ Amthauer memerlukan tabel norma usia resmi IST."

        return scores to interpretation
    }

    private fun enrichKuder(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        var rawV = 0
        for (rule in AssessmentKuderData.RULES_V) {
            if (AssessmentKuderData.checkRule(rule, answers)) rawV++
        }
        val validityMeaning = when {
            rawV <= 37 -> "Kurang konsisten / perlu verifikasi (V = $rawV <= 37)."
            rawV <= 44 -> "Dalam rentang verifikasi standar (V = $rawV; 38–44)."
            else -> "Perlu verifikasi instruksi/pola respons (V = $rawV > 44)."
        }

        val scaleScores = AssessmentKuderData.VOCATIONAL_SCALES.map { (_, name, rules) ->
            var raw = 0
            for (rule in rules) {
                if (AssessmentKuderData.checkRule(rule, answers)) raw++
            }
            Triple(name, raw, rules.size.coerceAtLeast(1))
        }

        val top3 = scaleScores.sortedByDescending { it.second }.take(3)
        val scores = mutableListOf<AssessmentScore>()
        scores.add(
            AssessmentScore(
                label = "Skala Validitas (V)",
                value = rawV.toDouble(),
                minimum = 0.0,
                maximum = 63.0,
                meaning = validityMeaning
            )
        )
        for ((name, raw, maxRules) in scaleScores) {
            scores.add(
                AssessmentScore(
                    label = name,
                    value = raw.toDouble(),
                    minimum = 0.0,
                    maximum = maxRules.toDouble(),
                    meaning = "Skor mentah: $raw/$maxRules pilihan; persentil memerlukan tabel norma sesuai jenis kelamin."
                )
            )
        }

        val topDesc = top3.joinToString(", ") { "${it.first} (${it.second})" }
        val interpretation = "Hasil inventori minat Kuder (KPR-V Form C): $validityMeaning. " +
            "Tiga area dengan jumlah pilihan mentah tertinggi: $topDesc. " +
            "Persentil dan kategori tinggi/rendah tidak disimpulkan otomatis karena norma KPR-V mensyaratkan pemilihan kelompok jenis kelamin."

        return scores to interpretation
    }

    private fun enrichKraepelinDetailed(answers: Map<Int, Int>): EnrichedScoringOutcome {
        val cols = answers.keys.filter { it in 0..49 }.sorted()
        if (cols.isEmpty()) {
            return EnrichedScoringOutcome(
                scores = emptyList(),
                interpretation = "Tes Kraepelin belum memiliki data lajur yang selesai dikerjakan.",
                scoringStatus = InstrumentImplementationStatus.RAW_SCORE_ONLY,
                validityStatus = AssessmentValidityStatus.INVALID,
                validityWarnings = listOf("Belum ada lajur Kraepelin yang diselesaikan."),
                normReference = PsychometricInstrumentRegistry.specFor("kraepelin").normStatus
            )
        }

        val n = cols.size
        val yValues = cols.map { answers[it] ?: 0 }
        val errors = cols.map { answers[100 + it] ?: 0 }
        val skips = cols.map { answers[200 + it] ?: 0 }

        val sumY = yValues.sum()
        val meanY = sumY.toDouble() / n
        val totalErrors = errors.sum()
        val totalSkips = skips.sum()
        val tianker = totalErrors + totalSkips

        val yMin = yValues.minOrNull() ?: 0
        val yMax = yValues.maxOrNull() ?: 0
        val range = yMax - yMin
        val avDev = yValues.map { kotlin.math.abs(it - meanY) }.sum() / n

        val meanX = (n + 1) / 2.0
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val x = (i + 1).toDouble()
            val y = yValues[i].toDouble()
            num += (x - meanX) * (y - meanY)
            den += (x - meanX) * (x - meanX)
        }
        val bSlope = if (den > 0.0) num / den else 0.0
        val deltaY = bSlope * (n - 1)

        val observations = cols.mapIndexed { idx, colIdx ->
            AssessmentObservation(
                key = "kraepelin_col_${colIdx + 1}",
                label = "Lajur ${colIdx + 1}",
                value = yValues[idx].toString(),
                orderIndex = colIdx + 1,
                tags = listOf("err=${errors[idx]}", "skip=${skips[idx]}")
            )
        }

        val scores = listOf(
            AssessmentScore(
                label = "PANKER (Rerata Kecepatan)",
                value = meanY,
                minimum = 0.0,
                maximum = 30.0,
                meaning = "Rerata: ${String.format(Locale.US, "%.2f", meanY)} penjumlahan/lajur (Total: $sumY dalam $n lajur)."
            ),
            AssessmentScore(
                label = "TIANKER (Salah + Lewat)",
                value = tianker.toDouble(),
                minimum = 0.0,
                maximum = 100.0,
                meaning = "Salah: $totalErrors, Terlewati: $totalSkips (Total Tianker: $tianker)."
            ),
            AssessmentScore(
                label = "JANKER (Deviasi Rerata)",
                value = avDev,
                minimum = 0.0,
                maximum = 15.0,
                meaning = "Average Deviation: ${String.format(Locale.US, "%.2f", avDev)}, Rentang: $range ($yMin–$yMax)."
            ),
            AssessmentScore(
                label = "HANKER (Gradien Regresi)",
                value = bSlope,
                minimum = -2.0,
                maximum = 2.0,
                meaning = "Kemiringan b: ${String.format(Locale.US, "%.3f", bSlope)}, Perubahan tren Δy: ${String.format(Locale.US, "%.1f", deltaY)}."
            )
        )

        val warnings = if (n < 50) {
            listOf("Baru $n dari 50 lajur Kraepelin tercatat; interpretasi kurva utuh memerlukan kelengkapan penuh.")
        } else {
            emptyList()
        }
        val validity = if (n >= 40) AssessmentValidityStatus.VALID else AssessmentValidityStatus.QUESTIONABLE

        val interpretation = "Rekap kuantitatif Kraepelin ($n lajur tercatat): " +
            "PANKER rerata ${String.format(Locale.US, "%.2f", meanY)}/lajur (total $sumY), " +
            "TIANKER $tianker (salah=$totalErrors, lewat=$totalSkips), " +
            "JANKER deviasi rerata ${String.format(Locale.US, "%.2f", avDev)} (rentang $yMin–$yMax), " +
            "HANKER gradien tren b=${String.format(Locale.US, "%.3f", bSlope)}. " +
            "Kategorisasi norma sikap kerja merujuk norma populasi pembanding."

        return outcomeRaw(
            id = "kraepelin",
            pair = scores to interpretation,
            warnings = warnings,
            validity = validity,
            observations = observations
        )
    }

    private fun enrichMit(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val dims = listOf(
            Triple("mit_linguistic", "Linguistik (Word Smart)", 0..6),
            Triple("mit_logical", "Logis-Matematis (Logic/Number Smart)", 7..13),
            Triple("mit_spatial", "Visual-Spasial (Picture Smart)", 14..20),
            Triple("mit_bodily", "Kinestetik-Jasmani (Body Smart)", 21..27),
            Triple("mit_musical", "Musikal (Music Smart)", 28..34),
            Triple("mit_interpersonal", "Interpersonal (People Smart)", 35..41),
            Triple("mit_intrapersonal", "Intrapersonal (Self Smart)", 42..48),
            Triple("mit_naturalist", "Naturalis (Nature Smart)", 49..55)
        )

        val dimResults = dims.map { (_, name, range) ->
            val answered = range.mapNotNull { idx -> answers[idx]?.let { it.coerceIn(0, 3) + 1 } }
            val raw = answered.sum()
            val percentage = (raw.toDouble() / 28.0) * 100.0
            Triple(name, raw, percentage to answered.size)
        }

        val top3 = dimResults.sortedByDescending { it.second }.take(3)
        val scores = dimResults.map { (name, raw, pctAnswered) ->
            val (pct, answeredCount) = pctAnswered
            AssessmentScore(
                label = name,
                value = raw.toDouble(),
                minimum = 0.0,
                maximum = 28.0,
                meaning = "Skor mentah: $raw/28 (${String.format(Locale.US, "%.1f", pct)}%; $answeredCount/7 butir dijawab). Refleksi laporan diri."
            )
        }

        val topNames = top3.joinToString(", ") { "${it.first} (${it.second}/28)" }
        val interpretation = "Profil Reflektif Multiple Intelligences (56 butir; ${answeredSummary(answers.size, 56)}): " +
            "Tiga kecenderungan skor mentah tertinggi: $topNames. " +
            "Hasil ini bersifat refleksi diri deskriptif mengenai preferensi modalitas aktivitas dan bukan skor IQ tervalidasi."

        return scores to interpretation
    }

    private fun enrichMsaiDetailed(answers: Map<Int, Int>): EnrichedScoringOutcome {
        val skills = listOf(
            Triple("Managing Development of Others", listOf(5, 20, 24, 25, 47), "Clan"),
            Triple("Managing Interpersonal Relationships", listOf(1, 13, 23, 48, 50), "Clan"),
            Triple("Managing Teams", listOf(12, 18, 21, 22, 49), "Clan"),
            Triple("Managing Innovation", listOf(2, 8, 27, 45, 51), "Adhocracy"),
            Triple("Managing the Future", listOf(9, 14, 28, 46, 59), "Adhocracy"),
            Triple("Managing Continuous Improvement", listOf(26, 29, 44, 52, 53), "Adhocracy"),
            Triple("Managing Competitiveness", listOf(15, 30, 35, 42, 43), "Market"),
            Triple("Energising Employees", listOf(3, 6, 7, 31, 60), "Market"),
            Triple("Managing Customer Service", listOf(32, 33, 41, 54, 55), "Market"),
            Triple("Managing Coordination", listOf(11, 17, 37, 38, 57), "Hierarchy"),
            Triple("Managing the Control System", listOf(4, 16, 19, 36, 39), "Hierarchy"),
            Triple("Managing Acculturation", listOf(10, 34, 40, 56, 58), "Hierarchy")
        )

        val answeredCount = answers.keys.count { it in 0 until 60 }
        val skillScores: List<Triple<String, Double?, String>> = skills.map { (name, itemNums, quad) ->
            val vals = itemNums.mapNotNull { num -> answers[num - 1]?.let { it.coerceIn(0, 4) + 1 } }
            val avg: Double? = if (vals.isNotEmpty()) vals.average() else null
            Triple(name, avg, quad)
        }

        fun quadAvg(quadKey: String): Double? {
            val nonNull = skillScores.filter { it.third == quadKey && it.second != null }.mapNotNull { it.second }
            return if (nonNull.isNotEmpty()) nonNull.average() else null
        }

        val quadPairs = listOf(
            "Kuadran Clan (Collaborate)" to quadAvg("Clan"),
            "Kuadran Adhocracy (Create)" to quadAvg("Adhocracy"),
            "Kuadran Market (Compete)" to quadAvg("Market"),
            "Kuadran Hierarchy (Control)" to quadAvg("Hierarchy")
        )

        val scores = mutableListOf<AssessmentScore>()
        quadPairs.forEach { (label, avg) ->
            if (avg != null) {
                scores += AssessmentScore(
                    label = label,
                    value = avg,
                    minimum = 1.0,
                    maximum = 5.0,
                    meaning = "Rerata kompetensi terjawab pada kuadran: ${String.format(Locale.US, "%.2f", avg)}/5.00."
                )
            }
        }
        skillScores.forEach { (name, avg, quad) ->
            if (avg != null) {
                scores += AssessmentScore(
                    label = "$name ($quad)",
                    value = avg,
                    minimum = 1.0,
                    maximum = 5.0,
                    meaning = "Rerata kompetensi ($quad): ${String.format(Locale.US, "%.2f", avg)}/5.00."
                )
            }
        }

        val validQuads = quadPairs.mapNotNull { (name, avg) -> avg?.let { name to it } }.sortedByDescending { it.second }
        val validSkills = skillScores.mapNotNull { (name, avg, quad) -> avg?.let { Triple(name, it, quad) } }
        val dominantText = validQuads.firstOrNull()?.let {
            "${it.first} (${String.format(Locale.US, "%.2f", it.second)}/5.00)"
        } ?: "Belum ada kuadran terisi"
        val topSkillText = validSkills.maxByOrNull { it.second }?.let {
            "${it.first} (${String.format(Locale.US, "%.2f", it.second)}/5.00)"
        } ?: "-"
        val lowSkillText = validSkills.minByOrNull { it.second }?.let {
            "${it.first} (${String.format(Locale.US, "%.2f", it.second)}/5.00)"
        } ?: "-"

        val warnings = if (answeredCount < 60) {
            listOf(
                "Protokol MSAI belum lengkap ($answeredCount/60 butir); " +
                    "kompetensi tanpa jawaban tidak diimputasi dengan nilai default 3.0."
            )
        } else {
            emptyList()
        }
        val validity = when {
            answeredCount == 0 -> AssessmentValidityStatus.INVALID
            answeredCount < 60 -> AssessmentValidityStatus.QUESTIONABLE
            else -> AssessmentValidityStatus.VALID
        }

        val interpretation = "Profil Competing Values Framework MSAI ($answeredCount/60 butir terjawab): " +
            "Kuadran tertinggi berdasarkan data terjawab adalah $dominantText. " +
            "Skor kompetensi tertinggi: $topSkillText; terendah: $lowSkillText. " +
            "Laporan dihitung hanya dari butir yang benar-benar dijawab."

        return outcomeRaw("msai", scores to interpretation, warnings = warnings, validity = validity)
    }

    private fun enrichMsdtDetailed(answers: Map<Int, Int>): EnrichedScoringOutcome {
        val answeredCount = (0 until 64).count { answers.containsKey(it) }
        val isComplete = answeredCount == 64

        val koreksi = listOf(1, 2, 1, 0, 3, -1, 0, -4)
        val rowA = IntArray(8)
        val colB = IntArray(8)

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val qIdx = r * 8 + c
                val ans = answers[qIdx]
                if (ans == 0) {
                    rowA[r]++
                } else if (ans == 1) {
                    colB[c]++
                }
            }
        }

        val jumlah = IntArray(8) { k -> rowA[k] + colB[k] + koreksi[k] }
        val styleNames = listOf(
            "Deserter (Ds - Pasif)" to "Separated; berorientasi rendah pada tugas maupun relasi.",
            "Missionary (Mi - Berorientasi Relasi)" to "Related; berfokus tinggi pada keharmonisan relasi.",
            "Autocrat (Au - Berorientasi Tugas)" to "Dedicated; berfokus tinggi pada arahan tugas.",
            "Compromiser (Co - Kompromis)" to "Integrated; cenderung mencari jalan tengah antara tugas dan relasi.",
            "Bureaucrat (Bu - Prosedural)" to "Separated + Efektif; menekankan aturan dan ketertiban prosedur.",
            "Developer (Dv - Pembina)" to "Related + Efektif; menekankan pengembangan dan pendelegasian tim.",
            "Benevolent Autocrat (Ba - Tegas Terstruktur)" to "Dedicated + Efektif; menjaga target secara terarah.",
            "Executive (E - Terintegrasi)" to "Integrated + Efektif; memadukan standar tugas dan kolaborasi."
        )

        val to = jumlah[2] + jumlah[3] + jumlah[6] + jumlah[7]
        val ro = jumlah[1] + jumlah[3] + jumlah[5] + jumlah[7]
        val ef = jumlah[4] + jumlah[5] + jumlah[6] + jumlah[7]

        val rankedStyles = styleNames.indices.map { idx ->
            Triple(styleNames[idx].first, jumlah[idx], styleNames[idx].second)
        }.sortedByDescending { it.second }

        val dominantStyle = rankedStyles.first()
        val secondaryStyle = rankedStyles[1]

        val scores = mutableListOf<AssessmentScore>()
        scores.add(
            AssessmentScore(
                label = "Task Orientation (TO)",
                value = to.toDouble(),
                minimum = -10.0,
                maximum = 50.0,
                meaning = "Orientasi tugas (Au+Co+Ba+E = $to)."
            )
        )
        scores.add(
            AssessmentScore(
                label = "Relationship Orientation (RO)",
                value = ro.toDouble(),
                minimum = -10.0,
                maximum = 50.0,
                meaning = "Orientasi relasi (Mi+Co+Dv+E = $ro)."
            )
        )
        scores.add(
            AssessmentScore(
                label = "Effectiveness (E)",
                value = ef.toDouble(),
                minimum = -10.0,
                maximum = 50.0,
                meaning = "Efektivitas situasional (Bu+Dv+Ba+E = $ef)."
            )
        )
        rankedStyles.forEach { (name, scoreVal, desc) ->
            scores.add(
                AssessmentScore(
                    label = name,
                    value = scoreVal.toDouble(),
                    minimum = -10.0,
                    maximum = 30.0,
                    meaning = "Skor matriks: $scoreVal. $desc"
                )
            )
        }

        val warnings = if (!isComplete) {
            listOf(
                "Protokol MSDT tidak lengkap ($answeredCount/64); " +
                    "matriks 8 kuadran dan konstanta koreksi memerlukan kelengkapan penuh."
            )
        } else {
            emptyList()
        }
        val validity = if (isComplete) AssessmentValidityStatus.VALID else AssessmentValidityStatus.INVALID
        val incompleteNote = if (!isComplete) {
            " PERINGATAN VALIDITAS: Baru $answeredCount/64 butir terjawab sehingga konstanta koreksi tidak representatif."
        } else {
            ""
        }

        val interpretation = "Profil MSDT Reddin ($answeredCount/64 butir): gaya matriks tertinggi sementara adalah ${dominantStyle.first} " +
            "(Skor ${dominantStyle.second}), diikuti ${secondaryStyle.first} (Skor ${secondaryStyle.second}). " +
            "Dimensi: TO=$to, RO=$ro, Effectiveness=$ef.$incompleteNote"

        return outcomeRaw("msdt", scores to interpretation, warnings = warnings, validity = validity)
    }

    private val papiItemScales = listOf(
        "G" to "E", "A" to "N", "P" to "A", "X" to "P", "B" to "X",
        "O" to "B", "Z" to "O", "K" to "Z", "F" to "K", "W" to "F",
        "G" to "C", "L" to "E", "P" to "N", "X" to "A", "B" to "P",
        "O" to "X", "Z" to "B", "K" to "O", "F" to "Z", "W" to "K",
        "G" to "D", "L" to "C", "I" to "E", "X" to "N", "B" to "A",
        "O" to "P", "Z" to "X", "K" to "B", "F" to "O", "W" to "Z",
        "G" to "R", "L" to "D", "I" to "C", "T" to "E", "B" to "N",
        "O" to "A", "Z" to "P", "K" to "X", "F" to "B", "W" to "O",
        "G" to "S", "L" to "R", "I" to "D", "T" to "C", "V" to "E",
        "O" to "N", "Z" to "A", "K" to "P", "F" to "X", "W" to "B",
        "G" to "V", "L" to "S", "I" to "R", "T" to "D", "V" to "C",
        "S" to "E", "Z" to "N", "K" to "A", "F" to "P", "W" to "X",
        "G" to "T", "L" to "V", "I" to "S", "T" to "R", "V" to "D",
        "S" to "C", "R" to "E", "K" to "N", "F" to "A", "W" to "P",
        "G" to "I", "L" to "T", "I" to "V", "T" to "S", "V" to "R",
        "S" to "D", "R" to "C", "D" to "E", "F" to "N", "W" to "A",
        "G" to "L", "L" to "I", "I" to "T", "T" to "V", "V" to "S",
        "S" to "R", "R" to "D", "D" to "C", "C" to "E", "W" to "N"
    )

    private fun enrichPapikostick(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val scaleCounts = mutableMapOf<String, Int>()
        val scaleCodes = listOf(
            "G", "L", "I", "T", "V", "S", "R", "D", "C", "E",
            "N", "A", "P", "X", "B", "O", "Z", "K", "F", "W"
        )
        scaleCodes.forEach { scaleCounts[it] = 0 }

        var answeredCount = 0
        answers.forEach { (qIdx, ans) ->
            if (qIdx in papiItemScales.indices && (ans == 0 || ans == 1)) {
                answeredCount++
                val pair = papiItemScales[qIdx]
                if (ans == 0) {
                    scaleCounts[pair.first] = (scaleCounts[pair.first] ?: 0) + 1
                } else {
                    scaleCounts[pair.second] = (scaleCounts[pair.second] ?: 0) + 1
                }
            }
        }

        val scaleLabels = mapOf(
            "G" to "Peran Pekerja Keras (Hard Intense Worker)",
            "L" to "Peran Kepemimpinan (Leadership Role)",
            "I" to "Peran Pembuat Keputusan (Ease in Decision Making)",
            "T" to "Peran Tipe Sibuk / Pace (Pace of Work)",
            "V" to "Peran Penuh Vitalitas (Vigorous Type)",
            "S" to "Peran Hubungan Sosial (Social Extension)",
            "R" to "Peran Berpikir Teoritis (Theoretical Type)",
            "D" to "Peran Bekerja dengan Detail (Interest in Details)",
            "C" to "Peran Keteraturan (Organized Type)",
            "E" to "Peran Pengendalian Emosi (Emotional Restraint)",
            "N" to "Kebutuhan Menyelesaikan Tugas (Need to Finish Task)",
            "A" to "Kebutuhan Berprestasi (Need to Achieve)",
            "P" to "Kebutuhan Mengendalikan Orang Lain (Need to Control)",
            "X" to "Kebutuhan Menjadi Pusat Perhatian (Need to be Noticed)",
            "B" to "Kebutuhan Menjadi Bagian Kelompok (Need to Belong)",
            "O" to "Kebutuhan Kedekatan & Afeksi (Need for Closeness)",
            "Z" to "Kebutuhan Perubahan / Variasi (Need for Change)",
            "K" to "Kebutuhan Membela Diri / Agresi (Need to be Forceful)",
            "F" to "Kebutuhan Dukungan Atasan (Need for Support)",
            "W" to "Kebutuhan Arahan & Aturan (Need for Rules/Supervision)"
        )

        val scores = scaleCodes.map { code ->
            val count = scaleCounts[code] ?: 0
            AssessmentScore(
                label = "${scaleLabels[code]} [$code]",
                value = count.toDouble(),
                minimum = 0.0,
                maximum = 9.0,
                meaning = "Skor mentah: $count/9."
            )
        }

        val sorted = scaleCodes.sortedByDescending { scaleCounts[it] ?: 0 }
        val top3 = sorted.take(3).joinToString(", ") { "$it (${scaleCounts[it] ?: 0}/9)" }
        val bottom3 = sorted.takeLast(3).joinToString(", ") { "$it (${scaleCounts[it] ?: 0}/9)" }

        val interpretation = "Profil Skor Mentah PAPI Kostick ($answeredCount/90 pasang butir terjawab): " +
            "Skor relatif tertinggi pada $top3; skor relatif terendah pada $bottom3. " +
            "PAPI Kostick bersifat ipsatif (forced-choice 90 pasang) sehingga interpretasi mempertimbangkan keseluruhan 20 aspek peran dan kebutuhan kerja."

        return scores to interpretation
    }

    private fun enrichPauliDetailed(answers: Map<Int, Int>): EnrichedScoringOutcome {
        val cols = answers.keys.filter { it in 0..99 }.sorted()
        if (cols.isEmpty()) {
            return EnrichedScoringOutcome(
                scores = emptyList(),
                interpretation = "Tes Pauli belum memiliki data interval yang tersimpan.",
                scoringStatus = InstrumentImplementationStatus.RAW_SCORE_ONLY,
                validityStatus = AssessmentValidityStatus.INVALID,
                validityWarnings = listOf("Belum ada interval Pauli yang tersimpan."),
                normReference = PsychometricInstrumentRegistry.specFor("pauli").normStatus
            )
        }

        val intervalVals = cols.map { answers[it] ?: 0 }
        val errors = cols.sumOf { answers[100 + it] ?: 0 }
        val skips = cols.sumOf { answers[200 + it] ?: 0 }
        val n = cols.size
        val sumAdditions = intervalVals.sum()
        val meanPerf = sumAdditions.toDouble() / n
        val maxInterval = intervalVals.maxOrNull() ?: 0
        val minInterval = intervalVals.minOrNull() ?: 0
        val maxIdx = intervalVals.indexOf(maxInterval) + 1
        val minIdx = intervalVals.indexOf(minInterval) + 1
        val avDev = intervalVals.map { kotlin.math.abs(it - meanPerf) }.average()

        val third = (n / 3).coerceAtLeast(1)
        val earlyMean = intervalVals.take(third).average()
        val lateMean = intervalVals.takeLast(third).average()

        val observations = cols.mapIndexed { idx, col ->
            val err = answers[100 + col] ?: 0
            val skip = answers[200 + col] ?: 0
            AssessmentObservation(
                key = "pauli_interval_${idx + 1}",
                label = "Interval ${col + 1}",
                value = intervalVals[idx].toString(),
                orderIndex = col + 1,
                tags = listOf("err=$err", "skip=$skip")
            )
        }

        val scores = listOf(
            AssessmentScore(
                label = "Total Penjumlahan",
                value = sumAdditions.toDouble(),
                minimum = 0.0,
                maximum = 2500.0,
                meaning = "Total penjumlahan valid dalam $n interval."
            ),
            AssessmentScore(
                label = "Rerata per Interval",
                value = meanPerf,
                minimum = 0.0,
                maximum = 100.0,
                meaning = "Rerata: ${String.format(Locale.US, "%.2f", meanPerf)} penjumlahan per interval."
            ),
            AssessmentScore(
                label = "Puncak Interval",
                value = maxInterval.toDouble(),
                minimum = 0.0,
                maximum = 100.0,
                meaning = "Puncak $maxInterval pada interval ke-$maxIdx (terendah $minInterval pada interval ke-$minIdx)."
            ),
            AssessmentScore(
                label = "Simpangan Deviasi",
                value = avDev,
                minimum = 0.0,
                maximum = 20.0,
                meaning = "Deviasi rerata antarinterval: ${String.format(Locale.US, "%.2f", avDev)}."
            ),
            AssessmentScore(
                label = "Total Salah & Lewatan",
                value = (errors + skips).toDouble(),
                minimum = 0.0,
                maximum = 200.0,
                meaning = "Salah: $errors, Terlewati: $skips (tidak dihitung sebagai interval kerja)."
            )
        )

        val interpretation = "Rekap kuantitatif Tes Pauli ($n interval kerja murni): " +
            "Total Penjumlahan = $sumAdditions, Rerata/interval = ${String.format(Locale.US, "%.2f", meanPerf)}, " +
            "Puncak = $maxInterval (interval ke-$maxIdx), Terendah = $minInterval (interval ke-$minIdx), " +
            "Deviasi rerata = ${String.format(Locale.US, "%.2f", avDev)}, Salah = $errors, Lewatan = $skips. " +
            "Perbandingan rerata sepertiga awal (${String.format(Locale.US, "%.1f", earlyMean)}) ke sepertiga akhir " +
            "(${String.format(Locale.US, "%.1f", lateMean)}) ditampilkan sebagai tren deskriptif."

        return outcomeRaw("pauli", scores to interpretation, observations = observations)
    }

    private fun enrichPersonalityTest(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val pYes = setOf(10, 14, 22, 31, 39)
        val pNo = setOf(2, 6, 18, 26, 28, 35, 43)
        val eYes = setOf(3, 7, 11, 15, 19, 23, 32, 36, 44, 48)
        val eNo = setOf(27, 41)
        val nYes = setOf(1, 5, 9, 13, 17, 21, 25, 30, 34, 38, 42, 46)
        val lYes = setOf(4, 16, 45)
        val lNo = setOf(8, 12, 20, 24, 29, 33, 37, 40, 47)

        var pScore = 0
        var eScore = 0
        var nScore = 0
        var lScore = 0

        for (itemNum in 1..48) {
            val ans = answers[itemNum - 1] ?: continue
            val isYes = (ans == 0)
            val isNo = (ans == 1)

            if ((itemNum in pYes && isYes) || (itemNum in pNo && isNo)) pScore++
            if ((itemNum in eYes && isYes) || (itemNum in eNo && isNo)) eScore++
            if (itemNum in nYes && isYes) nScore++
            if ((itemNum in lYes && isYes) || (itemNum in lNo && isNo)) lScore++
        }

        val lieNote = if (lScore >= 7) {
            " Catatan validitas: Skor L ($lScore/12) tinggi; tinjau kemungkinan respons defensif."
        } else {
            ""
        }

        val scores = listOf(
            AssessmentScore(
                label = "Extraversion (E)",
                value = eScore.toDouble(),
                minimum = 0.0,
                maximum = 12.0,
                meaning = "Skor mentah E: $eScore/12 (EPQ-RS 48-butir; memerlukan validasi norma lokal)."
            ),
            AssessmentScore(
                label = "Neuroticism (N)",
                value = nScore.toDouble(),
                minimum = 0.0,
                maximum = 12.0,
                meaning = "Skor mentah N: $nScore/12."
            ),
            AssessmentScore(
                label = "Psychoticism (P)",
                value = pScore.toDouble(),
                minimum = 0.0,
                maximum = 12.0,
                meaning = "Skor mentah P: $pScore/12."
            ),
            AssessmentScore(
                label = "Lie Scale (L)",
                value = lScore.toDouble(),
                minimum = 0.0,
                maximum = 12.0,
                meaning = "Skor mentah L: $lScore/12.$lieNote"
            )
        )

        val interpretation = "Skor mentah kandidat EPQ-RS 48-butir (${answeredSummary(answers.size, 48)}): " +
            "E=$eScore/12, N=$nScore/12, P=$pScore/12, L=$lScore/12.$lieNote " +
            "Klasifikasi temperamen dan perbandingan normatif memerlukan tabel norma tervalidasi serta verifikasi item lokal."

        return scores to interpretation
    }

    private fun enrichWarteggDetailed(answers: Map<Int, Int>): EnrichedScoringOutcome {
        val drafts = (0 until 8).map { boxIdx ->
            val raw = answers[boxIdx]
            if (raw == null) {
                WarteggBoxDraft(boxNumber = boxIdx + 1)
            } else {
                val drawOrder = if (raw >= 10) (raw / 10).takeIf { it in 1..8 } else null
                val evalCode = if (raw >= 10) raw % 10 else raw
                val valence = when (evalCode) {
                    0 -> "+"
                    1 -> "-"
                    else -> null
                }
                val difficulty = when (evalCode) {
                    2 -> "M"
                    3 -> "S"
                    else -> null
                }
                WarteggBoxDraft(
                    boxNumber = boxIdx + 1,
                    title = if (valence == null && difficulty == null) "Input Kotak ${boxIdx + 1}" else "",
                    drawOrder = drawOrder,
                    valence = valence,
                    difficulty = difficulty
                )
            }
        }
        return scoreWarteggFromDrafts(drafts)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun enrichRmibDetailed(answers: Map<Int, Int>): EnrichedScoringOutcome {
        val catNames = listOf(
            "Outdoor (OUT)", "Mechanical (ME)", "Computational (COMP)", "Scientific (SCI)",
            "Personal Contact (PRS)", "Aesthetic (AESTH)", "Literary (LIT)", "Musical (MUS)",
            "Social Service (S.S)", "Clerical (CLER)", "Practical (PRAC)", "Medical (MED)"
        )
        val catMeanings = listOf(
            "Minat aktivitas lapangan, alam terbuka, pertanian, dan lingkungan.",
            "Minat pada permesinan, keteknikan, dan rancang bangun.",
            "Minat pada perhitungan angka, akuntansi, dan analisis finansial.",
            "Minat pada riset sains, eksperimen laboratorium, biologi, dan kimia.",
            "Minat pada relasi interpersonal, persuasi, komunikasi, dan pemasaran.",
            "Minat pada seni rupa, arsitektur, desain grafis, dan estetika visual.",
            "Minat pada sastra, membaca, menulis artikel, dan kepustakaan.",
            "Minat pada musik, instrumen, vokal, dan komposisi nada.",
            "Minat pada bimbingan kemanusiaan, pendidikan, dan pelayanan sosial.",
            "Minat pada ketatausahaan, pengarsipan, dan administrasi kantor.",
            "Minat pada keterampilan tangan praktis dan reparasi.",
            "Minat pada kedokteran, farmasi, dan perawatan kesehatan."
        )

        val completeGroups = (0 until 9).count { g ->
            val groupRanks = (0 until 12).mapNotNull { pos -> answers[g * 12 + pos] }.filter { it in 1..12 }
            groupRanks.toSet().size == 12
        }

        val catScores = IntArray(12)
        for (k in 0 until 12) {
            var sum = 0
            for (g in 0 until 9) {
                val p = (k - g).mod(12)
                val rank = answers[g * 12 + p]?.takeIf { it in 1..12 }
                if (rank != null) {
                    sum += rank
                }
            }
            catScores[k] = sum
        }

        val totalSum = catScores.sum()
        val sortedIndices = (0 until 12).sortedBy { catScores[it] }
        val rankMap = mutableMapOf<Int, Int>()
        sortedIndices.forEachIndexed { rankIndex, catIdx -> rankMap[catIdx] = rankIndex + 1 }

        val scores = mutableListOf<AssessmentScore>()
        for (k in 0 until 12) {
            val r = rankMap[k] ?: 12
            scores.add(
                AssessmentScore(
                    label = "${catNames[k]} [Rank $r]",
                    value = catScores[k].toDouble(),
                    minimum = 0.0,
                    maximum = 108.0,
                    meaning = "Skor mentah: ${catScores[k]} (peringkat relatif #$r; skor lebih kecil = minat lebih tinggi). ${catMeanings[k]}"
                )
            )
        }
        scores.add(
            AssessmentScore(
                label = "Total Skor RMIB",
                value = totalSum.toDouble(),
                minimum = 0.0,
                maximum = 702.0,
                meaning = "Total matriks: $totalSum (invarian lengkap 9 kelompok = 702; kelompok lengkap: $completeGroups/9)."
            )
        )

        val warnings = if (completeGroups < 9) {
            listOf("Baru $completeGroups/9 kelompok RMIB terisi lengkap (peringkat unik 1–12 tidak diisi otomatis).")
        } else {
            emptyList()
        }
        val validity = if (completeGroups == 9) AssessmentValidityStatus.VALID else AssessmentValidityStatus.INVALID

        val top1 = sortedIndices[0]
        val top2 = sortedIndices[1]
        val top3 = sortedIndices[2]
        val bot1 = sortedIndices[11]
        val incompleteNote = if (completeGroups < 9) {
            " Peringatan: baru $completeGroups/9 kelompok lengkap sehingga peringkat belum mencerminkan matriks utuh."
        } else {
            ""
        }

        val interpretation = "Profil Rothwell–Miller Interest Blank (RMIB; $completeGroups/9 kelompok lengkap, total=$totalSum/702): " +
            "Minat teratas (skor terendah): 1) ${catNames[top1]} (${catScores[top1]}), " +
            "2) ${catNames[top2]} (${catScores[top2]}), 3) ${catNames[top3]} (${catScores[top3]}). " +
            "Paling kurang diminati: ${catNames[bot1]} (${catScores[bot1]}).$incompleteNote"

        return outcomeRaw("rmib", scores to interpretation, warnings = warnings, validity = validity)
    }

    @Suppress("LongMethod")
    private fun enrichVrtDetailed(answers: Map<Int, Int>): EnrichedScoringOutcome {
        val subtestNames = listOf(
            "Subtes 1: Absurdities",
            "Subtes 2: Intruders",
            "Subtes 3: Relationships",
            "Subtes 4: Differences",
            "Subtes 5: Idiomatic Expressions",
            "Subtes 6: Family Relations",
            "Subtes 7: Classifications"
        )
        val subtestMeanings = listOf(
            "Penalaran kritis menemukan ketidaksesuaian logis verbal.",
            "Diskriminasi semantik elemen yang tidak selaras.",
            "Analogi verbal dan relasi fungsional konsep.",
            "Pembedaan esensial dua konsep verbal.",
            "Pemahaman kiasan dan makna non-literal.",
            "Logika deduktif hubungan kekerabatan.",
            "Kategorisasi abstrak dan konsep superordinat."
        )

        val answeredCount = answers.keys.count { it in 0 until 49 }
        val subtestScores = (0 until 7).map { s ->
            (s * 7 until (s + 1) * 7).sumOf { i ->
                when (answers[i]) {
                    0 -> 2
                    1 -> 1
                    else -> 0
                }
            }
        }
        val totalScore = subtestScores.sum()

        val scores = mutableListOf<AssessmentScore>()
        for (s in 0 until 7) {
            scores.add(
                AssessmentScore(
                    label = subtestNames[s],
                    value = subtestScores[s].toDouble(),
                    minimum = 0.0,
                    maximum = 14.0,
                    meaning = "Skor mentah: ${subtestScores[s]}/14. ${subtestMeanings[s]}"
                )
            )
        }
        scores.add(
            AssessmentScore(
                label = "Total Skor Penalaran Verbal",
                value = totalScore.toDouble(),
                minimum = 0.0,
                maximum = 98.0,
                meaning = "Total skor mentah: $totalScore/98 ($answeredCount/49 butir dijawab)."
            )
        )

        val warnings = buildList {
            add("Sumber protokol & norma VRT masih berstatus SOURCE_INCOMPLETE dan belum terverifikasi norma pembanding lokal.")
            if (answeredCount < 49) {
                add("Baru $answeredCount dari 49 butir VRT dijawab.")
            }
        }
        val validity = if (answeredCount < 49) AssessmentValidityStatus.INVALID else AssessmentValidityStatus.QUESTIONABLE
        val interpretation = "Skor Mentah Verbal Reasoning Test (VRT; $answeredCount/49 butir dijawab): Total = $totalScore/98 " +
            "(Subtes 1–7: ${subtestScores.joinToString("/")}). Kategori normatif tidak diterapkan tanpa verifikasi manual VRT lengkap."

        return EnrichedScoringOutcome(
            scores = scores,
            interpretation = interpretation,
            scoringStatus = InstrumentImplementationStatus.SOURCE_INCOMPLETE,
            validityStatus = validity,
            validityWarnings = warnings,
            normReference = PsychometricInstrumentRegistry.specFor("vrt").normStatus
        )
    }

    private fun enrichMseaiDetailed(answers: Map<Int, Int>, rawScores: List<AssessmentScore>): EnrichedScoringOutcome {
        val total = rawScores.sumOf { it.value }
        val maxTotal = rawScores.sumOf { it.maximum }.coerceAtLeast(24.0)
        val totalScore = AssessmentScore(
            label = "Total Skor Eksploratif MSEAI",
            value = total,
            minimum = rawScores.sumOf { it.minimum },
            maximum = maxTotal,
            meaning = "Akumulasi 4 domain (${answers.size}/24 butir dijawab); status eksploratif purwarupa."
        )
        val allScores = listOf(totalScore) + rawScores
        val scaleSummary = rawScores.joinToString("; ") { "${it.label}=${it.displayValue()}/${it.maximum.toInt()}" }
        return EnrichedScoringOutcome(
            scores = allScores,
            interpretation = "Skor Eksploratif MSEAI (${answers.size}/24 butir; Total=${totalScore.displayValue()}/${maxTotal.toInt()}): " +
                "$scaleSummary. Hasil dihitung deterministik untuk simulasi & refleksi purwarupa penelitian; manual resmi & norma eksternal belum tersedia.",
            scoringStatus = InstrumentImplementationStatus.EXPLORATORY,
            validityStatus = if (answers.size >= 24) AssessmentValidityStatus.VALID else AssessmentValidityStatus.QUESTIONABLE,
            validityWarnings = if (answers.size < 24) listOf("Baru ${answers.size}/24 butir MSEAI dijawab.") else emptyList(),
            normReference = PsychometricInstrumentRegistry.specFor("mseai").normStatus
        )
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun enrichEppsDetailed(answers: Map<Int, Int>): EnrichedScoringOutcome {
        val needLabels = listOf(
            "Achievement (ach) · Dorongan Berprestasi",
            "Deference (def) · Kepatuhan Otoritas & Aturan",
            "Order (ord) · Keteraturan & Perencanaan",
            "Exhibition (exh) · Menonjolkan Diri",
            "Autonomy (aut) · Kemandirian & Kebebasan",
            "Affiliation (aff) · Afiliasi & Persahabatan",
            "Intraception (int) · Analisis Psikologis Diri",
            "Succorance (suc) · Kebutuhan Simpati & Bantuan",
            "Dominance (dom) · Kepemimpinan & Pengaruh",
            "Abasement (aba) · Kerendahan Hati / Mengalah",
            "Nurturance (nur) · Sikap Menolong & Mengasuh",
            "Change (chg) · Variasi & Pengalaman Baru",
            "Endurance (end) · Daya Tahan & Ketekunan",
            "Heterosexuality (het) · Relasi & Minat Lawan Jenis",
            "Aggression (agg) · Asertivitas & Kritik Terbuka"
        )
        val needDescriptions = listOf(
            "Dorongan menyelesaikan tugas menantang.",
            "Kesediaan mengikuti arahan dan aturan.",
            "Kecenderungan bekerja secara sistematis dan terencana.",
            "Keinginan menjadi pusat perhatian.",
            "Kebutuhan bertindak mandiri dalam keputusan.",
            "Keinginan menjalin persahabatan erat.",
            "Minat memahami motif dan dinamika diri maupun orang lain.",
            "Kebutuhan memperoleh dorongan dan dukungan sosial.",
            "Dorongan mengarahkan dan memimpin kelompok.",
            "Kecenderungan mengalah dan introspektif.",
            "Keinginan menolong dan merawat sesama.",
            "Kebutuhan variasi dan pengalaman baru.",
            "Kegigihan menuntaskan tugas hingga selesai.",
            "Ketertarikan interaksi sosial dengan lawan jenis.",
            "Keberanian mengemukakan kritik atau sikap asertif terbuka."
        )

        val rScores = IntArray(15)
        val cScores = IntArray(15)
        val rawScores = IntArray(15)
        var answeredPairs = 0

        for (r in 0 until 15) {
            for (c in 0 until 15) {
                if (r == c) continue
                val qIndex = (r / 5) * 75 + c * 5 + (r % 5)
                val ans = answers[qIndex] ?: continue
                answeredPairs++
                if (ans == 0) {
                    rScores[r]++
                } else {
                    cScores[c]++
                }
            }
        }

        for (k in 0 until 15) {
            rawScores[k] = rScores[k] + cScores[k]
        }

        val consPairs = listOf(
            0 to 150, 6 to 156, 12 to 162, 18 to 168, 24 to 174,
            25 to 100, 31 to 106, 37 to 112, 43 to 118, 49 to 124,
            50 to 200, 56 to 206, 62 to 212, 68 to 218, 74 to 224
        )
        val consScore = consPairs.count { (q1, q2) ->
            val a1 = answers[q1]
            val a2 = answers[q2]
            a1 != null && a2 != null && a1 == a2
        }

        val scores = mutableListOf<AssessmentScore>()
        for (k in 0 until 15) {
            val rs = rawScores[k].coerceIn(0, 28)
            scores.add(
                AssessmentScore(
                    label = needLabels[k],
                    value = rs.toDouble(),
                    minimum = 0.0,
                    maximum = 28.0,
                    meaning = "Skor mentah: $rs/28 (r=${rScores[k]}, c=${cScores[k]}). ${needDescriptions[k]}"
                )
            )
        }
        scores.add(
            AssessmentScore(
                label = "Konsistensi Respons (CONS)",
                value = consScore.toDouble(),
                minimum = 0.0,
                maximum = 15.0,
                meaning = "Skor konsistensi pasangan identik: $consScore/15 (referensi kelayakan umum >= 10)."
            )
        )
        val totalRaw = rawScores.sum()
        scores.add(
            AssessmentScore(
                label = "Total Raw Score EPPS",
                value = totalRaw.toDouble(),
                minimum = 0.0,
                maximum = 210.0,
                meaning = "Total seluruh 15 kebutuhan = $totalRaw/210 (pasangan utama terjawab: $answeredPairs/210)."
            )
        )

        val warnings = buildList {
            if (answeredPairs < 210) {
                add("Protokol EPPS tidak lengkap ($answeredPairs/210 pasangan utama terjawab); total invarian 210 memerlukan kelengkapan penuh.")
            }
            if (consScore < 10) {
                add("Skor Konsistensi (CONS) = $consScore/15 berada di bawah ambang >= 10; disarankan tinjauan kesungguhan pengerjaan.")
            }
        }
        val validity = when {
            answeredPairs < 210 -> AssessmentValidityStatus.INVALID
            consScore < 10 -> AssessmentValidityStatus.QUESTIONABLE
            else -> AssessmentValidityStatus.VALID
        }

        val topRawText = (0 until 15).sortedByDescending { rawScores[it] }
            .take(3)
            .joinToString(", ") { "${needLabels[it].substringBefore(" ·")} (${rawScores[it]}/28)" }
        val interpretation = "Profil Skor Mentah Edwards Personal Preference Schedule (EPPS; $answeredPairs/210 pasangan utama terjawab): " +
            "CONS = $consScore/15, Total Raw = $totalRaw/210. Kebutuhan skor mentah tertinggi: $topRawText. " +
            "Konversi persentil norma terpisah per jenis kelamin/populasi memerlukan tabel norma resmi EPPS."

        return outcomeRaw("epps", scores to interpretation, warnings = warnings, validity = validity)
    }

    private fun enrichRorschachDetailed(answers: Map<Int, Int>): EnrichedScoringOutcome {
        if (answers.isEmpty()) {
            return scoreRorschachFromDrafts(emptyList())
        }

        fun mapLocation(code: Int): String? = when (code) {
            0 -> "W"
            1 -> "D"
            2 -> "Dd"
            3 -> "S"
            else -> null
        }

        fun mapDeterminant(code: Int): String? = when (code) {
            0 -> "F"
            1 -> "M"
            2 -> "FM"
            3 -> "m"
            4 -> "FC"
            5 -> "CF"
            6 -> "C"
            7 -> "C'"
            8 -> "T"
            9 -> "V"
            10 -> "Y"
            else -> null
        }

        fun mapFormQuality(code: Int): String? = when (code) {
            0 -> "FQ+"
            1 -> "FQo"
            2 -> "FQu"
            3 -> "FQ-"
            else -> null
        }

        val drafts = answers.entries.sortedBy { it.key }.map { (key, raw) ->
            val cardNumber = if (key in 0..9) key + 1 else (key / 100).coerceIn(0, 9) + 1
            val respIndex = if (key in 0..9) 1 else (key % 100) + 1
            if (raw >= 10) {
                RorschachResponseDraft(
                    cardNumber = cardNumber,
                    responseIndex = respIndex,
                    location = mapLocation((raw / 100000) % 10),
                    determinant = mapDeterminant((raw / 1000) % 100),
                    formQuality = mapFormQuality((raw / 100) % 10),
                    popular = ((raw / 10) % 10) == 1
                )
            } else {
                RorschachResponseDraft(
                    cardNumber = cardNumber,
                    responseIndex = respIndex,
                    location = mapLocation(raw)
                )
            }
        }
        return scoreRorschachFromDrafts(drafts)
    }

    private fun answeredSummary(answered: Int, total: Int): String = "$answered/$total butir terisi"
}
