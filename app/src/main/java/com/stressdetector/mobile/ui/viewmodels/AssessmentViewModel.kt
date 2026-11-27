package com.stressdetector.mobile.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stressdetector.mobile.data.repository.AssessmentRepository
import com.stressdetector.mobile.di.AppLocator
import com.stressdetector.mobile.domain.assessment.AssessmentDiscussion
import com.stressdetector.mobile.domain.assessment.AssessmentEnrichers
import com.stressdetector.mobile.domain.assessment.AssessmentForm
import com.stressdetector.mobile.domain.assessment.AssessmentInstrument
import com.stressdetector.mobile.domain.assessment.AssessmentResult
import com.stressdetector.mobile.domain.assessment.AssessmentScoring
import com.stressdetector.mobile.domain.assessment.AssessmentValidityStatus
import com.stressdetector.mobile.domain.assessment.CounselingKnowledgeModule
import com.stressdetector.mobile.domain.assessment.ExaminerReviewStatus
import com.stressdetector.mobile.domain.assessment.InstrumentImplementationStatus
import com.stressdetector.mobile.domain.assessment.ProjectiveImageRenderer
import com.stressdetector.mobile.domain.assessment.ProjectivePanelDraft
import com.stressdetector.mobile.domain.assessment.PsychometricInstrumentRegistry
import com.stressdetector.mobile.domain.assessment.RorschachResponseDraft
import com.stressdetector.mobile.domain.assessment.WarteggBoxDraft
import com.stressdetector.mobile.domain.assessment.WarteggNormalizedPoint
import com.stressdetector.mobile.domain.engine.AssetPromptAssetProvider
import com.stressdetector.mobile.domain.search.SearchCategory
import com.stressdetector.mobile.domain.search.SearchHistoryEntry
import com.stressdetector.mobile.domain.search.SearchRepository
import com.stressdetector.mobile.domain.search.SearchSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AssessmentUiState(
    val loading: Boolean = true,
    val instruments: List<AssessmentInstrument> = emptyList(),
    val filteredInstruments: List<AssessmentInstrument> = emptyList(),
    val instrument: AssessmentInstrument? = null,
    val result: AssessmentResult? = null,
    val counselingModules: List<CounselingKnowledgeModule> = emptyList(),
    val filteredCounselingModules: List<CounselingKnowledgeModule> = emptyList(),
    val selectedModule: CounselingKnowledgeModule? = null,
    val selectedModuleContent: String? = null,
    val moduleExplanation: String? = null,
    val selectedTab: Int = 0,
    val activeSearchCategory: SearchCategory = SearchCategory.ASSESSMENT,
    val selectedCategory: String = "Semua",
    val selectedCounselingCategory: String = "Semua",
    val searchQuery: String = "",
    val isSearchFocused: Boolean = false,
    val suggestions: List<SearchSuggestion> = emptyList(),
    val showSuggestions: Boolean = false,
    val recentSearches: List<SearchHistoryEntry> = emptyList(),
    val showClearHistoryConfirmation: Boolean = false,
    val searchLoading: Boolean = false,
    val question: Int = 0,
    val answers: Map<Int, Int> = emptyMap(),
    val markedScores: Map<Int, String> = emptyMap(),
    val answering: Boolean = false,
    val isMarkedSheetEntry: Boolean = false,
    val busy: Boolean = false,
    val canScore: Boolean = false,
    val resultExplanation: String? = null,
    val guideExplanation: String? = null,
    val savedResults: List<AssessmentResult> = emptyList(),
    val pendingResultNavigation: Pair<String, String>? = null,
    val error: String? = null
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
internal class AssessmentViewModel(
    private val id: String?,
    private val repository: AssessmentRepository,
    private val discussion: AssessmentDiscussion,
    private val searchRepository: SearchRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val resultId: String? = null
) : ViewModel() {
    private data class SearchDispatch(
        val query: String,
        val category: SearchCategory,
        val assessmentCategoryFilter: String,
        val counselingCategoryFilter: String,
        val allowSuggestions: Boolean,
        val immediate: Boolean
    )

    private val state = MutableStateFlow(AssessmentUiState())
    val uiState = state.asStateFlow()

    private val activeCategoryFlow = MutableStateFlow(SearchCategory.ASSESSMENT)
    private val queriesByCategory = mutableMapOf(
        SearchCategory.ASSESSMENT to "",
        SearchCategory.COUNSELING_MATERIAL to ""
    )
    private val searchDispatchFlow = MutableStateFlow(
        SearchDispatch(
            query = "",
            category = SearchCategory.ASSESSMENT,
            assessmentCategoryFilter = "Semua",
            counselingCategoryFilter = "Semua",
            allowSuggestions = false,
            immediate = true
        )
    )

    init {
        observeSearchHistory()
        observeSearchQueries()
        reload()
    }

    private fun observeSearchHistory() {
        viewModelScope.launch {
            activeCategoryFlow
                .flatMapLatest { category -> searchRepository.observeRecentSearches(category) }
                .collect { entries ->
                    state.update { it.copy(recentSearches = entries) }
                }
        }
    }

    private fun observeSearchQueries() {
        viewModelScope.launch {
            searchDispatchFlow
                .debounce { dispatch ->
                    if (dispatch.immediate || dispatch.query.isBlank()) 0L else SEARCH_DEBOUNCE_MS
                }
                .distinctUntilChanged()
                .collectLatest { dispatch ->
                    executeSearch(dispatch)
                }
        }
    }

    fun reload() = work("Katalog asesmen belum dapat dibuka.") {
        val instruments = repository.instruments()
        val instrument = instruments.firstOrNull { it.id == id }
        val result = when {
            !resultId.isNullOrBlank() -> repository.getResultById(resultId)
            instrument != null -> repository.getResult(instrument.id)
            else -> null
        }
        val allSaved = runCatching { repository.getAllResults() }.getOrNull() ?: emptyList()
        val modules = repository.getCounselingModules()
        state.update { current ->
            current.copy(
                instruments = instruments,
                filteredInstruments = filterInstrumentsByCategory(instruments, current.selectedCategory),
                instrument = instrument,
                result = result,
                savedResults = allSaved,
                counselingModules = modules,
                filteredCounselingModules = filterCounselingModules(modules, current.selectedCounselingCategory),
                error = if (!resultId.isNullOrBlank() && result == null) {
                    "Hasil asesmen ($resultId) tidak ditemukan."
                } else {
                    current.error
                }
            )
        }
        val active = state.value
        if (active.searchQuery.isNotBlank()) {
            dispatchSearch(
                query = active.searchQuery,
                category = active.activeSearchCategory,
                allowSuggestions = active.isSearchFocused,
                immediate = true
            )
        }
    }

    fun onSearchQueryChange(query: String) {
        val category = state.value.activeSearchCategory
        queriesByCategory[category] = query
        state.update {
            it.copy(
                searchQuery = query,
                showSuggestions = query.isNotBlank() && it.isSearchFocused,
                searchLoading = query.isNotBlank()
            )
        }
        dispatchSearch(
            query = query,
            category = category,
            allowSuggestions = state.value.isSearchFocused && query.isNotBlank(),
            immediate = false
        )
    }

    fun onSearchFocusChange(focused: Boolean) {
        state.update { current ->
            current.copy(
                isSearchFocused = focused,
                showSuggestions = focused && current.searchQuery.isNotBlank() && current.suggestions.isNotEmpty()
            )
        }
        if (focused && state.value.searchQuery.isNotBlank()) {
            dispatchSearch(
                query = state.value.searchQuery,
                category = state.value.activeSearchCategory,
                allowSuggestions = true,
                immediate = true
            )
        }
    }

    fun onSelectSuggestion(suggestion: SearchSuggestion) {
        val canonicalTitle = suggestion.canonicalTitle
        queriesByCategory[suggestion.category] = canonicalTitle
        state.update {
            it.copy(
                searchQuery = canonicalTitle,
                suggestions = emptyList(),
                showSuggestions = false,
                isSearchFocused = false,
                searchLoading = false
            )
        }
        dispatchSearch(
            query = canonicalTitle,
            category = suggestion.category,
            allowSuggestions = false,
            immediate = true
        )
        viewModelScope.launch {
            searchRepository.saveSearch(
                SearchHistoryEntry(
                    canonicalItemId = suggestion.canonicalItemId,
                    canonicalTitle = canonicalTitle,
                    category = suggestion.category,
                    lastSearchedAt = clock()
                )
            )
        }
    }

    fun onCommitSearch(query: String = state.value.searchQuery) {
        val trimmed = query.trim()
        val category = state.value.activeSearchCategory
        queriesByCategory[category] = trimmed
        state.update {
            it.copy(
                searchQuery = trimmed,
                showSuggestions = false,
                suggestions = emptyList()
            )
        }
        dispatchSearch(
            query = trimmed,
            category = category,
            allowSuggestions = false,
            immediate = true
        )
        if (trimmed.isNotBlank()) {
            viewModelScope.launch {
                val topMatch = searchRepository.search(trimmed, category).firstOrNull() ?: return@launch
                searchRepository.saveSearch(
                    SearchHistoryEntry(
                        canonicalItemId = topMatch.item.id,
                        canonicalTitle = topMatch.item.title,
                        category = category,
                        lastSearchedAt = clock()
                    )
                )
            }
        }
    }

    fun onSelectHistoryChip(entry: SearchHistoryEntry) {
        val title = entry.canonicalTitle
        queriesByCategory[entry.category] = title
        state.update {
            it.copy(
                searchQuery = title,
                showSuggestions = false,
                suggestions = emptyList(),
                isSearchFocused = false
            )
        }
        dispatchSearch(
            query = title,
            category = entry.category,
            allowSuggestions = false,
            immediate = true
        )
        viewModelScope.launch {
            searchRepository.saveSearch(entry.copy(lastSearchedAt = clock()))
        }
    }

    fun onRemoveHistoryChip(entry: SearchHistoryEntry) {
        viewModelScope.launch {
            searchRepository.removeSearch(entry.canonicalItemId, entry.category)
        }
    }

    fun onRequestClearHistory() {
        state.update { it.copy(showClearHistoryConfirmation = true) }
    }

    fun onDismissClearHistory() {
        state.update { it.copy(showClearHistoryConfirmation = false) }
    }

    fun onConfirmClearHistory() {
        val category = state.value.activeSearchCategory
        state.update { it.copy(showClearHistoryConfirmation = false) }
        viewModelScope.launch {
            searchRepository.clearHistory(category)
        }
    }

    fun onClearSearchQuery() {
        val category = state.value.activeSearchCategory
        queriesByCategory[category] = ""
        state.update {
            it.copy(
                searchQuery = "",
                suggestions = emptyList(),
                showSuggestions = false,
                searchLoading = false
            )
        }
        dispatchSearch(
            query = "",
            category = category,
            allowSuggestions = false,
            immediate = true
        )
    }

    fun recordItemOpened(itemId: String, itemTitle: String) {
        val current = state.value
        if (current.searchQuery.isBlank()) return
        val category = current.activeSearchCategory
        viewModelScope.launch {
            searchRepository.saveSearch(
                SearchHistoryEntry(
                    canonicalItemId = itemId,
                    canonicalTitle = itemTitle,
                    category = category,
                    lastSearchedAt = clock()
                )
            )
        }
    }

    fun selectTab(tab: Int) {
        val category = if (tab == 0) SearchCategory.ASSESSMENT else SearchCategory.COUNSELING_MATERIAL
        val tabQuery = queriesByCategory[category].orEmpty()
        activeCategoryFlow.value = category
        state.update {
            it.copy(
                selectedTab = tab,
                activeSearchCategory = category,
                searchQuery = tabQuery,
                suggestions = emptyList(),
                showSuggestions = false,
                isSearchFocused = false
            )
        }
        dispatchSearch(
            query = tabQuery,
            category = category,
            allowSuggestions = false,
            immediate = true
        )
    }

    fun selectCategory(category: String) {
        state.update { it.copy(selectedCategory = category) }
        dispatchSearch(
            query = state.value.searchQuery,
            category = SearchCategory.ASSESSMENT,
            allowSuggestions = state.value.showSuggestions,
            immediate = true
        )
    }

    fun selectCounselingCategory(category: String) {
        state.update { it.copy(selectedCounselingCategory = category) }
        dispatchSearch(
            query = state.value.searchQuery,
            category = SearchCategory.COUNSELING_MATERIAL,
            allowSuggestions = state.value.showSuggestions,
            immediate = true
        )
    }

    private fun dispatchSearch(query: String, category: SearchCategory, allowSuggestions: Boolean, immediate: Boolean) {
        searchDispatchFlow.value = SearchDispatch(
            query = query,
            category = category,
            assessmentCategoryFilter = state.value.selectedCategory,
            counselingCategoryFilter = state.value.selectedCounselingCategory,
            allowSuggestions = allowSuggestions,
            immediate = immediate
        )
    }

    private suspend fun executeSearch(dispatch: SearchDispatch) {
        if (dispatch.query.isBlank()) {
            state.update { latest ->
                if (latest.searchQuery.isNotBlank() || latest.activeSearchCategory != dispatch.category) {
                    return@update latest
                }
                when (dispatch.category) {
                    SearchCategory.ASSESSMENT -> latest.copy(
                        filteredInstruments = filterInstrumentsByCategory(
                            latest.instruments,
                            dispatch.assessmentCategoryFilter
                        ),
                        suggestions = emptyList(),
                        showSuggestions = false,
                        searchLoading = false
                    )
                    SearchCategory.COUNSELING_MATERIAL -> latest.copy(
                        filteredCounselingModules = filterCounselingModules(
                            latest.counselingModules,
                            dispatch.counselingCategoryFilter
                        ),
                        suggestions = emptyList(),
                        showSuggestions = false,
                        searchLoading = false
                    )
                }
            }
            return
        }

        val rankedResults = searchRepository.search(dispatch.query, dispatch.category)
        val suggestions = if (dispatch.allowSuggestions) {
            searchRepository.getSuggestions(dispatch.query, dispatch.category)
        } else {
            emptyList()
        }

        state.update { latest ->
            if (latest.searchQuery != dispatch.query || latest.activeSearchCategory != dispatch.category) {
                return@update latest
            }
            when (dispatch.category) {
                SearchCategory.ASSESSMENT -> {
                    val instrumentById = latest.instruments.associateBy { it.id }
                    val rankedInstruments = rankedResults.mapNotNull { instrumentById[it.item.id] }
                    val inCategory = filterInstrumentsByCategory(rankedInstruments, dispatch.assessmentCategoryFilter)
                    val finalFiltered = if (inCategory.isNotEmpty() || dispatch.assessmentCategoryFilter == "Semua") {
                        inCategory
                    } else {
                        rankedInstruments
                    }
                    latest.copy(
                        filteredInstruments = finalFiltered,
                        suggestions = suggestions,
                        showSuggestions = dispatch.allowSuggestions && suggestions.isNotEmpty() && latest.isSearchFocused,
                        searchLoading = false
                    )
                }
                SearchCategory.COUNSELING_MATERIAL -> {
                    val moduleById = latest.counselingModules.associateBy { it.id }
                    val rankedModules = rankedResults.mapNotNull { moduleById[it.item.id] }
                    val inCategory = filterCounselingModules(rankedModules, dispatch.counselingCategoryFilter)
                    val finalModules = if (inCategory.isNotEmpty() || dispatch.counselingCategoryFilter == "Semua") {
                        inCategory
                    } else {
                        rankedModules
                    }
                    latest.copy(
                        filteredCounselingModules = finalModules,
                        suggestions = suggestions,
                        showSuggestions = dispatch.allowSuggestions && suggestions.isNotEmpty() && latest.isSearchFocused,
                        searchLoading = false
                    )
                }
            }
        }
    }

    private fun filterCounselingModules(
        modules: List<CounselingKnowledgeModule>,
        category: String
    ): List<CounselingKnowledgeModule> = if (category == "Semua") {
        modules
    } else {
        modules.filter { it.category.equals(category, ignoreCase = true) }
    }

    private fun filterInstrumentsByCategory(instruments: List<AssessmentInstrument>, category: String): List<AssessmentInstrument> =
        when (category) {
            "Semua" -> instruments
            "Kognitif & IQ" -> instruments.filter { it.category in setOf("Kognitif", "Kompetensi") }
            "Kepribadian" -> instruments.filter {
                it.category in setOf("Kepribadian", "Kepribadian kerja", "Sikap kerja")
            }
            "Minat Karier" -> instruments.filter { it.category == "Minat" }
            "Skrining & Klinis" -> instruments.filter {
                it.category in setOf("Skrining", "Klinis", "Kelelahan")
            }
            "Refleksi" -> instruments.filter { it.category in setOf("Refleksi", "Proyektif") }
            else -> instruments.filter { it.category.contains(category, ignoreCase = true) }
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

    fun submitArmyAlphaAll(itemData: Map<Int, Int>) {
        state.update { current ->
            val form = current.instrument?.form ?: return@update current
            val newAnswers = current.answers.toMutableMap()
            newAnswers.putAll(itemData)
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
        val allSaved = runCatching { repository.getAllResults() }.getOrNull() ?: emptyList()
        state.update {
            it.copy(
                result = result,
                savedResults = allSaved,
                answering = false,
                isMarkedSheetEntry = false,
                answers = emptyMap(),
                markedScores = emptyMap(),
                canScore = false,
                pendingResultNavigation = result.instrumentId to result.resultId
            )
        }
    }

    fun submitWarteggDrafts(drafts: List<WarteggBoxDraft>) = work("Hasil Wartegg belum tersimpan. Coba lagi.") {
        val panels = drafts.map { box ->
            ProjectivePanelDraft(
                panelIndex = box.boxNumber - 1,
                panelLabel = "Kotak ${box.boxNumber}",
                strokes = box.strokes,
                title = box.title,
                notes = listOfNotNull(box.valence, box.difficulty).joinToString(" · "),
                drawOrder = box.drawOrder,
                durationSeconds = box.durationSeconds
            )
        }
        val renderedPng = ProjectiveImageRenderer.renderWarteggGridPng(drafts)
        val report = discussion.analyzeProjective(
            instrumentId = "wartegg",
            instrumentTitle = "Wartegg",
            panels = panels,
            verbalNotes = drafts.mapNotNull { it.title.takeIf(String::isNotBlank) },
            renderedImagesPng = listOfNotNull(renderedPng)
        )
        val result = repository.saveWarteggDrafts(drafts, report.summary)
        val allSaved = runCatching { repository.getAllResults() }.getOrNull() ?: emptyList()
        state.update {
            it.copy(
                result = result,
                savedResults = allSaved,
                answering = false,
                isMarkedSheetEntry = false,
                answers = emptyMap(),
                markedScores = emptyMap(),
                canScore = false,
                pendingResultNavigation = result.instrumentId to result.resultId
            )
        }
    }

    fun submitProjectivePanels(panels: List<ProjectivePanelDraft>) = work("Hasil proyektif belum tersimpan. Coba lagi.") {
        val instrument = requireNotNull(state.value.instrument)
        val renderedPng = ProjectiveImageRenderer.renderPanelsGridPng(panels)
        val report = discussion.analyzeProjective(
            instrumentId = instrument.id,
            instrumentTitle = instrument.title,
            panels = panels,
            verbalNotes = panels.mapNotNull { p ->
                listOfNotNull(p.title.takeIf(String::isNotBlank), p.notes.takeIf(String::isNotBlank))
                    .joinToString(": ").takeIf(String::isNotBlank)
            },
            renderedImagesPng = listOfNotNull(renderedPng)
        )
        val result = repository.saveProjectivePanels(instrument.id, panels, report.summary)
        val allSaved = runCatching { repository.getAllResults() }.getOrNull() ?: emptyList()
        state.update {
            it.copy(
                result = result,
                savedResults = allSaved,
                answering = false,
                isMarkedSheetEntry = false,
                answers = emptyMap(),
                markedScores = emptyMap(),
                canScore = false,
                pendingResultNavigation = result.instrumentId to result.resultId
            )
        }
    }

    fun submitRorschachResponses(
        responses: List<RorschachResponseDraft>,
        systemId: String
    ) = work("Protokol Rorschach belum tersimpan. Coba lagi.") {
        val verbalSummary = responses.map { r ->
            "Kartu ${r.cardNumber}: ${r.freeAssociation.ifBlank { r.location.orEmpty() }}" +
                (r.inquiry.takeIf(String::isNotBlank)?.let { " ($it)" } ?: "")
        }
        val report = discussion.analyzeProjective(
            instrumentId = "rorschach",
            instrumentTitle = "Tes Rorschach ($systemId)",
            panels = emptyList(),
            verbalNotes = verbalSummary,
            renderedImagesPng = emptyList()
        )
        val result = repository.saveRorschachResponses(responses, systemId, report.summary)
        val allSaved = runCatching { repository.getAllResults() }.getOrNull() ?: emptyList()
        state.update {
            it.copy(
                result = result,
                savedResults = allSaved,
                answering = false,
                isMarkedSheetEntry = false,
                answers = emptyMap(),
                markedScores = emptyMap(),
                canScore = false,
                pendingResultNavigation = result.instrumentId to result.resultId
            )
        }
    }

    fun runPrototypeDemo() = work("Simulasi demo purwarupa belum tersimpan.") {
        val instrument = requireNotNull(state.value.instrument)
        val saved = when (instrument.id) {
            "wartegg" -> {
                val demoDrafts = (1..8).map { box ->
                    WarteggBoxDraft(
                        boxNumber = box,
                        strokes = listOf(
                            listOf(
                                WarteggNormalizedPoint(0.25f, 0.3f, 0.55f, 1000L + box * 500L, true),
                                WarteggNormalizedPoint(0.75f, 0.7f, 0.68f, 1400L + box * 500L, true)
                            )
                        ),
                        title = "Demo Gambar $box",
                        drawOrder = box,
                        durationSeconds = 12 + box * 2,
                        valence = if (box % 2 == 1) "+" else "-",
                        difficulty = if (box <= 4) "M" else "S"
                    )
                }
                val outcome = AssessmentEnrichers.scoreWarteggFromDrafts(
                    demoDrafts,
                    "Data Demo Sintetis Wartegg: 8 kotak simulasi beserta telemetri stylus sintetis."
                )
                repository.savePrototypeDemoResult(
                    id = instrument.id,
                    scores = outcome.scores,
                    interpretation = outcome.interpretation,
                    observations = outcome.observations,
                    warteggDrafts = demoDrafts,
                    multimodalSummary = outcome.multimodalSummary
                )
            }
            "rorschach" -> {
                val demoResp = listOf(
                    RorschachResponseDraft(1, 1, "Kelelawar hitam bersayap", "Bentuk keseluruhan simetris", location = "W", determinant = "F", formQuality = "FQo", popular = true),
                    RorschachResponseDraft(2, 1, "Dua orang bersalaman", "Terlihat seperti dua figur", location = "W", determinant = "M", formQuality = "FQo", popular = true),
                    RorschachResponseDraft(3, 1, "Bunga merah di tengah", "Karena warnanya merah cerah", location = "D", determinant = "FC", formQuality = "FQo", popular = false)
                )
                val outcome = AssessmentEnrichers.scoreRorschachFromDrafts(
                    demoResp,
                    "EXNER_CS",
                    "Data Demo Sintetis Rorschach: 3 respons sampel untuk menguji tampilan protokol & AI+."
                )
                repository.savePrototypeDemoResult(
                    id = instrument.id,
                    scores = outcome.scores,
                    interpretation = outcome.interpretation,
                    observations = outcome.observations,
                    rorschachResponses = demoResp,
                    multimodalSummary = outcome.multimodalSummary
                )
            }
            "htp", "dap", "baum" -> {
                val labels = when (instrument.id) {
                    "htp" -> listOf("Rumah", "Pohon", "Orang")
                    "dap" -> listOf("Figur Utama", "Figur Pembanding")
                    else -> listOf("Pohon")
                }
                val demoPanels = labels.mapIndexed { idx, lbl ->
                    ProjectivePanelDraft(
                        panelIndex = idx,
                        panelLabel = lbl,
                        strokes = listOf(
                            listOf(
                                WarteggNormalizedPoint(0.2f, 0.8f, 0.52f, 1000L, true),
                                WarteggNormalizedPoint(0.5f, 0.25f, 0.66f, 1500L, true),
                                WarteggNormalizedPoint(0.8f, 0.8f, 0.58f, 2000L, true)
                            )
                        ),
                        title = "$lbl (Demo Sintetis)",
                        drawOrder = idx + 1,
                        durationSeconds = 15 + idx * 5
                    )
                }
                val outcome = AssessmentEnrichers.scoreProjectivePanels(
                    instrument.id,
                    instrument.title,
                    demoPanels,
                    "Data Demo Sintetis ${instrument.title}: sketsa simulasi untuk evaluasi tampilan & AI+."
                )
                repository.savePrototypeDemoResult(
                    id = instrument.id,
                    scores = outcome.scores,
                    interpretation = outcome.interpretation,
                    observations = outcome.observations,
                    projectivePanels = demoPanels,
                    multimodalSummary = outcome.multimodalSummary
                )
            }
            else -> {
                val form = requireNotNull(instrument.form) { "Formulir demo belum tersedia." }
                val demoAnswers = buildDemoAnswers(instrument.id, form)
                val rawScores = AssessmentScoring.score(form, demoAnswers)
                val outcome = AssessmentEnrichers.enrichDetailed(instrument.id, form, demoAnswers, rawScores)
                repository.savePrototypeDemoResult(
                    id = instrument.id,
                    scores = outcome.scores,
                    interpretation = outcome.interpretation,
                    observations = outcome.observations
                )
            }
        }
        val allSaved = runCatching { repository.getAllResults() }.getOrNull() ?: emptyList()
        state.update {
            it.copy(
                result = saved,
                savedResults = allSaved,
                answering = false,
                isMarkedSheetEntry = false,
                answers = emptyMap(),
                markedScores = emptyMap(),
                canScore = false,
                pendingResultNavigation = saved.instrumentId to saved.resultId
            )
        }
    }

    private fun buildDemoAnswers(id: String, form: AssessmentForm): Map<Int, Int> = when (id) {
        "disc" -> buildMap {
            for (q in 0 until 24) {
                put(q * 2, q % 4)
                put(q * 2 + 1, (q + 1) % 4)
            }
        }
        "kuder" -> buildMap {
            for (q in 0 until 168) {
                put(q * 2, 0)
                put(q * 2 + 1, 2)
            }
        }
        "rmib" -> buildMap {
            for (g in 0 until 9) {
                for (c in 0 until 12) put(g * 12 + c, c + 1)
            }
        }
        "kraepelin", "pauli" -> buildMap {
            for (c in 0 until form.items.size) {
                put(c, 16 + (c % 5))
                put(100 + c, if (c % 7 == 0) 1 else 0)
                put(200 + c, if (c % 9 == 0) 1 else 0)
            }
        }
        else -> form.items.mapIndexed { idx, item ->
            val count = (item.choices?.size ?: form.values.size).coerceAtLeast(1)
            idx to if (item.multiSelect) 1 else (idx % count)
        }.toMap()
    }

    fun consumeResultNavigation() {
        state.update { it.copy(pendingResultNavigation = null) }
    }

    fun submitExaminerReview(
        reviewStatus: ExaminerReviewStatus,
        reviewerId: String,
        reviewNotes: String,
        examinerCodes: Map<String, String> = emptyMap(),
        validityOverride: AssessmentValidityStatus? = null
    ) = work("Review psikolog belum tersimpan. Coba lagi.", showErrorMessage = true) {
        val instrumentId = requireNotNull(id)
        val targetResultId = resultId ?: state.value.result?.resultId
        val result = repository.submitExaminerReview(
            instrumentId = instrumentId,
            reviewStatus = reviewStatus,
            reviewerId = reviewerId,
            reviewNotes = reviewNotes,
            examinerCodes = examinerCodes,
            validityOverride = validityOverride,
            resultId = targetResultId
        )
        val allSaved = runCatching { repository.getAllResults() }.getOrNull() ?: emptyList()
        state.update { it.copy(result = result, savedResults = allSaved, resultExplanation = null) }
    }

    fun updateConsentFlags(
        aiContextAllowed: Boolean,
        researchExportAllowed: Boolean
    ) = work("Pengaturan persetujuan belum tersimpan. Coba lagi.", showErrorMessage = true) {
        val instrumentId = requireNotNull(id)
        val targetResultId = resultId ?: state.value.result?.resultId
        val result = repository.updateConsentFlags(
            instrumentId = instrumentId,
            aiContextAllowed = aiContextAllowed,
            researchExportAllowed = researchExportAllowed,
            resultId = targetResultId
        )
        val allSaved = runCatching { repository.getAllResults() }.getOrNull() ?: emptyList()
        state.update {
            it.copy(
                result = result,
                savedResults = allSaved,
                resultExplanation = if (aiContextAllowed) it.resultExplanation else null
            )
        }
    }

    fun delete() = work("Hasil belum terhapus. Coba lagi.") {
        val targetResultId = resultId ?: state.value.result?.resultId
        if (!targetResultId.isNullOrBlank()) {
            repository.deleteByResultId(targetResultId)
        } else {
            repository.delete(requireNotNull(id))
        }
        val allSaved = runCatching { repository.getAllResults() }.getOrNull() ?: emptyList()
        state.update { it.copy(result = null, savedResults = allSaved, resultExplanation = null) }
    }

    fun explain() = work("Penjelasan AI belum tersedia.", showErrorMessage = true) {
        val targetResultId = resultId ?: state.value.result?.resultId
        val result = (if (!targetResultId.isNullOrBlank()) repository.getResultById(targetResultId) else repository.getResult(requireNotNull(id)))
            ?: error("Hasil asesmen sudah tidak tersedia.")
        check(result.resultId == state.value.result?.resultId) { "Hasil berubah. Buka ulang asesmen." }
        check(result.aiContextAllowed) {
            result.aiIneligibilityReason() ?: "Izin penggunaan hasil untuk AI+ dinonaktifkan oleh pengguna."
        }
        val explanation = discussion.explain(result)
        val reloaded = if (!targetResultId.isNullOrBlank()) repository.getResultById(targetResultId) else repository.getResult(requireNotNull(id))
        check(reloaded?.resultId == result.resultId) { "Hasil berubah saat penjelasan dibuat." }
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

    class Factory(
        context: Context,
        private val id: String?,
        private val resultId: String? = null
    ) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AssessmentViewModel(
            id = id,
            repository = AppLocator.assessmentRepository(appContext),
            discussion = AssessmentDiscussion(
                AppLocator.llmEngine(appContext),
                AssetPromptAssetProvider(appContext)
            ) { AppLocator.database(appContext).consentDao().getConsent("AI_COUNSELING")?.isGranted == true },
            searchRepository = AppLocator.searchRepository(appContext),
            resultId = resultId
        ) as T
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 180L
    }
}
