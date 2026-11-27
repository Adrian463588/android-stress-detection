package com.stressdetector.mobile.domain.assessment

import kotlin.math.abs
import kotlin.math.sqrt

data class AssessmentProtocol(
    val instrumentId: String,
    val edition: String,
    val section: String,
    val method: String,
    val interpretation: String,
    val limitation: String,
    val entry: AssessmentScoreEntry? = null
)

enum class ScoreOperation {
    TOTAL,
    AQ,
    MSAI,
    PROFILE,
    HIGH_FIRST,
    LOW_FIRST,
    FRT,
    VAK,
    DISC,
    KRAEPELIN,
    EPPS,
    PAULI,
    BDI,
    MBTI,
    SRQ29,
    MMPI,
    WARTEGG
}

data class AssessmentScoreField(val label: String, val minimum: Int, val maximum: Int)

@Suppress("MagicNumber", "CyclomaticComplexMethod")
data class AssessmentScoreEntry(
    val version: String,
    val instructions: String,
    val operation: ScoreOperation,
    val fields: List<AssessmentScoreField>,
    val requiredTotal: Int? = null,
    val groupSize: Int? = null
) {
    internal fun accepts(values: List<Int>): Boolean {
        if (values.size != fields.size || values.zip(fields).any { (value, field) -> value !in field.minimum..field.maximum }) {
            return false
        }
        return when {
            operation == ScoreOperation.KRAEPELIN -> values.sum() <= fields.dropLast(2).sumOf { it.maximum }
            operation == ScoreOperation.EPPS -> values.dropLast(1).sum() == requiredTotal
            operation == ScoreOperation.PAULI -> {
                val reviewed = values[values.lastIndex - 1]
                val total = values.dropLast(2).sum()
                values.last() <= reviewed && reviewed <= total && total <= fields.first().maximum
            }
            requiredTotal == null -> true
            else -> values.chunked(groupSize ?: values.size).all { it.sum() == requiredTotal }
        }
    }

    internal fun score(values: List<Int>): List<AssessmentScore> {
        require(accepts(values))
        val scores = values.zip(fields).map { (value, field) ->
            AssessmentScore(field.label, value.toDouble(), field.minimum.toDouble(), field.maximum.toDouble(), "Rekap lembar.")
        }
        return when (operation) {
            ScoreOperation.PROFILE, ScoreOperation.EPPS -> scores
            ScoreOperation.HIGH_FIRST -> scores.sortedByDescending { it.value }
            ScoreOperation.LOW_FIRST -> scores.sortedBy { it.value }
            ScoreOperation.TOTAL, ScoreOperation.AQ -> listOf(
                AssessmentScore(
                    "Total mentah",
                    values.sum().toDouble() * totalWeight,
                    fields.sumOf { it.minimum }.toDouble() * totalWeight,
                    fields.sumOf { it.maximum }.toDouble() * totalWeight,
                    if (operation == ScoreOperation.AQ) "Jumlah CORE dikali dua." else "Jumlah skor."
                )
            ) + scores
            ScoreOperation.MSAI -> scoreMsai(scores)
            ScoreOperation.KRAEPELIN -> scoreKraepelin(values)
            ScoreOperation.PAULI -> scorePauli(values)
            ScoreOperation.FRT -> listOf(
                AssessmentScore(
                    "Skor terkoreksi",
                    values[0] - values[1] * FRT_WRONG_PENALTY,
                    -fields[1].maximum * FRT_WRONG_PENALTY,
                    fields[0].maximum.toDouble(),
                    "Benar dikurangi seperempat jumlah salah."
                )
            ) + scores
            ScoreOperation.VAK -> scores.map {
                AssessmentScore(
                    "${it.label} (%)",
                    it.value * PERCENT / values.sum(),
                    0.0,
                    PERCENT,
                    "Proporsi pilihan pada lembar ini."
                )
            }.sortedByDescending { it.value }
            ScoreOperation.DISC -> {
                val (most, least) = scores.chunked(requireNotNull(groupSize))
                most.dropLast(1).zip(least).map { (a, b) ->
                    AssessmentScore(a.label.substringBefore(" "), a.value - b.value, -b.maximum, a.maximum, "Selisih Most − Least.")
                } + scores
            }
            ScoreOperation.BDI -> scoreBdi(values)
            ScoreOperation.MBTI -> scoreMbti(values)
            ScoreOperation.SRQ29 -> scoreSrq29(values, scores)
            ScoreOperation.MMPI -> scoreMmpi(scores)
            ScoreOperation.WARTEGG -> scoreWartegg(scores)
        }
    }

    private fun scoreBdi(values: List<Int>): List<AssessmentScore> {
        val total = values.sum().toDouble()
        val severity = when {
            total <= BDI_CUTOFF_MINIMAL -> "Minimal (0–13)"
            total <= BDI_CUTOFF_MILD -> "Ringan (14–19)"
            total <= BDI_CUTOFF_MODERATE -> "Sedang (20–28)"
            else -> "Berat (29–63)"
        }
        val item9 = if (values.size >= 9) values[8] else 0
        val item9Note = if (item9 >= 1) " PERINGATAN: Butir 9 bernilai $item9, evaluasi risiko segera." else ""
        val kognitif = values.take(14).sum().toDouble()
        val somatik = values.drop(14).sum().toDouble()
        return listOf(
            AssessmentScore(
                label = "Total skor BDI-II",
                value = total,
                minimum = 0.0,
                maximum = 63.0,
                meaning = "Tingkat keparahan gejala: $severity.$item9Note"
            ),
            AssessmentScore("Butir 9 Ide Bunuh Diri", item9.toDouble(), 0.0, 3.0, "Item kritis keselamatan."),
            AssessmentScore("Subskala Kognitif-Afektif", kognitif, 0.0, 42.0, "Jumlah butir 1–14."),
            AssessmentScore("Subskala Somatik", somatik, 0.0, 21.0, "Jumlah butir 15–21.")
        )
    }

    private fun scoreMbti(values: List<Int>): List<AssessmentScore> {
        val e = values[0]
        val i = values[1]
        val s = values[2]
        val n = values[3]
        val t = values[4]
        val f = values[5]
        val j = values[6]
        val p = values[7]
        val typeE = if (e >= i) "E" else "I"
        val diffEI = abs(e - i)
        val typeS = if (s >= n) "S" else "N"
        val diffSN = abs(s - n)
        val typeT = if (t >= f) "T" else "F"
        val diffTF = abs(t - f)
        val typeJ = if (j >= p) "J" else "P"
        val diffJP = abs(j - p)
        val fullType = "$typeE$typeS$typeT$typeJ"
        val maxVal = fields[0].maximum.toDouble()
        return listOf(
            AssessmentScore(
                label = "Selisih Dominan E/I ($fullType)",
                value = diffEI.toDouble(),
                minimum = 0.0,
                maximum = maxVal,
                meaning = "Kecenderungan preferensi: $fullType (E/I=$diffEI, S/N=$diffSN, T/F=$diffTF, J/P=$diffJP)."
            ),
            AssessmentScore("Dimensi E/I ($typeE)", diffEI.toDouble(), 0.0, maxVal, "Selisih Extraversion vs Introversion."),
            AssessmentScore("Dimensi S/N ($typeS)", diffSN.toDouble(), 0.0, maxVal, "Selisih Sensing vs Intuition."),
            AssessmentScore("Dimensi T/F ($typeT)", diffTF.toDouble(), 0.0, maxVal, "Selisih Thinking vs Feeling."),
            AssessmentScore("Dimensi J/P ($typeJ)", diffJP.toDouble(), 0.0, maxVal, "Selisih Judging vs Perceiving.")
        )
    }

    private fun scoreSrq29(values: List<Int>, scores: List<AssessmentScore>): List<AssessmentScore> {
        val totalYes = values.sum().toDouble()
        val gme = values[0]
        val substance = values[1]
        val psychotic = values[2]
        val ptsd = values[3]
        val gmeStatus = if (gme >= SRQ_GME_CUTOFF) "Indikasi Gangguan Mental Emosional (ambang >=6)" else "Dalam batas normal"
        val subStatus = if (substance >= 1) "Indikasi Penggunaan Zat/Alkohol" else "Negatif"
        val psyStatus = if (psychotic >= 1) "Indikasi Gejala Psikotik (Perlu Rujukan)" else "Negatif"
        val ptsdStatus = if (ptsd >= 1) "Indikasi Gejala PTSD" else "Negatif"
        return listOf(
            AssessmentScore(
                label = "Total Skrining SRQ-29",
                value = totalYes,
                minimum = 0.0,
                maximum = fields.sumOf { it.maximum }.toDouble(),
                meaning = "Hasil: GME=$gmeStatus; Zat=$subStatus; Psikotik=$psyStatus; PTSD=$ptsdStatus."
            )
        ) + scores
    }

    private fun scoreMmpi(scores: List<AssessmentScore>): List<AssessmentScore> {
        val validityWarning = when {
            scores.find { it.label.startsWith("F") }?.value?.let { it >= MMPI_F_INVALID_THRESHOLD } == true ->
                "Peringatan: Skala F >= 80 (kemungkinan over-reporting pada MMPI-2 klasik)."
            scores.find { it.label.startsWith("L") }?.value?.let { it >= MMPI_L_DEFENSIVE_THRESHOLD } == true ->
                "Peringatan: Skala L >= 70 (kemungkinan defensif/under-reporting pada MMPI-2 klasik)."
            else -> "Skala validitas MMPI-2 klasik dalam rentang skrining (wajib verifikasi psikolog)."
        }
        val elevatedScales = scores.drop(3).filter { it.value >= MMPI_ELEVATION_THRESHOLD }.map { it.label }
        val elevationSummary = if (elevatedScales.isNotEmpty()) {
            "Skala elevasi klinis (T >= 65): ${elevatedScales.joinToString(", ")}."
        } else {
            "Tidak ada skala klinis dengan elevasi bermakna (semua T < 65)."
        }
        return listOf(
            AssessmentScore(
                label = "Ringkasan Profil MMPI-2 Klasik",
                value = scores.drop(3).map { it.value }.average(),
                minimum = 30.0,
                maximum = 120.0,
                meaning = "$validityWarning $elevationSummary"
            )
        ) + scores
    }

    private fun scoreWartegg(scores: List<AssessmentScore>): List<AssessmentScore> = scores.take(8).mapIndexed { idx, box ->
        box.copy(
            label = "Observasi Kotak ${idx + 1}",
            meaning = "Rekap lembar kotak ${idx + 1}; skor kepribadian memerlukan coding manual pemeriksa."
        )
    }

    private fun scorePauli(values: List<Int>): List<AssessmentScore> {
        val intervals = values.dropLast(2)
        val total = intervals.sum().toDouble()
        val average = intervals.average()
        val peak = intervals.max()
        val peakMinute = (intervals.indexOf(peak) + 1) * PAULI_INTERVAL_MINUTES
        val deviation = sqrt(intervals.map { (it - average) * (it - average) }.average())
        val reviewed = values[values.lastIndex - 1]
        val errorPercent = values.last() * PERCENT / reviewed
        val maximum = fields.first().maximum.toDouble()
        return listOf(
            AssessmentScore("Total dikerjakan", total, 0.0, maximum, "Jumlah dari semua interval."),
            AssessmentScore("Rerata per interval", average, 0.0, maximum / intervals.size, "Rerata capaian tiap tiga menit."),
            AssessmentScore("Hasil awal", intervals.first().toDouble(), 0.0, maximum, "Capaian interval pertama."),
            AssessmentScore("Tinggi puncak", peak.toDouble(), 0.0, maximum, "Capaian terbesar dalam satu interval."),
            AssessmentScore(
                "Puncak pertama (menit)",
                peakMinute,
                PAULI_INTERVAL_MINUTES,
                PAULI_DURATION,
                "Akhir interval puncak pertama."
            ),
            AssessmentScore("Simpangan baku", deviation, 0.0, maximum / 2, "Simpangan baku antarinterval."),
            AssessmentScore("Salah (%)", errorPercent, 0.0, PERCENT, "Persentase salah dari jumlah yang diperiksa.")
        )
    }

    private fun scoreKraepelin(values: List<Int>): List<AssessmentScore> {
        val correct = values.dropLast(2)
        val maximumPerColumn = fields.first().maximum.toDouble()
        val mean = correct.average()
        val middle = correct.indices.average()
        val centered = correct.indices.map { it - middle }
        val deviation = correct.map { abs(it - mean) }.average()
        val centeredSquares = centered.sumOf { it * it }
        val slope = centered.zip(correct).sumOf { (x, y) -> x * y } / centeredSquares
        val maximumHanker = centered.filter { it > 0 }.sum() * maximumPerColumn * correct.size / centeredSquares
        return listOf(
            AssessmentScore("Panker", mean, 0.0, maximumPerColumn, "Rerata benar per lajur."),
            AssessmentScore(
                "Tianker",
                values.takeLast(2).sum().toDouble(),
                0.0,
                maximumPerColumn * correct.size,
                "Salah ditambah lompatan."
            ),
            AssessmentScore("Janker", deviation, 0.0, maximumPerColumn / 2, "Rerata simpangan absolut."),
            AssessmentScore("Hanker", slope * correct.size, -maximumHanker, maximumHanker, "Selisih garis tren pada lajur 50 dan 0.")
        )
    }

    private fun scoreMsai(scores: List<AssessmentScore>): List<AssessmentScore> {
        val competencies = scores.map {
            it.copy(
                value = it.value / MSAI_ITEMS_PER_COMPETENCY,
                minimum = MSAI_MINIMUM_RATING,
                maximum = MSAI_MAXIMUM_RATING,
                meaning = "Rerata kompetensi."
            )
        }
        val quadrants = competencies.chunked(MSAI_COMPETENCIES_PER_QUADRANT).zip(msaiQuadrants) { group, label ->
            AssessmentScore(
                label,
                group.map { it.value }.average(),
                MSAI_MINIMUM_RATING,
                MSAI_MAXIMUM_RATING,
                "Rerata kuadran."
            )
        }
        return quadrants + competencies
    }

    private val totalWeight: Double
        get() = if (operation == ScoreOperation.AQ) 2.0 else 1.0

    private companion object {
        const val FRT_WRONG_PENALTY = 0.25
        const val PERCENT = 100.0
        const val PAULI_INTERVAL_MINUTES = 3.0
        const val PAULI_DURATION = 60.0
        const val MSAI_ITEMS_PER_COMPETENCY = 5.0
        const val MSAI_COMPETENCIES_PER_QUADRANT = 3
        const val MSAI_MINIMUM_RATING = 1.0
        const val MSAI_MAXIMUM_RATING = 5.0
        val msaiQuadrants = listOf("Clan", "Adhocracy", "Market", "Hierarchy")
        const val BDI_CUTOFF_MINIMAL = 13.0
        const val BDI_CUTOFF_MILD = 19.0
        const val BDI_CUTOFF_MODERATE = 28.0
        const val SRQ_GME_CUTOFF = 6
        const val MMPI_F_INVALID_THRESHOLD = 80.0
        const val MMPI_L_DEFENSIVE_THRESHOLD = 70.0
        const val MMPI_ELEVATION_THRESHOLD = 65.0
    }
}
