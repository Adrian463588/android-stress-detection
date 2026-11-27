package com.stressdetector.mobile.domain.assessment

import com.stressdetector.mobile.domain.engine.CitationRegistry
import com.stressdetector.mobile.domain.engine.ConservativeGemmaTokenCounter
import com.stressdetector.mobile.domain.engine.CounselingInteractionMode
import com.stressdetector.mobile.domain.engine.CounselorGenerationRequest
import com.stressdetector.mobile.domain.engine.CounselorOutputGuard
import com.stressdetector.mobile.domain.engine.CounselorPromptBuilder
import com.stressdetector.mobile.domain.engine.GenerationTextAssembler
import com.stressdetector.mobile.domain.engine.GuardedCounselorOutput
import com.stressdetector.mobile.domain.engine.KnowledgeCitation
import com.stressdetector.mobile.domain.engine.LlmGenerationState
import com.stressdetector.mobile.domain.engine.LlmRuntimeReadiness
import com.stressdetector.mobile.domain.engine.OnDeviceLlmEngine
import com.stressdetector.mobile.domain.engine.PromptAssetProvider
import com.stressdetector.mobile.domain.engine.PromptBudget
import com.stressdetector.mobile.domain.engine.RagMode

private const val MODULE_SNIPPET_LENGTH = 280

internal class AssessmentDiscussion(
    private val engine: OnDeviceLlmEngine,
    private val prompts: PromptAssetProvider,
    private val consent: suspend () -> Boolean
) {
    suspend fun explain(instrument: AssessmentInstrument): String {
        val protocol = requireNotNull(instrument.protocol)
        return explain(
            instrument.id,
            instrument.title,
            "${instrument.source} · §${protocol.section}",
            assessmentGuideEvidence(protocol),
            "Terangkan cara hitung dan batas panduan ini dalam satu paragraf. Jangan menilai pengguna."
        )
    }

    suspend fun explain(result: AssessmentResult): String = explain(
        result.instrumentId,
        result.title,
        result.sourceLocator,
        assessmentEvidence(result),
        "Jelaskan dalam satu kalimat arti skor pada data hasil ini. Sebutkan nama instrumen. Jangan mengulang angka skor."
    )

    suspend fun explain(module: CounselingKnowledgeModule, contentSnippet: String): String = explain(
        module.id,
        module.title,
        "Bank Materi Konseling · ${module.pillar}",
        boundedPassages(listOf("Modul ${module.title}.", module.summary, contentSnippet.take(MODULE_SNIPPET_LENGTH))),
        "Jelaskan prinsip inti dan cara penerapan praktis modul konseling ini dalam satu paragraf ringkas."
    )

    suspend fun analyzeProjective(
        instrumentId: String,
        instrumentTitle: String,
        panels: List<ProjectivePanelDraft>,
        verbalNotes: List<String> = emptyList(),
        renderedImagesPng: List<ByteArray> = emptyList()
    ): ProjectiveMultimodalReport {
        val activeEngine = if (consent()) engine else null
        return ProjectiveMultimodalAnalyzer(activeEngine).analyze(
            instrumentId = instrumentId,
            instrumentTitle = instrumentTitle,
            panels = panels,
            verbalNotes = verbalNotes,
            renderedImagesPng = renderedImagesPng
        )
    }

    private suspend fun explain(instrumentId: String, title: String, locator: String, evidence: List<String>, question: String): String {
        check(consent()) { "Aktifkan consent AI Counselor di Profile." }
        val readiness = engine.preflight()
        check(readiness is LlmRuntimeReadiness.Ready) {
            (readiness as? LlmRuntimeReadiness.Unavailable)?.userMessage ?: "Model Gemma belum siap."
        }
        val sources = evidence.mapIndexed { index, passage ->
            KnowledgeCitation(
                citationId = "ref-${index + 1}",
                sourceTitle = title,
                section = locator,
                text = passage,
                relevanceScore = 1f,
                sourceLocator = locator,
                documentId = instrumentId
            )
        }
        val sourceRegistry = CitationRegistry.fromIncludedEvidence(local = sources, web = emptyList())
        val request = CounselorGenerationRequest(
            promptAsset = prompts.getCounselorPrompt().copy(
                systemPrompt = "Jelaskan panduan atau hasil asesmen lokal secara deskriptif dalam bahasa Indonesia.",
                scopeInstruction = "Diagnosis, estimasi IQ dan klasifikasi normatif di luar cakupan.",
                userPromptTemplate = "Instrumen $title. ${evidence.first()}\n\n{user_message}",
                groundedInstruction = "Sumber ditampilkan oleh aplikasi. Tulis isi penjelasan tanpa menyalin blok sumber. " +
                    "Gunakan hanya evidence dan ikuti batas interpretasinya."
            ),
            userMessage = question,
            ragMode = RagMode.GROUNDED,
            retrievedCitations = sources,
            stressHistoryContext = "",
            citationRegistry = sourceRegistry,
            interactionMode = CounselingInteractionMode.ASSESSMENT_INTERPRETATION
        )
        val registry = CounselorPromptBuilder().prepare(request).citationRegistry
        check(registry.bindings.size == sources.size) { "Rujukan $title melebihi kapasitas prompt lokal." }
        val output = GenerationTextAssembler()
        engine.generate(request).collect { update ->
            check(consent()) { "Consent AI Counselor dicabut." }
            when (update) {
                is LlmGenerationState.Content -> output.append(update)
                else -> error("Gemma belum dapat membahas hasil. Coba lagi.")
            }
        }
        check(consent()) { "Consent AI Counselor dicabut." }
        val answer = output.finish()
        check(answer.text.isNotBlank()) { "Gemma belum menyelesaikan penjelasan. Coba lagi." }
        val explanation = registry.bindings.fold(answer.text) { outputText, binding ->
            outputText.replace(binding.marker, "")
        }.trim()
        // All passages come from the single source already shown by the app.
        val guarded = CounselorOutputGuard.Default.validate(explanation, RagMode.GENERAL_SAFE, emptyList())
        check(guarded is GuardedCounselorOutput.Accepted) { "Gemma belum dapat menjelaskan asesmen secara aman." }
        check(!guarded.text.contains("mohon berikan", ignoreCase = true)) { "Gemma belum merujuk pada data asesmen yang tersedia." }
        return if (answer.incomplete) "${guarded.text}\n\nPenjelasan berhenti sebelum selesai." else guarded.text
    }
}

internal fun assessmentGuideEvidence(protocol: AssessmentProtocol): List<String> = boundedPassages(
    listOf("Edisi ${protocol.edition}.", protocol.method, protocol.interpretation, protocol.limitation)
)

internal fun assessmentEvidence(result: AssessmentResult): List<String> {
    val input = if (result.fromMarkedSheet) "rekap lembar yang dimasukkan pengguna" else "jawaban di aplikasi"
    val scores = result.scores.groupBy(AssessmentScore::displayRange).flatMap { (range, group) ->
        listOf("Skala $range:") + group.map { "${it.label} ${it.displayValue()};" }
    }
    return boundedPassages(listOf("Edisi ${result.version}. Asal: $input.", result.interpretation) + scores)
}

private fun boundedPassages(parts: List<String>): List<String> {
    val budget = PromptBudget()
    val passages = mutableListOf<String>()
    var passage = ""
    parts.forEach { rawPart ->
        val part = if (ConservativeGemmaTokenCounter.count(rawPart) <= budget.maxCitationTokens) {
            rawPart
        } else {
            ConservativeGemmaTokenCounter.take(rawPart, budget.maxCitationTokens)
        }
        val combined = listOf(passage, part).filter(String::isNotBlank).joinToString(" ")
        if (ConservativeGemmaTokenCounter.count(combined) > budget.maxCitationTokens) {
            if (passages.size + 1 < budget.maxCitationCount) {
                passages += passage
                passage = part
            }
        } else {
            passage = combined
        }
    }
    if (passage.isNotBlank()) passages += passage
    check(passages.size <= budget.maxCitationCount)
    return passages
}
