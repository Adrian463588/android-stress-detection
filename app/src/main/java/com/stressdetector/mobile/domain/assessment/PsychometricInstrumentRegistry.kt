package com.stressdetector.mobile.domain.assessment

object PsychometricInstrumentRegistry {
    const val SCORER_VERSION = "psychometry-deterministic-v2"
    const val RESULT_SCHEMA_VERSION = "1.0"

    private val registry: Map<String, InstrumentAuditSpec> = listOf(
        spec(
            id = "bdi",
            title = "Beck Depression Inventory (BDI-II)",
            version = "BDI-II-21-ID-2026",
            status = InstrumentImplementationStatus.VERIFIED,
            viz = AssessmentVisualizationType.SCORE_INDICATOR,
            norm = "Band gejala BDI-II standar (bukan diagnosis klinis)",
            license = "Skrining terstruktur; butir 9 memicu keselamatan",
            refs = listOf("Beck et al. (1996) BDI-II", "PsikoTest/BDI"),
            aiScreen = true,
            minutes = 5
        ),
        spec(
            id = "srq-29",
            title = "Self Reporting Questionnaire (SRQ-29)",
            version = "SRQ-29-ID-KEMENKES-2026",
            status = InstrumentImplementationStatus.VERIFIED,
            viz = AssessmentVisualizationType.HORIZONTAL_BAR,
            norm = "Skor mentah per domain Kemenkes (ambang >=6 GME bersifat skrining)",
            license = "Domain publik WHO / Kemenkes RI",
            refs = listOf("Kemenkes RI SRQ-29", "PsikoTest/SRQ-29"),
            aiScreen = true,
            minutes = 5
        ),
        spec(
            id = "big_five",
            title = "Big Five · IPIP-BFM-50",
            version = "IPIP-BFM-50-ID-2019",
            status = InstrumentImplementationStatus.VERIFIED,
            viz = AssessmentVisualizationType.HORIZONTAL_BAR,
            norm = "Skor mentah deskriptif 10-50 per dimensi O/C/E/A/N",
            license = "IPIP domain publik",
            refs = listOf("Goldberg IPIP-BFM-50", "PsikoTest/Big_Five"),
            aiScreen = true,
            minutes = 8
        ),
        spec(
            id = "disc",
            title = "DISC",
            version = "DISC-24 Form Baku",
            status = InstrumentImplementationStatus.RAW_SCORE_ONLY,
            viz = AssessmentVisualizationType.PROFILE_LINE_CHART,
            norm = "Skor mentah Profil A (Most), B (Least), C (Change); norma grafik eksternal belum dibundel",
            license = "Bentuk lokal terverifikasi untuk skor mentah",
            refs = listOf("PsikoTest/DISC"),
            aiScreen = true,
            minutes = 8
        ),
        spec(
            id = "mmpi",
            title = "MMPI",
            version = "MMPI-2-RF / MMPI-2",
            status = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
            viz = AssessmentVisualizationType.CLINICAL_PROFILE_CHART,
            review = true,
            norm = "Wajib verifikasi skala validitas & penetapan edisi sebelum profil klinis",
            license = "Terbatas: administrasi & interpretasi oleh psikolog berlisensi",
            refs = listOf("Pearson MMPI-2/2-RF", "PsikoTest/MMPI"),
            dedicated = true,
            minutes = 45
        ),
        spec(
            id = "wartegg",
            title = "Wartegg",
            version = "Wartegg-8-Kotak",
            status = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
            viz = AssessmentVisualizationType.WARTEGG_EIGHT_BOX,
            review = true,
            norm = "Menunggu coding sistem manual (mis. Kinget / Crisi) oleh pemeriksa",
            license = "Tes proyektif; skor numerik hanya sah setelah coding pemeriksa",
            refs = listOf("Wartegg / Crisi System", "PsikoTest/Wartegg"),
            dedicated = true,
            minutes = 20
        ),
        spec(
            id = "rorschach",
            title = "Tes Rorschach",
            version = "Rorschach-10-Kartu",
            status = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
            viz = AssessmentVisualizationType.EXAMINER_CODED_PROFILE,
            review = true,
            norm = "Satu sistem tunggal (Exner CS / R-PAS); wajib minimum R >= 14 & coding pemeriksa",
            license = "Terbatas: wajib kompetensi pemeriksa terlatih",
            refs = listOf("Exner CS / R-PAS", "PsikoTest/Rorschcach"),
            dedicated = true,
            minutes = 45
        ),
        spec(
            id = "mseai",
            title = "MSEAI",
            version = "MSEAI-24-Exploratory",
            status = InstrumentImplementationStatus.EXPLORATORY,
            viz = AssessmentVisualizationType.HORIZONTAL_BAR,
            norm = "Skor mentah eksploratif 4 domain; manual & ekspansi resmi belum terkonfirmasi",
            license = "Mode eksploratif purwarupa penelitian (bukan diagnosis atau norma klinis baku)",
            refs = listOf("PsikoTest/MSEAI/MSEAI_Acuan_dan_Skoring.md"),
            aiScreen = true,
            dedicated = false
        ),
        spec(
            id = "vrt",
            title = "Verbal Reasoning Test (VRT)",
            version = "VRT-Unverified-Local",
            status = InstrumentImplementationStatus.SOURCE_INCOMPLETE,
            viz = AssessmentVisualizationType.HORIZONTAL_BAR,
            norm = "Manual asli & norma usia/pendidikan tidak tersedia lokal; menampilkan skor mentah 7 subtes",
            license = "Mode eksploratif purwarupa (hindari klaim cutoff normatif tanpa manual lengkap)",
            refs = listOf("Basagni et al. (2017)", "PsikoTest/VRT"),
            aiScreen = true,
            dedicated = false
        ),
        spec(
            id = "esj",
            title = "ESJ",
            version = "ESJ-Exploratory-4",
            status = InstrumentImplementationStatus.EXPLORATORY,
            viz = AssessmentVisualizationType.HORIZONTAL_BAR,
            norm = "Mode eksploratif purwarupa penelitian; identitas & norma resmi belum terkonfirmasi",
            license = "Mode eksploratif purwarupa penelitian (bukan skor psikometri resmi)",
            refs = listOf("PRD_SPRINT_PSYCHOMETRY_COUNSELING_AI_INTEGRATION.md"),
            aiScreen = true,
            dedicated = false
        ),
        spec(
            id = "cbi_tbi",
            title = "CBI-TBI",
            version = "Chacko-2025-18-Exploratory",
            status = InstrumentImplementationStatus.EXPLORATORY,
            viz = AssessmentVisualizationType.HORIZONTAL_BAR,
            norm = "Adaptasi eksploratif kelelahan kerja/burnout; kecocokan bentuk vs CBI (NFA-2004) bersifat pendahuluan",
            license = "Mode eksploratif purwarupa penelitian (konfirmasi form resmi disarankan)",
            refs = listOf("Chacko et al. (2025)", "PsikoTest/CBI"),
            aiScreen = true,
            dedicated = false
        ),
        spec(
            id = "htp",
            title = "House-Tree-Person (HTP)",
            version = "Proyektif-Observasi",
            status = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
            viz = AssessmentVisualizationType.OBSERVATION_MATRIX,
            review = true,
            norm = "Observasi kualitatif & tinjauan psikolog; tidak memakai metrik kepribadian buatan",
            license = "Terbatas: evaluasi klinis profesional",
            refs = listOf("PsikoTest/Wartegg/Wartegg_Acuan_dan_Skoring.md"),
            dedicated = true
        ),
        spec(
            id = "dap",
            title = "Draw-A-Person (DAP)",
            version = "Proyektif-Observasi",
            status = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
            viz = AssessmentVisualizationType.OBSERVATION_MATRIX,
            review = true,
            norm = "Observasi kualitatif & tinjauan psikolog; tidak memakai metrik kepribadian buatan",
            license = "Terbatas: evaluasi klinis profesional",
            refs = listOf("PsikoTest/Wartegg/Wartegg_Acuan_dan_Skoring.md"),
            dedicated = true
        ),
        spec(
            id = "baum",
            title = "Baum Test (Tes Pohon)",
            version = "Proyektif-Observasi",
            status = InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED,
            viz = AssessmentVisualizationType.OBSERVATION_MATRIX,
            review = true,
            norm = "Observasi kualitatif & tinjauan psikolog; tidak memakai metrik kepribadian buatan",
            license = "Terbatas: evaluasi klinis profesional",
            refs = listOf("PsikoTest/Wartegg/Wartegg_Acuan_dan_Skoring.md"),
            dedicated = true
        ),
        rawSpec("16pf", "16PF", "16PF Form C (105 Butir)", AssessmentVisualizationType.HORIZONTAL_BAR, "Skor mentah & faktor; norma sten lokal tanpa stratifikasi usia/jenis kelamin", true),
        rawSpec("epps", "EPPS", "EPPS 1959 (225 Pasangan)", AssessmentVisualizationType.HORIZONTAL_BAR, "Skor mentah 15 kebutuhan + konsistensi; tabel persentil lokal memerlukan audit", true),
        rawSpec("papikostick", "PAPI Kostick", "PAPI Kostick 90-Item", AssessmentVisualizationType.HORIZONTAL_BAR, "Skor mentah 20 dimensi kebutuhan & peran (0-9)", false),
        rawSpec("cfit_2a", "CFIT Scale 2 Form A", "CFIT-2A-46", AssessmentVisualizationType.HORIZONTAL_BAR, "Skor mentah per subtes; konversi IQ tanpa norma populasi sah tidak ditampilkan", true),
        rawSpec("cfit_3a", "CFIT Scale 3 Form A", "CFIT-3A-50", AssessmentVisualizationType.HORIZONTAL_BAR, "Skor mentah per subtes; konversi IQ tanpa norma populasi sah tidak ditampilkan", true),
        rawSpec("ist", "Intelligenz-Struktur-Test (IST)", "IST-9-Subtes", AssessmentVisualizationType.HORIZONTAL_BAR, "Skor mentah per subtes; konversi IQ workbook tidak diterapkan", true),
        rawSpec("spm", "Raven Standard Progressive Matrices (SPM)", "SPM-60", AssessmentVisualizationType.HORIZONTAL_BAR, "Skor mentah total & per set A-E; persentil menunggu norma usia resmi", true),
        rawSpec("army_alpha", "Army Alpha", "UI-10013-96 (12 Butir)", AssessmentVisualizationType.HORIZONTAL_BAR, "Skor observasi gambar & rekap manual; pengenalan goresan bersifat bantu", true),
        rawSpec("tiu_5", "TIU 5", "UI-10144-96 (30 Butir)", AssessmentVisualizationType.SCORE_INDICATOR, "Skor mentah jawaban benar dari 30 butir; norma lokal bersifat internal", true),
        rawSpec("tiu_6", "TIU 6", "UI-10154-96 (40 Butir)", AssessmentVisualizationType.SCORE_INDICATOR, "Skor mentah jawaban benar dari 40 butir; kategori band eksternal tidak diklaim baku", true),
        rawSpec("wpt", "Wonderlic Personnel Test (WPT)", "WPT-50", AssessmentVisualizationType.SCORE_INDICATOR, "Skor mentah benar dari 50 butir; timer baku 12 menit & IQ tanpa norma tidak diklaim", true),
        rawSpec("frt", "Figure Reasoning Test (FRT)", "FRT-FormA-45", AssessmentVisualizationType.SCORE_INDICATOR, "Skor mentah & terkoreksi R - 0.25W; tanpa norma kelompok eksternal", true),
        rawSpec("holland", "Holland RIASEC", "O*NET Interest Profiler 60", AssessmentVisualizationType.RANKED_BAR, "Skor mentah 6 dimensi minat RIASEC", false, aiScreen = true),
        rawSpec("rmib", "Rothwell-Miller Interest Blank (RMIB)", "RMIB-9x12", AssessmentVisualizationType.RANKED_BAR, "Peringkat jumlah terendah = minat tertinggi; norma terpisah pria/wanita diperhatikan", false),
        rawSpec("kuder", "Kuder Preference Record", "Kuder-168-Triad", AssessmentVisualizationType.RANKED_BAR, "Skor mentah skala V dan 10 bidang minat; norma persentil memerlukan jenis kelamin eksplisit", true),
        rawSpec("kraepelin", "Kraepelin", "Kraepelin-Interval", AssessmentVisualizationType.TIME_SERIES_CURVE, "Kurva kecepatan, ketelitian, ajeg, & kesalahan per interval waktu aktual", true),
        rawSpec("pauli", "Pauli", "Tes Pauli Lajur", AssessmentVisualizationType.TIME_SERIES_CURVE, "Kurva performa, rerata, simpangan, & kesalahan per interval waktu aktual", true),
        rawSpec("cbi", "Copenhagen Burnout Inventory (CBI)", "CBI-NFA-2004-EN", AssessmentVisualizationType.HORIZONTAL_BAR, "Skor 0-100 per subskala kelelahan (Personal/Kerja/Klien); tanpa cutoff diagnostik universal", false, aiScreen = true),
        rawSpec("adversity_quotient", "Adversity Quotient", "AQ-15-ID", AssessmentVisualizationType.HORIZONTAL_BAR, "Pemetaan 15 butir Q/Ca/Cl sesuai workbook paket lokal (terpisah dari rekap 20-butir ARP CORE)", false),
        rawSpec("mbti", "MBTI", "MBTI-70-Baku", AssessmentVisualizationType.BIPOLAR_PREFERENCE, "Skor kecenderungan dikotomi 4 pasang kutub; tipe tidak bersifat kaku", false),
        rawSpec("msai", "MSAI", "MSAI-60", AssessmentVisualizationType.HORIZONTAL_BAR, "Rerata kompetensi berdasarkan butir yang dijawab aktual", false),
        rawSpec("msdt", "MSDT", "MSDT-64-Reddin", AssessmentVisualizationType.HORIZONTAL_BAR, "Skor gaya kepemimpinan 8 kuadran berdasarkan kelengkapan penuh", false),
        rawSpec("enneagram", "Enneagram", "Enneagram-180", AssessmentVisualizationType.HORIZONTAL_BAR, "Profil kecenderungan skor refleksi 9 tipe (tanpa penetapan tipe tetap)", false),
        rawSpec("mit", "Multiple Intelligences", "MIT-56", AssessmentVisualizationType.HORIZONTAL_BAR, "Skor mentah 8 kecerdasan majemuk untuk refleksi", false),
        rawSpec("vak", "VAK", "VAK-30", AssessmentVisualizationType.HORIZONTAL_BAR, "Distribusi preferensi Visual/Auditory/Kinesthetic (bukan penetapan gaya belajar baku)", false),
        rawSpec("personality_test", "Personality Test (Kandidat EPQ-R)", "EPQ-RS-48", AssessmentVisualizationType.HORIZONTAL_BAR, "Skor mentah 4 subskala; identitas & norma populasi masih dalam tinjauan", true)
    ).associateBy { it.instrumentId }

    fun normalizeInstrumentId(rawId: String): String {
        val cleaned = rawId.trim().lowercase()
        return when (cleaned) {
            "srq29" -> "srq-29"
            "cfit2a", "cfit-2a" -> "cfit_2a"
            "cfit3a", "cfit-3a" -> "cfit_3a"
            "armyalpha", "army-alpha" -> "army_alpha"
            "tiu5", "tiu-5" -> "tiu_5"
            "tiu6", "tiu-6" -> "tiu_6"
            "bigfive", "big-five" -> "big_five"
            "aq", "adversity-quotient" -> "adversity_quotient"
            "cbi-tbi", "cbitbi" -> "cbi_tbi"
            "baum test", "baum-test", "tes pohon" -> "baum"
            else -> cleaned
        }
    }

    fun specFor(instrumentId: String): InstrumentAuditSpec {
        val canonicalId = normalizeInstrumentId(instrumentId)
        return registry[canonicalId] ?: InstrumentAuditSpec(
            instrumentId = instrumentId,
            canonicalTitle = instrumentId,
            formVersion = "UNVERIFIED",
            implementationStatus = InstrumentImplementationStatus.UNRESOLVED_IDENTITY,
            visualizationType = AssessmentVisualizationType.STATUS_ONLY,
            requiresExaminerReview = false,
            normStatus = "Instrumen belum terdaftar dalam audit formal.",
            licenseNote = "Mode eksploratif purwarupa untuk instrumen non-katalog.",
            sourceReferences = emptyList(),
            eligibleForAiScreening = false,
            dedicatedScreenRequired = true,
            estimatedMinutes = null
        )
    }

    fun allSpecs(): List<InstrumentAuditSpec> = registry.values.toList()

    fun eligibleAiScreeningIds(): Set<String> = registry.values.filter { it.eligibleForAiScreening }.mapTo(linkedSetOf()) { it.instrumentId }

    private fun rawSpec(
        id: String,
        title: String,
        version: String,
        viz: AssessmentVisualizationType,
        norm: String,
        dedicated: Boolean,
        aiScreen: Boolean = false
    ) = spec(
        id = id,
        title = title,
        version = version,
        status = InstrumentImplementationStatus.RAW_SCORE_ONLY,
        viz = viz,
        norm = norm,
        license = "Skor mentah deskriptif; hindari klaim normatif tanpa norma populasi sah",
        refs = listOf("PsikoTest/$id"),
        aiScreen = aiScreen,
        dedicated = dedicated
    )

    private fun spec(
        id: String,
        title: String,
        version: String,
        status: InstrumentImplementationStatus,
        viz: AssessmentVisualizationType,
        norm: String,
        license: String,
        refs: List<String>,
        review: Boolean = false,
        aiScreen: Boolean = false,
        dedicated: Boolean = false,
        minutes: Int? = null
    ) = InstrumentAuditSpec(
        instrumentId = id,
        canonicalTitle = title,
        formVersion = version,
        implementationStatus = status,
        visualizationType = viz,
        requiresExaminerReview = review,
        normStatus = norm,
        licenseNote = license,
        sourceReferences = refs,
        eligibleForAiScreening = aiScreen,
        dedicatedScreenRequired = dedicated,
        estimatedMinutes = minutes
    )
}
