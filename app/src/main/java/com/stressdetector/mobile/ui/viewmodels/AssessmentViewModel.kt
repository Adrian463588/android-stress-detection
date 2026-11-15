package com.stressdetector.mobile.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stressdetector.mobile.data.repository.AssessmentRepository
import com.stressdetector.mobile.di.AppLocator
import com.stressdetector.mobile.domain.assessment.AssessmentDiscussion
import com.stressdetector.mobile.domain.assessment.AssessmentInstrument
import com.stressdetector.mobile.domain.assessment.AssessmentResult
import com.stressdetector.mobile.domain.assessment.AssessmentScoring
import com.stressdetector.mobile.domain.assessment.CounselingKnowledgeModule
import com.stressdetector.mobile.domain.engine.AssetPromptAssetProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AssessmentUiState(
    val loading: Boolean = true,
    val instruments: List<AssessmentInstrument> = emptyList(),
    val instrument: AssessmentInstrument? = null,
    val result: AssessmentResult? = null,
    val counselingModules: List<CounselingKnowledgeModule> = emptyList(),
    val selectedModule: CounselingKnowledgeModule? = null,
    val selectedModuleContent: String? = null,
    val moduleExplanation: String? = null,
    val selectedTab: Int = 0,
    val selectedCategory: String = "Semua",
    val question: Int = 0,
    val answers: Map<Int, Int> = emptyMap(),
    val markedScores: Map<Int, String> = emptyMap(),
    val answering: Boolean = false,
    val isMarkedSheetEntry: Boolean = false,
    val busy: Boolean = false,
    val canScore: Boolean = false,
    val resultExplanation: String? = null,
    val guideExplanation: String? = null,
    val error: String? = null
)

@Suppress("TooManyFunctions")
internal class AssessmentViewModel(
    private val id: String?,
    private val repository: AssessmentRepository,
    private val discussion: AssessmentDiscussion
) : ViewModel() {
    private val state = MutableStateFlow(AssessmentUiState())
    val uiState = state.asStateFlow()

    init {
        reload()
    }

    fun reload() = work("Katalog asesmen belum dapat dibuka.") {
        val instruments = repository.instruments()
        val instrument = instruments.firstOrNull { it.id == id }
        val result = instrument?.let { repository.getResult(it.id) }
        val modules = repository.getCounselingModules()
        state.update {
            it.copy(
                instruments = instruments,
                instrument = instrument,
                result = result,
                counselingModules = modules
            )
        }
    }

    fun selectTab(tab: Int) {
        state.update { it.copy(selectedTab = tab) }
    }

    fun selectCategory(category: String) {
        state.update { it.copy(selectedCategory = category) }
    }

    fun openModule(module: CounselingKnowledgeModule?) {
        if (module == null) {
            state.update { it.copy(selectedModule = null, selectedModuleContent = null, moduleExplanation = null) }
            return
        }
        work("Gagal membaca modul konseling.") {
            val content = repository.readCounselingModule(module)
            state.update { it.copy(selectedModule = module, selectedModuleContent = content, moduleExplanation = null) }
        }
    }

    fun explainModule(module: CounselingKnowledgeModule) = work("Penjelasan modul belum tersedia.", showErrorMessage = true) {
        val content = state.value.selectedModuleContent ?: repository.readCounselingModule(module)
        val explanation = discussion.explain(module, content)
        state.update { it.copy(moduleExplanation = explanation) }
    }

    fun start() {
        if (state.value.instrument?.form != null) {
            startInteractiveForm()
        } else {
            startMarkedSheetEntry()
        }
    }

    fun startInteractiveForm() {
        state.update {
            it.copy(
                answering = true,
                isMarkedSheetEntry = false,
                answers = emptyMap(),
                markedScores = emptyMap(),
                question = 0,
                canScore = false,
                resultExplanation = null,
                error = null
            )
        }
    }

    fun startMarkedSheetEntry() {
        state.update {
            it.copy(
                answering = true,
                isMarkedSheetEntry = true,
                answers = emptyMap(),
                markedScores = emptyMap(),
                question = 0,
                canScore = false,
                resultExplanation = null,
                error = null
            )
        }
    }

    fun stop() {
        state.update {
            it.copy(
                answering = false,
                isMarkedSheetEntry = false,
                answers = emptyMap(),
                markedScores = emptyMap(),
                canScore = false,
                error = null
            )
        }
    }

    fun move(delta: Int) {
        state.update {
            val form = it.instrument?.form ?: return@update it
            it.copy(question = (it.question + delta).coerceIn(form.items.indices))
        }
    }

    fun answer(choice: Int?) {
        state.update {
            val form = it.instrument?.form ?: return@update it
            val answers = if (choice == null) it.answers - it.question else it.answers + (it.question to choice)
            it.copy(answers = answers, canScore = AssessmentScoring.canScore(form, answers))
        }
    }

    fun toggleMultiOption(choice: Int, maxSelections: Int = 2) {
        state.update { current ->
            val form = current.instrument?.form ?: return@update current
            val q = current.question
            val currentMask = current.answers[q] ?: 0
            val bit = 1 shl choice
            val newMask = if ((currentMask and bit) != 0) {
                currentMask and bit.inv()
            } else {
                val count = (0 until 30).count { b -> (currentMask and (1 shl b)) != 0 }
                if (count >= maxSelections) {
                    currentMask
                } else {
                    currentMask or bit
                }
            }
            val newAnswers = if (newMask == 0) current.answers - q else current.answers + (q to newMask)
            current.copy(answers = newAnswers, canScore = AssessmentScoring.canScore(form, newAnswers))
        }
    }

    fun answerDisc(statementIndex: Int, isMost: Boolean) {
        state.update { current ->
            val form = current.instrument?.form ?: return@update current
            val q = current.question
            val mostKey = q * 2
            val leastKey = q * 2 + 1
            val currentMost = current.answers[mostKey]
            val currentLeast = current.answers[leastKey]

            val newAnswers = current.answers.toMutableMap()
            if (isMost) {
                if (currentMost == statementIndex) {
                    newAnswers.remove(mostKey)
                } else {
                    if (currentLeast == statementIndex) {
                        newAnswers.remove(leastKey)
                    }
                    newAnswers[mostKey] = statementIndex
                }
            } else {
                if (currentLeast == statementIndex) {
                    newAnswers.remove(leastKey)
                } else {
                    if (currentMost == statementIndex) {
                        newAnswers.remove(mostKey)
                    }
                    newAnswers[leastKey] = statementIndex
                }
            }
            current.copy(
                answers = newAnswers,
                canScore = AssessmentScoring.canScore(form, newAnswers)
            )
        }
    }

    fun answerTriad(statementIndex: Int, isMost: Boolean) = answerDisc(statementIndex, isMost)

    fun submitPauliAll(results: Map<Int, Triple<Int, Int, Int>>) = submitKraepelinAll(results)

    fun submitKraepelinAll(results: Map<Int, Triple<Int, Int, Int>>) {
        state.update { current ->
            val form = current.instrument?.form ?: return@update current
            val newAnswers = current.answers.toMutableMap()
            results.forEach { (col, triple) ->
                newAnswers[col] = triple.first
                newAnswers[100 + col] = triple.second
                newAnswers[200 + col] = triple.third
            }
            current.copy(
                answers = newAnswers,
                canScore = AssessmentScoring.canScore(form, newAnswers)
            )
        }
        submit()
    }

    fun submitRmibAll(rankings: Map<Int, Int>) {
        state.update { current ->
            val form = current.instrument?.form ?: return@update current
            val newAnswers = current.answers.toMutableMap()
            newAnswers.putAll(rankings)
            current.copy(
                answers = newAnswers,
                canScore = AssessmentScoring.canScore(form, newAnswers)
            )
        }
        submit()
    }

    fun submitWarteggAll(boxData: Map<Int, Int>) {
        state.update { current ->
            val form = current.instrument?.form ?: return@update current
            val newAnswers = current.answers.toMutableMap()
            newAnswers.putAll(boxData)
            current.copy(
                answers = newAnswers,
                canScore = AssessmentScoring.canScore(form, newAnswers)
            )
        }
        submit()
    }

    fun submitRorschachAll(cardData: Map<Int, Int>) {
        state.update { current ->
            val form = current.instrument?.form ?: return@update current
            val newAnswers = current.answers.toMutableMap()
            newAnswers.putAll(cardData)
            current.copy(
                answers = newAnswers,
                canScore = AssessmentScoring.canScore(form, newAnswers)
            )
        }
        submit()
    }

    fun jumpTo(index: Int) {
        state.update { current ->
            val form = current.instrument?.form ?: return@update current
            current.copy(question = index.coerceIn(form.items.indices))
        }
    }

    fun enterMarkedScore(index: Int, value: String) {
        state.update {
            val entry = it.instrument?.protocol?.entry ?: return@update it
            val field = entry.fields[index]
            if (value.length > field.maximum.toString().length || value.any { !it.isDigit() }) return@update it
            val scores = it.markedScores + (index to value)
            val parsed = entry.fields.indices.mapNotNull { field -> scores[field]?.toIntOrNull() }
            it.copy(markedScores = scores, canScore = entry.accepts(parsed))
        }
    }

    fun submit() = work("Hasil belum tersimpan. Coba lagi.") {
        val current = state.value
        val instrument = requireNotNull(current.instrument)
        val entry = instrument.protocol?.entry
        val result = if (!current.isMarkedSheetEntry && instrument.form != null) {
            repository.save(instrument.id, current.answers)
        } else if (entry != null) {
            val scores = entry.fields.indices.map { current.markedScores.getValue(it).toInt() }
            repository.saveMarkedSheet(instrument.id, scores)
        } else {
            error("Metode pengisian asesmen tidak valid.")
        }
        state.update {
            it.copy(
                result = result,
                answering = false,
                isMarkedSheetEntry = false,
                answers = emptyMap(),
                markedScores = emptyMap(),
                canScore = false
            )
        }
    }

    fun delete() = work("Hasil belum terhapus. Coba lagi.") {
        repository.delete(requireNotNull(id))
        state.update { it.copy(result = null, resultExplanation = null) }
    }

    fun explain() = work("Penjelasan AI belum tersedia.", showErrorMessage = true) {
        val result = repository.getResult(requireNotNull(id)) ?: error("Hasil asesmen sudah tidak tersedia.")
        check(result.resultId == state.value.result?.resultId) { "Hasil berubah. Buka ulang asesmen." }
        val explanation = discussion.explain(result)
        check(repository.getResult(id)?.resultId == result.resultId) { "Hasil berubah saat penjelasan dibuat." }
        state.update { it.copy(resultExplanation = explanation) }
    }

    fun explainGuide() = work("Penjelasan panduan belum tersedia.", showErrorMessage = true) {
        val instrument = requireNotNull(state.value.instrument)
        val explanation = discussion.explain(instrument)
        state.update { it.copy(guideExplanation = explanation) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun work(message: String, showErrorMessage: Boolean = false, action: suspend () -> Unit) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalStateException) {
                state.update {
                    it.copy(error = if (showErrorMessage) error.message ?: message else message)
                }
            } catch (_: Exception) {
                state.update { it.copy(error = message) }
            } finally {
                state.update { it.copy(loading = false, busy = false) }
            }
        }
    }

    class Factory(context: Context, private val id: String?) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AssessmentViewModel(
            id,
            AppLocator.assessmentRepository(appContext),
            AssessmentDiscussion(
                AppLocator.llmEngine(appContext),
                AssetPromptAssetProvider(appContext)
            ) { AppLocator.database(appContext).consentDao().getConsent("AI_COUNSELING")?.isGranted == true }
        ) as T
    }
}
