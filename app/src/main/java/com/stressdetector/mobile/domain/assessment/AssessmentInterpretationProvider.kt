package com.stressdetector.mobile.domain.assessment

data class BaselineInterpretationReport(
    val scoreDescription: String,
    val constructMeaning: String,
    val profileMapping: String,
    val resultInterpretation: String,
    val relativeTendencies: String,
    val limitations: String,
    val references: String,
    val professionalNotes: String
)

object AssessmentInterpretationProvider {

    fun build(result: AssessmentResult): BaselineInterpretationReport {
        val canonicalId = PsychometricInstrumentRegistry.normalizeInstrumentId(result.instrumentId)
        val spec = PsychometricInstrumentRegistry.specFor(canonicalId)
        val status = result.resolvedScoringStatus()
        val validity = result.resolvedValidityStatus()

        val scoreDesc = buildScoreDescription(result, status)
        val construct = constructDescriptionFor(canonicalId, result.title)
        val mapping = buildProfileMapping(canonicalId, result)
        val mainInterpretation = when {
            status == InstrumentImplementationStatus.DEMONSTRATION ->
                "[Mode Demonstrasi Sintetis] ${result.interpretation}"
            status == InstrumentImplementationStatus.EXPLORATORY ||
                status == InstrumentImplementationStatus.UNRESOLVED_IDENTITY ->
                "[Mode Eksploratif Purwarupa] ${result.interpretation}"
            status == InstrumentImplementationStatus.SOURCE_INCOMPLETE ->
                "[Eksploratif · Manual/Norma Parsial] ${result.interpretation}"
            status == InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED &&
                result.resolvedReviewStatus() != ExaminerReviewStatus.APPROVED ->
                "[Menunggu Review Psikolog] ${result.interpretation}"
            else -> result.interpretation
        }
        val tendencies = buildRelativeTendencies(canonicalId, result, status, validity)
        val limits = buildString {
            append(result.normReference ?: spec.normStatus)
            if (spec.licenseNote.isNotBlank()) append(" · ${spec.licenseNote}")
            if (status == InstrumentImplementationStatus.RAW_SCORE_ONLY) {
                append(" · Menampilkan deskripsi skor mentah tanpa penetapan kategori normatif eksternal.")
            }
            if (canonicalId in setOf("vak", "mit", "enneagram")) {
                append(" · Instrumen refleksi diri non-diagnostik.")
            }
        }
        val refs = buildString {
            append("${result.sourceTitle} (${result.version})")
            if (result.sourceLocator.isNotBlank()) append(" · ${result.sourceLocator}")
            if (spec.sourceReferences.isNotEmpty()) {
                append(" · Acuan: ${spec.sourceReferences.joinToString(", ")}")
            }
        }
        val profNotes = result.reviewNotes?.takeIf(String::isNotBlank)?.let {
            "Catatan (${result.reviewerId ?: "Pemeriksa"}): $it"
        } ?: "Status review: ${result.resolvedReviewStatus().displayLabel}."

        return BaselineInterpretationReport(
            scoreDescription = scoreDesc,
            constructMeaning = construct,
            profileMapping = mapping,
            resultInterpretation = mainInterpretation,
            relativeTendencies = tendencies,
            limitations = limits,
            references = refs,
            professionalNotes = profNotes
        )
    }

    private fun buildScoreDescription(result: AssessmentResult, status: InstrumentImplementationStatus): String {
        if (result.scores.isEmpty()) {
            return if (result.observations.isNotEmpty()) {
                "Tercatat ${result.observations.size} entri observasi terstruktur tanpa konversi skor numerik paksa."
            } else {
                "Status: ${status.displayLabel} (belum ada skor kuantitatif sah)."
            }
        }
        val mode = if (result.fromMarkedSheet) "rekap lembar jawaban" else "pengerjaan digital (${result.answered}/${result.itemCount})"
        return "Dihitung secara deterministik melalui $mode (${result.scorerVersion ?: PsychometricInstrumentRegistry.SCORER_VERSION}) " +
            "dengan ${result.scores.size} komponen skala terukur."
    }

    private fun buildProfileMapping(canonicalId: String, result: AssessmentResult): String {
        if (result.scores.isEmpty()) {
            return when (canonicalId) {
                "wartegg" -> "Pemetaan 8 kotak stimulus proyektif (urutan pengerjaan, durasi, evaluasi subjektif, & observasi grafis)."
                "rorschach" -> "Protokol 10 kartu (jumlah respons R, distribusi lokasi W/D/Dd/S, determinan, FQ, Popular)."
                "htp", "dap" -> "Matriks observasi kualitatif aspek grafis proyektif tanpa skor persentase buatan."
                else -> "Halaman status bukti instrumen; belum tersedia pemetaan skala terkuantifikasi."
            }
        }
        return when (canonicalId) {
            "disc" -> "Perbandingan tiga garis profil D-I-S-C: Profil A (Most), Profil B (Least), dan Profil C (Change)."
            "mmpi" -> "Pemeriksaan skala validitas terlebih dahulu sebelum membaca elevasi profil klinis."
            "mbti" -> "Peta preferensi dikotomi 4 pasang kutub bipolar (E↔I, S↔N, T↔F, J↔P)."
            "big_five" -> "Pemetaan 5 dimensi kepribadian (Openness, Conscientiousness, Extraversion, Agreeableness, Neuroticism)."
            "holland", "rmib", "kuder" -> "Urutan peringkat preferensi bidang minat (pada RMIB, skor jumlah terendah = minat tertinggi)."
            "pauli", "kraepelin" -> "Kurva laju performa per interval kerja serta pemisahan hitungan salah dan terlewati."
            "cfit_2a", "cfit_3a", "ist", "spm", "army_alpha", "tiu_5", "tiu_6", "wpt", "frt" ->
                "Profil skor mentah subtes/ketepatan penalaran; konversi IQ hanya valid jika tabel norma resmi dilampirkan."
            else -> result.scores.take(4).joinToString("; ") { "${it.label}: ${it.displayValue()}" }
        }
    }

    private fun buildRelativeTendencies(
        canonicalId: String,
        result: AssessmentResult,
        status: InstrumentImplementationStatus,
        validity: AssessmentValidityStatus
    ): String {
        val caveatPrefix = when {
            status == InstrumentImplementationStatus.DEMONSTRATION -> "Simulasi sintetis: "
            status == InstrumentImplementationStatus.EXPLORATORY ||
                status == InstrumentImplementationStatus.UNRESOLVED_IDENTITY ||
                status == InstrumentImplementationStatus.SOURCE_INCOMPLETE -> "Eksploratif purwarupa: "
            status == InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED &&
                result.resolvedReviewStatus() != ExaminerReviewStatus.APPROVED -> "Observasi awal (menunggu review psikolog): "
            validity == AssessmentValidityStatus.INVALID -> "Protokol parsial/terbatas: "
            else -> ""
        }
        if (result.scores.size < 2) {
            return result.scores.firstOrNull()?.let { "${caveatPrefix}Fokus utama pada ${it.label} (${it.displayValue()})." }
                ?: "${caveatPrefix}Merujuk catatan observasi terstruktur."
        }
        val comparable = result.scores.filter {
            !it.label.contains("Total", ignoreCase = true) && !it.label.startsWith("Ringkasan", ignoreCase = true)
        }
        if (comparable.isEmpty()) return "${caveatPrefix}Mengikuti ringkasan skala utama."
        val isLowerBetter = canonicalId == "rmib"
        val top = if (isLowerBetter) comparable.minByOrNull { it.value } else comparable.maxByOrNull { it.value }
        val low = if (isLowerBetter) comparable.maxByOrNull { it.value } else comparable.minByOrNull { it.value }
        return if (top != null && low != null && top != low) {
            "${caveatPrefix}Secara deskriptif dalam sesi ini, nilai paling menonjol berada pada ${top.label} (${top.displayValue()}) " +
                "dibandingkan ${low.label} (${low.displayValue()}); perbandingan ini bersifat relatif internal sesi."
        } else {
            "${caveatPrefix}Skor tersebar merata tanpa selisih ekstrem antar dimensi yang ditampilkan."
        }
    }

    private fun constructDescriptionFor(canonicalId: String, title: String): String = when (canonicalId) {
        "bdi" -> "Skrining intensitas gejala depresi (ranah kognitif-afektif dan somatik) dalam 2 minggu terakhir."
        "srq-29" -> "Skrining 29 butir gangguan mental emosional, penggunaan zat, gejala psikotik, dan PTSD (WHO/Kemenkes)."
        "cbi" -> "Pengukuran kelelahan (burnout) pada domain personal, pekerjaan, dan interaksi klien."
        "disc" -> "Kecenderungan gaya perilaku adaptasi kerja pada Dominance, Influence, Steadiness, dan Compliance."
        "mmpi" -> "Inventori kepribadian klinis & skala validitas respons (membutuhkan peninjauan profesional berlisensi)."
        "big_five" -> "Lima dimensi luas kepribadian model IPIP-BFM-50."
        "mbti" -> "Preferensi gaya kognitif dan interpersonal berdasarkan 4 dikotomi Jungian."
        "16pf", "epps", "papikostick" -> "Profil ciri/kebutuhan/peran kepribadian terstruktur dari respons pilihan peserta."
        "cfit_2a", "cfit_3a", "ist", "spm", "army_alpha", "tiu_5", "tiu_6", "wpt", "frt" ->
            "Kemampuan penalaran kognitif non-verbal, verbal, atau numerik sesuai subtes pada bentuk instrumen."
        "holland", "rmib", "kuder" -> "Pola minat vokasional dan preferensi aktivitas karier."
        "kraepelin", "pauli" -> "Ketahanan kerja, kecepatan pemrosesan numerik berulang, ketelitian, dan stabilitas ritme antarinterval."
        "wartegg", "rorschach", "htp", "dap" -> "Respons proyektif terstruktur yang wajib dikodekan dan ditinjau oleh pemeriksa berwenang."
        "vak", "mit", "enneagram" -> "Alat bantu refleksi preferensi belajar, kecenderungan kecerdasan majemuk, atau gaya kepribadian."
        else -> "Konstruk psikologis atau observasional pada instrumen $title sesuai acuan manual yang tersedia."
    }
}
