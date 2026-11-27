package com.stressdetector.mobile.data.repository

import android.content.Context
import com.google.gson.Gson
import com.stressdetector.mobile.data.db.AssessmentResultDao
import com.stressdetector.mobile.data.db.AssessmentResultEntity
import com.stressdetector.mobile.data.db.AssessmentResultSnapshotEntity
import com.stressdetector.mobile.domain.assessment.AssessmentCatalog
import com.stressdetector.mobile.domain.assessment.AssessmentEnrichers
import com.stressdetector.mobile.domain.assessment.AssessmentInstrument
import com.stressdetector.mobile.domain.assessment.AssessmentObservation
import com.stressdetector.mobile.domain.assessment.AssessmentProtocol
import com.stressdetector.mobile.domain.assessment.AssessmentResult
import com.stressdetector.mobile.domain.assessment.AssessmentScore
import com.stressdetector.mobile.domain.assessment.AssessmentScoring
import com.stressdetector.mobile.domain.assessment.AssessmentValidityStatus
import com.stressdetector.mobile.domain.assessment.AssessmentVisualizationType
import com.stressdetector.mobile.domain.assessment.CounselingKnowledgeModule
import com.stressdetector.mobile.domain.assessment.ExaminerReviewStatus
import com.stressdetector.mobile.domain.assessment.InstrumentImplementationStatus
import com.stressdetector.mobile.domain.assessment.ProjectivePanelDraft
import com.stressdetector.mobile.domain.assessment.PsychometricInstrumentRegistry
import com.stressdetector.mobile.domain.assessment.RorschachResponseDraft
import com.stressdetector.mobile.domain.assessment.WarteggBoxDraft
import com.stressdetector.mobile.ml.rag.AssetBundledLocalCorpus
import com.stressdetector.mobile.ml.rag.assessmentFollowUpReference
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AssessmentRepository(
    context: Context,
    private val onResultPersisted: (suspend (AssessmentResult) -> Unit)? = null,
    private val resultDao: () -> AssessmentResultDao
) {
    private val appContext = context.applicationContext
    private val assets = appContext.assets
    private val gson = Gson()
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
        resultDao().snapshotsForInstrument(id).firstOrNull()?.decode()
            ?: resultDao().get(id)?.decode()
    }

    suspend fun getResultById(resultId: String): AssessmentResult? = withContext(Dispatchers.IO) {
        resultDao().getSnapshot(resultId)?.decode()
            ?: resultDao().all().map { it.decode() }.firstOrNull { it.resultId == resultId }
    }

    suspend fun getResultsForInstrument(instrumentId: String): List<AssessmentResult> = withContext(Dispatchers.IO) {
        val snapshots = resultDao().snapshotsForInstrument(instrumentId).map { it.decode() }
        if (snapshots.isNotEmpty()) return@withContext snapshots
        listOfNotNull(resultDao().get(instrumentId)?.decode())
    }

    suspend fun getAllResults(): List<AssessmentResult> = withContext(Dispatchers.IO) {
        val snapshots = resultDao().allSnapshots().map { it.decode() }
        val knownIds = snapshots.mapTo(HashSet()) { it.resultId }
        val legacyOnly = resultDao().all().map { it.decode() }.filter { it.resultId !in knownIds }
        (snapshots + legacyOnly).sortedByDescending { it.createdAtMillis }
    }

    suspend fun resultForDiscussion(query: String): AssessmentResult? = withContext(Dispatchers.IO) {
        val instrument = catalog.findNamedInstrument(query) ?: return@withContext null
        resultDao().get(instrument.id)?.decode()
    }

    suspend fun followUpReference() = assessmentFollowUpReference(AssetBundledLocalCorpus(appContext))

    suspend fun save(id: String, answers: Map<Int, Int>): AssessmentResult = withContext(Dispatchers.IO) {
        val form = requireNotNull(catalog.find(id)?.form)
        val rawScores = AssessmentScoring.score(form, answers)
        val outcome = AssessmentEnrichers.enrichDetailed(id, form, answers, rawScores)
        val spec = PsychometricInstrumentRegistry.specFor(id)
        val answeredCount = when (id) {
            "disc" -> (0 until 24).count { answers.containsKey(it * 2) && answers.containsKey(it * 2 + 1) }
            "kuder" -> (0 until 168).count { answers.containsKey(it * 2) && answers.containsKey(it * 2 + 1) }
            "kraepelin", "pauli" -> (0 until form.items.size).count { answers.containsKey(it) }
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
            scores = outcome.scores,
            interpretation = outcome.interpretation,
            schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
            administrationMode = "interactive_form",
            scoringStatus = outcome.scoringStatus,
            validityStatus = outcome.validityStatus,
            validityWarnings = outcome.validityWarnings,
            reviewStatus = outcome.reviewStatus,
            scorerVersion = PsychometricInstrumentRegistry.SCORER_VERSION,
            normReference = outcome.normReference ?: spec.normStatus,
            visualizationType = spec.visualizationType,
            observations = outcome.observations,
            warteggDrafts = outcome.warteggDrafts,
            rorschachResponses = outcome.rorschachResponses
        )
        store(result)
    }

    suspend fun saveWarteggDrafts(
        drafts: List<WarteggBoxDraft>,
        multimodalSummary: String? = null
    ): AssessmentResult = withContext(Dispatchers.IO) {
        val instrument = requireNotNull(catalog.find("wartegg"))
        val form = instrument.form
        val spec = PsychometricInstrumentRegistry.specFor("wartegg")
        val outcome = AssessmentEnrichers.scoreWarteggFromDrafts(drafts, multimodalSummary)
        val result = AssessmentResult(
            resultId = UUID.randomUUID().toString(),
            instrumentId = "wartegg",
            title = form?.title ?: instrument.title,
            version = form?.version ?: spec.formVersion,
            sourceTitle = form?.sourceTitle ?: instrument.source,
            sourceUrl = form?.sourceUrl ?: instrument.referenceUrl.orEmpty(),
            sourceLocator = form?.sourceLocator ?: instrument.source,
            createdAtMillis = System.currentTimeMillis(),
            answered = drafts.count { it.hasContent() },
            itemCount = 8,
            scores = outcome.scores,
            interpretation = outcome.interpretation,
            schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
            administrationMode = "wartegg_canvas",
            scoringStatus = outcome.scoringStatus,
            validityStatus = outcome.validityStatus,
            validityWarnings = outcome.validityWarnings,
            reviewStatus = outcome.reviewStatus,
            scorerVersion = PsychometricInstrumentRegistry.SCORER_VERSION,
            normReference = outcome.normReference ?: spec.normStatus,
            visualizationType = AssessmentVisualizationType.WARTEGG_EIGHT_BOX,
            observations = outcome.observations,
            warteggDrafts = drafts,
            multimodalSummary = outcome.multimodalSummary
        )
        store(result)
    }

    suspend fun saveProjectivePanels(
        id: String,
        panels: List<ProjectivePanelDraft>,
        multimodalSummary: String? = null
    ): AssessmentResult = withContext(Dispatchers.IO) {
        val instrument = requireNotNull(catalog.find(id))
        val form = instrument.form
        val spec = PsychometricInstrumentRegistry.specFor(id)
        val outcome = AssessmentEnrichers.scoreProjectivePanels(id, instrument.title, panels, multimodalSummary)
        val result = AssessmentResult(
            resultId = UUID.randomUUID().toString(),
            instrumentId = id,
            title = form?.title ?: instrument.title,
            version = form?.version ?: spec.formVersion,
            sourceTitle = form?.sourceTitle ?: instrument.source,
            sourceUrl = form?.sourceUrl ?: instrument.referenceUrl.orEmpty(),
            sourceLocator = form?.sourceLocator ?: instrument.source,
            createdAtMillis = System.currentTimeMillis(),
            answered = panels.count { it.hasContent() },
            itemCount = panels.size.coerceAtLeast(1),
            scores = outcome.scores,
            interpretation = outcome.interpretation,
            schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
            administrationMode = "${id}_canvas",
            scoringStatus = outcome.scoringStatus,
            validityStatus = outcome.validityStatus,
            validityWarnings = outcome.validityWarnings,
            reviewStatus = outcome.reviewStatus,
            scorerVersion = PsychometricInstrumentRegistry.SCORER_VERSION,
            normReference = outcome.normReference ?: spec.normStatus,
            visualizationType = spec.visualizationType,
            observations = outcome.observations,
            projectivePanels = panels,
            multimodalSummary = outcome.multimodalSummary
        )
        store(result)
    }

    suspend fun saveRorschachResponses(
        responses: List<RorschachResponseDraft>,
        systemId: String = "EXNER_CS",
        multimodalSummary: String? = null
    ): AssessmentResult = withContext(Dispatchers.IO) {
        val instrument = requireNotNull(catalog.find("rorschach"))
        val form = instrument.form
        val spec = PsychometricInstrumentRegistry.specFor("rorschach")
        val outcome = AssessmentEnrichers.scoreRorschachFromDrafts(responses, systemId, multimodalSummary)
        val result = AssessmentResult(
            resultId = UUID.randomUUID().toString(),
            instrumentId = "rorschach",
            title = form?.title ?: instrument.title,
            version = form?.version ?: spec.formVersion,
            sourceTitle = form?.sourceTitle ?: instrument.source,
            sourceUrl = form?.sourceUrl ?: instrument.referenceUrl.orEmpty(),
            sourceLocator = form?.sourceLocator ?: instrument.source,
            createdAtMillis = System.currentTimeMillis(),
            answered = responses.map { it.cardNumber }.distinct().size,
            itemCount = 10,
            scores = outcome.scores,
            interpretation = outcome.interpretation,
            schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
            administrationMode = "rorschach_protocol",
            scoringStatus = outcome.scoringStatus,
            validityStatus = outcome.validityStatus,
            validityWarnings = outcome.validityWarnings,
            reviewStatus = outcome.reviewStatus,
            scorerVersion = PsychometricInstrumentRegistry.SCORER_VERSION,
            normReference = outcome.normReference ?: spec.normStatus,
            visualizationType = AssessmentVisualizationType.EXAMINER_CODED_PROFILE,
            observations = outcome.observations,
            rorschachResponses = responses,
            multimodalSummary = outcome.multimodalSummary
        )
        store(result)
    }

    suspend fun savePrototypeDemoResult(
        id: String,
        scores: List<AssessmentScore>,
        interpretation: String,
        observations: List<AssessmentObservation> = emptyList(),
        warteggDrafts: List<WarteggBoxDraft> = emptyList(),
        rorschachResponses: List<RorschachResponseDraft> = emptyList(),
        projectivePanels: List<ProjectivePanelDraft> = emptyList(),
        multimodalSummary: String? = null
    ): AssessmentResult = withContext(Dispatchers.IO) {
        val instrument = requireNotNull(catalog.find(id))
        val form = instrument.form
        val spec = PsychometricInstrumentRegistry.specFor(id)
        val result = AssessmentResult(
            resultId = UUID.randomUUID().toString(),
            instrumentId = id,
            title = form?.title ?: instrument.title,
            version = "${form?.version ?: spec.formVersion} · Demo",
            sourceTitle = form?.sourceTitle ?: instrument.source,
            sourceUrl = form?.sourceUrl ?: instrument.referenceUrl.orEmpty(),
            sourceLocator = form?.sourceLocator ?: instrument.source,
            createdAtMillis = System.currentTimeMillis(),
            answered = form?.items?.size ?: scores.size.coerceAtLeast(1),
            itemCount = form?.items?.size ?: scores.size.coerceAtLeast(1),
            scores = scores,
            interpretation = interpretation,
            fromMarkedSheet = false,
            schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
            administrationMode = "prototype_demo_synthetic",
            scoringStatus = InstrumentImplementationStatus.DEMONSTRATION,
            validityStatus = AssessmentValidityStatus.VALID,
            validityWarnings = listOf("Data Demo Sintetis purwarupa penelitian — bukan hasil asesmen klien nyata."),
            reviewStatus = if (spec.requiresExaminerReview) ExaminerReviewStatus.PENDING else ExaminerReviewStatus.NOT_REQUIRED,
            scorerVersion = PsychometricInstrumentRegistry.SCORER_VERSION,
            normReference = spec.normStatus,
            visualizationType = spec.visualizationType,
            observations = observations,
            warteggDrafts = warteggDrafts,
            rorschachResponses = rorschachResponses,
            projectivePanels = projectivePanels,
            multimodalSummary = multimodalSummary
        )
        store(result)
    }

    suspend fun saveMarkedSheet(id: String, values: List<Int>): AssessmentResult = withContext(Dispatchers.IO) {
        val instrument = requireNotNull(catalog.find(id))
        val protocol = requireNotNull(instrument.protocol)
        val entry = requireNotNull(protocol.entry)
        val spec = PsychometricInstrumentRegistry.specFor(id)
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
        val scoringStatus = if (spec.requiresExaminerReview) {
            InstrumentImplementationStatus.EXAMINER_REVIEW_REQUIRED
        } else {
            spec.implementationStatus
        }
        val validityStatus = when (id) {
            "mmpi" -> AssessmentValidityStatus.PENDING
            else -> AssessmentValidityStatus.VALID
        }
        val warnings = emptyList<String>()
        val reviewStatus = if (spec.requiresExaminerReview) {
            ExaminerReviewStatus.PENDING
        } else {
            ExaminerReviewStatus.NOT_REQUIRED
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
                fromMarkedSheet = true,
                schemaVersion = PsychometricInstrumentRegistry.RESULT_SCHEMA_VERSION,
                administrationMode = "marked_sheet",
                scoringStatus = scoringStatus,
                validityStatus = validityStatus,
                validityWarnings = warnings,
                reviewStatus = reviewStatus,
                scorerVersion = PsychometricInstrumentRegistry.SCORER_VERSION,
                normReference = spec.normStatus,
                visualizationType = spec.visualizationType
            )
        )
    }

    suspend fun submitExaminerReview(
        instrumentId: String,
        reviewStatus: ExaminerReviewStatus,
        reviewerId: String,
        reviewNotes: String,
        examinerCodes: Map<String, String> = emptyMap(),
        validityOverride: AssessmentValidityStatus? = null,
        resultId: String? = null
    ): AssessmentResult? = withContext(Dispatchers.IO) {
        val existing = (if (!resultId.isNullOrBlank()) getResultById(resultId) else null)
            ?: getResult(instrumentId)
            ?: return@withContext null
        val updated = existing.copy(
            reviewStatus = reviewStatus,
            reviewerId = reviewerId.ifBlank { null },
            reviewNotes = reviewNotes.ifBlank { null },
            reviewedAtMillis = System.currentTimeMillis(),
            examinerCodes = existing.examinerCodes + examinerCodes,
            validityStatus = validityOverride ?: existing.validityStatus
        )
        store(updated)
    }

    suspend fun updateConsentFlags(
        instrumentId: String,
        aiContextAllowed: Boolean,
        researchExportAllowed: Boolean,
        resultId: String? = null
    ): AssessmentResult? = withContext(Dispatchers.IO) {
        val existing = (if (!resultId.isNullOrBlank()) getResultById(resultId) else null)
            ?: getResult(instrumentId)
            ?: return@withContext null
        store(existing.copy(aiContextAllowed = aiContextAllowed, researchExportAllowed = researchExportAllowed))
    }

    private fun enrichMarkedSheetInterpretation(protocol: AssessmentProtocol, scores: List<AssessmentScore>): String {
        val firstMeaning = scores.firstOrNull()?.meaning.orEmpty()
        return if (firstMeaning.isNotBlank() && firstMeaning != "Rekap lembar.") {
            "${scores.first().label}: ${scores.first().displayValue()}. $firstMeaning ${protocol.limitation}"
        } else {
            "${protocol.interpretation} ${protocol.limitation}"
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        resultDao().delete(id)
        resultDao().deleteSnapshotsForInstrument(id)
    }

    suspend fun deleteByResultId(resultId: String) = withContext(Dispatchers.IO) {
        val target = getResultById(resultId) ?: return@withContext
        resultDao().deleteSnapshot(resultId)
        val remainingLatest = resultDao().snapshotsForInstrument(target.instrumentId).firstOrNull()?.decode()
        if (remainingLatest != null) {
            resultDao().save(AssessmentResultEntity(target.instrumentId, gson.toJson(remainingLatest)))
        } else {
            resultDao().delete(target.instrumentId)
        }
    }

    private suspend fun store(result: AssessmentResult): AssessmentResult {
        val payload = gson.toJson(result)
        resultDao().save(AssessmentResultEntity(result.instrumentId, payload))
        resultDao().saveSnapshot(
            AssessmentResultSnapshotEntity(
                resultId = result.resultId,
                instrumentId = result.instrumentId,
                participantId = result.participantId,
                createdAtMillis = result.createdAtMillis,
                payloadJson = payload
            )
        )
        onResultPersisted?.let { callback ->
            runCatching { callback(result) }.onFailure {
                if (it is CancellationException) throw it
            }
        }
        return result
    }

    private fun AssessmentResultEntity.decode(): AssessmentResult =
        gson.fromJson(payloadJson, AssessmentResult::class.java).also { require(it.instrumentId == instrumentId) }

    private fun AssessmentResultSnapshotEntity.decode(): AssessmentResult =
        gson.fromJson(payloadJson, AssessmentResult::class.java).also { require(it.instrumentId == instrumentId) }
}
