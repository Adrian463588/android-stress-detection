package com.stressdetector.mobile.data.repository

import android.content.Context
import com.google.gson.Gson
import com.stressdetector.mobile.data.db.AssessmentResultDao
import com.stressdetector.mobile.data.db.AssessmentResultEntity
import com.stressdetector.mobile.domain.assessment.AssessmentCatalog
import com.stressdetector.mobile.domain.assessment.AssessmentEnrichers
import com.stressdetector.mobile.domain.assessment.AssessmentInstrument
import com.stressdetector.mobile.domain.assessment.AssessmentProtocol
import com.stressdetector.mobile.domain.assessment.AssessmentResult
import com.stressdetector.mobile.domain.assessment.AssessmentScore
import com.stressdetector.mobile.domain.assessment.AssessmentScoring
import com.stressdetector.mobile.domain.assessment.CounselingKnowledgeModule
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AssessmentRepository(context: Context, private val resultDao: () -> AssessmentResultDao) {
    private val appContext = context.applicationContext
    private val assets = appContext.assets
    private val gson = Gson()
    private val searchPrefs by lazy {
        appContext.getSharedPreferences("assessment_search_prefs", Context.MODE_PRIVATE)
    }
    private val catalog by lazy {
        val instruments = assets.open("ai/psychometrics/catalog.json").bufferedReader().use {
            gson.fromJson(it, Array<AssessmentInstrument>::class.java).toList()
        }
        require(instruments.map { it.id }.toSet().size == instruments.size)
        val protocols = assets.open("ai/psychometrics/protocols.json").bufferedReader().use {
            gson.fromJson(it, Array<AssessmentProtocol>::class.java).toList()
        }
        val byId = protocols.associateBy { it.instrumentId }
        require(byId.size == protocols.size && byId.keys == instruments.map { it.id }.toSet())
        instruments.mapNotNull { it.form }.forEach { form ->
            require(form.items.isNotEmpty() && form.minimumAnswers in 1..form.items.size)
            require(form.choices.size == form.values.size && form.values.size > 1)
            require(form.scales.map { it.id }.toSet() == form.items.map { it.scale }.toSet())
            require(form.items.all { it.choices == null || it.choices.size in 2..form.values.size })
        }
        AssessmentCatalog(instruments.map { it.copy(protocol = byId.getValue(it.id)) })
    }

    suspend fun instruments(): List<AssessmentInstrument> = withContext(Dispatchers.IO) { catalog.instruments }

    val counselingModules: List<CounselingKnowledgeModule> = listOf(
        CounselingKnowledgeModule(
            id = "bk-01",
            title = "Dasar, Asas & Kode Etik BK",
            pillar = "Pilar 1",
            category = "Etika & Regulasi",
            summary = "Asas kerahasiaan, 12 asas konseling Prayitno, kode etik ABKIN, dan prosedur referral.",
            assetFileName = "bk_01_dasar_dan_etika.md",
            keyTopics = listOf("Asas Kerahasiaan", "Kode Etik ABKIN", "Asas Alih Tangan", "Informed Consent")
        ),
        CounselingKnowledgeModule(
            id = "bk-02",
            title = "4 Bidang Layanan Konseling",
            pillar = "Pilar 2",
            category = "Bidang Layanan",
            summary = "Layanan pribadi, sosial, belajar, dan karier berbasis teori Holland RIASEC & Donald Super.",
            assetFileName = "bk_02_bidang_layanan.md",
            keyTopics = listOf("Bimbingan Pribadi-Sosial", "Bimbingan Belajar", "Holland RIASEC", "Pelangi Karier Super")
        ),
        CounselingKnowledgeModule(
            id = "bk-03",
            title = "Pendekatan & Intervensi",
            pillar = "Pilar 3",
            category = "Pendekatan Klinis",
            summary = "CBT Restrukturisasi Kognitif, REBT ABCDE, Terapi Realitas WDEP, " +
                "Person-Centered Rogers, dan Model Regulasi Emosi James Gross.",
            assetFileName = "bk_03_teori_dan_intervensi.md",
            keyTopics = listOf("CBT Kognitif", "REBT Kerangka ABCDE", "Reality Therapy WDEP", "Regulasi Emosi Gross")
        ),
        CounselingKnowledgeModule(
            id = "bk-04",
            title = "Layanan Kelompok & Klasikal",
            pillar = "Pilar 4",
            category = "Metode Layanan",
            summary = "Dinamika kelompok, 4 tahapan konseling kelompok, dan format RPL bimbingan klasikal.",
            assetFileName = "bk_04_kelompok_dan_klasikal.md",
            keyTopics = listOf("Dinamika Kelompok", "4 Tahap Konseling", "Format RPL Klasikal", "Kohesi Kelompok")
        ),
        CounselingKnowledgeModule(
            id = "bk-05",
            title = "Keterampilan Mikro Konseling",
            pillar = "Pilar 5",
            category = "Teknik Komunikasi",
            summary = "Keterampilan attending SOLER, empati primer & lanjut, paraphrasing, " +
                "refleksi perasaan, pertanyaan terbuka, dan konfrontasi lembut.",
            assetFileName = "bk_05_keterampilan_mikro.md",
            keyTopics = listOf("Sikap Duduk SOLER", "Empati Lanjut", "Paraphrasing", "Refleksi Emosi")
        ),
        CounselingKnowledgeModule(
            id = "bk-06",
            title = "Asesmen Psikologis & Evaluasi",
            pillar = "Pilar 6",
            category = "Asesmen & Tindak Lanjut",
            summary = "Asesmen tes & non-tes, integrasi hasil psikometri ke dalam konseling, " +
                "evaluasi program, penanganan krisis, dan referral darurat.",
            assetFileName = "bk_06_asesmen_dan_tindak_lanjut.md",
            keyTopics = listOf("Integrasi Psikometri", "Asesmen Non-Tes", "Evaluasi Program CIPP", "Prosedur Kasus Darurat")
        ),
        CounselingKnowledgeModule(
            id = "bk-07",
            title = "Asesmen Proyektif: Tes Rorschach",
            pillar = "Pilar 7",
            category = "Psikodiagnostik Proyektif",
            summary = "Sistem Komprehensif Exner & R-PAS, analisis psikogram EB (M : WSumC), kapasitas koping EA/es/D-Score, uji realitas, dan eksplorasi proyektif konseling.",
            assetFileName = "rorschach_projective_assessment.md",
            keyTopics = listOf("Erlebnistypus EB", "Kapasitas Koping EA-es", "Uji Realitas X+%", "Eksplorasi Proyektif", "Exner CS & R-PAS")
        )
    )

    suspend fun getCounselingModules(): List<CounselingKnowledgeModule> = withContext(Dispatchers.IO) { counselingModules }

    suspend fun readCounselingModule(module: CounselingKnowledgeModule): String = withContext(Dispatchers.IO) {
        assets.open("ai/counseling/${module.assetFileName}").bufferedReader().use { it.readText() }
    }

    suspend fun recommend(query: String): List<AssessmentInstrument> = withContext(Dispatchers.IO) { catalog.recommend(query) }

    suspend fun getResult(id: String): AssessmentResult? = withContext(Dispatchers.IO) {
        resultDao().get(id)?.decode()
    }

    suspend fun getAllResults(): List<AssessmentResult> = withContext(Dispatchers.IO) {
        resultDao().all().map { it.decode() }
    }

    suspend fun getRecentAssessmentSummary(): String = withContext(Dispatchers.IO) {
        val results = resultDao().all().map { it.decode() }
        if (results.isEmpty()) return@withContext ""
        results.take(3).joinToString("; ") { result ->
            val scoresSummary = if (result.scores.isNotEmpty()) {
                result.scores.joinToString(", ") { "${it.label}: ${it.displayValue()}" }
            } else {
                "${result.answered} dijawab"
            }
            "${result.title} (Skor: $scoresSummary | Interpretasi: ${result.interpretation})"
        }
    }

    suspend fun save(id: String, answers: Map<Int, Int>): AssessmentResult = withContext(Dispatchers.IO) {
        val form = requireNotNull(catalog.find(id)?.form)
        val rawScores = AssessmentScoring.score(form, answers)
        val (finalScores, dynamicInterpretation) = AssessmentEnrichers.enrich(id, form, answers, rawScores)
        val answeredCount = when (id) {
            "disc" -> (0 until 24).count { answers.containsKey(it * 2) && answers.containsKey(it * 2 + 1) }
            "kuder" -> (0 until 168).count { answers.containsKey(it * 2) && answers.containsKey(it * 2 + 1) }
            "kraepelin" -> (0 until form.items.size).count { answers.containsKey(it) }
            else -> answers.size
        }
        val result = AssessmentResult(
            resultId = UUID.randomUUID().toString(),
            instrumentId = id,
            title = form.title,
            version = form.version,
            sourceTitle = form.sourceTitle,
            sourceUrl = form.sourceUrl,
            sourceLocator = form.sourceLocator,
            createdAtMillis = System.currentTimeMillis(),
            answered = answeredCount,
            itemCount = form.items.size,
            scores = finalScores,
            interpretation = dynamicInterpretation
        )
        store(result)
    }

    suspend fun saveMarkedSheet(id: String, values: List<Int>): AssessmentResult = withContext(Dispatchers.IO) {
        val instrument = requireNotNull(catalog.find(id))
        val protocol = requireNotNull(instrument.protocol)
        val entry = requireNotNull(protocol.entry)
        val (scores, dynamicInterpretation) = when {
            id == "wpt" && values.size >= 2 -> {
                AssessmentEnrichers.enrichWptRecap(values[0], values[1])
            }
            id == "tiu_5" && values.size >= 2 -> {
                AssessmentEnrichers.enrichTiu5Recap(values[0], values[1])
            }
            id == "tiu_6" && values.size >= 2 -> {
                AssessmentEnrichers.enrichTiu6Recap(values[0], values[1])
            }
            id == "spm" && values.size >= 5 -> {
                AssessmentEnrichers.enrichSpmRecap(values[0], values[1], values[2], values[3], values[4])
            }
            id == "cfit_2a" && values.size >= 4 -> {
                AssessmentEnrichers.enrichCfit2aRecap(values[0], values[1], values[2], values[3])
            }
            id == "cfit_3a" && values.size >= 4 -> {
                AssessmentEnrichers.enrichCfit3aRecap(values[0], values[1], values[2], values[3])
            }
            id == "army_alpha" && values.size >= 2 -> {
                AssessmentEnrichers.enrichArmyAlphaRecap(values[0], values[1])
            }
            else -> {
                val s = entry.score(values)
                s to enrichMarkedSheetInterpretation(protocol, s)
            }
        }
        store(
            AssessmentResult(
                resultId = UUID.randomUUID().toString(),
                instrumentId = id,
                title = instrument.title,
                version = entry.version,
                sourceTitle = "Bank PsikoTest · ${protocol.edition}",
                sourceUrl = instrument.referenceUrl.orEmpty(),
                sourceLocator = "${instrument.source} · §${protocol.section}",
                createdAtMillis = System.currentTimeMillis(),
                answered = values.size,
                itemCount = entry.fields.size,
                scores = scores,
                interpretation = dynamicInterpretation,
                fromMarkedSheet = true
            )
        )
    }

    private fun enrichMarkedSheetInterpretation(protocol: AssessmentProtocol, scores: List<AssessmentScore>): String {
        val firstMeaning = scores.firstOrNull()?.meaning.orEmpty()
        return if (firstMeaning.isNotBlank() && firstMeaning != "Rekap lembar.") {
            "${scores.first().label}: ${scores.first().displayValue()}. $firstMeaning ${protocol.limitation}"
        } else {
            "${protocol.interpretation} ${protocol.limitation}"
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { resultDao().delete(id) }

    private suspend fun store(result: AssessmentResult): AssessmentResult {
        resultDao().save(AssessmentResultEntity(result.instrumentId, gson.toJson(result)))
        return result
    }

    private fun AssessmentResultEntity.decode(): AssessmentResult =
        gson.fromJson(payloadJson, AssessmentResult::class.java).also { require(it.instrumentId == instrumentId) }

    fun getRecentSearches(): List<String> {
        val raw = searchPrefs.getString("recent_searches", null) ?: return emptyList()
        return try {
            gson.fromJson(raw, Array<String>::class.java).toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveRecentSearch(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return getRecentSearches()
        val current = getRecentSearches().toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        current.add(0, trimmed)
        val capped = current.take(10)
        searchPrefs.edit().putString("recent_searches", gson.toJson(capped)).apply()
        return capped
    }

    fun removeRecentSearch(query: String): List<String> {
        val current = getRecentSearches().toMutableList()
        current.removeAll { it.equals(query.trim(), ignoreCase = true) }
        searchPrefs.edit().putString("recent_searches", gson.toJson(current)).apply()
        return current
    }

    fun clearRecentSearches(): List<String> {
        searchPrefs.edit().remove("recent_searches").apply()
        return emptyList()
    }
}
