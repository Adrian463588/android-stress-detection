package com.stressdetector.mobile.domain.assessment

import java.util.Locale

@Suppress("MagicNumber", "LongMethod", "CyclomaticComplexMethod", "LargeClass")
object AssessmentEnrichers {

    fun enrich(
        id: String,
        form: AssessmentForm,
        answers: Map<Int, Int>,
        rawScores: List<AssessmentScore>
    ): Pair<List<AssessmentScore>, String> = when (id) {
        "bdi" -> enrichBdi(answers)
        "srq-29" -> enrichSrq29(answers)
        "vak" -> enrichVak(answers)
        "adversity_quotient" -> enrichAq(answers)
        "big_five" -> enrichBigFive(rawScores)
        "cbi" -> enrichCbi(rawScores)
        "disc" -> enrichDisc(answers)
        "mmpi" -> enrichMmpi(answers)
        "tiu_6" -> enrichTiu6(answers)
        "spm" -> enrichSpm(answers)
        "mbti" -> enrichMbti(answers)
        "tiu_5" -> enrichTiu5(answers)
        "holland" -> enrichHolland(answers)
        "wpt" -> enrichWpt(answers)
        "16pf" -> enrich16pf(answers)
        "army_alpha" -> enrichArmyAlpha(answers)
        "frt" -> enrichFrt(answers)
        "cfit_2a" -> enrichCfit2a(answers)
        "cfit_3a" -> enrichCfit3a(answers)
        "enneagram" -> enrichEnneagram(answers)
        "ist" -> enrichIst(answers)
        "kuder" -> enrichKuder(answers)
        "kraepelin" -> enrichKraepelin(answers)
        "mit" -> enrichMit(answers)
        "msai" -> enrichMsai(answers)
        "msdt" -> enrichMsdt(answers)
        "papikostick" -> enrichPapikostick(answers)
        "pauli" -> enrichPauli(answers)
        "personality_test" -> enrichPersonalityTest(answers)
        "wartegg" -> enrichWartegg(answers)
        "rmib" -> enrichRmib(answers)
        "vrt" -> enrichVrt(answers)
        "mseai" -> enrichMseai(answers)
        "epps" -> enrichEpps(answers)
        "rorschach" -> enrichRorschach(answers)
        else -> rawScores to form.interpretationNote
    }

    private fun enrichBdi(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
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
        val alert = if (item9 > 0) {
            " ⚠️ PERINGATAN KESELAMATAN: Butir 9 (Ide Bunuh Diri) bernilai $item9. Evaluasi risiko segera!"
        } else {
            ""
        }
        val scores = listOf(
            AssessmentScore("Total Skor BDI-II", total, 0.0, 63.0, "Tingkat keparahan gejala: $severity.$alert"),
            AssessmentScore("Butir 9 Ide Bunuh Diri", item9.toDouble(), 0.0, 3.0, "Item kritis keselamatan."),
            AssessmentScore("Subskala Kognitif-Afektif", cognitive, 0.0, 42.0, "Jumlah butir 1–14."),
            AssessmentScore("Subskala Somatik", somatic, 0.0, 21.0, "Jumlah butir 15–21.")
        )
        val interpretation = "Tingkat keparahan gejala depresi selama 2 minggu terakhir berada pada kategori $severity " +
            "(Skor Total: ${total.toInt()}/63). Rincian: Kognitif-Afektif ${cognitive.toInt()}, Somatik ${somatic.toInt()}.$alert " +
            "Hasil ini bersifat skrining suportif dan bukan penegak diagnosis klinis tunggal."
        return scores to interpretation
    }

    private fun enrichSrq29(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val gme = answers.filterKeys { it in 0..19 }.values.sum()
        val substance = answers[20] ?: 0
        val psychotic = answers.filterKeys { it in 21..23 }.values.sum()
        val ptsd = answers.filterKeys { it in 24..28 }.values.sum()
        val totalYes = answers.values.sum()
        val item17 = answers[16] ?: 0
        val gmeStatus = if (gme >= 5) "Indikasi Gangguan Mental Emosional (ambang >=5)" else "Dalam batas normal"
        val subStatus = if (substance >= 1) "Indikasi Penggunaan Zat/Alkohol" else "Negatif"
        val psyStatus = if (psychotic >= 1) "Indikasi Gejala Psikotik (Perlu Rujukan)" else "Negatif"
        val ptsdStatus = if (ptsd >= 1) "Indikasi Gejala PTSD" else "Negatif"
        val alert = if (item17 == 1) {
            " ⚠️ PERINGATAN: Jawaban YA pada Butir 17 (pikiran mengakhiri hidup). Wajib konsultasi/rujukan segera."
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
        val interpretation = "Hasil Skrining SRQ-29 Kemenkes RI: $gmeStatus (Skor GME: $gme/20). Status zat: $subStatus. " +
            "Status psikotik: $psyStatus. Status PTSD: $ptsdStatus.$alert " +
            "Konsultasikan hasil ini dengan konselor atau psikolog untuk evaluasi komprehensif."
        return scores to interpretation
    }

    private fun enrichVak(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val countV = answers.values.count { it == 0 }
        val countA = answers.values.count { it == 1 }
        val countK = answers.values.count { it == 2 }
        val total = (countV + countA + countK).coerceAtLeast(1)
        val pV = countV * 100.0 / total
        val pA = countA * 100.0 / total
        val pK = countK * 100.0 / total
        val dominant = when {
            pV >= pA && pV >= pK -> "Visual (Modalitas Penglihatan)"
            pA >= pV && pA >= pK -> "Auditori (Modalitas Pendengaran)"
            else -> "Kinestetik (Modalitas Praktik & Gerak)"
        }
        val scores = listOf(
            AssessmentScore("Gaya Dominan", 1.0, 1.0, 1.0, dominant),
            AssessmentScore("Modalitas Visual", pV, 0.0, 100.0, "$countV butir (${format1(pV)}%)"),
            AssessmentScore("Modalitas Auditori", pA, 0.0, 100.0, "$countA butir (${format1(pA)}%)"),
            AssessmentScore("Modalitas Kinestetik", pK, 0.0, 100.0, "$countK butir (${format1(pK)}%)")
        )
        val interpretation = "Modalitas belajar dominan Anda adalah $dominant. " +
            "Proporsi: Visual ${format1(pV)}%, Auditori ${format1(pA)}%, Kinestetik ${format1(pK)}%. " +
            "Manfaatkan strategi belajar yang mengoptimalkan modalitas utama untuk daya serap maksimal."
        return scores to interpretation
    }

    private fun enrichAq(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val c = answers.filterKeys { it in listOf(0, 4, 8, 12) }.values.sum()
        val o = answers.filterKeys { it in listOf(1, 5, 9, 13) }.values.sum()
        val r = answers.filterKeys { it in listOf(2, 6, 10, 14) }.values.sum()
        val e = answers.filterKeys { it in listOf(3, 7, 11) }.values.sum()
        val rawSum = c + o + r + e
        val totalAq = rawSum * 2
        val profile = when {
            totalAq >= 166 -> "Climber (Daya Juang Tinggi)"
            totalAq >= 134 -> "Camper (Daya Juang Cukup)"
            else -> "Quitter (Daya Juang Rentan)"
        }
        val scores = listOf(
            AssessmentScore("Total Skor AQ", totalAq.toDouble(), 0.0, 200.0, "Profil: $profile"),
            AssessmentScore("Dimensi C (Control)", c.toDouble(), 0.0, 20.0, "Persepsi kendali atas kesulitan."),
            AssessmentScore("Dimensi O (Ownership)", o.toDouble(), 0.0, 20.0, "Tanggung jawab terhadap solusi."),
            AssessmentScore("Dimensi R (Reach)", r.toDouble(), 0.0, 20.0, "Sejauh mana kesulitan merembet."),
            AssessmentScore("Dimensi E (Endurance)", e.toDouble(), 0.0, 20.0, "Persepsi daya tahan kesulitan.")
        )
        val interpretation = "Adversity Quotient Anda berada pada kategori $profile (Skor AQ: $totalAq/200). " +
            "Dimensi: Control $c/20, Ownership $o/20, Reach $r/20, Endurance $e/20. " +
            "Tingkatkan persepsi kendali dan kepemilikan solusi dalam menghadapi tantangan berat."
        return scores to interpretation
    }

    private fun enrichBigFive(rawScores: List<AssessmentScore>): Pair<List<AssessmentScore>, String> {
        val summary = rawScores.joinToString(", ") { "${it.label}: ${it.displayValue()}" }
        val highest = rawScores.maxByOrNull { it.value }?.label ?: "Seimbang"
        val interpretation = "Profil Big Five Personality mengindikasikan dimensi paling menonjol pada $highest. " +
            "Rincian dimensi: $summary. Gunakan pemahaman ini untuk mengoptimalkan potensi kerja dan komunikasi."
        return rawScores to interpretation
    }

    private fun enrichCbi(rawScores: List<AssessmentScore>): Pair<List<AssessmentScore>, String> {
        val overall = rawScores.map { it.value }.average()
        val level = when {
            overall < 25.0 -> "Sangat Rendah (Bebas Kejenuhan)"
            overall < 50.0 -> "Rendah (Tingkat Stres Terkendali)"
            overall < 75.0 -> "Sedang (Waspada Kelelahan)"
            else -> "Tinggi (Risiko Kejenuhan Berat)"
        }
        val interpretation = "Tingkat keletihan dan kejenuhan kerja (CBI) berada pada level $level " +
            "(Rerata: ${format1(overall)}/100). Jaga keseimbangan istirahat dan pulihkan energi mental secara teratur."
        return rawScores to interpretation
    }

    // =========================================================================
    // 1. DISC Scoring & Profiling (24 Groups Most & Least)
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

        val line3Map = listOf("D" to d3, "I" to i3, "S" to s3, "C" to c3).sortedByDescending { it.second }
        val dominantLetter = line3Map.first().first
        val secondLetter = line3Map[1].first
        val archetype = getDiscArchetype(dominantLetter, secondLetter, line3Map.first().second, line3Map[1].second)

        val scores = listOf(
            AssessmentScore("Pola Dominan", 1.0, 1.0, 1.0, "$dominantLetter (${archetype.first})"),
            AssessmentScore("Grafik 1 (Mask) Dominance", d1.toDouble(), 0.0, 24.0, "Perilaku adaptasi publik."),
            AssessmentScore("Grafik 1 (Mask) Influence", i1.toDouble(), 0.0, 24.0, "Perilaku adaptasi publik."),
            AssessmentScore("Grafik 1 (Mask) Steadiness", s1.toDouble(), 0.0, 24.0, "Perilaku adaptasi publik."),
            AssessmentScore("Grafik 1 (Mask) Compliance", c1.toDouble(), 0.0, 24.0, "Perilaku adaptasi publik."),
            AssessmentScore("Grafik 2 (Core) Dominance", d2.toDouble(), 0.0, 24.0, "Respon alami di bawah tekanan."),
            AssessmentScore("Grafik 2 (Core) Influence", i2.toDouble(), 0.0, 24.0, "Respon alami di bawah tekanan."),
            AssessmentScore("Grafik 2 (Core) Steadiness", s2.toDouble(), 0.0, 24.0, "Respon alami di bawah tekanan."),
            AssessmentScore("Grafik 2 (Core) Compliance", c2.toDouble(), 0.0, 24.0, "Respon alami di bawah tekanan."),
            AssessmentScore("Grafik 3 (Mirror) D", d3.toDouble(), -24.0, 24.0, "Selisih Most - Least."),
            AssessmentScore("Grafik 3 (Mirror) I", i3.toDouble(), -24.0, 24.0, "Selisih Most - Least."),
            AssessmentScore("Grafik 3 (Mirror) S", s3.toDouble(), -24.0, 24.0, "Selisih Most - Least."),
            AssessmentScore("Grafik 3 (Mirror) C", c3.toDouble(), -24.0, 24.0, "Selisih Most - Least.")
        )

        val interpretation = "Profil DISC menunjukkan tipe dominan ${archetype.first} ($dominantLetter/$secondLetter). " +
            "Grafik 1 (Perilaku Publik/Mask): D=$d1, I=$i1, S=$s1, C=$c1. " +
            "Grafik 2 (Respon Stres/Core): D=$d2, I=$i2, S=$s2, C=$c2. " +
            "Grafik 3 (Konsep Diri/Mirror): D=$d3, I=$i3, S=$s3, C=$c3. " +
            "Karakteristik: ${archetype.second}."
        return scores to interpretation
    }

    private fun getDiscArchetype(dom: String, sec: String, val1: Int, val2: Int): Pair<String, String> = when (dom) {
        "D" -> if (sec == "I" && val2 > 0) {
            "NEGOTIATOR / RESULT-ORIENTED" to "Tegas, persuasif, berorientasi hasil cepat, dan aktif memotivasi tim"
        } else if (sec == "C" && val2 > 0) {
            "DESIGNER / ARCHITECT" to "Visioner, berstandar tinggi, kritis, dan fokus pada efisiensi sistematis"
        } else {
            "ESTABLISHER / DEVELOPER" to "Percaya diri, menyukai tantangan, berorientasi target, dan berani mengambil risiko"
        }
        "I" -> if (sec == "S" && val2 > 0) {
            "COUNSELOR / HARMONIZER" to "Hangat, empatik, ramah, membina relasi harmonis, dan pendengar suportif"
        } else if (sec == "D" && val2 > 0) {
            "PROMOTER / INSPIRER" to "Antusias, komunikatif, ekspresif, dan mendorong aksi positif orang lain"
        } else {
            "PERSUADER / MOTIVATOR" to "Optimis, mudah bergaul, persuasif, dan membangun jejaring luas"
        }
        "S" -> if (sec == "C" && val2 > 0) {
            "PERFECTIONIST / STABILIZER" to "Sabar, konsisten, teliti, loyal, dan menjaga ketertiban prosedur"
        } else if (sec == "I" && val2 > 0) {
            "AGENT / SUPPORTER" to "Kooperatif, toleran, mengutamakan kenyamanan bersama, dan stabil"
        } else {
            "SPECIALIST / RELIABLE" to "Tenang di bawah tekanan, fokus pada tugas rutin, dan pendengar andal"
        }
        else -> if (sec == "S" && val2 > 0) {
            "QUALITY CONTROLLER / AUDITOR" to "Sistematis, hati-hati, patuh standar, dan menjaga kualitas tanpa cela"
        } else if (sec == "D" && val2 > 0) {
            "LOGICAL THINKER / ANALYST" to "Objektif, berbasis data, analitis tajam, dan memecahkan masalah terstruktur"
        } else {
            "THINKER / COMPLIER" to "Teliti, akurat, menjaga integritas prosedur, dan menghindari spekulasi"
        }
    }

    // =========================================================================
    // 2. MMPI-2-RF Scoring (51 Scales, Safety SUI, Validity Flags)
    // =========================================================================
    private fun enrichMmpi(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val suiItems = listOf(92, 119, 163, 250, 333)
        val suiScore = suiItems.count { answers[it] == 1 }
        val hasSuiRisk = suiScore > 0

        // Higher-Order
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

        // Validity Scales
        val frScore = listOf(
            13, 29, 45, 55, 66, 70, 73, 105, 116, 119, 138, 145, 163,
            202, 217, 230, 239, 252, 263, 274, 276, 280, 293, 300, 309, 311, 321
        ).count { answers[it] == 1 } + listOf(58, 82, 101, 173, 226).count { answers[it] == 0 }
        val lrScore = listOf(60, 181, 267).count { answers[it] == 1 } +
            listOf(15, 44, 69, 94, 126, 153, 182, 210, 240, 297, 324).count { answers[it] == 0 }
        val krScore = listOf(79, 201).count { answers[it] == 1 } +
            listOf(9, 22, 35, 43, 71, 88, 104, 139, 201, 216, 221, 233, 245, 281, 292).count { answers[it] == 0 }

        // Restructured Clinical
        val rcdScore = listOf(
            5, 21, 29, 47, 61, 73, 88, 116, 129, 143, 157,
            171, 186, 203, 231, 246, 260, 273, 287, 298, 314, 330
        ).count { answers[it] == 1 } + listOf(104, 216).count { answers[it] == 0 }
        val rc1Score = listOf(14, 42, 75, 100, 136, 175, 229, 241, 276, 300, 327).count { answers[it] == 1 }
        val rc2Score = listOf(3, 16, 24, 52, 63, 82, 101, 139, 159, 181, 194, 201, 221, 245, 281, 301, 322).count { answers[it] == 0 }
        val rc4Score = listOf(4, 20, 48, 65, 95, 140, 155, 172, 204, 217, 222, 252, 265, 296, 311, 328).count { answers[it] == 1 }

        val alert = if (hasSuiRisk) {
            " ⚠️ PERINGATAN KESELAMATAN KLINIS: Terdeteksi respons positif pada $suiScore butir Ide Bunuh Diri (SUI). " +
                "Wajib dilakukan evaluasi keselamatan dan rujukan klinis segera!"
        } else {
            ""
        }

        val validityStatus = when {
            frScore >= 20 -> "Over-Reporting Tinggi (F-r: $frScore)"
            lrScore >= 10 -> "Defensif / Under-Reporting (L-r: $lrScore)"
            else -> "Protokol Valid & Dapat Diinterpretasikan"
        }

        val scores = listOf(
            AssessmentScore("Validitas Protokol", if (frScore < 20 && lrScore < 10) 1.0 else 0.0, 0.0, 1.0, validityStatus),
            AssessmentScore(
                "SUI (Ide Bunuh Diri)",
                suiScore.toDouble(),
                0.0,
                5.0,
                if (hasSuiRisk) "Elevasi kritis risiko.$alert" else "Tidak ada indikasi."
            ),
            AssessmentScore("H-O EID (Disfungsi Emosi)", eidScore.toDouble(), 0.0, 22.0, "Emotional / Internalizing Dysfunction."),
            AssessmentScore("H-O THD (Disfungsi Pikiran)", thdScore.toDouble(), 0.0, 24.0, "Thought Dysfunction."),
            AssessmentScore("H-O BXD (Kendala Perilaku)", bxdScore.toDouble(), 0.0, 20.0, "Behavioral / Externalizing Dysfunction."),
            AssessmentScore("RCd (Demoralisasi)", rcdScore.toDouble(), 0.0, 24.0, "Tingkat ketidakpuasan hidup / demoralisasi."),
            AssessmentScore("RC1 (Keluhan Somatik)", rc1Score.toDouble(), 0.0, 11.0, "Keluhan fisik dan somatik."),
            AssessmentScore("RC2 (Emosi Positif Rendah)", rc2Score.toDouble(), 0.0, 17.0, "Anhedonia / kesulitan merasakan kegembiraan."),
            AssessmentScore("RC4 (Perilaku Antisosial)", rc4Score.toDouble(), 0.0, 16.0, "Kecenderungan pelanggaran norma."),
            AssessmentScore("F-r (Infrequent Responses)", frScore.toDouble(), 0.0, 32.0, "Skala validitas pelaporan gejala."),
            AssessmentScore("L-r (Uncommon Virtues)", lrScore.toDouble(), 0.0, 14.0, "Skala validitas pelaporan kebajikan moral."),
            AssessmentScore("K-r (Adjustment Validity)", krScore.toDouble(), 0.0, 17.0, "Skala validitas defensif penyesuaian.")
        )

        val interpretation = "Hasil MMPI-2-RF (338 Butir): $validityStatus. " +
            "Ringkasan skala utama: Disfungsi Emosi (EID: $eidScore), Disfungsi Pikiran (THD: $thdScore), " +
            "Kendala Perilaku (BXD: $bxdScore), Demoralisasi (RCd: $rcdScore), Keluhan Somatik (RC1: $rc1Score).$alert " +
            "Hasil ini merupakan instrumen pendukung klinis komprehensif bagi psikolog/psikiater."
        return scores to interpretation
    }

    // =========================================================================
    // 3. TIU 6 Scoring (40 Spatial 3D Folding Items)
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

        val ws = when {
            correctCount >= 33 -> 5
            correctCount >= 25 -> 4
            correctCount >= 17 -> 3
            correctCount >= 9 -> 2
            else -> 1
        }

        val category = when (ws) {
            5 -> "BAIK SEKALI (Skala 5)"
            4 -> "BAIK (Skala 4)"
            3 -> "RATA-RATA (Skala 3)"
            2 -> "KURANG (Skala 2)"
            else -> "KURANG SEKALI (Skala 1)"
        }

        val scores = listOf(
            AssessmentScore("Skor Baku TIU 6", ws.toDouble(), 1.0, 5.0, category),
            AssessmentScore("Total Jawaban Benar", correctCount.toDouble(), 0.0, 40.0, "Raw Score / 40 butir."),
            AssessmentScore("Baris 1 (Kubus)", familyCorrect[0].toDouble(), 0.0, 5.0, "Jaring bangun kubus."),
            AssessmentScore("Baris 2 (Piramida)", familyCorrect[1].toDouble(), 0.0, 5.0, "Jaring piramida persegi."),
            AssessmentScore("Baris 3 (Prisma)", familyCorrect[2].toDouble(), 0.0, 5.0, "Jaring prisma segitiga."),
            AssessmentScore("Baris 4 (Piramida 3)", familyCorrect[3].toDouble(), 0.0, 5.0, "Jaring limas segitiga."),
            AssessmentScore("Baris 5 (Poligon)", familyCorrect[4].toDouble(), 0.0, 5.0, "Jaring ruang poligon."),
            AssessmentScore("Baris 6 (Bentuk Arsir)", familyCorrect[5].toDouble(), 0.0, 5.0, "Jaring bentuk berarsir."),
            AssessmentScore("Baris 7 (Kubus Kompleks)", familyCorrect[6].toDouble(), 0.0, 5.0, "Jaring kubus kompleks."),
            AssessmentScore("Baris 8 (Gabungan)", familyCorrect[7].toDouble(), 0.0, 5.0, "Jaring bentuk gabungan.")
        )

        val interpretation = "Hasil Tes Inteligensi Umum 6 (Penalaran Spasial & Bangun Ruang): Kategori $category " +
            "(Skor Mentah: $correctCount/40). Kemampuan memvisualisasikan rotasi, lipatan 2D ke 3D, " +
            "dan manipulasi bangun ruang berada pada tingkat yang ${category.lowercase(Locale.ROOT)}. " +
            "Sangat relevan untuk bidang teknik, arsitektur, IT sistem, dan pemecahan masalah desain struktural."
        return scores to interpretation
    }

    // =========================================================================
    // 4. SPM Scoring (Raven's Standard Progressive Matrices - 60 Items)
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

        val grade = when {
            totalCorrect >= 55 -> "Grade I (Intelektual Superior / ≥ P95)"
            totalCorrect >= 48 -> "Grade II (Di Atas Rata-Rata / P75–P94)"
            totalCorrect >= 35 -> "Grade III (Rata-Rata Intelektual / P25–P74)"
            totalCorrect >= 20 -> "Grade IV (Di Bawah Rata-Rata / P5–P24)"
            else -> "Grade V (Hambatan Intelektual / < P5)"
        }

        val hasAtypicalPattern = (sE > sA + 2) || (sE > sB + 3)
        val consistencyNote = if (hasAtypicalPattern) {
            " Catatan Konsistensi: Terdapat deviasi pola pengerjaan di mana skor set tingkat lanjut (Set E) melampaui set dasar (Set A/B), disarankan pemeriksaan lembar jawaban untuk memverifikasi ada tidaknya tebakan acak."
        } else {
            " Konsistensi antar-set menunjukkan progresi wajar dari set dasar ke set lanjutan."
        }

        val scores = listOf(
            AssessmentScore("Klasifikasi Grade SPM", 1.0, 1.0, 5.0, grade),
            AssessmentScore("Total Skor Mentah", totalCorrect.toDouble(), 0.0, 60.0, "Total jawaban benar / 60 butir."),
            AssessmentScore("Set A (Pola Kontinu)", sA.toDouble(), 0.0, 12.0, "Penyelesaian pola statis."),
            AssessmentScore("Set B (Analogi Figur)", sB.toDouble(), 0.0, 12.0, "Analogi simetri 2x2."),
            AssessmentScore("Set C (Progresi Matriks)", sC.toDouble(), 0.0, 12.0, "Perubahan progresi 3x3."),
            AssessmentScore("Set D (Permutasi Matriks)", sD.toDouble(), 0.0, 12.0, "Permutasi elemen teratur."),
            AssessmentScore("Set E (Dekomposisi Logika)", sE.toDouble(), 0.0, 12.0, "Dekomposisi aturan logika abstrak.")
        )

        val interpretation = "Hasil Raven Standard Progressive Matrices (SPM): $grade (Skor Total: $totalCorrect/60). " +
            "Rincian per set: Set A=$sA/12, Set B=$sB/12, Set C=$sC/12, Set D=$sD/12, Set E=$sE/12. " +
            "Menggambarkan kapasitas eductive ability murni dalam memecahkan masalah logika baru secara non-verbal.$consistencyNote"
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
    // 6. TIU 5 Scoring (30 Figural Pattern Reasoning Items)
    // =========================================================================
    private fun enrichTiu5(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val keys = listOf(
            2, 5, 4, 4, 5, 4, 5, 1, 5, 3,
            5, 4, 5, 3, 2, 3, 2, 3, 4, 4,
            3, 3, 4, 4, 5, 1, 2, 5, 3, 5
        )

        var correctCount = 0
        for (i in 0 until 30) {
            val ans = answers[i]
            // Choice 0..4 corresponds to options 1..5
            if (ans != null && (ans + 1) == keys[i]) {
                correctCount++
            }
        }

        val ws = when {
            correctCount >= 29 -> 18
            correctCount >= 27 -> 16
            correctCount >= 25 -> 15
            correctCount >= 23 -> 14
            correctCount >= 22 -> 13
            correctCount >= 20 -> 12
            correctCount >= 18 -> 11
            correctCount >= 16 -> 10
            correctCount >= 14 -> 9
            correctCount >= 12 -> 8
            correctCount >= 10 -> 7
            correctCount >= 8 -> 6
            correctCount >= 6 -> 5
            correctCount >= 4 -> 4
            correctCount >= 3 -> 3
            correctCount >= 1 -> 2
            else -> 1
        }

        val category = when {
            ws >= 17 -> "Baik Sekali (BS)"
            ws >= 13 -> "Baik (B)"
            ws >= 9 -> "Sedang / Cukup (S)"
            ws >= 5 -> "Kurang (K)"
            else -> "Kurang Sekali (KS)"
        }

        val operationalMeaning = when {
            ws >= 17 -> "Daya abstraksi visual, logika pola transformasional, dan kecepatan pemecahan masalah figural sangat unggul. Mampu menarik kesimpulan analogis non-verbal secara cepat dan presisi tinggi."
            ws >= 13 -> "Cepat memahami aturan perubahan konsep figural dan cermat mengenali konsistensi pola logika non-verbal. Kapasitas nalar visual sangat baik untuk tuntutan analitis profesional."
            ws >= 9 -> "Kapasitas penalaran logis praktis memadai untuk tuntutan tugas standar operasional. Mampu menyelesaikan persoalan visual rutin dengan akurasi cukup."
            ws >= 5 -> "Memerlukan instruksi konkret langkah demi langkah serta waktu belajar lebih panjang dalam memahami konsep visual atau logika simbolik yang kompleks."
            else -> "Mengalami hambatan nyata dalam menarik kesimpulan logis non-verbal di bawah batasan waktu. Memerlukan pendampingan teknis dan tugas dengan panduan visual eksplisit."
        }

        val scores = listOf(
            AssessmentScore("Kategori TIU 5", ws.toDouble(), 1.0, 20.0, category),
            AssessmentScore("Weighted Score (WS)", ws.toDouble(), 0.0, 20.0, "Konversi norma resmi UI-10144-96."),
            AssessmentScore("Raw Score (Benar)", correctCount.toDouble(), 0.0, 30.0, "Jumlah jawaban benar / 30 butir.")
        )

        val interpretation = "Hasil Tes Inteligensi Umum 5 (TIU 5 Figural): $category " +
            "(Skor Mentah: $correctCount/30, Weighted Score: $ws/20). " +
            "Menunjukkan daya nalar logika figural, kecepatan menangkap analogi bentuk, " +
            "dan kelincahan analitis visual non-verbal praktis."
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

        val scores = listOf(
            AssessmentScore("Kode 3-Huruf Holland", 1.0, 1.0, 1.0, top3Code),
            AssessmentScore("Realistic (R)", rScore.toDouble(), 0.0, 40.0, "Minat teknik, mesin, alam, dan fisik."),
            AssessmentScore("Investigative (I)", iScore.toDouble(), 0.0, 40.0, "Minat riset ilmiah, data, dan sains."),
            AssessmentScore("Artistic (A)", aScore.toDouble(), 0.0, 40.0, "Minat desain, seni, tulisan, dan ekspresi."),
            AssessmentScore("Social (S)", sScore.toDouble(), 0.0, 40.0, "Minat konseling, mengajar, dan menolong sesama."),
            AssessmentScore("Enterprising (E)", eScore.toDouble(), 0.0, 40.0, "Minat memimpin, bisnis, dan persuasi."),
            AssessmentScore("Conventional (C)", cScore.toDouble(), 0.0, 40.0, "Minat administrasi, akuntansi, dan prosedur."),
            AssessmentScore("Diferensiasi Minat", diff.toDouble(), 0.0, 40.0, if (diff >= 15) "Minat terfokus tajam." else "Minat merata.")
        )

        val interpretation = "Kode Minat Karier Holland Anda adalah $top3Code (Diferensiasi: $diff poin). " +
            "Dimensi tertinggi: 1) ${list[0].first} (${list[0].second}/40), 2) ${list[1].first} (${list[1].second}/40), " +
            "3) ${list[2].first} (${list[2].second}/40). Rekomendasi karier berfokus pada bidang yang memadukan aktivitas " +
            "${getHollandTitle(list[0].first)}, ${getHollandTitle(list[1].first)}, dan ${getHollandTitle(list[2].first)}."
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
    // 8. WPT Scoring (Wonderlic Personnel Test - 50 Items)
    // =========================================================================
    private fun enrichWpt(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        // Kunci jawaban otentik 50 butir Wonderlic Personnel Test (berbasis indeks pilihan 0)
        // Divalidasi terhadap Software WPT.xlsx (baris 7-56) dan Soal WPT.pdf
        val keys = listOf(
            3, 1, 2, 1, 2, 0, 2, 0, 0, 3, // Butir 1-10
            2, 2, 0, 1, 1, 1, 0, 1, 2, 0, // Butir 11-20
            1, 1, 1, 1, 2, 0, 0, 2, 1, 1, // Butir 21-30
            1, 0, 2, 1, 0, 2, 0, 1, 1, 1, // Butir 31-40
            0, 1, 3, 1, 2, 1, 2, 2, 1, 2  // Butir 41-50
        )

        // Subskala butir WPT
        val verbalItems = setOf(0, 1, 2, 3, 4, 5, 8, 9, 10, 13, 16, 18, 22, 24, 27, 31, 32, 38, 40, 43, 45) // 21 butir
        val numericItems = setOf(7, 11, 14, 17, 20, 23, 26, 28, 29, 30, 33, 34, 35, 36, 42, 44, 47, 49) // 18 butir
        val logicItems = setOf(12, 19, 21, 25, 46) // 5 butir
        val spatialItems = setOf(6, 37, 41, 48) // 4 butir (butir 7, 38, 42, 49)
        val clericalItems = setOf(15, 39) // 2 butir (butir 16, 40)

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

        // Tabel Konversi IQ Resmi dari Software WPT.xlsx (H137:I187)
        val iqTable = intArrayOf(
            59, 59, 61, 64, 67, 69, 71, 73, 75, 78, // RS 0-9
            80, 81, 83, 86, 88, 90, 93, 95, 97, 98, // RS 10-19
            100, 102, 104, 106, 108, 111, 113, 114, 116, 118, // RS 20-29
            120, 121, 123, 125, 126, 128, 130, 132, 134, 136, // RS 30-39
            138, 140, 142, 143, 146, 146, 146, 146, 146, 146, 146 // RS 40-50
        )
        val iq = iqTable[correctCount.coerceIn(0, 50)]

        // Klasifikasi Baku Kognitif dari Software WPT.xlsx sel F12
        val classification = when {
            iq <= 65 -> "Mentally Defective"
            iq <= 79 -> "Borderline Defective"
            iq <= 90 -> "Low Average"
            iq <= 110 -> "Average"
            iq <= 119 -> "High Average"
            iq <= 127 -> "Superior"
            iq <= 139 -> "Very Superior"
            else -> "Genius"
        }

        // Benchmark Okupasi Resmi Wonderlic Manual 1983
        val (benchmarkCategory, benchmarkDesc) = when {
            correctCount >= 29 -> "Tinggi / Analitis Kompleks (≥29)" to
                "Kapasitas penalaran tingkat tinggi; cocok untuk posisi riset, rekayasa teknik, sistem analitis, kepemimpinan strategis, dan pengambilan keputusan kompleks."
            correctCount >= 24 -> "Menengah-Atas (24–28)" to
                "Kapasitas belajar dan pemecahan masalah cepat; cocok untuk analis investasi, spesialis teknis, supervisor profesional, dan manajemen lini menengah."
            correctCount >= 20 -> "Rata-Rata Operasional (20–23)" to
                "Rentang rata-rata populasi pekerja dewasa; sesuai untuk perawat, staf perbankan, kasir, teknisi terampil, dan staf operasional reguler."
            correctCount >= 15 -> "Keterampilan Teknis Dasar (15–19)" to
                "Sesuai untuk pekerjaan operasional dengan prosedur tetap, keterampilan teknis langsung (welder, security, staf pergudangan) dengan panduan terstruktur."
            else -> "Di Bawah Benchmark Umum (<15)" to
                "Memerlukan arahan tugas yang sangat spesifik, pelatihan berulang, dan supervisi langsung untuk tugas-tugas terstruktur."
        }

        val percentileEst = when {
            correctCount >= 40 -> 99.0
            correctCount >= 35 -> 95.0
            correctCount >= 30 -> 88.0
            correctCount >= 25 -> 75.0
            correctCount >= 21 -> 50.0
            correctCount >= 17 -> 25.0
            correctCount >= 13 -> 10.0
            else -> (correctCount * 0.7).coerceAtLeast(1.0)
        }

        val scores = listOf(
            AssessmentScore("Total Skor Mentah (RS)", correctCount.toDouble(), 0.0, 50.0, "Jawaban benar dalam batas waktu 12 menit."),
            AssessmentScore("Estimasi IQ Wonderlic", iq.toDouble(), 59.0, 146.0, "Klasifikasi: $classification."),
            AssessmentScore("Penalaran Verbal", verbalCorrect.toDouble(), 0.0, 21.0, "Kosakata, analogi, pemahaman makna kalimat."),
            AssessmentScore("Penalaran Numerik", numericCorrect.toDouble(), 0.0, 18.0, "Aritmetika praktis, deret angka, persentase."),
            AssessmentScore("Penalaran Logika Deduktif", logicCorrect.toDouble(), 0.0, 5.0, "Silogisme dan konsistensi inferensi logis."),
            AssessmentScore("Penalaran Spasial & Geometri", spatialCorrect.toDouble(), 0.0, 4.0, "Rekonstruksi bidang dan bangun geometri."),
            AssessmentScore("Ketelitian Klerikal", clericalCorrect.toDouble(), 0.0, 2.0, "Kecepatan dan ketelitian deteksi duplikasi."),
            AssessmentScore("Persentil Estimasi", percentileEst, 1.0, 99.0, "Estimasi posisi terhadap norma pekerja dewasa umum."),
            AssessmentScore("Benchmark Jabatan", 1.0, 1.0, 1.0, benchmarkCategory)
        )

        val interpretation = buildString {
            append("Hasil Wonderlic Personnel Test (WPT): Skor Mentah $correctCount/50 | Estimasi IQ: $iq ($classification).\n\n")
            append("• Klasifikasi Kognitif: $classification. Menunjukkan kapasitas pemrosesan informasi umum, daya tangkap instruksi baru, dan kelincahan berpikir kritis di bawah batasan waktu 12 menit.\n")
            append("• Profil Subskala: Verbal ($verbalCorrect/21), Numerik ($numericCorrect/18), Logika Deduktif ($logicCorrect/5), Spasial-Geometri ($spatialCorrect/4), Ketelitian Klerikal ($clericalCorrect/2).\n")
            append("• Kesesuaian Benchmark Jabatan: $benchmarkCategory. $benchmarkDesc\n\n")
            append("Catatan Keputusan & Etika: Wonderlic adalah instrumen skrining kecepatan kognitif (speed-power hybrid test). Skor sangat dipengaruhi oleh batas waktu, kecepatan membaca bahasa tes, serta tingkat kecemasan situasional. Hasil ini disarankan dikombinasikan dengan asesmen terstruktur mendalam (seperti IST atau CFIT) dan observasi klinis sebelum perumusan keputusan akhir.")
        }
        return scores to interpretation
    }

    fun enrichWptRecap(part1: Int, part2: Int): Pair<List<AssessmentScore>, String> {
        val correctCount = (part1 + part2).coerceIn(0, 50)
        val iqTable = intArrayOf(
            59, 59, 61, 64, 67, 69, 71, 73, 75, 78, // RS 0-9
            80, 81, 83, 86, 88, 90, 93, 95, 97, 98, // RS 10-19
            100, 102, 104, 106, 108, 111, 113, 114, 116, 118, // RS 20-29
            120, 121, 123, 125, 126, 128, 130, 132, 134, 136, // RS 30-39
            138, 140, 142, 143, 146, 146, 146, 146, 146, 146, 146 // RS 40-50
        )
        val iq = iqTable[correctCount]

        val classification = when {
            iq <= 65 -> "Mentally Defective"
            iq <= 79 -> "Borderline Defective"
            iq <= 90 -> "Low Average"
            iq <= 110 -> "Average"
            iq <= 119 -> "High Average"
            iq <= 127 -> "Superior"
            iq <= 139 -> "Very Superior"
            else -> "Genius"
        }

        val (benchmarkCategory, benchmarkDesc) = when {
            correctCount >= 29 -> "Tinggi / Analitis Kompleks (≥29)" to
                "Kapasitas penalaran tingkat tinggi; cocok untuk posisi riset, rekayasa teknik, sistem analitis, kepemimpinan strategis, dan pengambilan keputusan kompleks."
            correctCount >= 24 -> "Menengah-Atas (24–28)" to
                "Kapasitas belajar dan pemecahan masalah cepat; cocok untuk analis investasi, spesialis teknis, supervisor profesional, dan manajemen lini menengah."
            correctCount >= 20 -> "Rata-Rata Operasional (20–23)" to
                "Rentang rata-rata populasi pekerja dewasa; sesuai untuk perawat, staf perbankan, kasir, teknisi terampil, dan staf operasional reguler."
            correctCount >= 15 -> "Keterampilan Teknis Dasar (15–19)" to
                "Sesuai untuk pekerjaan operasional dengan prosedur tetap, keterampilan teknis langsung (welder, security, staf pergudangan) dengan panduan terstruktur."
            else -> "Di Bawah Benchmark Umum (<15)" to
                "Memerlukan arahan tugas yang sangat spesifik, pelatihan berulang, dan supervisi langsung untuk tugas-tugas terstruktur."
        }

        val percentileEst = when {
            correctCount >= 40 -> 99.0
            correctCount >= 35 -> 95.0
            correctCount >= 30 -> 88.0
            correctCount >= 25 -> 75.0
            correctCount >= 21 -> 50.0
            correctCount >= 17 -> 25.0
            correctCount >= 13 -> 10.0
            else -> (correctCount * 0.7).coerceAtLeast(1.0)
        }

        val scores = listOf(
            AssessmentScore("Total Skor Mentah (RS)", correctCount.toDouble(), 0.0, 50.0, "Jawaban benar pada rekap lembar WPT 50 butir."),
            AssessmentScore("Estimasi IQ Wonderlic", iq.toDouble(), 59.0, 146.0, "Klasifikasi: $classification."),
            AssessmentScore("Benar butir 1–25", part1.toDouble(), 0.0, 25.0, "Rekap butir paruh pertama."),
            AssessmentScore("Benar butir 26–50", part2.toDouble(), 0.0, 25.0, "Rekap butir paruh kedua."),
            AssessmentScore("Persentil Estimasi", percentileEst, 1.0, 99.0, "Estimasi posisi terhadap norma pekerja dewasa umum."),
            AssessmentScore("Benchmark Jabatan", 1.0, 1.0, 1.0, benchmarkCategory)
        )

        val interpretation = buildString {
            append("Hasil Wonderlic Personnel Test (WPT): Skor Mentah $correctCount/50 | Estimasi IQ: $iq ($classification).\n\n")
            append("• Klasifikasi Kognitif: $classification. Menunjukkan kapasitas pemrosesan informasi umum, daya tangkap instruksi baru, dan kelincahan berpikir kritis di bawah batasan waktu 12 menit.\n")
            append("• Rekap Paruh Lembar: Butir 1–25 ($part1/25), Butir 26–50 ($part2/25).\n")
            append("• Kesesuaian Benchmark Jabatan: $benchmarkCategory. $benchmarkDesc\n\n")
            append("Catatan Keputusan & Etika: Wonderlic adalah instrumen skrining kecepatan kognitif (speed-power hybrid test). Skor sangat dipengaruhi oleh batas waktu, kecepatan membaca bahasa tes, serta tingkat kecemasan situasional. Hasil ini disarankan dikombinasikan dengan asesmen terstruktur mendalam (seperti IST atau CFIT) dan observasi klinis sebelum perumusan keputusan akhir.")
        }
        return scores to interpretation
    }

    fun enrichTiu5Recap(part1: Int, part2: Int): Pair<List<AssessmentScore>, String> {
        val correctCount = (part1 + part2).coerceIn(0, 30)
        val ws = when {
            correctCount >= 29 -> 18
            correctCount >= 27 -> 16
            correctCount >= 25 -> 15
            correctCount >= 23 -> 14
            correctCount >= 22 -> 13
            correctCount >= 20 -> 12
            correctCount >= 18 -> 11
            correctCount >= 16 -> 10
            correctCount >= 14 -> 9
            correctCount >= 12 -> 8
            correctCount >= 10 -> 7
            correctCount >= 8 -> 6
            correctCount >= 6 -> 5
            correctCount >= 4 -> 4
            correctCount >= 3 -> 3
            correctCount >= 1 -> 2
            else -> 1
        }

        val category = when {
            ws >= 17 -> "Baik Sekali (BS)"
            ws >= 13 -> "Baik (B)"
            ws >= 9 -> "Sedang / Cukup (S)"
            ws >= 5 -> "Kurang (K)"
            else -> "Kurang Sekali (KS)"
        }

        val operationalMeaning = when {
            ws >= 17 -> "Daya abstraksi visual, logika pola transformasional, dan kecepatan pemecahan masalah figural sangat unggul. Mampu menarik kesimpulan analogis non-verbal secara cepat dan presisi tinggi."
            ws >= 13 -> "Cepat memahami aturan perubahan konsep figural dan cermat mengenali konsistensi pola logika non-verbal. Kapasitas nalar visual sangat baik untuk tuntutan analitis profesional."
            ws >= 9 -> "Kapasitas penalaran logis praktis memadai untuk tuntutan tugas standar operasional. Mampu menyelesaikan persoalan visual rutin dengan akurasi cukup."
            ws >= 5 -> "Memerlukan instruksi konkret langkah demi langkah serta waktu belajar lebih panjang dalam memahami konsep visual atau logika simbolik yang kompleks."
            else -> "Mengalami hambatan nyata dalam menarik kesimpulan logis non-verbal di bawah batasan waktu. Memerlukan pendampingan teknis dan tugas dengan panduan visual eksplisit."
        }

        val scores = listOf(
            AssessmentScore("Kategori TIU 5", ws.toDouble(), 1.0, 20.0, category),
            AssessmentScore("Weighted Score (WS)", ws.toDouble(), 0.0, 20.0, "Konversi norma resmi UI-10144-96."),
            AssessmentScore("Total Skor Mentah (RS)", correctCount.toDouble(), 0.0, 30.0, "Jumlah benar: Part 1 ($part1) + Part 2 ($part2) / 30."),
            AssessmentScore("Benar butir 1–15", part1.toDouble(), 0.0, 15.0, "Rekap butir paruh pertama."),
            AssessmentScore("Benar butir 16–30", part2.toDouble(), 0.0, 15.0, "Rekap butir paruh kedua.")
        )

        val interpretation = "Hasil Rekap Tes Inteligensi Umum 5 (TIU 5 Figural): $category " +
            "(Skor Mentah: $correctCount/30, Weighted Score: $ws/20). " +
            "Menunjukkan daya nalar logika figural, kecepatan menangkap analogi bentuk, " +
            "dan kelincahan analitis visual non-verbal praktis."
        return scores to interpretation
    }

    fun enrichTiu6Recap(part1: Int, part2: Int): Pair<List<AssessmentScore>, String> {
        val correctCount = (part1 + part2).coerceIn(0, 40)
        val ws = when {
            correctCount >= 33 -> 5
            correctCount >= 25 -> 4
            correctCount >= 17 -> 3
            correctCount >= 9 -> 2
            else -> 1
        }

        val category = when (ws) {
            5 -> "BAIK SEKALI (Skala 5)"
            4 -> "BAIK (Skala 4)"
            3 -> "RATA-RATA (Skala 3)"
            2 -> "KURANG (Skala 2)"
            else -> "KURANG SEKALI (Skala 1)"
        }

        val operationalMeaning = when (ws) {
            5 -> "Daya abstraksi spasial 2D–3D sangat unggul; sangat cepat dan presisi tinggi dalam memanipulasi orientasi, konstruksi, dan lipatan bangun ruang kompleks."
            4 -> "Pemahaman hubungan ruang dan ketelitian orientasi objek visual di atas rata-rata populasi. Mampu memecahkan masalah konstruksi bentuk geometris secara efektif."
            3 -> "Kemampuan visual-spasial berada pada rentang memadai / rata-rata untuk tuntutan tugas teknis, operasional, dan pemecahan masalah spasial standar."
            2 -> "Mengalami hambatan dalam memvisualisasikan rotasi atau transformasi lipatan bangun ruang kompleks tanpa bantuan media konkret."
            else -> "Hambatan nyata pada visualisasi spasial 2D–3D di bawah tekanan waktu. Disarankan evaluasi ulang pemahaman instruksi atau pendampingan latihan manipulasi ruang."
        }

        val scores = listOf(
            AssessmentScore("Skor Baku TIU 6", ws.toDouble(), 1.0, 5.0, category),
            AssessmentScore("Total Jawaban Benar", correctCount.toDouble(), 0.0, 40.0, "Raw Score: $correctCount dari 40 butir tes inti."),
            AssessmentScore("Benar butir 1–20", part1.coerceIn(0, 20).toDouble(), 0.0, 20.0, "Rekap butir 1–20."),
            AssessmentScore("Benar butir 21–40", part2.coerceIn(0, 20).toDouble(), 0.0, 20.0, "Rekap butir 21–40.")
        )

        val interpretation = "Hasil Rekap Tes Inteligensi Umum 6 (Penalaran Spasial & Bangun Ruang): Kategori $category " +
            "(Skor Mentah: $correctCount/40, Skala Baku: $ws/5). $operationalMeaning " +
            "Sangat relevan untuk penempatan pada bidang teknik rekayasa, arsitektur, teknologi informasi, dan logistik/operasional presisi."

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

        val stenNorms = mapOf(
            "A" to intArrayOf(1, 1, 1, 1, 2, 3, 3, 4, 5, 6, 7, 8, 9),
            "B" to intArrayOf(1, 2, 3, 4, 5, 6, 8, 9, 10, 10, 10, 10, 10),
            "C" to intArrayOf(1, 1, 2, 2, 3, 4, 4, 5, 6, 7, 8, 9, 10),
            "E" to intArrayOf(1, 2, 3, 4, 4, 5, 6, 7, 8, 9, 9, 10, 10),
            "F" to intArrayOf(1, 1, 1, 2, 3, 4, 5, 6, 6, 7, 8, 9, 10),
            "G" to intArrayOf(1, 1, 2, 2, 3, 4, 4, 5, 6, 7, 8, 9, 10),
            "H" to intArrayOf(1, 1, 2, 3, 3, 4, 5, 5, 6, 7, 8, 9, 10),
            "I" to intArrayOf(1, 1, 2, 3, 4, 4, 5, 6, 7, 7, 8, 9, 10),
            "L" to intArrayOf(1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 10),
            "M" to intArrayOf(1, 1, 2, 3, 4, 5, 6, 7, 7, 8, 9, 10, 10),
            "N" to intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 8, 9, 10, 10, 10),
            "O" to intArrayOf(1, 2, 3, 3, 4, 5, 5, 6, 7, 7, 8, 9, 10),
            "Q1" to intArrayOf(1, 1, 1, 2, 3, 4, 5, 6, 7, 7, 8, 9, 10),
            "Q2" to intArrayOf(2, 3, 4, 5, 6, 6, 7, 8, 9, 9, 10, 10, 10),
            "Q3" to intArrayOf(1, 1, 1, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
            "Q4" to intArrayOf(1, 2, 2, 3, 4, 5, 6, 7, 7, 8, 9, 10, 10),
            "MD" to intArrayOf(1, 1, 1, 2, 2, 3, 4, 5, 6, 7, 8, 8, 9, 10, 10)
        )

        val stenScores = rawScores.mapValues { (factor, raw) ->
            val table = stenNorms[factor] ?: return@mapValues 5
            table[raw.coerceIn(0, table.size - 1)]
        }

        val stenA = stenScores["A"] ?: 5
        val stenB = stenScores["B"] ?: 5
        val stenC = stenScores["C"] ?: 5
        val stenE = stenScores["E"] ?: 5
        val stenF = stenScores["F"] ?: 5
        val stenG = stenScores["G"] ?: 5
        val stenH = stenScores["H"] ?: 5
        val stenI = stenScores["I"] ?: 5
        val stenL = stenScores["L"] ?: 5
        val stenM = stenScores["M"] ?: 5
        val stenN = stenScores["N"] ?: 5
        val stenO = stenScores["O"] ?: 5
        val stenQ1 = stenScores["Q1"] ?: 5
        val stenQ2 = stenScores["Q2"] ?: 5
        val stenQ3 = stenScores["Q3"] ?: 5
        val stenQ4 = stenScores["Q4"] ?: 5
        val mdSten = stenScores["MD"] ?: 5
        val mdRaw = rawScores["MD"] ?: 0

        val exGlobal = (stenA + stenF + stenH + (11 - stenN) + (11 - stenQ2)) / 5.0
        val axGlobal = ((11 - stenC) + stenL + stenO + stenQ4) / 4.0
        val tmGlobal = ((11 - stenA) + (11 - stenI) + (11 - stenM) + (11 - stenQ1)) / 4.0
        val inGlobal = (stenE + stenH + stenL + stenQ1) / 4.0
        val scGlobal = ((11 - stenF) + stenG + (11 - stenM) + stenQ3) / 4.0

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
            "A" to "Berhati-hati, pendiam, menjaga jarak",
            "B" to "Berpikir konkret, pemecahan masalah sederhana",
            "C" to "Reaktif, emosi mudah berubah, rentan frustrasi",
            "E" to "Rendah hati, patuh, mengalah, pasrah",
            "F" to "Serius, tenang, berhati-hati, pendiam",
            "G" to "Fleksibel terhadap aturan, superego bebas",
            "H" to "Pemalu, peka ancaman sosial, menjaga jarak",
            "I" to "Realistis, objektif, praktis, rasional",
            "L" to "Mudah percaya, menerima keadaan orang lain",
            "M" to "Praktis, fokus pada fakta nyata kini",
            "N" to "Lugas, berterus terang, apa adanya",
            "O" to "Percaya diri, tenang, tenteram, bebas cemas",
            "Q1" to "Konservatif, tradisional, setia cara mapan",
            "Q2" to "Berorientasi kelompok, komunal, bergantung tim",
            "Q3" to "Fleksibel, toleran terhadap ketidakteraturan",
            "Q4" to "Santai, tenang, sabar, lamban terpicu"
        )

        val factorHighPole = mapOf(
            "A" to "Ramah tamah, hangat, mudah berpartisipasi",
            "B" to "Cepat menangkap ide abstrak, inteligensi skolastik tinggi",
            "C" to "Emosi mantap, matang menghadapi realitas, tenang",
            "E" to "Asertif, dominan, kompetitif, teguh pendirian",
            "F" to "Spontan, antusias, ekspresif, ceria",
            "G" to "Tekun, bermoral, patuh aturan, superego kuat",
            "H" to "Berani secara sosial, tahan tekanan, percaya diri",
            "I" to "Peka rasa, empatik, estetis, lembut hati",
            "L" to "Waspada, skeptis terhadap motif orang lain",
            "M" to "Imajinatif, teoretis, kreatif, tenggelam ide",
            "N" to "Lihai, cermat sosial, diplomatis, menjaga privasi",
            "O" to "Mudah cemas, menyalahkan diri, peka tekanan",
            "Q1" to "Terbuka pada hal baru, eksperimental, analitis",
            "Q2" to "Mandiri, banyak akal, mengambil keputusan sendiri",
            "Q3" to "Disiplin diri tinggi, teratur, terencana, kompulsif",
            "Q4" to "Tegang, tidak sabaran, terdorong kuat, energi tinggi"
        )

        val scores = mutableListOf<AssessmentScore>()

        for (fKey in listOf("A", "B", "C", "E", "F", "G", "H", "I", "L", "M", "N", "O", "Q1", "Q2", "Q3", "Q4")) {
            val sten = stenScores[fKey] ?: 5
            val raw = rawScores[fKey] ?: 0
            val desc = when {
                sten <= 3 -> "Rendah (Sten $sten): ${factorLowPole[fKey]} (Raw: $raw)"
                sten >= 8 -> "Tinggi (Sten $sten): ${factorHighPole[fKey]} (Raw: $raw)"
                else -> "Rata-rata (Sten $sten): Keseimbangan situasional (Raw: $raw)"
            }
            scores.add(AssessmentScore("Faktor $fKey: ${factorNamesIndo[fKey]}", sten.toDouble(), 1.0, 10.0, desc))
        }

        val mdAlert = if (mdSten >= 6 || mdRaw >= 8) {
            " ⚠️ PERINGATAN: Skor MD tinggi (Sten $mdSten, Raw $mdRaw/14) mengindikasikan kemungkinan faking good."
        } else {
            ""
        }
        scores.add(
            AssessmentScore(
                "Skala Validitas MD (Motivational Distortion)",
                mdSten.toDouble(),
                1.0,
                10.0,
                "Sten $mdSten/10 (Raw: $mdRaw/14).$mdAlert"
            )
        )

        scores.add(AssessmentScore("Faktor Global: Extraversion (EX)", exGlobal, 1.0, 10.0, "Orientasi sosial & kehangatan."))
        scores.add(AssessmentScore("Faktor Global: Anxiety (AX)", axGlobal, 1.0, 10.0, "Tingkat ketegangan & kecemasan."))
        scores.add(AssessmentScore("Faktor Global: Tough-Mindedness (TM)", tmGlobal, 1.0, 10.0, "Ketegasan rasional vs kepekaan."))
        scores.add(AssessmentScore("Faktor Global: Independence (IN)", inGlobal, 1.0, 10.0, "Kemandirian berpikir & bertindak."))
        scores.add(AssessmentScore("Faktor Global: Self-Control (SC)", scGlobal, 1.0, 10.0, "Pengendalian diri & kepatuhan aturan."))

        val exSummary = if (exGlobal >= 6.5) "ekstrover dan sosial" else if (exGlobal <= 4.5) "introver dan mandiri" else "seimbang"
        val axSummary = if (axGlobal >= 6.5) "tinggi (waspada stres)" else if (axGlobal <= 4.5) "tenang dan rileks" else "dalam batas wajar"

        val interpretation = "Profil 16PF Form C Cattell menghasilkan gambaran 16 faktor kepribadian primer dan 5 faktor global. " +
            "Gaya hubungan interpersonal cenderung $exSummary (EX: ${String.format(Locale.US, "%.1f", exGlobal)}), " +
            "dengan tingkat kecemasan $axSummary (AX: ${String.format(Locale.US, "%.1f", axGlobal)}). " +
            "Faktor Penalaran (B) berada pada Sten $stenB. Keteraturan diri (SC) mencapai ${String.format(Locale.US, "%.1f", scGlobal)}.$mdAlert " +
            "Hasil ini merupakan alat bantu keputusan konseling dan asesmen potensi kerja/pribadi normal."

        return scores to interpretation
    }

    fun enrichArmyAlphaRecap(part1: Int, part2: Int): Pair<List<AssessmentScore>, String> {
        val p1Clamped = part1.coerceIn(0, 6)
        val p2Clamped = part2.coerceIn(0, 6)
        val totalCorrect = p1Clamped + p2Clamped
        // In 12-item LPSP3 UI sheet: Items 1-6 has 5 directions & 1 speed; Items 7-12 has 2 directions & 4 speed
        val estimatedDir = (p1Clamped * 5 / 6) + (p2Clamped * 2 / 6)
        val estimatedSpeed = totalCorrect - estimatedDir
        return buildArmyAlphaScores(totalCorrect, estimatedDir, estimatedSpeed)
    }

    private fun enrichArmyAlpha(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val followingCommandsItems = setOf(0, 1, 2, 4, 5, 6, 10)
        val speedAccuracyItems = setOf(3, 7, 8, 9, 11)

        val dirCorrect = followingCommandsItems.count { answers[it] == 0 }
        val speedCorrect = speedAccuracyItems.count { answers[it] == 0 }
        val totalCorrect = dirCorrect + speedCorrect

        return buildArmyAlphaScores(totalCorrect, dirCorrect, speedCorrect)
    }

    private fun buildArmyAlphaScores(
        totalCorrect: Int,
        dirCorrect: Int,
        speedCorrect: Int
    ): Pair<List<AssessmentScore>, String> {
        val scale = when {
            totalCorrect >= 10 -> 5
            totalCorrect >= 8 -> 4
            totalCorrect >= 6 -> 3
            totalCorrect >= 4 -> 2
            else -> 1
        }
        val scaleLabel = when (scale) {
            5 -> "Baik Sekali (Skala 5)"
            4 -> "Baik (Skala 4)"
            3 -> "Rata-Rata (Skala 3)"
            2 -> "Kurang (Skala 2)"
            else -> "Kurang Sekali (Skala 1)"
        }
        val meaning = when (scale) {
            5 -> "Daya tangkap instruksi lisan sangat tajam, tanggap, dan akurat di bawah batasan waktu ketat."
            4 -> "Mampu memahami dan mengeksekusi instruksi kompleks dengan ketelitian di atas rata-rata."
            3 -> "Daya tangkap instruksi lisan memadai pada kondisi standar, cukup cermat dengan sedikit kendala waktu."
            2 -> "Memerlukan waktu adaptasi lebih lama untuk instruksi lisan terarah dan rawan keliru saat terburu-buru."
            else -> "Kesulitan memahami instruksi bertingkat secara cepat; membutuhkan panduan tertulis atau bertahap."
        }

        val scores = listOf(
            AssessmentScore(
                "Total Benar Army Alpha",
                totalCorrect.toDouble(),
                0.0,
                12.0,
                "Norma LPSP3 UI: $scaleLabel. $meaning"
            ),
            AssessmentScore(
                "Skala Army Alpha (Weighted)",
                scale.toDouble(),
                1.0,
                5.0,
                "Skala 1–5: $scaleLabel"
            ),
            AssessmentScore(
                "Subtes Following Commands",
                dirCorrect.toDouble(),
                0.0,
                7.0,
                "Ketelitian eksekusi perintah lisan visual (butir 1–3, 5–7, 11)"
            ),
            AssessmentScore(
                "Subtes Speed & Accuracy",
                speedCorrect.toDouble(),
                0.0,
                5.0,
                "Kecepatan pemrosesan logika & angka (butir 4, 8–10, 12)"
            )
        )

        val interpretation = "Hasil Tes Army Alpha (UI-10013-96) menunjukkan total skor benar $totalCorrect/12 " +
            "yang berada pada klasifikasi $scaleLabel. $meaning Rincian: Subtes Perintah Lisan $dirCorrect/7 benar, " +
            "Subtes Kecepatan & Ketelitian $speedCorrect/5 benar. Digunakan sebagai data pendukung daya tangkap konseling."

        return scores to interpretation
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

        val classification = when {
            rawScore >= 38.0 -> "Very Superior (Sangat Superior)"
            rawScore >= 32.0 -> "Superior (Tinggi)"
            rawScore >= 26.0 -> "High Average (Di Atas Rata-Rata)"
            rawScore >= 18.0 -> "Average (Rata-Rata)"
            else -> "Below Average (Di Bawah Rata-Rata)"
        }

        val scores = listOf(
            AssessmentScore(
                label = "Skor Terkoreksi FRT",
                value = rawScore,
                minimum = 0.0,
                maximum = 45.0,
                meaning = "Rumus baku R - 0.25W: $classification."
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
                meaning = "Jumlah jawaban keliru (dikenakan penalti 0.25)."
            ),
            AssessmentScore(
                label = "Jumlah Kosong (O)",
                value = o.toDouble(),
                minimum = 0.0,
                maximum = 45.0,
                meaning = "Jumlah butir yang tidak dijawab (tanpa penalti)."
            ),
            AssessmentScore(
                label = "Tahap Asosiatif",
                value = asosiatifCorrect.toDouble(),
                minimum = 0.0,
                maximum = 15.0,
                meaning = "Daya tangkap relasi dasar figur geometris (butir 1–15)."
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
                meaning = "Penalaran deduktif & pemecahan matriks kompleks (butir 31–45)."
            )
        )

        val interpretation = "Hasil Figure Reasoning Test (FRT Form A / 9B) menunjukkan Skor Terkoreksi " +
            "${String.format(Locale.US, "%.2f", rawScore)}/45 yang berada pada klasifikasi $classification. " +
            "Rincian: Benar (R) $r, Salah (W) $w, Kosong (O) $o. Capaian per tahap kognitif: " +
            "Tahap Asosiatif $asosiatifCorrect/15, Tahap Sintesis $sintesisCorrect/15, dan Tahap Analisis $analisisCorrect/15. " +
            "Menggambarkan kapasitas penalaran logis figural tanpa pengaruh bias verbal atau budaya."

        return scores to interpretation
    }

    fun enrichCfit2aRecap(r1: Int, r2: Int, r3: Int, r4: Int): Pair<List<AssessmentScore>, String> {
        return buildCfit2aScores(r1, r2, r3, r4)
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

        val iqMap = mapOf(
            48 to 183, 47 to 179, 46 to 176, 45 to 173, 44 to 169, 43 to 168, 42 to 165,
            41 to 160, 40 to 159, 39 to 155, 38 to 152, 37 to 149, 36 to 145, 35 to 144,
            34 to 140, 33 to 136, 32 to 133, 31 to 131, 30 to 128, 29 to 124, 28 to 123,
            27 to 119, 26 to 116, 25 to 113, 24 to 109, 23 to 106, 22 to 103, 21 to 100,
            20 to 98, 19 to 94, 18 to 91, 17 to 88, 16 to 85, 15 to 81, 14 to 78,
            13 to 75, 12 to 73, 11 to 70, 10 to 68, 9 to 63, 8 to 62,
            7 to 57, 6 to 55, 5 to 53, 4 to 51, 3 to 48, 2 to 47, 1 to 46, 0 to 45
        )

        val iq = when {
            totalRaw >= 48 -> 183
            totalRaw <= 0 -> 45
            else -> iqMap[totalRaw] ?: (45 + totalRaw * 2)
        }

        val iqCategory = when {
            iq >= 140 -> "Very Superior (Sangat Superior)"
            iq >= 120 -> "Superior (Tinggi)"
            iq >= 110 -> "High Average (Rata-Rata Tinggi)"
            iq >= 90 -> "Average (Rata-Rata)"
            iq >= 80 -> "Low Average (Rata-Rata Rendah)"
            iq >= 70 -> "Borderline (Garis Batas)"
            else -> "Below Average (Perlu Dukungan Khusus)"
        }

        val scores = listOf(
            AssessmentScore(
                label = "Total Skor Mentah CFIT 2A",
                value = totalRaw.toDouble(),
                minimum = 0.0,
                maximum = 46.0,
                meaning = "Jumlah benar 4 subtes: $totalRaw/46."
            ),
            AssessmentScore(
                label = "Estimasi IQ Cattell (Scale 2)",
                value = iq.toDouble(),
                minimum = 45.0,
                maximum = 183.0,
                meaning = "Klasifikasi norma: $iqCategory."
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

        val interpretation = "Profil CFIT Skala 2 Bentuk A menghasilkan Total Skor Mentah $totalRaw/46 " +
            "dengan estimasi Standard IQ Cattell $iq ($iqCategory). Capaian subtes: " +
            "Seriasi $r1/12, Klasifikasi $r2/14, Matriks $r3/12, dan Persyaratan Titik $r4/8. " +
            "Asesmen ini mengukur kemampuan inteligensi umum bebas pengaruh budaya (fluid intelligence)."

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

        val cfit3aIqTable = mapOf(
            0 to 38, 1 to 40, 2 to 43, 3 to 45, 4 to 47, 5 to 48, 6 to 52, 7 to 55, 8 to 57, 9 to 60,
            10 to 63, 11 to 67, 12 to 70, 13 to 72, 14 to 75, 15 to 78, 16 to 81, 17 to 85, 18 to 88, 19 to 91,
            20 to 94, 21 to 96, 22 to 100, 23 to 103, 24 to 106, 25 to 109, 26 to 113, 27 to 116, 28 to 119, 29 to 121,
            30 to 124, 31 to 128, 32 to 131, 33 to 133, 34 to 137, 35 to 140, 36 to 142, 37 to 145, 38 to 149, 39 to 152,
            40 to 155, 41 to 157, 42 to 161, 43 to 165, 44 to 167, 45 to 169, 46 to 173, 47 to 176, 48 to 179, 49 to 183, 50 to 183
        )

        val iq = cfit3aIqTable[totalRaw.coerceIn(0, 50)] ?: 100

        val iqCategory = when {
            iq >= 140 -> "Genius / Very Superior (Sangat Superior)"
            iq >= 120 -> "Superior (Tinggi)"
            iq >= 110 -> "High Average (Rata-Rata Tinggi)"
            iq >= 90 -> "Average (Rata-Rata)"
            iq >= 80 -> "Low Average (Rata-Rata Rendah)"
            iq >= 70 -> "Borderline (Garis Batas)"
            else -> "Mentally Retardation (Keterbelakangan Mental)"
        }

        val scores = listOf(
            AssessmentScore(
                label = "Total Skor Mentah CFIT 3A",
                value = totalRaw.toDouble(),
                minimum = 0.0,
                maximum = 50.0,
                meaning = "Jumlah benar 4 subtes: $totalRaw/50."
            ),
            AssessmentScore(
                label = "Standard IQ Cattell (Scale 3)",
                value = iq.toDouble(),
                minimum = 38.0,
                maximum = 183.0,
                meaning = "Norma Dewasa/Mahasiswa: $iqCategory."
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
            "$totalRaw/50 dengan Standard IQ Cattell $iq ($iqCategory). Rincian subtes: " +
            "Subtes 1 Deret $r1/13, Subtes 2 Klasifikasi $r2/14 (2 pilihan tepat per butir), Subtes 3 Matriks $r3/13, " +
            "dan Subtes 4 Topologi Titik $r4/10. Mengukur kapasitas penalaran induktif-deduktif nonverbal tingkat lanjut."

        return scores to interpretation
    }

    private fun enrichEnneagram(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val type1Indices = listOf(26, 38, 44, 46, 54, 65, 84, 87, 91, 102, 109, 117, 119, 124, 136, 137, 149, 155, 164, 169)
        val type2Indices = listOf(10, 11, 18, 25, 33, 37, 43, 59, 60, 73, 75, 80, 83, 88, 93, 96, 98, 133, 160, 167)
        val type3Indices = listOf(0, 5, 12, 20, 50, 55, 66, 72, 74, 85, 89, 111, 113, 134, 138, 142, 150, 162, 168, 173)
        val type4Indices = listOf(2, 7, 16, 21, 29, 40, 41, 42, 68, 79, 82, 92, 101, 110, 115, 141, 148, 156, 158, 177)
        val type5Indices = listOf(4, 30, 39, 51, 100, 105, 106, 114, 116, 125, 126, 129, 132, 135, 144, 153, 157, 159, 166, 174)
        val type6Indices = listOf(6, 15, 31, 35, 36, 48, 49, 53, 56, 58, 67, 70, 78, 95, 120, 128, 143, 152, 165, 172)
        val type7Indices = listOf(3, 17, 19, 52, 57, 69, 77, 90, 94, 97, 103, 112, 140, 146, 147, 151, 154, 171, 175, 179)
        val type8Indices = listOf(8, 9, 23, 24, 28, 61, 64, 81, 86, 104, 107, 121, 123, 130, 131, 139, 145, 161, 163, 178)
        val type9Indices = listOf(1, 13, 14, 22, 27, 32, 34, 45, 47, 62, 63, 71, 76, 99, 108, 118, 122, 127, 170, 176)

        val s1 = type1Indices.sumOf { answers[it] ?: 0 }
        val s2 = type2Indices.sumOf { answers[it] ?: 0 }
        val s3 = type3Indices.sumOf { answers[it] ?: 0 }
        val s4 = type4Indices.sumOf { answers[it] ?: 0 }
        val s5 = type5Indices.sumOf { answers[it] ?: 0 }
        val s6 = type6Indices.sumOf { answers[it] ?: 0 }
        val s7 = type7Indices.sumOf { answers[it] ?: 0 }
        val s8 = type8Indices.sumOf { answers[it] ?: 0 }
        val s9 = type9Indices.sumOf { answers[it] ?: 0 }

        val typeScores = listOf(
            1 to s1, 2 to s2, 3 to s3, 4 to s4, 5 to s5, 6 to s6, 7 to s7, 8 to s8, 9 to s9
        )
        val sorted = typeScores.sortedByDescending { it.second }
        val dominantType = sorted.first().first
        val dominantScore = sorted.first().second

        val (leftWing, rightWing) = when (dominantType) {
            1 -> 9 to 2
            9 -> 8 to 1
            else -> (dominantType - 1) to (dominantType + 1)
        }
        val leftScore = typeScores.first { it.first == leftWing }.second
        val rightScore = typeScores.first { it.first == rightWing }.second
        val wing = if (leftScore >= rightScore) leftWing else rightWing
        val wingTag = "${dominantType}w$wing"

        val typeNames = mapOf(
            1 to "The Reformer / Perfeksionis",
            2 to "The Helper / Penolong",
            3 to "The Achiever / Pengejar Prestasi",
            4 to "The Individualist / Romantis",
            5 to "The Investigator / Pengamat",
            6 to "The Loyalist / Pencemas",
            7 to "The Enthusiast / Petualang",
            8 to "The Challenger / Pejuang",
            9 to "The Peacemaker / Pendamai"
        )

        val dominantTitle = typeNames[dominantType] ?: "Tipe $dominantType"
        val wingTitle = typeNames[wing] ?: "Tipe $wing"

        val typeMeanings = mapOf(
            1 to "Memiliki standar tinggi, idealis, bijaksana, produktif, minim membuat kesalahan.",
            2 to "Murah hati, suka menolong sesama, peka perasaan orang lain, hangat dan sosial.",
            3 to "Produktif, ambisius, optimis, kompetitif, fokus pencapaian target dan efisiensi.",
            4 to "Perasaan halus, ekspresif, menghargai estetika seni, mendalam, mencari autentisitas.",
            5 to "Rasa ingin tahu tinggi, analitis, objektif, pemecah masalah, pemikir mandiri.",
            6 to "Komitmen kuat, setia, bertanggung jawab, waspada, mengutamakan kepastian dan keamanan.",
            7 to "Spontan, antusias, enerjik, imajinatif, menyukai variasi dan pengalaman baru.",
            8 to "Semangat baja, tidak mudah menyerah, mandiri, percaya diri, jiwa kepemimpinan tegas.",
            9 to "Diplomatis, cinta damai, sabar, pendengar yang baik, menyatukan lingkungan harmonis."
        )

        val scores = listOf(
            AssessmentScore(
                label = "Tipe Dominan: $wingTag",
                value = dominantType.toDouble(),
                minimum = 1.0,
                maximum = 9.0,
                meaning = "$dominantTitle dengan sayap $wingTag ($wingTitle) (Skor: $dominantScore/60)."
            ),
            AssessmentScore(
                label = "Tipe 1: The Reformer",
                value = s1.toDouble(),
                minimum = 0.0,
                maximum = 60.0,
                meaning = typeMeanings[1].orEmpty()
            ),
            AssessmentScore(
                label = "Tipe 2: The Helper",
                value = s2.toDouble(),
                minimum = 0.0,
                maximum = 60.0,
                meaning = typeMeanings[2].orEmpty()
            ),
            AssessmentScore(
                label = "Tipe 3: The Achiever",
                value = s3.toDouble(),
                minimum = 0.0,
                maximum = 60.0,
                meaning = typeMeanings[3].orEmpty()
            ),
            AssessmentScore(
                label = "Tipe 4: The Individualist",
                value = s4.toDouble(),
                minimum = 0.0,
                maximum = 60.0,
                meaning = typeMeanings[4].orEmpty()
            ),
            AssessmentScore(
                label = "Tipe 5: The Investigator",
                value = s5.toDouble(),
                minimum = 0.0,
                maximum = 60.0,
                meaning = typeMeanings[5].orEmpty()
            ),
            AssessmentScore(
                label = "Tipe 6: The Loyalist",
                value = s6.toDouble(),
                minimum = 0.0,
                maximum = 60.0,
                meaning = typeMeanings[6].orEmpty()
            ),
            AssessmentScore(
                label = "Tipe 7: The Enthusiast",
                value = s7.toDouble(),
                minimum = 0.0,
                maximum = 60.0,
                meaning = typeMeanings[7].orEmpty()
            ),
            AssessmentScore(
                label = "Tipe 8: The Challenger",
                value = s8.toDouble(),
                minimum = 0.0,
                maximum = 60.0,
                meaning = typeMeanings[8].orEmpty()
            ),
            AssessmentScore(
                label = "Tipe 9: The Peacemaker",
                value = s9.toDouble(),
                minimum = 0.0,
                maximum = 60.0,
                meaning = typeMeanings[9].orEmpty()
            )
        )

        val top3Text = sorted.take(3).joinToString(", ") { "Tipe ${it.first} (${it.second}/60)" }
        val interpretation = "Profil Enneagram 180 butir menunjukkan tipe dominan Tipe $dominantType ($dominantTitle) " +
            "dengan sayap $wingTag ($wingTitle). Tiga orientasi skor tertinggi: $top3Text. " +
            "Karakteristik utama: ${typeMeanings[dominantType]}. Tipe dominan ini mencerminkan dorongan motivasional dasar, " +
            "mekanisme pertahanan diri, dan arah pertumbuhan reflektif pribadi dalam konseling."

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

        val gePoints = (0 until 16).sumOf { i ->
            when (answers[60 + i]) {
                0 -> 2
                1 -> 1
                else -> 0
            }
        }
        val rwGe = when {
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

        val rwRa = raKeys.indices.count { answers[76 + it] == raKeys[it] }
        val rwZr = zrKeys.indices.count { answers[96 + it] == zrKeys[it] }
        val rwFa = faKeys.indices.count { answers[116 + it] == faKeys[it] }
        val rwWu = wuKeys.indices.count { answers[136 + it] == wuKeys[it] }
        val rwMe = meKeys.indices.count { answers[156 + it] == meKeys[it] }

        val seSwTable = intArrayOf(68, 71, 74, 76, 79, 82, 85, 88, 91, 94, 97, 100, 103, 106, 109, 112, 115, 118, 121, 124, 126)
        val waSwTable = intArrayOf(63, 66, 70, 74, 77, 81, 84, 88, 91, 95, 99, 102, 106, 109, 113, 116, 120, 124, 127, 131, 134)
        val anSwTable = intArrayOf(76, 78, 81, 83, 86, 88, 91, 93, 96, 98, 101, 103, 106, 108, 111, 113, 116, 118, 121, 123, 126)
        val geSwTable = intArrayOf(69, 72, 75, 78, 81, 83, 86, 89, 92, 94, 97, 100, 103, 106, 108, 111, 114, 117, 119, 122, 125)
        val raSwTable = intArrayOf(74, 77, 79, 82, 85, 88, 91, 94, 97, 99, 102, 105, 108, 111, 114, 117, 119, 122, 125, 128, 131)
        val zrSwTable = intArrayOf(77, 80, 82, 84, 87, 89, 91, 94, 96, 99, 101, 103, 106, 108, 110, 113, 115, 118, 120, 122, 125)
        val faSwTable = intArrayOf(70, 73, 76, 79, 81, 84, 87, 90, 93, 96, 99, 101, 104, 107, 110, 113, 116, 119, 121, 124, 127)
        val wuSwTable = intArrayOf(72, 75, 77, 80, 83, 86, 89, 92, 95, 97, 100, 103, 106, 109, 112, 115, 117, 120, 123, 126, 129)
        val meSwTable = intArrayOf(75, 77, 80, 82, 84, 87, 89, 91, 94, 96, 98, 101, 102, 105, 108, 110, 112, 115, 117, 119, 122)

        val swSe = seSwTable[rwSe.coerceIn(0, 20)]
        val swWa = waSwTable[rwWa.coerceIn(0, 20)]
        val swAn = anSwTable[rwAn.coerceIn(0, 20)]
        val swGe = geSwTable[rwGe.coerceIn(0, 20)]
        val swRa = raSwTable[rwRa.coerceIn(0, 20)]
        val swZr = zrSwTable[rwZr.coerceIn(0, 20)]
        val swFa = faSwTable[rwFa.coerceIn(0, 20)]
        val swWu = wuSwTable[rwWu.coerceIn(0, 20)]
        val swMe = meSwTable[rwMe.coerceIn(0, 20)]

        val totalSw = swSe + swWa + swAn + swGe + swRa + swZr + swFa + swWu + swMe
        val meanSw = totalSw / 9.0
        val iq = kotlin.math.round(100.0 + 1.5 * (meanSw - 100.0)).toInt()

        val iqCategory = when {
            iq >= 130 -> "Very Superior (Sangat Superior)"
            iq >= 120 -> "Superior (Tinggi)"
            iq >= 110 -> "High Average (Di Atas Rata-rata)"
            iq >= 90 -> "Average (Rata-rata)"
            iq >= 80 -> "Low Average (Di Bawah Rata-rata)"
            iq >= 70 -> "Borderline (Garis Batas)"
            else -> "Defective / Rendah"
        }

        val verbalSw = (swSe + swWa + swAn + swGe) / 4.0
        val numericSw = (swRa + swZr) / 2.0
        val spatialSw = (swFa + swWu) / 2.0

        val practicalScore = swSe + swAn
        val theoreticalScore = swWa + swGe
        val thinkingStyle = when {
            practicalScore > theoreticalScore -> "Pola W (Berpikir Praktis-Konkret)"
            theoreticalScore > practicalScore -> "Pola M (Berpikir Teoretis-Abstrak)"
            else -> "Pola Seimbang (Fleksibel)"
        }

        val flexibility = when {
            swGe - swZr >= 5 -> "Fleksibilitas kognitif sangat dominan"
            swZr - swGe >= 5 -> "Penalaran terstruktur dan disiplin logis dominan"
            else -> "Fleksibilitas dan disiplin logika seimbang"
        }

        val scores = listOf(
            AssessmentScore(
                label = "Skor IQ Amthauer (IST)",
                value = iq.toDouble(),
                minimum = 40.0,
                maximum = 160.0,
                meaning = "Klasifikasi: $iqCategory (Mean SW: ${String.format(Locale.US, "%.1f", meanSw)})."
            ),
            AssessmentScore(
                label = "Total Standard Score (SW)",
                value = totalSw.toDouble(),
                minimum = 540.0,
                maximum = 1170.0,
                meaning = "Jumlah skor terstandarisasi 9 subtes (Norma Usia 21–25)."
            ),
            AssessmentScore(
                label = "Klaster Verbal (SE, WA, AN, GE)",
                value = verbalSw,
                minimum = 63.0,
                maximum = 134.0,
                meaning = "Pemahaman bahasa, penalaran analogi, dan abstraksi konsep."
            ),
            AssessmentScore(
                label = "Klaster Numerik (RA, ZR)",
                value = numericSw,
                minimum = 74.0,
                maximum = 131.0,
                meaning = "Penalaran matematika praktis dan keteraturan logika deret."
            ),
            AssessmentScore(
                label = "Klaster Figural-Spasial (FA, WU)",
                value = spatialSw,
                minimum = 70.0,
                maximum = 129.0,
                meaning = "Sintesis visual 2D dan rotasi balok kubus spasial 3D."
            ),
            AssessmentScore(
                label = "Daya Ingat Kata (ME)",
                value = swMe.toDouble(),
                minimum = 75.0,
                maximum = 122.0,
                meaning = "Retensi memori jangka pendek materi verbal terstruktur (RW: $rwMe/20)."
            ),
            AssessmentScore(
                label = "Subtes 1: SE (Melengkapi Kalimat)",
                value = swSe.toDouble(),
                minimum = 68.0,
                maximum = 126.0,
                meaning = "RW: $rwSe/20. Pembentukan keputusan dan akal sehat logis."
            ),
            AssessmentScore(
                label = "Subtes 2: WA (Mencari Kata Berbeda)",
                value = swWa.toDouble(),
                minimum = 63.0,
                maximum = 134.0,
                meaning = "RW: $rwWa/20. Rasa bahasa dan diferensiasi konsep verbal."
            ),
            AssessmentScore(
                label = "Subtes 3: AN (Analogi Kata)",
                value = swAn.toDouble(),
                minimum = 76.0,
                maximum = 126.0,
                meaning = "RW: $rwAn/20. Daya kombinasi dan relasi analogis verbal."
            ),
            AssessmentScore(
                label = "Subtes 4: GE (Kesamaan Konsep)",
                value = swGe.toDouble(),
                minimum = 69.0,
                maximum = 125.0,
                meaning = "Poin: $gePoints/32 (RW: $rwGe/20). Daya abstraksi dan generalisasi."
            ),
            AssessmentScore(
                label = "Subtes 5: RA (Hitungan Praktis)",
                value = swRa.toDouble(),
                minimum = 74.0,
                maximum = 131.0,
                meaning = "RW: $rwRa/20. Pemecahan masalah matematis praktis sehari-hari."
            ),
            AssessmentScore(
                label = "Subtes 6: ZR (Deret Angka)",
                value = swZr.toDouble(),
                minimum = 77.0,
                maximum = 125.0,
                meaning = "RW: $rwZr/20. Penalaran induktif kuantitatif dan keteraturan deret."
            ),
            AssessmentScore(
                label = "Subtes 7: FA (Bentuk Potongan 2D)",
                value = swFa.toDouble(),
                minimum = 70.0,
                maximum = 127.0,
                meaning = "RW: $rwFa/20. Visualisasi ruang bidang dan rekonstruksi bentuk."
            ),
            AssessmentScore(
                label = "Subtes 8: WU (Kubus Balok 3D)",
                value = swWu.toDouble(),
                minimum = 72.0,
                maximum = 129.0,
                meaning = "RW: $rwWu/20. Orientasi ruang tiga dimensi dan rotasi mental kubus."
            ),
            AssessmentScore(
                label = "Subtes 9: ME (Daya Ingat Kata)",
                value = swMe.toDouble(),
                minimum = 75.0,
                maximum = 122.0,
                meaning = "RW: $rwMe/20. Konsentrasi retensi dan rekoleksi informasi hafalan."
            )
        )

        val interpretation = "Profil Intelligenz-Struktur-Test (IST) 176 butir menunjukkan Skor IQ Amthauer $iq ($iqCategory) " +
            "dengan Total Standard Score $totalSw (Rerata SW: ${String.format(Locale.US, "%.1f", meanSw)}). " +
            "Gaya berpikir teridentifikasi sebagai $thinkingStyle, dengan capaian klaster: Verbal " +
            "${String.format(Locale.US, "%.1f", verbalSw)}, Numerik ${String.format(Locale.US, "%.1f", numericSw)}, " +
            "Spasial ${String.format(Locale.US, "%.1f", spatialSw)}, dan Memori $swMe. Dinamika kognitif: $flexibility. " +
            "Hasil ini mengukur struktur inteligensi multidimensi terstandarisasi untuk panduan konseling karir dan kognitif."

        return scores to interpretation
    }

    private fun enrichKuder(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        var rawV = 0
        for (rule in AssessmentKuderData.RULES_V) {
            if (AssessmentKuderData.checkRule(rule, answers)) rawV++
        }
        val validityMeaning = when {
            rawV <= 37 -> "Kurang valid (testee kurang bersungguh-sungguh atau kurang konsisten, skor <= 37)."
            rawV <= 44 -> "Valid (skor verifikasi berada pada rentang akurat 38–44)."
            else -> "Kurang valid (indikasi ketidakpahaman instruksi atau bias pola, skor > 44)."
        }

        val scaleScores = AssessmentKuderData.VOCATIONAL_SCALES.mapIndexed { idx, (_, name, rules) ->
            var raw = 0
            for (rule in rules) {
                if (AssessmentKuderData.checkRule(rule, answers)) raw++
            }
            val clampedRaw = raw.coerceIn(0, 99)
            val pMale = AssessmentKuderData.NORMS_MALE[idx].getOrElse(clampedRaw) { 50 }
            val category = when {
                pMale <= 24 -> "Rendah"
                pMale <= 74 -> "Cukup"
                else -> "Tinggi"
            }
            Triple(name, raw, pMale to category)
        }

        val top3 = scaleScores.sortedByDescending { it.third.first }.take(3)
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
        for ((name, raw, pCat) in scaleScores) {
            val (p, cat) = pCat
            scores.add(
                AssessmentScore(
                    label = name,
                    value = p.toDouble(),
                    minimum = 0.0,
                    maximum = 100.0,
                    meaning = "Skor Mentah: $raw, Persentil: $p% (Kategori: $cat)"
                )
            )
        }

        val topDesc = top3.joinToString(", ") { "${it.first} (${it.third.first}%)" }
        val interpretation = "Hasil inventori minat Kuder (KPR-V Form C) menunjukkan status verifikasi: $validityMeaning. " +
            "Tiga area minat vokasional paling dominan adalah: $topDesc. " +
            "Area dengan persentil >= 75% merefleksikan kecenderungan intrinsik yang kuat terhadap aktivitas kerja terkait " +
            "dan menjadi rekomendasi utama untuk eksplorasi karier serta penjurusan studi."

        return scores to interpretation
    }

    private fun enrichKraepelin(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val cols = answers.keys.filter { it in 0..49 }.sorted()
        if (cols.isEmpty()) {
            return listOf(
                AssessmentScore("PANKER (Kecepatan)", 0.0, 0.0, 30.0, "Belum ada lajur yang diselesaikan."),
                AssessmentScore("TIANKER (Ketelitian)", 0.0, 0.0, 100.0, "Tidak ada data kesalahan."),
                AssessmentScore("JANKER (Keajegan)", 0.0, 0.0, 20.0, "Tidak ada data deviasi."),
                AssessmentScore("HANKER (Ketahanan)", 0.0, -10.0, 10.0, "Tidak ada data tren.")
            ) to "Tes Kraepelin belum selesai dikerjakan."
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

        val pankerCat = when {
            meanY >= 14.6 -> "Baik Sekali (Tempo Cepat)"
            meanY >= 12.5 -> "Baik (Cepat & Cekatan)"
            meanY >= 10.4 -> "Sedang (Rerata Normal)"
            meanY >= 8.3 -> "Kurang (Tempo Lambat)"
            else -> "Kurang Sekali (Sangat Lambat)"
        }

        val tiankerCat = when {
            tianker <= 2 -> "Baik Sekali (Sangat Teliti & Presisi)"
            tianker <= 6 -> "Baik (Teliti, Kesalahan Minimal)"
            tianker <= 12 -> "Sedang (Akurasi Cukup)"
            tianker <= 20 -> "Kurang (Rentan Ceroboh / Lewatan)"
            else -> "Kurang Sekali (Banyak Salah & Terlewati)"
        }

        val jankerCat = when {
            avDev < 1.5 -> "Baik Sekali (Sangat Ajeg & Konsisten)"
            avDev <= 2.5 -> "Baik (Ritme Kerja Stabil)"
            avDev <= 3.8 -> "Sedang (Fluktuasi Wajar)"
            avDev <= 5.2 -> "Kurang (Mudah Terdistraksi)"
            else -> "Kurang Sekali (Sangat Tidak Stabil)"
        }

        val hankerCat = when {
            bSlope > 0.05 -> "Positif (Daya Tahan Meningkat / Stamina Prima)"
            bSlope >= -0.05 -> "Konstan (Stamina Terjaga Stabil)"
            else -> "Negatif (Penurunan Daya Tahan / Cepat Lelah)"
        }

        val scores = listOf(
            AssessmentScore(
                label = "PANKER (Kecepatan Kerja)",
                value = meanY,
                minimum = 0.0,
                maximum = 30.0,
                meaning = "Rerata: ${String.format(Locale.US, "%.2f", meanY)} benar/lajur (Total: $sumY benar dalam $n lajur). Kategori: $pankerCat."
            ),
            AssessmentScore(
                label = "TIANKER (Ketelitian Kerja)",
                value = tianker.toDouble(),
                minimum = 0.0,
                maximum = 50.0,
                meaning = "Total Kesalahan: $totalErrors, Terlewati: $totalSkips (Tianker: $tianker). Kategori: $tiankerCat."
            ),
            AssessmentScore(
                label = "JANKER (Keajegan Ritme)",
                value = avDev,
                minimum = 0.0,
                maximum = 10.0,
                meaning = "Average Deviation: ${String.format(Locale.US, "%.2f", avDev)}, Range: $range ($yMin–$yMax). Kategori: $jankerCat."
            ),
            AssessmentScore(
                label = "HANKER (Ketahanan Kerja)",
                value = bSlope,
                minimum = -2.0,
                maximum = 2.0,
                meaning = "Kemiringan Regresi b: ${String.format(Locale.US, "%.3f", bSlope)}, Tren Δy: ${String.format(Locale.US, "%.1f", deltaY)}. Kategori: $hankerCat."
            )
        )

        val interpretation = "Hasil evaluasi tes sikap kerja Kraepelin ($n lajur) menunjukkan: " +
            "Kecepatan kerja (Panker) berada pada tingkat $pankerCat dengan rata-rata ${String.format(Locale.US, "%.1f", meanY)} " +
            "penjumlahan per 15 detik. Ketelitian (Tianker) menunjukkan tingkat $tiankerCat dengan akumulasi $tianker kesalahan/lompatan. " +
            "Kestabilan ritme kerja (Janker) terkategori $jankerCat (deviasi rata-rata ${String.format(Locale.US, "%.2f", avDev)}). " +
            "Ketahanan kerja mental (Hanker) teridentifikasi $hankerCat dengan gradien tren ${String.format(Locale.US, "%.3f", bSlope)}. " +
            "Profil ini mencerminkan dinamika energi kerja, ketahanan di bawah tekanan waktu berulang, dan fokus eksekutif."

        return scores to interpretation
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
            val raw = range.sumOf { idx ->
                val ans = answers[idx] ?: 0
                (ans.coerceIn(0, 3)) + 1
            }
            val percentage = (raw.toDouble() / 28.0) * 100.0
            val cat = when {
                raw >= 22 -> "Tinggi (Kecerdasan Menonjol)"
                raw >= 15 -> "Sedang (Berkembang Baik)"
                else -> "Perlu Stimulasi"
            }
            Triple(name, raw, percentage to cat)
        }

        val top3 = dimResults.sortedByDescending { it.second }.take(3)
        val scores = dimResults.map { (name, raw, pctCat) ->
            val (pct, cat) = pctCat
            AssessmentScore(
                label = name,
                value = raw.toDouble(),
                minimum = 7.0,
                maximum = 28.0,
                meaning = "Skor: $raw/28 (${String.format(Locale.US, "%.1f", pct)}%). Kategori: $cat."
            )
        }

        val topNames = top3.joinToString(", ") { "${it.first} (${it.second}/28)" }
        val interpretation = "Profil Multiple Intelligences (Model Howard Gardner 56 butir) mengidentifikasi " +
            "tiga kecerdasan paling menonjol pada diri Anda: $topNames. " +
            "Dimensi-dimensi dengan kategori Tinggi (skor >= 22) merupakan modalitas belajar dan pemecahan masalah alami yang paling efektif, " +
            "serta menjadi referensi relevan dalam pengembangan karier, kolaborasi tim, dan pengoptimalan potensi diri."

        return scores to interpretation
    }

    private fun enrichMsai(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
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

        val skillScores = skills.map { (name, itemNums, quad) ->
            val vals = itemNums.mapNotNull { num ->
                answers[num - 1]?.let { ans ->
                    (ans.coerceIn(0, 4)) + 1
                }
            }
            val avg = if (vals.isNotEmpty()) vals.average() else 3.0
            Triple(name, avg, quad)
        }

        val clanAvg = skillScores.filter { it.third == "Clan" }.map { it.second }.average()
        val adhocAvg = skillScores.filter { it.third == "Adhocracy" }.map { it.second }.average()
        val marketAvg = skillScores.filter { it.third == "Market" }.map { it.second }.average()
        val hierAvg = skillScores.filter { it.third == "Hierarchy" }.map { it.second }.average()

        val quadrants = listOf(
            "Clan (Collaborate)" to clanAvg,
            "Adhocracy (Create)" to adhocAvg,
            "Market (Compete)" to marketAvg,
            "Hierarchy (Control)" to hierAvg
        ).sortedByDescending { it.second }

        val dominantQuad = quadrants.first()
        val topSkill = skillScores.maxByOrNull { it.second } ?: skillScores.first()
        val lowestSkill = skillScores.minByOrNull { it.second } ?: skillScores.last()

        val scores = mutableListOf<AssessmentScore>()
        scores.add(
            AssessmentScore(
                label = "Kuadran Dominan: ${dominantQuad.first}",
                value = dominantQuad.second,
                minimum = 1.0,
                maximum = 5.0,
                meaning = "Rerata kuadran: ${String.format(Locale.US, "%.2f", dominantQuad.second)}/5.00. Orientasi nilai manajerial utama."
            )
        )
        scores.add(
            AssessmentScore(
                label = "Kuadran Clan (Collaborate)",
                value = clanAvg,
                minimum = 1.0,
                maximum = 5.0,
                meaning = "Pengembangan SDM, empati interpersonal, dan pembinaan tim."
            )
        )
        scores.add(
            AssessmentScore(
                label = "Kuadran Adhocracy (Create)",
                value = adhocAvg,
                minimum = 1.0,
                maximum = 5.0,
                meaning = "Inovasi, visi perubahan masa depan, dan perbaikan berkelanjutan."
            )
        )
        scores.add(
            AssessmentScore(
                label = "Kuadran Market (Compete)",
                value = marketAvg,
                minimum = 1.0,
                maximum = 5.0,
                meaning = "Daya saing eksternal, dorongan produktivitas kerja, dan fokus kepuasan pelanggan."
            )
        )
        scores.add(
            AssessmentScore(
                label = "Kuadran Hierarchy (Control)",
                value = hierAvg,
                minimum = 1.0,
                maximum = 5.0,
                meaning = "Koordinasi operasional, sistem pengawasan terukur, dan kepatuhan prosedur."
            )
        )

        skillScores.forEach { (name, avg, quad) ->
            scores.add(
                AssessmentScore(
                    label = "$name ($quad)",
                    value = avg,
                    minimum = 1.0,
                    maximum = 5.0,
                    meaning = "Skor kompetensi: ${String.format(Locale.US, "%.2f", avg)}/5.00."
                )
            )
        }

        val interpretation = "Berdasarkan Competing Values Framework (CVF) Cameron & Quinn, profil manajerial Anda menunjukkan " +
            "orientasi paling dominan pada ${dominantQuad.first} (rerata ${String.format(Locale.US, "%.2f", dominantQuad.second)}/5.00). " +
            "Kekuatan manajerial utama teridentifikasi pada ${topSkill.first} (${String.format(Locale.US, "%.2f", topSkill.second)}/5.00). " +
            "Area prioritas pengembangan kepemimpinan adalah ${lowestSkill.first} (${String.format(Locale.US, "%.2f", lowestSkill.second)}/5.00). " +
            "Rekomendasi: Tingkatkan keseimbangan antara fleksibilitas inovatif dan ketertiban sistem operasional sesuai dinamika unit Anda."

        return scores to interpretation
    }

    private fun enrichMsdt(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val koreksi = listOf(1, 2, 1, 0, 3, -1, 0, -4)
        val rowA = IntArray(8) { 0 }
        val colB = IntArray(8) { 0 }

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
            "Deserter (Ds - Pelari / Pasif)" to "Separated, rendah tugas & relasi, menarik diri.",
            "Missionary (Mi - Pencinta Damai)" to "Related, orientasi relasi tinggi namun mengabaikan tugas.",
            "Autocrat (Au - Otokratis Murni)" to "Dedicated, orientasi tugas tinggi tanpa peduli relasi.",
            "Compromiser (Co - Kompromis Lemah)" to "Integrated, ingin menyenangkan semua pihak namun ragu-ragu.",
            "Bureaucrat (Bu - Birokrat Prosedural)" to "Separated + Efektif, taat aturan, objektif dan teliti.",
            "Developer (Dv - Pembina / Pengembang)" to "Related + Efektif, percaya penuh pada bawahan, mendelegasikan wewenang.",
            "Benevolent Autocrat (Ba - Otokrat Bijak)" to "Dedicated + Efektif, tegas mencapai target tanpa merusak hubungan kerja.",
            "Executive (E - Eksekutif Terintegrasi)" to "Integrated + Efektif, standar tinggi, motivasi tinggi, dan komunikasi dua arah."
        )

        val ds = jumlah[0]
        val mi = jumlah[1]
        val au = jumlah[2]
        val co = jumlah[3]
        val bu = jumlah[4]
        val dv = jumlah[5]
        val ba = jumlah[6]
        val e = jumlah[7]

        val to = au + co + ba + e
        val ro = mi + co + dv + e
        val ef = bu + dv + ba + e

        val rankedStyles = styleNames.indices.map { idx ->
            Triple(styleNames[idx].first, jumlah[idx], styleNames[idx].second)
        }.sortedByDescending { it.second }

        val dominantStyle = rankedStyles.first()
        val secondaryStyle = rankedStyles[1]

        val scores = mutableListOf<AssessmentScore>()
        scores.add(
            AssessmentScore(
                label = "Gaya Dominan: ${dominantStyle.first.split(" (")[0]}",
                value = dominantStyle.second.toDouble(),
                minimum = -10.0,
                maximum = 30.0,
                meaning = "Skor: ${dominantStyle.second}. ${dominantStyle.third}"
            )
        )
        scores.add(
            AssessmentScore(
                label = "Gaya Pendukung: ${secondaryStyle.first.split(" (")[0]}",
                value = secondaryStyle.second.toDouble(),
                minimum = -10.0,
                maximum = 30.0,
                meaning = "Skor: ${secondaryStyle.second}. ${secondaryStyle.third}"
            )
        )
        scores.add(
            AssessmentScore(
                label = "Task Orientation (TO)",
                value = to.toDouble(),
                minimum = -10.0,
                maximum = 50.0,
                meaning = "Orientasi terhadap pencapaian target tugas dan instruksi (Au+Co+Ba+E = $to)."
            )
        )
        scores.add(
            AssessmentScore(
                label = "Relationship Orientation (RO)",
                value = ro.toDouble(),
                minimum = -10.0,
                maximum = 50.0,
                meaning = "Orientasi terhadap relasi antarmanusia dan keharmonisan (Mi+Co+Dv+E = $ro)."
            )
        )
        scores.add(
            AssessmentScore(
                label = "Effectiveness (E)",
                value = ef.toDouble(),
                minimum = -10.0,
                maximum = 50.0,
                meaning = "Tingkat efektivitas situasional dan adaptabilitas kepemimpinan (Bu+Dv+Ba+E = $ef)."
            )
        )

        rankedStyles.forEach { (name, scoreVal, desc) ->
            scores.add(
                AssessmentScore(
                    label = name,
                    value = scoreVal.toDouble(),
                    minimum = -10.0,
                    maximum = 30.0,
                    meaning = "Skor matrik: $scoreVal. $desc"
                )
            )
        }

        val interpretation = "Diagnosis Gaya Manajemen Reddin (MSDT) menunjukkan gaya dominan Anda adalah ${dominantStyle.first} " +
            "(Skor: ${dominantStyle.second}) dengan gaya pendukung ${secondaryStyle.first} (Skor: ${secondaryStyle.second}). " +
            "Dimensi kepemimpinan: Task Orientation (TO) = $to, Relationship Orientation (RO) = $ro, Efektivitas Situasional (E) = $ef. " +
            "Profil ini mencerminkan fleksibilitas dalam memilih pendekatan manajerial yang tepat antara ketegasan tugas dan pembinaan relasi sesuai dinamika tim."

        return scores to interpretation
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

        answers.forEach { (qIdx, ans) ->
            if (qIdx in papiItemScales.indices) {
                val pair = papiItemScales[qIdx]
                if (ans == 0) {
                    scaleCounts[pair.first] = (scaleCounts[pair.first] ?: 0) + 1
                } else if (ans == 1) {
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
            val cat = when {
                count >= 7 -> "Tinggi"
                count >= 4 -> "Sedang"
                else -> "Rendah"
            }
            AssessmentScore(
                label = "${scaleLabels[code]} [$code]",
                value = count.toDouble(),
                minimum = 0.0,
                maximum = 9.0,
                meaning = "Skor: $count/9. Kategori: $cat."
            )
        }

        val highScores = scaleCodes.filter { (scaleCounts[it] ?: 0) >= 7 }.map { "${scaleLabels[it]?.split(" (")?.get(0)} ($it)" }
        val lowScores = scaleCodes.filter { (scaleCounts[it] ?: 0) <= 2 }.map { "${scaleLabels[it]?.split(" (")?.get(0)} ($it)" }

        val highStr = if (highScores.isNotEmpty()) highScores.joinToString(", ") else "Tidak ada dimensi dominan ekstrem"
        val lowStr = if (lowScores.isNotEmpty()) lowScores.joinToString(", ") else "Tidak ada kebutuhan minimal ekstrem"

        val interpretation = "Profil PAPI Kostick (Perception and Preference Inventory 90 butir) memetakan 20 aspek peran dan kebutuhan kerja: " +
            "Dimensi menonjol (Skor Tinggi >= 7): $highStr. " +
            "Dimensi dengan intensitas rendah (Skor <= 2): $lowStr. " +
            "Kombinasi ini menggambarkan preferensi lingkungan kerja, gaya komunikasi profesional, serta dorongan motivasional utama Anda di tempat kerja."

        return scores to interpretation
    }

    private fun enrichPauli(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val n = answers.size.coerceAtLeast(1)
        val sortedVals = answers.entries.sortedBy { it.key }.map { it.value }
        val sumAdditions = sortedVals.sum()
        val meanPerf = sumAdditions.toDouble() / n
        val maxInterval = sortedVals.maxOrNull() ?: 0
        val minInterval = sortedVals.minOrNull() ?: 0
        val maxIdx = sortedVals.indexOf(maxInterval) + 1
        val minIdx = sortedVals.indexOf(minInterval) + 1

        val avDev = sortedVals.map { kotlin.math.abs(it - meanPerf) }.average()

        val third = (n / 3).coerceAtLeast(1)
        val earlyMean = sortedVals.take(third).average()
        val lateMean = sortedVals.takeLast(third).average()
        val curveTrend = when {
            lateMean > earlyMean + 1.0 -> "Meningkat (Warm-up positif, daya tahan prima)"
            lateMean < earlyMean - 1.0 -> "Menurun (Terindikasi kelelahan kerja / fatigue)"
            else -> "Stabil / Konsisten (Ritme kerja terpelihara dengan baik)"
        }

        val scores = listOf(
            AssessmentScore(
                label = "Hasil Kerja Total (Jumlah)",
                value = sumAdditions.toDouble(),
                minimum = 0.0,
                maximum = 2000.0,
                meaning = "Total penjumlahan yang diselesaikan: $sumAdditions dalam $n interval."
            ),
            AssessmentScore(
                label = "Kecepatan Rata-rata per Interval",
                value = meanPerf,
                minimum = 0.0,
                maximum = 50.0,
                meaning = "Rerata: ${String.format(Locale.US, "%.1f", meanPerf)} penjumlahan per interval waktu."
            ),
            AssessmentScore(
                label = "Puncak Prestasi Kerja (Peak)",
                value = maxInterval.toDouble(),
                minimum = 0.0,
                maximum = 60.0,
                meaning = "Puncak output $maxInterval penjumlahan dicapai pada interval ke-$maxIdx (Palung: $minInterval pada interval ke-$minIdx)."
            ),
            AssessmentScore(
                label = "Keajegan Kerja (Deviasi Rata-rata)",
                value = avDev,
                minimum = 0.0,
                maximum = 15.0,
                meaning = "Deviasi rerata: ${String.format(Locale.US, "%.2f", avDev)}. Nilai lebih kecil menunjukkan kestabilan emosi dan ritme."
            ),
            AssessmentScore(
                label = "Tren Kurva Kerja",
                value = if (lateMean >= earlyMean) 1.0 else 0.0,
                minimum = 0.0,
                maximum = 1.0,
                meaning = "$curveTrend (Fase awal: ${String.format(Locale.US, "%.1f", earlyMean)} vs Fase akhir: ${String.format(Locale.US, "%.1f", lateMean)})."
            )
        )

        val interpretation = "Evaluasi tes sikap kerja Pauli ($n interval kerja dari atas ke bawah) menunjukkan: " +
            "Total produktivitas kerja sebanyak $sumAdditions penjumlahan dengan rerata kecepatan ${String.format(Locale.US, "%.1f", meanPerf)} per interval. " +
            "Puncak performa ($maxInterval penjumlahan) dicapai pada interval ke-$maxIdx. " +
            "Indeks keajegan kerja (deviasi rerata ${String.format(Locale.US, "%.2f", avDev)}) mencerminkan konsistensi kemauan (willpower). " +
            "Dinamika kurva kerja menunjukkan pola $curveTrend. " +
            "Profil ini mengindikasikan kapasitas energi kerja, ketahanan terhadap kelelahan monotonik, dan pengendalian diri di bawah tekanan berkelanjutan."

        return scores to interpretation
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
            val idx = itemNum - 1
            val ans = answers[idx] ?: continue
            val isYes = (ans == 0)
            val isNo = (ans == 1)

            if ((itemNum in pYes && isYes) || (itemNum in pNo && isNo)) pScore++
            if ((itemNum in eYes && isYes) || (itemNum in eNo && isNo)) eScore++
            if (itemNum in nYes && isYes) nScore++
            if ((itemNum in lYes && isYes) || (itemNum in lNo && isNo)) lScore++
        }

        val lieWarning = if (lScore >= 7) {
            " ⚠️ PERINGATAN VALIDITAS: Skor Lie Scale tinggi ($lScore/12) mengindikasikan kecenderungan defensif / faking good (menampilkan citra diri sosial ideal)."
        } else {
            ""
        }

        val temperament = when {
            eScore >= 6 && nScore < 6 -> "Sanguinis (Stabil Ekstrovert: Ramah, terbuka, optimis, mudah beradaptasi)"
            eScore >= 6 && nScore >= 6 -> "Koleris (Labil Ekstrovert: Berenergi tinggi, asertif, reaktif terhadap stres)"
            eScore < 6 && nScore >= 6 -> "Melankolis (Labil Introvert: Sensitif, mendalam, cemas, penuh kehati-hatian)"
            else -> "Plegmatis (Stabil Introvert: Tenang, terkontrol, damai, konsisten)"
        }

        val scores = listOf(
            AssessmentScore(
                label = "Tipe Temperamen: ${temperament.split(" (")[0]}",
                value = eScore.toDouble(),
                minimum = 0.0,
                maximum = 12.0,
                meaning = temperament
            ),
            AssessmentScore(
                label = "Extraversion (E)",
                value = eScore.toDouble(),
                minimum = 0.0,
                maximum = 12.0,
                meaning = "Skor: $eScore/12. " + if (eScore >= 6) "Tinggi (Sosiabel, aktif, mencari stimulasi sosial)" else "Rendah (Introver, reflektif, menyendiri)"
            ),
            AssessmentScore(
                label = "Neuroticism (N)",
                value = nScore.toDouble(),
                minimum = 0.0,
                maximum = 12.0,
                meaning = "Skor: $nScore/12. " + if (nScore >= 6) "Tinggi (Reaktivitas emosional tinggi, mudah cemas)" else "Rendah (Stabilitas emosi baik, tenang menghadapi tekanan)"
            ),
            AssessmentScore(
                label = "Psychoticism (P)",
                value = pScore.toDouble(),
                minimum = 0.0,
                maximum = 12.0,
                meaning = "Skor: $pScore/12. " + if (pScore >= 4) "Tinggi (Tough-mindedness, mandiri, non-konformis)" else "Rendah (Konformis sosial, hangat, berempati)"
            ),
            AssessmentScore(
                label = "Lie Scale / Kejujuran (L)",
                value = lScore.toDouble(),
                minimum = 0.0,
                maximum = 12.0,
                meaning = "Skor: $lScore/12. Skala konformitas sosial & kejujuran respons.$lieWarning"
            )
        )

        val interpretation = "Berdasarkan inventori kepribadian EPQ-R (Eysenck Personality Questionnaire – Revised 48 butir), " +
            "profil kepribadian Anda terpetakan pada tipe temperamen $temperament. " +
            "Dimensi Extraversion (E) bernilai $eScore/12, Neuroticism (N) bernilai $nScore/12, dan Psychoticism (P) bernilai $pScore/12. " +
            "Tingkat kejujuran/desirabilitas sosial (L) adalah $lScore/12.$lieWarning " +
            "Hasil ini membantu memahami pola regulasi emosi dasar, respons terhadap stresor lingkungan, dan dinamika interaksi sosial."

        return scores to interpretation
    }


    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun enrichWartegg(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val completedBoxes = (0 until 8).count { answers.containsKey(it) }
        val orderList = mutableListOf<Pair<Int, Int>>() // box to order
        val evalMap = mutableMapOf<Int, Int>() // box to eval (0: +, 1: -, 2: M, 3: S)

        for (b in 0 until 8) {
            val v = answers[b] ?: 0
            val order = if (v >= 10) v / 10 else (b + 1)
            val eval = if (v >= 10) v % 10 else v
            orderList.add(b to order)
            evalMap[b] = eval
        }

        val sortedByOrder = orderList.sortedBy { it.second }
        val isSequential = sortedByOrder.map { it.first } == (0 until 8).toList()

        // 4 Fungsi Kinget:
        // Emosi: Kotak 2 (kehangatan sosial) & Kotak 7 (sensitivitas perasaan)
        // Imajinasi: Kotak 1 (ego/pemusatan diri) & Kotak 8 (perlindungan/rasa aman)
        // Intelektual: Kotak 3 (ambisi berprestasi) & Kotak 6 (sintesis logis)
        // Aktivitas: Kotak 4 (pengendalian kecemasan/beban) & Kotak 5 (dinamisme bertindak)
        fun calculateFunctionScore(boxA: Int, boxB: Int): Double {
            var score = 5.0
            val orderA = orderList.firstOrNull { it.first == boxA }?.second ?: 4
            val orderB = orderList.firstOrNull { it.first == boxB }?.second ?: 5
            val avgOrder = (orderA + orderB) / 2.0
            if (avgOrder <= 3.5) score += 2.0 else if (avgOrder >= 5.5) score -= 1.0
            val evalA = evalMap[boxA] ?: -1
            val evalB = evalMap[boxB] ?: -1
            if (evalA == 0 || evalB == 0) score += 2.0 // Disukai (+)
            if (evalA == 1 || evalB == 1) score -= 2.0 // Kurang disukai (-)
            if (evalA == 2 || evalB == 2) score += 1.0 // Mudah (M)
            if (evalA == 3 || evalB == 3) score -= 1.0 // Sukar (S)
            return score.coerceIn(1.0, 10.0)
        }

        val emosiScore = calculateFunctionScore(1, 6)
        val imajinasiScore = calculateFunctionScore(0, 7)
        val intelektualScore = calculateFunctionScore(2, 5)
        val aktivitasScore = calculateFunctionScore(3, 4)
        val orderRegularity = if (isSequential) 10.0 else 7.5

        val likedBox = evalMap.entries.firstOrNull { it.value == 0 }?.key?.plus(1) ?: 1
        val dislikedBox = evalMap.entries.firstOrNull { it.value == 1 }?.key?.plus(1) ?: 4
        val easiestBox = evalMap.entries.firstOrNull { it.value == 2 }?.key?.plus(1) ?: 2
        val hardestBox = evalMap.entries.firstOrNull { it.value == 3 }?.key?.plus(1) ?: 5

        val scores = listOf(
            AssessmentScore(
                label = "Fungsi Emosi (Kotak 2 & 7)",
                value = emosiScore,
                minimum = 1.0,
                maximum = 10.0,
                meaning = "Skor: ${emosiScore.toInt()}/10. Sensitivitas afektif, kehangatan hubungan antarpribadi, dan empati."
            ),
            AssessmentScore(
                label = "Fungsi Imajinasi (Kotak 1 & 8)",
                value = imajinasiScore,
                minimum = 1.0,
                maximum = 10.0,
                meaning = "Skor: ${imajinasiScore.toInt()}/10. Kekuatan ego, integrasi diri, kreativitas, dan rasa aman."
            ),
            AssessmentScore(
                label = "Fungsi Intelektual (Kotak 3 & 6)",
                value = intelektualScore,
                minimum = 1.0,
                maximum = 10.0,
                meaning = "Skor: ${intelektualScore.toInt()}/10. Ambisi pencapaian, keteraturan berpikir, dan kemampuan analisis-sintesis logis."
            ),
            AssessmentScore(
                label = "Fungsi Aktivitas (Kotak 4 & 5)",
                value = aktivitasScore,
                minimum = 1.0,
                maximum = 10.0,
                meaning = "Skor: ${aktivitasScore.toInt()}/10. Dinamisme tindakan, penyelesaian rintangan, dan regulasi ketahanan stres."
            ),
            AssessmentScore(
                label = "Keteraturan Urutan Kerja",
                value = orderRegularity,
                minimum = 1.0,
                maximum = 10.0,
                meaning = if (isSequential) "Urutan pengerjaan sistematis dan teratur (1 s.d. 8)." else "Urutan pengerjaan dinamis dan fleksibel (kombinatif)."
            ),
            AssessmentScore(
                label = "Kotak Diselesaikan",
                value = completedBoxes.toDouble(),
                minimum = 1.0,
                maximum = 8.0,
                meaning = "$completedBoxes dari 8 kotak stimulus telah digambar."
            )
        )

        val orderStr = sortedByOrder.joinToString(" → ") { "K${it.first + 1}" }
        val interpretation = "Berdasarkan evaluasi tes proyektif Wartegg (Marian Kinget & Ehrig Wartegg), " +
            "urutan pengerjaan kotak adalah $orderStr (${if (isSequential) "terstruktur & metodis" else "fleksibel & intuitif"}). " +
            "Gambar yang paling disukai (+) adalah Kotak $likedBox, sedangkan yang paling dihindari (-) adalah Kotak $dislikedBox. " +
            "Kotak termudah (M) adalah Kotak $easiestBox, dan yang paling menantang (S) adalah Kotak $hardestBox. " +
            "Profil 4 fungsi kepribadian menunjukkan skor Emosi: ${emosiScore.toInt()}/10, Imajinasi: ${imajinasiScore.toInt()}/10, " +
            "Intelektual: ${intelektualScore.toInt()}/10, dan Aktivitas: ${aktivitasScore.toInt()}/10. " +
            "Kombinasi ini mengindikasikan integrasi diri yang adaptif dalam menghadapi dinamika tugas dan relasi sosial."

        return scores to interpretation
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun enrichRmib(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val catCodes = listOf("OUT", "ME", "COMP", "SCI", "PRS", "AESTH", "LIT", "MUS", "S.S", "CLER", "PRAC", "MED")
        val catNames = listOf(
            "Outdoor (OUT)", "Mechanical (ME)", "Computational (COMP)", "Scientific (SCI)",
            "Personal Contact (PRS)", "Aesthetic (AESTH)", "Literary (LIT)", "Musical (MUS)",
            "Social Service (S.S)", "Clerical (CLER)", "Practical (PRAC)", "Medical (MED)"
        )
        val catMeanings = listOf(
            "Minat aktivitas lapangan, alam terbuka, pertanian, dan lingkungan.",
            "Minat pada permesinan, keteknikan, instalasi alat mekanis, dan rancang bangun.",
            "Minat pada perhitungan angka, akuntansi, perpajakan, dan analisis finansial.",
            "Minat pada riset sains, eksperimen laboratorium, biologi, dan kimia.",
            "Minat pada relasi interpersonal, persuasi, komunikasi, dan pemasaran.",
            "Minat pada seni rupa, arsitektur, desain grafis, dan estetika visual.",
            "Minat pada sastra, membaca, menulis artikel, dan dunia kepustakaan.",
            "Minat pada musik, alat instrumen, vokal, dan komposisi nada.",
            "Minat pada bimbingan kemanusiaan, pendidikan, dan pelayanan sosial.",
            "Minat pada ketatausahaan, pengarsipan rutin, dan administrasi kantor.",
            "Minat pada keterampilan tangan praktis, mebel, perkayuan, dan reparasi.",
            "Minat pada kedokteran, pengobatan medis, farmasi, dan perawatan kesehatan."
        )

        val catScores = IntArray(12) { 0 }
        for (k in 0 until 12) {
            var sum = 0
            for (g in 0 until 9) {
                val p = (k - g).mod(12)
                val rank = answers[g * 12 + p] ?: (p + 1)
                sum += rank
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
                    minimum = 9.0,
                    maximum = 108.0,
                    meaning = "Skor mentah: ${catScores[k]} (Peringkat $r dari 12). ${catMeanings[k]}"
                )
            )
        }
        scores.add(
            AssessmentScore(
                label = "Total Invarian RMIB",
                value = totalSum.toDouble(),
                minimum = 702.0,
                maximum = 702.0,
                meaning = "Total invarian skor seluruh matriks 9 kelompok x 12 profesi = $totalSum (Invarian baku 702)."
            )
        )

        val top1 = sortedIndices[0]
        val top2 = sortedIndices[1]
        val top3 = sortedIndices[2]
        val bot1 = sortedIndices[11]

        val interpretation = "Berdasarkan inventori minat pekerjaan Rothwell–Miller Interest Blank (RMIB) matriks tangga 9 kelompok pekerjaan (total invarian: $totalSum/702), " +
            "tiga kategori minat vokasi paling dominan Anda adalah: " +
            "1. ${catNames[top1]} (Skor: ${catScores[top1]}) — ${catMeanings[top1]} " +
            "2. ${catNames[top2]} (Skor: ${catScores[top2]}) — ${catMeanings[top2]} " +
            "3. ${catNames[top3]} (Skor: ${catScores[top3]}) — ${catMeanings[top3]} " +
            "Kategori yang paling tidak diminati adalah ${catNames[bot1]} (Skor: ${catScores[bot1]}). " +
            "Profil minat ini sangat relevan untuk dieksplorasi lebih mendalam bersama AI Counselor guna perencanaan karier dan studi lanjutan."

        return scores to interpretation
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun enrichVrt(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
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
            "Penalaran kritis menemukan keganjilan atau ketidaklogisan dalam pernyataan verbal.",
            "Kemampuan diskriminasi semantik dan menemukan elemen yang tidak selaras.",
            "Penalaran analogi verbal dan pembentukan relasi fungsional antar konsep.",
            "Daya analisis membedakan dua konsep yang tampak serupa secara esensial.",
            "Pemahaman makna kiasan, metafora, dan interpretasi bahasa non-literal.",
            "Logika deduktif dalam menganalisis hubungan silsilah kekerabatan.",
            "Kemampuan kategorisasi abstrak dan pengelompokan konsep superordinat."
        )

        val subtestScores = mutableListOf<Int>()
        for (s in 0 until 7) {
            val sScore = (s * 7 until (s + 1) * 7).sumOf { i ->
                val choice = answers[i] ?: 2
                when (choice) {
                    0 -> 2 // Tepat & Lengkap
                    1 -> 1 // Sebagian / Konkret
                    else -> 0 // Kurang Tepat
                }
            }
            subtestScores.add(sScore)
        }

        val totalScore = subtestScores.sum()
        val level = when {
            totalScore >= 85 -> "Sangat Tinggi (Superior Verbal Reasoning)"
            totalScore >= 70 -> "Tinggi (Good Analytical Verbal Reasoning)"
            totalScore >= 50 -> "Cukup / Rata-rata (Average Verbal Reasoning)"
            totalScore >= 30 -> "Rendah (Below Average Verbal Reasoning)"
            else -> "Sangat Rendah (Borderline Verbal Reasoning)"
        }

        val scores = mutableListOf<AssessmentScore>()
        for (s in 0 until 7) {
            scores.add(
                AssessmentScore(
                    label = subtestNames[s],
                    value = subtestScores[s].toDouble(),
                    minimum = 0.0,
                    maximum = 14.0,
                    meaning = "Skor: ${subtestScores[s]}/14. ${subtestMeanings[s]}"
                )
            )
        }
        scores.add(
            AssessmentScore(
                label = "Total Skor Penalaran Verbal",
                value = totalScore.toDouble(),
                minimum = 0.0,
                maximum = 98.0,
                meaning = "Skor total: $totalScore/98. Kategori: $level."
            )
        )

        val interpretation = "Berdasarkan Verbal Reasoning Test (VRT 49 butir; Basagni et al., 2017), " +
            "kemampuan penalaran verbal Anda berada pada tingkat $level dengan skor total $totalScore/98. " +
            "Rincian per subtes: Absurdities ${subtestScores[0]}/14, Intruders ${subtestScores[1]}/14, " +
            "Relationships ${subtestScores[2]}/14, Differences ${subtestScores[3]}/14, " +
            "Idioms ${subtestScores[4]}/14, Family ${subtestScores[5]}/14, dan Classifications ${subtestScores[6]}/14. " +
            "Hasil ini menunjukkan kapasitas pemahaman konseptual, fleksibilitas semantik, dan penalaran logis berbasis bahasa."

        return scores to interpretation
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun enrichMseai(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val domainNames = listOf(
            "Kesadaran Diri (Self-Awareness)",
            "Pengendalian Emosi (Emotional Regulation)",
            "Empati Sosial (Social Empathy)",
            "Relasi Interpersonal (Interpersonal Relations)"
        )
        val domainMeanings = listOf(
            "Kemampuan mengenali emosi, kelebihan, keterbatasan, dan motif pribadi secara objektif.",
            "Kemampuan mengelola stres, impulsivitas, dan ketenangan saat menghadapi tekanan.",
            "Kepekaan terhadap perasaan, kebutuhan, dan sudut pandang orang lain di lingkungan sosial.",
            "Keterampilan membangun komunikasi terbuka, kerja sama tim, dan resolusi konflik secara sehat."
        )

        val domainScores = mutableListOf<Int>()
        for (d in 0 until 4) {
            val dScore = (d * 6 until (d + 1) * 6).sumOf { i ->
                val choice = answers[i] ?: 2
                choice + 1 // choices 0..4 map to values 1..5
            }
            domainScores.add(dScore)
        }

        val totalScore = domainScores.sum()
        val level = when {
            totalScore >= 100 -> "Sangat Baik (Tingkat Adaptasi & Kematangan Emosional Unggul)"
            totalScore >= 80 -> "Baik (Adaptasi Emosional & Sosial Stabil)"
            totalScore >= 60 -> "Cukup (Adaptasi Moderat, Terdapat Area yang Perlu Penguatan)"
            else -> "Kurang (Memerlukan Dukungan Konseling & Penguatan Keterampilan Regulasi Emosi)"
        }

        val scores = mutableListOf<AssessmentScore>()
        for (d in 0 until 4) {
            scores.add(
                AssessmentScore(
                    label = domainNames[d],
                    value = domainScores[d].toDouble(),
                    minimum = 6.0,
                    maximum = 30.0,
                    meaning = "Skor: ${domainScores[d]}/30. ${domainMeanings[d]}"
                )
            )
        }
        scores.add(
            AssessmentScore(
                label = "Total Skor Adaptasi MSEAI",
                value = totalScore.toDouble(),
                minimum = 24.0,
                maximum = 120.0,
                meaning = "Skor total: $totalScore/120. Status: $level."
            )
        )

        val interpretation = "Berdasarkan Multidimensional Social & Emotional Adjustment Inventory (MSEAI 24 butir), " +
            "profil kematangan adaptasi sosial dan emosional Anda berada pada tingkat $level dengan skor total $totalScore/120. " +
            "Rincian dimensi: Kesadaran Diri ${domainScores[0]}/30, Pengendalian Emosi ${domainScores[1]}/30, " +
            "Empati Sosial ${domainScores[2]}/30, dan Relasi Interpersonal ${domainScores[3]}/30. " +
            "Skor ini mencerminkan stabilitas emosi, keterampilan berinteraksi, dan kapasitas penyesuaian sosial yang konstruktif."

        return scores to interpretation
    }



    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun enrichEpps(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val needCodes = listOf("ach", "def", "ord", "exh", "aut", "aff", "int", "suc", "dom", "aba", "nur", "chg", "end", "het", "agg")
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
            "Dorongan menyelesaikan tugas menantang dan mencapai keunggulan.",
            "Kesediaan mengikuti arahan, menghormati tradisi, dan menerima saran figur otoritas.",
            "Kecenderungan bekerja secara terorganisir, sistematis, dan terencana.",
            "Keinginan menjadi pusat perhatian dan menceritakan pencapaian pribadi.",
            "Kebutuhan bertindak mandiri, bebas mengambil keputusan tanpa keterikatan berlebih.",
            "Keinginan menjalin ikatan persahabatan erat, hangat, dan setia kawan.",
            "Kapasitas menyelami motif, perasaan, dan dinamika emosional diri maupun orang lain.",
            "Kebutuhan memperoleh penerimaan afektif, dorongan, dan dukungan orang lain saat sulit.",
            "Dorongan memimpin, mengarahkan kelompok, dan mengambil kendali situasi.",
            "Kecenderungan intropeksi, mengalah demi keharmonisan, dan mengakui kesalahan.",
            "Keinginan tulus menolong, merawat, dan berempati pada sesama yang kesusahan.",
            "Kebutuhan akan pembaharuan, mencoba hal baru, dan menghindari rutinitas monoton.",
            "Kegigihan menuntaskan pekerjaan hingga selesai tanpa mudah menyerah di tengah jalan.",
            "Ketertarikan berinteraksi sosial, bekerja sama, dan membangun relasi dengan lawan jenis.",
            "Keberanian mengemukakan kritik secara terbuka, asertif, atau menyerang pandangan yang berlawanan."
        )

        val rScores = IntArray(15)
        val cScores = IntArray(15)
        val rawScores = IntArray(15)

        for (r in 0 until 15) {
            for (c in 0 until 15) {
                if (r == c) continue // Skip diagonal garis merah
                val qIndex = (r / 5) * 75 + c * 5 + (r % 5)
                val ans = answers[qIndex] ?: 0
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

        // Konsistensi (CONS) - 15 pasangan item identik
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

        val consValidity = when {
            consScore >= 10 -> "Tinggi (Profil Sangat Valid & Konsisten)"
            consScore in 7..9 -> "Sedang (Konsistensi Cukup / Ragu-ragu)"
            else -> "Rendah (Pilihan Kurang Konsisten, Perlu Tinjauan)"
        }

        // Tabel Persentil Norma Dewasa/Mahasiswa Indonesia (Edwards EPPS)
        val percentilesTable = arrayOf(
            intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
            intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
            intArrayOf(1, 1, 3, 1, 1, 1, 1, 2, 1, 2, 1, 1, 1, 1, 1),
            intArrayOf(1, 1, 6, 1, 1, 1, 1, 5, 1, 3, 1, 1, 3, 1, 2),
            intArrayOf(1, 3, 9, 1, 1, 1, 1, 9, 1, 5, 2, 1, 6, 1, 3),
            intArrayOf(1, 6, 14, 1, 2, 2, 1, 13, 2, 9, 3, 2, 10, 2, 6),
            intArrayOf(1, 10, 19, 1, 3, 3, 4, 20, 3, 12, 6, 3, 13, 3, 9),
            intArrayOf(2, 16, 27, 3, 6, 4, 5, 27, 4, 18, 10, 5, 18, 5, 13),
            intArrayOf(4, 23, 36, 6, 11, 7, 9, 34, 5, 24, 13, 7, 24, 6, 17),
            intArrayOf(7, 34, 46, 9, 15, 11, 13, 42, 8, 31, 18, 10, 29, 9, 24),
            intArrayOf(10, 43, 54, 15, 22, 16, 17, 50, 9, 38, 24, 15, 37, 12, 32),
            intArrayOf(16, 52, 63, 21, 38, 21, 19, 58, 13, 45, 31, 20, 44, 14, 40),
            intArrayOf(22, 63, 71, 28, 34, 28, 25, 65, 17, 53, 7, 28, 51, 19, 47),
            intArrayOf(30, 73, 78, 37, 43, 36, 32, 72, 21, 61, 46, 33, 57, 3, 57),
            intArrayOf(40, 81, 84, 49, 52, 45, 39, 78, 27, 67, 55, 41, 64, 27, 65),
            intArrayOf(50, 88, 88, 62, 61, 54, 45, 83, 32, 75, 63, 50, 69, 32, 72),
            intArrayOf(58, 93, 92, 72, 68, 64, 51, 87, 38, 79, 70, 57, 75, 39, 77),
            intArrayOf(66, 96, 95, 81, 76, 72, 57, 90, 45, 84, 75, 64, 79, 45, 84),
            intArrayOf(74, 98, 96, 89, 82, 79, 64, 93, 54, 88, 82, 71, 85, 53, 88),
            intArrayOf(83, 99, 97, 93, 86, 84, 72, 95, 63, 92, 86, 78, 89, 59, 92),
            intArrayOf(86, 99, 99, 97, 91, 89, 78, 97, 73, 94, 90, 84, 92, 67, 95),
            intArrayOf(91, 99, 99, 98, 94, 93, 84, 98, 79, 97, 94, 89, 94, 73, 97),
            intArrayOf(95, 99, 99, 99, 96, 96, 89, 98, 85, 99, 96, 93, 96, 81, 98),
            intArrayOf(98, 99, 99, 99, 98, 98, 93, 98, 91, 99, 98, 97, 98, 89, 99),
            intArrayOf(99, 99, 99, 99, 99, 99, 95, 98, 94, 99, 99, 98, 99, 90, 99),
            intArrayOf(99, 99, 99, 99, 99, 99, 97, 98, 98, 99, 99, 99, 99, 93, 99),
            intArrayOf(99, 99, 99, 99, 99, 99, 99, 98, 99, 99, 99, 99, 99, 96, 99),
            intArrayOf(99, 99, 99, 99, 99, 99, 99, 98, 99, 99, 99, 99, 99, 99, 99),
            intArrayOf(99, 99, 99, 99, 99, 99, 99, 98, 99, 99, 99, 99, 99, 99, 99),
        )

        val scores = mutableListOf<AssessmentScore>()
        val topNeeds = mutableListOf<String>()
        val lowNeeds = mutableListOf<String>()

        for (k in 0 until 15) {
            val rs = rawScores[k].coerceIn(0, 28)
            val pct = percentilesTable[rs][k]
            val category = when {
                pct >= 85 -> "Sangat Tinggi"
                pct >= 60 -> "Tinggi"
                pct >= 40 -> "Rata-rata"
                pct >= 16 -> "Rendah"
                else -> "Sangat Rendah"
            }
            if (pct >= 85 || rs >= 18) topNeeds.add(needCodes[k].uppercase(Locale.ROOT))
            if (pct <= 15 || rs <= 8) lowNeeds.add(needCodes[k].uppercase(Locale.ROOT))

            scores.add(
                AssessmentScore(
                    label = needLabels[k],
                    value = rs.toDouble(),
                    minimum = 0.0,
                    maximum = 28.0,
                    meaning = "Skor Mentah: $rs/28 (r=${rScores[k]}, c=${cScores[k]}). Persentil: $pct% ($category). ${needDescriptions[k]}"
                )
            )
        }

        scores.add(
            AssessmentScore(
                label = "Konsistensi Respons (CONS)",
                value = consScore.toDouble(),
                minimum = 0.0,
                maximum = 15.0,
                meaning = "Skor: $consScore/15. $consValidity."
            )
        )

        val totalRaw = rawScores.sum()
        scores.add(
            AssessmentScore(
                label = "Total Raw Score EPPS (Invarian 210)",
                value = totalRaw.toDouble(),
                minimum = 210.0,
                maximum = 210.0,
                meaning = "Total seluruh 15 need = $totalRaw/210 (Invarian matematis terpenuhi: ${totalRaw == 210})."
            )
        )

        val topSummary = if (topNeeds.isNotEmpty()) topNeeds.joinToString(", ") else "Seimbang (Profil Rata-rata)"
        val lowSummary = if (lowNeeds.isNotEmpty()) lowNeeds.joinToString(", ") else "Tidak ada yang ekstrem rendah"

        val workEthic = "Orientasi Kerja: " + when {
            rawScores[0] + rawScores[12] + rawScores[2] >= 48 -> "Tekun, berorientasi target tinggi, dan sangat terorganisir (ACH + END + ORD tinggi)."
            rawScores[4] >= 18 -> "Sangat mandiri dan menyukai otonomi tinggi dalam menyelesaikan pekerjaan (AUT menonjol)."
            else -> "Fleksibel dan mampu menyesuaikan ritme kerja dengan kebutuhan kelompok."
        }

        val socialStyle = "Dinamika Interpersonal: " + when {
            rawScores[8] >= 18 && rawScores[4] >= 16 -> "Memiliki kepemimpinan kuat dan dominan dalam mengarahkan kelompok (DOM & AUT tinggi)."
            rawScores[5] + rawScores[10] >= 34 -> "Hangat, loyal, penuh empati, dan suka menolong rekan kerja (AFF & NUR dominan)."
            rawScores[9] >= 18 -> "Cenderung mengalah, menghargai harmoni sosial, dan menghindari gesekan (ABA tinggi)."
            else -> "Adaptif dan seimbang dalam interaksi sosial kerja."
        }

        val interpretation = "Berdasarkan inventori kepribadian Edwards Personal Preference Schedule (EPPS 225 butir), " +
            "intensitas 15 kebutuhan psikologis Murray terpetakan dengan Skor Konsistensi (CONS) sebesar $consScore/15 ($consValidity). " +
            "Kebutuhan yang paling dominan/kuat adalah: $topSummary, sedangkan kebutuhan dengan intensitas relatif rendah adalah: $lowSummary. " +
            "$workEthic $socialStyle " +
            "Total skor mentah = $totalRaw/210 sesuai invarian baku. Hasil ini bermanfaat untuk pemetaan potensi kerja, penempatan peran, dan refleksi pengembangan diri."

        return scores to interpretation
    }


    private data class RorschachCardResponse(
        val loc: Int,
        val det: Int,
        val fq: Int,
        val pop: Int,
        val contentCode: Int
    )

    private fun defaultRorschachDet(cardIndex: Int): Int = when (cardIndex) {
        0 -> 0 // Card I: F
        1 -> 4 // Card II: FC
        2 -> 1 // Card III: M
        3 -> 0 // Card IV: F
        4 -> 0 // Card V: F
        5 -> 8 // Card VI: T (Texture)
        6 -> 1 // Card VII: M
        7 -> 4 // Card VIII: FC
        8 -> 5 // Card IX: CF
        9 -> 4 // Card X: FC
        else -> 0
    }

    private fun enrichRorschach(answers: Map<Int, Int>): Pair<List<AssessmentScore>, String> {
        val totalR = answers.size.coerceAtLeast(1)
        val responses = (0 until 10).map { cardIdx ->
            val raw = answers[cardIdx] ?: 0
            if (raw >= 10) {
                RorschachCardResponse(
                    loc = (raw / 100000) % 10,
                    det = (raw / 1000) % 100,
                    fq = (raw / 100) % 10,
                    pop = (raw / 10) % 10,
                    contentCode = raw % 10
                )
            } else {
                RorschachCardResponse(
                    loc = raw % 4,
                    det = defaultRorschachDet(cardIdx),
                    fq = 1, // ordinary FQo
                    pop = if (raw % 4 == 0) 1 else 0,
                    contentCode = 0
                )
            }
        }

        // Location frequencies
        val wCount = responses.count { it.loc == 0 }
        val dCount = responses.count { it.loc == 1 }
        val ddCount = responses.count { it.loc == 2 }
        val sCount = responses.count { it.loc == 3 }

        // Determinants
        val fCount = responses.count { it.det == 0 }
        val mCount = responses.count { it.det == 1 }
        val fmCount = responses.count { it.det == 2 }
        val inanimMCount = responses.count { it.det == 3 }
        val fcCount = responses.count { it.det == 4 }
        val cfCount = responses.count { it.det == 5 }
        val cCount = responses.count { it.det == 6 }
        val cPrimeCount = responses.count { it.det == 7 }
        val tCount = responses.count { it.det == 8 }
        val vCount = responses.count { it.det == 9 }
        val yCount = responses.count { it.det == 10 }

        // Weighted Sum of Color (WSumC)
        val wSumC = (0.5 * fcCount) + (1.0 * cfCount) + (1.5 * cCount)
        val ea = mCount.toDouble() + wSumC
        val sumShading = (cPrimeCount + tCount + vCount + yCount).toDouble()
        val es = (fmCount + inanimMCount).toDouble() + sumShading
        val rawDiff = ea - es

        val dScore = when {
            rawDiff >= 4.0 -> 3
            rawDiff >= 2.5 -> 2
            rawDiff >= 1.0 -> 1
            rawDiff >= -1.0 -> 0
            rawDiff >= -2.5 -> -1
            rawDiff >= -4.0 -> -2
            else -> -3
        }

        val dMeaning = when {
            dScore > 0 -> "Kapasitas koping surplus / toleransi stres sangat baik (D = +$dScore)"
            dScore == 0 -> "Keseimbangan koping adekuat dan stabil menghadapi beban sehari-hari (D = 0)"
            dScore == -1 -> "Beban stres internal mulai melampaui sumber daya koping (D = -1)"
            else -> "Kelebihan beban stres signifikan / risiko disorganisasi afektif (D = $dScore)"
        }

        val ebDiff = mCount.toDouble() - wSumC
        val ebStyle = when {
            ebDiff > 1.5 -> "Introversif (Orientasi pemikiran internal & reflektif)"
            ebDiff < -1.5 -> "Ekstratensif (Orientasi stimulasi eksternal & respons afektif terbuka)"
            else -> "Ambiten (Fleksibel, gaya koping situasional)"
        }

        val nonF = (totalR - fCount).coerceAtLeast(1)
        val lambda = fCount.toDouble() / nonF.toDouble()
        val lambdaMeaning = when {
            lambda > 1.2 -> "Penyempitan afektif / defensif rasional berlebih"
            lambda in 0.3..1.2 -> "Efisiensi kognitif & keterbukaan emosional adaptif"
            else -> "Keterlibatan emosional intens / filter intelektual longgar"
        }

        val fqPlus = responses.count { it.fq == 0 }
        val fqO = responses.count { it.fq == 1 }
        val fqU = responses.count { it.fq == 2 }
        val fqMinus = responses.count { it.fq == 3 }

        val xPlusPct = ((fqPlus + fqO).toDouble() / totalR) * 100.0
        val xMinusPct = (fqMinus.toDouble() / totalR) * 100.0

        val popularCount = responses.count { it.pop == 1 }
        val pMeaning = when {
            popularCount in 4..7 -> "Konvensionalitas perseptual normatif (Adaptif)"
            popularCount > 7 -> "Konformitas sosial kaku (Takut menyimpang)"
            else -> "Persepsi idiosinkratik / cara pandang sangat unik"
        }

        // Special Clinical Constellations
        var sCon = 0
        if (vCount > 0) sCon++
        if (sumShading > 2.0) sCon++
        if (es > ea) sCon++
        if ((cfCount + cCount) > fcCount) sCon++
        if (xPlusPct < 70.0) sCon++
        if (sCount > 2) sCon++
        if (popularCount < 3 || popularCount > 8) sCon++
        if (responses.count { it.contentCode == 0 } < 2) sCon++
        if (totalR < 17) sCon++
        if (wSumC < 1.0) sCon++
        if (dScore < 0) sCon++
        if (fqMinus > 2) sCon++

        var depi = 0
        if (sumShading > (fmCount + inanimMCount)) depi++
        if (cPrimeCount > 0 || sCount > 2) depi++
        if (vCount > 0) depi++
        if (dScore < 0) depi++
        if (wSumC < 2.5) depi++
        if (responses.count { it.contentCode == 3 } > 0) depi++
        if (tCount > 1) depi++

        var cdi = 0
        if (ea < 6.0 || dScore < 0) cdi++
        if (wSumC < 2.5) cdi++
        if (responses.count { it.contentCode == 0 } < 2) cdi++
        if (tCount > 1) cdi++
        if (lambda > 1.0) cdi++

        var pti = 0
        if (xMinusPct > 20.0) pti++
        if (xPlusPct < 65.0) pti++
        if (fqMinus >= 3) pti++
        if (ddCount > 3) pti++
        if (mCount == 0 && wSumC == 0.0) pti++

        val sConMeaning = if (sCon >= 8) "Risiko Keselamatan / Stres Kritis (S-CON Positif)" else "Dalam Batas Aman (S-CON Negatif)"
        val depiMeaning = if (depi >= 5) "Kecenderungan Afek Disforik / Depresi (DEPI Positif)" else "Tidak Mengindikasikan Disforia Klinis (DEPI Negatif)"
        val cdiMeaning = if (cdi >= 4) "Defisit Sumber Daya Koping Keseharian (CDI Positif)" else "Kapasitas Koping Interpersonal Cukup (CDI Negatif)"
        val ptiMeaning = if (pti >= 3) "Indikasi Gangguan Uji Realitas / Berpikir (PTI Positif)" else "Proses Berpikir Koheren (PTI Negatif)"

        val scores = listOf(
            AssessmentScore(
                label = "Erlebnistypus (EB)",
                value = mCount.toDouble(),
                minimum = 0.0,
                maximum = 10.0,
                meaning = "Rasio EB (M:WSumC) = $mCount : ${String.format(Locale.US, "%.1f", wSumC)} ($ebStyle)"
            ),
            AssessmentScore(
                label = "Experience Actual (EA)",
                value = ea,
                minimum = 0.0,
                maximum = 20.0,
                meaning = "Kapasitas sumber daya adaptasi yang terorganisir = ${String.format(Locale.US, "%.1f", ea)}"
            ),
            AssessmentScore(
                label = "Experienced Stimulation (es)",
                value = es,
                minimum = 0.0,
                maximum = 20.0,
                meaning = "Beban tuntutan stres & dorongan internal yang dialami = ${String.format(Locale.US, "%.1f", es)}"
            ),
            AssessmentScore(
                label = "D-Score (Kapasitas Stres)",
                value = dScore.toDouble(),
                minimum = -5.0,
                maximum = 5.0,
                meaning = dMeaning
            ),
            AssessmentScore(
                label = "Lambda (λ)",
                value = lambda,
                minimum = 0.0,
                maximum = 3.0,
                meaning = "λ = ${String.format(Locale.US, "%.2f", lambda)}: $lambdaMeaning"
            ),
            AssessmentScore(
                label = "Reality Testing (X+%)",
                value = xPlusPct,
                minimum = 0.0,
                maximum = 100.0,
                meaning = "Akurasi persepsi konvensional = ${String.format(Locale.US, "%.1f%%", xPlusPct)}"
            ),
            AssessmentScore(
                label = "Distorsi Perseptual (X-%)",
                value = xMinusPct,
                minimum = 0.0,
                maximum = 100.0,
                meaning = "Tingkat distorsi bentuk = ${String.format(Locale.US, "%.1f%%", xMinusPct)}"
            ),
            AssessmentScore(
                label = "Popular Responses (P)",
                value = popularCount.toDouble(),
                minimum = 0.0,
                maximum = 10.0,
                meaning = "$popularCount/10 respons populer ($pMeaning)"
            ),
            AssessmentScore(
                label = "Konstelasi Keselamatan (S-CON)",
                value = sCon.toDouble(),
                minimum = 0.0,
                maximum = 12.0,
                meaning = "$sCon/12: $sConMeaning"
            ),
            AssessmentScore(
                label = "Depression Index (DEPI)",
                value = depi.toDouble(),
                minimum = 0.0,
                maximum = 7.0,
                meaning = "$depi/7: $depiMeaning"
            ),
            AssessmentScore(
                label = "Coping Deficit Index (CDI)",
                value = cdi.toDouble(),
                minimum = 0.0,
                maximum = 5.0,
                meaning = "$cdi/5: $cdiMeaning"
            ),
            AssessmentScore(
                label = "Perception-Thinking (PTI)",
                value = pti.toDouble(),
                minimum = 0.0,
                maximum = 5.0,
                meaning = "$pti/5: $ptiMeaning"
            )
        )

        val locSummary = "Lokasi persepsi: $wCount W (Whole), $dCount D (Common Detail), $ddCount Dd (Unusual Detail), $sCount S (Space)."
        val affectControl = when {
            fcCount > (cfCount + cCount) -> "Emosi terkendali dengan baik dan terintegrasi secara matang dengan pertimbangan rasional (FC dominan)."
            cCount > 0 -> "Terdapat potensi pelepasan emosi mentah tanpa kendali intelektual penuh (C muncul)."
            cfCount >= fcCount -> "Ekspresi emosional cenderung spontan, reaktif, dan sangat peka terhadap stimulasi lingkungan (CF dominan)."
            else -> "Respons emosional cenderung datar atau sangat tertahan."
        }

        val interpretation = "Berdasarkan evaluasi proyektif Tes Rorschach menggunakan standar Exner Comprehensive System (CS) dan R-PAS, " +
            "terpetakan profil psikogram kepribadian dan dinamika koping: " +
            "Gaya koping dasar (Erlebnistypus) bertipe $ebStyle dengan rasio EB $mCount : ${String.format(Locale.US, "%.1f", wSumC)}. " +
            "Toleransi terhadap stres menunjukkan $dMeaning (EA = ${String.format(Locale.US, "%.1f", ea)}, es = ${String.format(Locale.US, "%.1f", es)}). " +
            "Efisiensi kognitif (Lambda) berada pada nilai ${String.format(Locale.US, "%.2f", lambda)} ($lambdaMeaning). " +
            "Uji realitas perseptual (X+%) mencapai ${String.format(Locale.US, "%.1f%%", xPlusPct)} dengan distorsi (X-%) sebesar ${String.format(Locale.US, "%.1f%%", xMinusPct)}, serta respons populer P = $popularCount ($pMeaning). " +
            "$locSummary " +
            "Regulasi emosional: $affectControl " +
            "Hasil skrining klinis: S-CON = $sCon/12 ($sConMeaning), DEPI = $depi/7 ($depiMeaning), CDI = $cdi/5 ($cdiMeaning), PTI = $pti/5 ($ptiMeaning). " +
            "Hasil ini dirancang sebagai hipotesis klinis untuk eksplorasi lebih mendalam bersama konselor, bukan vonis diagnostik mutlak."

        return scores to interpretation
    }

}
