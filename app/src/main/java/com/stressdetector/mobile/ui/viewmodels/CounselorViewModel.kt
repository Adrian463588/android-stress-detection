package com.stressdetector.mobile.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stressdetector.mobile.data.db.ChatDao
import com.stressdetector.mobile.data.db.ChatMessageEntity
import com.stressdetector.mobile.data.db.ChatSessionEntity
import com.stressdetector.mobile.data.repository.RoomCrisisTurnRepository
import com.stressdetector.mobile.data.repository.SharedPreferencesCounselorSessionSelectionStore
import com.stressdetector.mobile.di.AppLocator
import com.stressdetector.mobile.domain.counselor.CounselorPersonaProfile
import com.stressdetector.mobile.domain.counselor.CounselorPersonaRepository
import com.stressdetector.mobile.domain.counselor.CounselorSessionSelectionStore
import com.stressdetector.mobile.domain.counselor.CrisisTurn
import com.stressdetector.mobile.domain.counselor.CrisisTurnRepository
import com.stressdetector.mobile.domain.engine.AssetPromptAssetProvider
import com.stressdetector.mobile.domain.engine.CitationSource
import com.stressdetector.mobile.domain.engine.ClinicalSafetyInterceptor
import com.stressdetector.mobile.domain.engine.ConversationTurn
import com.stressdetector.mobile.domain.engine.CounselorGenerationRequest
import com.stressdetector.mobile.domain.engine.CounselorOutputGuard
import com.stressdetector.mobile.domain.engine.CounselorPromptBuilder
import com.stressdetector.mobile.domain.engine.CrisisInterceptionResult
import com.stressdetector.mobile.domain.engine.CrisisResponseTemplateProvider
import com.stressdetector.mobile.domain.engine.CrisisSupportAction
import com.stressdetector.mobile.domain.engine.DocumentGroundingIntentDetector
import com.stressdetector.mobile.domain.engine.EvidenceSourceTier
import com.stressdetector.mobile.domain.engine.GenerationTextAssembler
import com.stressdetector.mobile.domain.engine.GuardedCounselorOutput
import com.stressdetector.mobile.domain.engine.IndonesianCrisisResponseTemplateProvider
import com.stressdetector.mobile.domain.engine.KnowledgeCitation
import com.stressdetector.mobile.domain.engine.LlmGenerationState
import com.stressdetector.mobile.domain.engine.LlmRuntimeReadiness
import com.stressdetector.mobile.domain.engine.OnDeviceLlmEngine
import com.stressdetector.mobile.domain.engine.PreparedCounselorPrompt
import com.stressdetector.mobile.domain.engine.PromptAsset
import com.stressdetector.mobile.domain.engine.PromptAssetProvider
import com.stressdetector.mobile.domain.engine.RagMode
import com.stressdetector.mobile.domain.engine.RagRetrievalResult
import com.stressdetector.mobile.domain.engine.RagRetriever
import com.stressdetector.mobile.domain.engine.SafetyInterceptor
import com.stressdetector.mobile.domain.interaction.IdempotentActionGate
import com.stressdetector.mobile.domain.search.PublicCounselorQuery
import com.stressdetector.mobile.domain.search.WebConsentStore
import com.stressdetector.mobile.domain.search.WebGroundingGateway
import com.stressdetector.mobile.domain.search.WebGroundingResult
import com.stressdetector.mobile.domain.search.WebQuerySanitizer
import com.stressdetector.mobile.domain.search.WebSearchMode
import com.stressdetector.mobile.domain.stress.StressHistoryContextProvider
import com.stressdetector.mobile.domain.stress.StressHistoryContextState
import com.stressdetector.mobile.domain.voice.VoiceInputEngine
import com.stressdetector.mobile.domain.voice.VoiceInputState
import com.stressdetector.mobile.domain.voice.VoiceLanguage
import com.stressdetector.mobile.domain.voice.VoiceOutputEngine
import com.stressdetector.mobile.domain.voice.VoiceOutputState
import com.stressdetector.mobile.domain.voice.VoicePlaybackCoordinator
import com.stressdetector.mobile.domain.voice.VoicePlaybackUiState
import com.stressdetector.mobile.domain.voice.VoiceSendMode
import com.stressdetector.mobile.domain.voice.VoiceSettingsRepository
import com.stressdetector.mobile.ml.llm.ModelDownloadState
import com.stressdetector.mobile.ml.llm.ModelManager
import com.stressdetector.mobile.ml.llm.ModelReadiness
import com.stressdetector.mobile.ml.rag.CounselorContextReceipt
import com.stressdetector.mobile.ml.rag.CounselorContextReceiptFactory
import com.stressdetector.mobile.ml.rag.RetrievalPolicyBinding
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class CounselorPhase {
    IDLE,
    MODEL_MISSING,
    LOADING_MODEL,
    GENERATING,
    SUCCESS,
    GENERAL_SAFE,
    RETRIEVAL_UNAVAILABLE,
    CRISIS_ESCALATION,
    CANCELLED,
    ERROR
}

sealed interface CrisisPersistenceState {
    data object Idle : CrisisPersistenceState
    data object Saving : CrisisPersistenceState
    data class RetryableFailure(val message: String) : CrisisPersistenceState
}

data class UiChatMessage(
    val id: String,
    val sender: String,
    val content: String,
    val timestampMs: Long,
    val citations: List<KnowledgeCitation> = emptyList(),
    val inputMode: String = "TEXT",
    val stressContextVersion: String? = null,
    val stressContextSessionIds: List<String> = emptyList(),
    val promptVersion: String? = null,
    val retrievalMode: RagMode? = null,
    val crisisSupportActions: List<CrisisSupportAction> = emptyList()
)

data class UiChatSession(val id: String, val title: String, val startedAtMillis: Long, val lastMessageAtMillis: Long)

data class CounselorUiState(
    val activeChatSessionId: String = UUID.randomUUID().toString(),
    val chatSessions: List<UiChatSession> = emptyList(),
    val messages: List<UiChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val isRetrieving: Boolean = false,
    val statusMessage: String? = null,
    val isModelReady: Boolean = false,
    val modelMetadata: String? = null,
    val modelDownloadState: ModelDownloadState = ModelDownloadState.Idle,
    val phase: CounselorPhase = CounselorPhase.MODEL_MISSING,
    val retrievalMode: RagMode? = null,
    val runtimeReadiness: LlmRuntimeReadiness = LlmRuntimeReadiness.Unknown,
    val runtimeTechnicalMessage: String? = null,
    val lastFailedUserMessage: String? = null,
    val personaConfigured: Boolean = false,
    val personaVersion: Long = 0,
    val voiceInputState: VoiceInputState = VoiceInputState.Idle,
    val voiceOutputState: VoiceOutputState = VoiceOutputState.Idle,
    val voicePlayback: VoicePlaybackUiState = VoicePlaybackUiState(),
    val voiceSendMode: VoiceSendMode = VoiceSendMode.REVIEW,
    val voiceReplyEnabled: Boolean = true,
    val voiceTranscript: String? = null,
    val voiceError: String? = null,
    val voiceLanguage: VoiceLanguage = VoiceLanguage.INDONESIAN,
    val webSearchMode: WebSearchMode = WebSearchMode.Disabled,
    val webConsentGranted: Boolean = false,
    val automaticWebAllowed: Boolean = false,
    val webConsentRequired: Boolean = false,
    val pendingWebQuery: String? = null,
    val useStressHistory: Boolean = false,
    val stressHistoryStatus: String? = null,
    val crisisPersistenceState: CrisisPersistenceState = CrisisPersistenceState.Idle
) {
    val canCancel: Boolean get() = isGenerating || isRetrieving || webConsentRequired
}

class CounselorViewModel(
    private val llmEngine: OnDeviceLlmEngine,
    private val ragRetriever: RagRetriever,
    private val chatDao: ChatDao,
    private val modelManager: ModelManager,
    private val safetyInterceptor: SafetyInterceptor = ClinicalSafetyInterceptor(),
    private val crisisResponseTemplateProvider: CrisisResponseTemplateProvider = IndonesianCrisisResponseTemplateProvider,
    private val promptAssetProvider: PromptAssetProvider = PromptAssetProvider.Static(DEFAULT_PROMPT),
    private val personaRepository: CounselorPersonaRepository = CounselorPersonaRepository.Empty,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val voiceInputEngine: VoiceInputEngine? = null,
    private val voiceOutputEngine: VoiceOutputEngine? = null,
    private val voiceSettingsRepository: VoiceSettingsRepository? = null,
    private val webGroundingGateway: WebGroundingGateway = WebGroundingGateway.Unavailable,
    private val stressHistoryContextProvider: StressHistoryContextProvider = StressHistoryContextProvider.Unavailable,
    private val aiConsentGranted: suspend () -> Boolean = { true },
    private val webConsentStore: WebConsentStore = WebConsentStore.Unavailable,
    private val outputGuard: CounselorOutputGuard = CounselorOutputGuard.Default,
    private val sessionSelectionStore: CounselorSessionSelectionStore = CounselorSessionSelectionStore.None,
    private val crisisTurnRepository: CrisisTurnRepository = RoomCrisisTurnRepository(chatDao),
    private val retrievalPolicyBinding: RetrievalPolicyBinding = RetrievalPolicyBinding.Uncalibrated(
        reason = "benchmark_artifact_missing"
    )
) : ViewModel() {

    private val gson = Gson()
    private val _uiState = MutableStateFlow(CounselorUiState())
    val uiState: StateFlow<CounselorUiState> = _uiState.asStateFlow()
    private var generationJob: Job? = null
    private var webFlowJob: Job? = null

    @Volatile private var turnEpoch = 0L
    private val promptBuilder = CounselorPromptBuilder()
    private var messageObservationJob: Job? = null
    private var voiceInputJob: Job? = null
    private var autoSentVoiceTranscript: String? = null
    private val actionGate = IdempotentActionGate<String>()
    private val sendMutex = Mutex()
    private val webPermissionMutex = Mutex()

    @Volatile private var requestedAutomaticWeb: Boolean? = null
    private val voicePlaybackCoordinator = voiceOutputEngine?.let { engine ->
        VoicePlaybackCoordinator(engine, viewModelScope)
    }
    private var pendingWebRequest: PendingWebRequest? = null
    private var pendingCrisisTurn: CrisisTurn? = null
    private var crisisEscalationActive = false

    init {
        observeChatSessions()
        initializeChatSessionAndLoadMessages()
        observePersona()
        observeModelState()
        observeRuntimeState()
        observeVoiceState()
        observeStressHistoryContext()
        observeWebConsent()
        observeAutomaticWebPermission()
    }

    private fun observeAutomaticWebPermission() {
        viewModelScope.launch {
            webConsentStore.automaticAllowed.collect { allowed ->
                val effectiveAllowed = allowed && requestedAutomaticWeb != false
                val revoked = _uiState.value.automaticWebAllowed && !effectiveAllowed
                _uiState.update {
                    it.copy(
                        automaticWebAllowed = effectiveAllowed,
                        webSearchMode = if (effectiveAllowed) WebSearchMode.Persistent else it.webSearchMode
                    )
                }
                if (revoked) {
                    if (_uiState.value.canCancel) cancelGeneration()
                    _uiState.update {
                        val mode = if (it.webSearchMode == WebSearchMode.Persistent) WebSearchMode.Disabled else it.webSearchMode
                        it.copy(webSearchMode = mode)
                    }
                }
            }
        }
    }

    /** Called only by the explicitly labelled automatic-public-search setting. */
    fun setAutomaticWebSearchEnabled(enabled: Boolean) {
        requestedAutomaticWeb = enabled
        if (!enabled) {
            if (_uiState.value.canCancel) cancelGeneration()
            _uiState.update {
                it.copy(
                    automaticWebAllowed = false,
                    webSearchMode = if (it.webSearchMode == WebSearchMode.Persistent) WebSearchMode.Disabled else it.webSearchMode
                )
            }
        }
        viewModelScope.launch(ioDispatcher) {
            webPermissionMutex.withLock {
                runCatching { webConsentStore.setAutomaticAllowed(enabled) }.onFailure { error ->
                    _uiState.update { it.copy(statusMessage = "Izin web belum tersimpan.", runtimeTechnicalMessage = error.message) }
                }
            }
        }
    }

    private fun observeWebConsent() {
        viewModelScope.launch {
            webConsentStore.granted.collect { granted ->
                val revoked = _uiState.value.webConsentGranted && !granted
                _uiState.update { it.copy(webConsentGranted = granted) }
                if (revoked && (_uiState.value.isRetrieving || _uiState.value.retrievalMode == RagMode.WEB_GROUNDED)) {
                    cancelGeneration()
                }
            }
        }
    }

    fun setUseStressHistory(enabled: Boolean) {
        stressHistoryContextProvider.setEnabled(enabled)
        _uiState.update {
            it.copy(
                useStressHistory = enabled,
                stressHistoryStatus = if (enabled) {
                    "Riwayat stress akan diringkas untuk AI+."
                } else {
                    "Riwayat stress tidak dikirim ke prompt AI+."
                }
            )
        }
    }

    private fun observeStressHistoryContext() {
        viewModelScope.launch(ioDispatcher) {
            stressHistoryContextProvider.observe().collect { state ->
                _uiState.update {
                    when (state) {
                        StressHistoryContextState.Disabled -> it.copy(
                            useStressHistory = false,
                            stressHistoryStatus = "Riwayat stress tidak digunakan."
                        )
                        is StressHistoryContextState.Ready -> it.copy(
                            useStressHistory = true,
                            stressHistoryStatus = state.snapshot.entries.count { entry -> entry.valid }
                                .takeIf { count -> count > 0 }
                                ?.let { count -> "Riwayat stress tersedia ($count sesi valid)." }
                                ?: "Tidak ada riwayat stress valid."
                        )
                        is StressHistoryContextState.Unavailable -> it.copy(
                            stressHistoryStatus = "Riwayat stress tidak tersedia: ${state.reason}"
                        )
                    }
                }
            }
        }
    }

    fun toggleWebSearchMode() {
        val nextMode = when (_uiState.value.webSearchMode) {
            WebSearchMode.Disabled -> WebSearchMode.NextMessage
            WebSearchMode.NextMessage -> WebSearchMode.Persistent
            WebSearchMode.Persistent -> WebSearchMode.Disabled
        }
        setWebSearchMode(nextMode)
    }

    fun setWebSearchMode(mode: WebSearchMode) {
        if (mode == WebSearchMode.Disabled && _uiState.value.canCancel) cancelGeneration()
        _uiState.update { it.copy(webSearchMode = mode) }
    }

    fun confirmWebConsent(publicQuery: String = _uiState.value.pendingWebQuery.orEmpty()) {
        if (!_uiState.value.webConsentRequired) return
        val request = pendingWebRequest ?: return
        if (isCurrentTurn(request.turnEpoch, request.sessionId)) {
            WebQuerySanitizer.sanitize(publicQuery).fold(
                onFailure = { error ->
                    _uiState.update { it.copy(statusMessage = error.message ?: "Masukkan pertanyaan publik terpisah.") }
                },
                onSuccess = { query -> confirmPublicQuery(request, publicQuery, query) }
            )
        }
    }

    private fun confirmPublicQuery(request: PendingWebRequest, original: String, query: String) {
        if (query != original.trim()) {
            _uiState.update { it.copy(pendingWebQuery = query, statusMessage = "Periksa query yang diringkas, lalu konfirmasi lagi.") }
        } else {
            val saved = runCatching {
                webConsentStore.setGranted(true)
                webConsentStore.granted.value
            }.getOrDefault(false)
            if (saved) {
                _uiState.update { it.copy(webConsentGranted = true, webConsentRequired = false, pendingWebQuery = null) }
                beginWebFlow(request, query)
            } else {
                _uiState.update { it.copy(statusMessage = "Persetujuan web belum tersimpan. Pencarian tidak dijalankan.") }
            }
        }
    }

    fun dismissWebConsent() {
        turnEpoch++
        webFlowJob?.cancel()
        webFlowJob = null
        _uiState.update {
            it.copy(
                webSearchMode = if (it.webSearchMode == WebSearchMode.NextMessage) {
                    WebSearchMode.Disabled
                } else {
                    it.webSearchMode
                },
                webConsentRequired = false,
                pendingWebQuery = null,
                isRetrieving = false,
                phase = CounselorPhase.RETRIEVAL_UNAVAILABLE,
                retrievalMode = RagMode.UNAVAILABLE,
                lastFailedUserMessage = pendingWebRequest?.rawText,
                statusMessage = "Pencarian web dibatalkan; tidak ada jawaban berbasis sumber yang dibuat."
            )
        }
        pendingWebRequest = null
    }

    private fun initializeChatSessionAndLoadMessages() {
        viewModelScope.launch {
            val initialEpoch = turnEpoch
            val sessions = chatDao.getAllChatSessions().first()
            val selectedSessionId = runCatching { sessionSelectionStore.read() }.getOrNull()
            val existing = sessions.firstOrNull { it.chatSessionId == selectedSessionId }
                ?: sessions.firstOrNull()
            if (turnEpoch != initialEpoch) {
                showSession(_uiState.value.activeChatSessionId)
                return@launch
            }
            // Keep the eagerly-created UI id when there is no history. A user
            // may send the first message before this Room read finishes.
            val sessionId = existing?.chatSessionId ?: _uiState.value.activeChatSessionId
            sessionSelectionStore.save(sessionId)
            // Opening the shell must not occupy an otherwise empty recovery destination.
            // Persist this draft only when the user sends a message (or explicitly creates a chat).
            showSession(sessionId)
        }
    }

    private fun observeChatSessions() {
        viewModelScope.launch(ioDispatcher) {
            chatDao.getAllChatSessions().collect { sessions ->
                _uiState.update { current ->
                    current.copy(chatSessions = sessions.map(::toUiSession))
                }
            }
        }
    }

    private fun observePersona() {
        viewModelScope.launch {
            personaRepository.observe().collect { persona ->
                _uiState.update {
                    it.copy(personaConfigured = persona.isCustomized, personaVersion = persona.version)
                }
            }
        }
    }

    private fun showSession(sessionId: String) {
        messageObservationJob?.cancel()
        sessionSelectionStore.save(sessionId)
        val switchingSession = _uiState.value.activeChatSessionId != sessionId
        if (switchingSession) pendingWebRequest = null
        _uiState.update {
            it.copy(
                activeChatSessionId = sessionId,
                messages = if (switchingSession) emptyList() else it.messages,
                // Keep a model/runtime readiness message visible during the
                // asynchronous first-session load; otherwise opening AI+ can
                // erase the actionable missing-model state.
                statusMessage = if (switchingSession && it.isModelReady) null else it.statusMessage,
                lastFailedUserMessage = if (switchingSession) null else it.lastFailedUserMessage,
                webSearchMode = if (switchingSession && !it.automaticWebAllowed) WebSearchMode.Disabled else it.webSearchMode,
                webConsentRequired = if (switchingSession) false else it.webConsentRequired,
                pendingWebQuery = if (switchingSession) null else it.pendingWebQuery
            )
        }
        messageObservationJob = viewModelScope.launch(ioDispatcher) {
            chatDao.getMessagesForSession(sessionId).collect { entities ->
                if (_uiState.value.activeChatSessionId == sessionId) {
                    val persisted = entities.map(::toUiMessage)
                    _uiState.update { current ->
                        current.copy(messages = ChatMessageListReducer.mergePersisted(persisted, current.messages))
                    }
                }
            }
        }
    }

    fun selectChatSession(sessionId: String) {
        if (sessionId == _uiState.value.activeChatSessionId) return
        viewModelScope.launch {
            if (!stopGenerationAndWait()) return@launch
            showSession(sessionId)
        }
    }

    fun newChat() {
        viewModelScope.launch {
            actionGate.run(ACTION_NEW_CHAT) {
                if (!stopGenerationAndWait()) return@run
                val now = System.currentTimeMillis()
                val session = ChatSessionEntity(
                    chatSessionId = UUID.randomUUID().toString(),
                    title = "Chat baru",
                    startedAtMillis = now,
                    lastMessageAtMillis = now
                )
                chatDao.insertChatSession(session)
                showSession(session.chatSessionId)
            }
        }
    }

    fun deleteChatSession(sessionId: String) {
        viewModelScope.launch {
            actionGate.run("chat:delete:$sessionId") {
                val wasActive = sessionId == _uiState.value.activeChatSessionId
                val nextSession = _uiState.value.chatSessions
                    .filterNot { it.id == sessionId }
                    .maxByOrNull { it.lastMessageAtMillis }
                if (!stopGenerationAndWait()) return@run
                chatDao.deleteChatSessionWithMessages(sessionId)
                if (wasActive) {
                    if (nextSession != null) {
                        showSession(nextSession.id)
                    } else {
                        createAndShowFreshChat()
                    }
                }
            }
        }
    }

    fun deleteAllChats() {
        viewModelScope.launch {
            actionGate.run(ACTION_DELETE_ALL_CHATS) {
                if (!stopGenerationAndWait()) return@run
                chatDao.deleteAllChatsWithMessages()
                createAndShowFreshChat()
            }
        }
    }

    private suspend fun createAndShowFreshChat() {
        val now = System.currentTimeMillis()
        val session = ChatSessionEntity(
            chatSessionId = UUID.randomUUID().toString(),
            title = "Chat baru",
            startedAtMillis = now,
            lastMessageAtMillis = now
        )
        chatDao.insertChatSession(session)
        showSession(session.chatSessionId)
    }

    private fun observeModelState() {
        // Publish the persisted readiness synchronously so AI+ cannot accept a
        // message while the collector is still waiting for its first dispatch.
        runCatching { modelManager.readiness.value }
            .getOrNull()
            ?.let(::publishModelReadiness)
        viewModelScope.launch(ioDispatcher) {
            // AI+ can be opened directly after process recreation. Reconcile the
            // persisted DownloadManager/finalizer state before exposing readiness
            // so a completed transfer is not mistaken for a missing model.
            runCatching { modelManager.reconcileDownloadState() }
        }
        viewModelScope.launch {
            modelManager.readiness.collect { readiness ->
                publishModelReadiness(readiness)
            }
        }
        viewModelScope.launch(ioDispatcher) {
            // Mockito-based ViewModel tests may not stub this optional observer;
            // production ModelManager always supplies a non-null Flow.
            runCatching { modelManager.observeDownload() }.getOrNull()?.collect { downloadState ->
                _uiState.update { it.copy(modelDownloadState = downloadState) }
            }
        }
    }

    private fun publishModelReadiness(readiness: ModelReadiness) {
        val ready = readiness is ModelReadiness.Ready
        _uiState.update { current ->
            current.copy(
                isModelReady = ready,
                modelMetadata = (readiness as? ModelReadiness.Ready)?.metadata?.let { metadata ->
                    "${metadata.fileName} · ${metadata.version} · ${metadata.license}"
                },
                phase = when {
                    !ready -> CounselorPhase.MODEL_MISSING
                    current.phase == CounselorPhase.MODEL_MISSING -> CounselorPhase.IDLE
                    else -> current.phase
                },
                statusMessage = readinessMessage(readiness)
            )
        }
    }

    private fun readinessMessage(readiness: ModelReadiness): String? = when (readiness) {
        ModelReadiness.Missing -> "Import signed model package ${ModelManager.MODEL_FILE} dan manifest bertanda tangan di Profile."
        is ModelReadiness.Corrupted -> readiness.reason
        is ModelReadiness.Untrusted -> readiness.reason
        is ModelReadiness.UnsupportedDevice -> readiness.reason
        is ModelReadiness.Ready -> null
    }

    private fun observeRuntimeState() {
        viewModelScope.launch {
            llmEngine.runtimeReadiness().collect { runtime ->
                _uiState.update { current ->
                    when (runtime) {
                        LlmRuntimeReadiness.Unknown -> current.copy(runtimeReadiness = runtime)
                        LlmRuntimeReadiness.Checking -> current.copy(
                            runtimeReadiness = runtime,
                            phase = if (current.isGenerating) CounselorPhase.LOADING_MODEL else current.phase
                        )
                        is LlmRuntimeReadiness.Ready -> current.copy(
                            runtimeReadiness = runtime,
                            runtimeTechnicalMessage = null
                        )
                        is LlmRuntimeReadiness.Unavailable -> current.copy(
                            runtimeReadiness = runtime,
                            runtimeTechnicalMessage = runtime.technicalMessage,
                            phase = if (current.isGenerating) CounselorPhase.ERROR else current.phase,
                            statusMessage = runtime.userMessage
                        )
                    }
                }
            }
        }
    }

    private fun observeVoiceState() {
        voiceSettingsRepository?.let { repository ->
            viewModelScope.launch {
                repository.settings.collect { settings ->
                    _uiState.update {
                        it.copy(
                            voiceLanguage = settings.language,
                            voiceSendMode = settings.sendMode,
                            voiceReplyEnabled = settings.replyEnabled
                        )
                    }
                }
            }
        }
        voiceInputEngine?.let { engine ->
            viewModelScope.launch {
                engine.state.collect { voiceState ->
                    _uiState.update {
                        it.copy(
                            voiceInputState = voiceState,
                            voiceTranscript = when (voiceState) {
                                is VoiceInputState.TranscriptReady -> voiceState.text
                                VoiceInputState.Listening,
                                VoiceInputState.Transcribing,
                                VoiceInputState.RequestingPermission,
                                is VoiceInputState.Initializing,
                                VoiceInputState.Idle -> null
                                is VoiceInputState.Error -> it.voiceTranscript
                            },
                            voiceError = (voiceState as? VoiceInputState.Error)?.message
                        )
                    }
                    val transcript = (voiceState as? VoiceInputState.TranscriptReady)?.text?.trim()
                    if (transcript != null &&
                        _uiState.value.voiceSendMode == VoiceSendMode.AUTO_SEND &&
                        autoSentVoiceTranscript != transcript
                    ) {
                        autoSentVoiceTranscript = transcript
                        sendVoiceMessage(transcript)
                    }
                }
            }
        }
        voicePlaybackCoordinator?.let { coordinator ->
            viewModelScope.launch {
                coordinator.state.collect { playbackState ->
                    _uiState.update {
                        it.copy(
                            voiceOutputState = playbackState.state,
                            voicePlayback = playbackState,
                            voiceError = playbackState.error ?: it.voiceError
                        )
                    }
                }
            }
        }
    }

    fun exportChat(context: Context, onResult: (String) -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            val exportMessages = _uiState.value.messages.map { ui ->
                ChatMessageEntity(
                    messageId = ui.id,
                    chatSessionId = _uiState.value.activeChatSessionId,
                    sender = ui.sender,
                    content = ui.content,
                    timestampMs = ui.timestampMs,
                    citationsJson = gson.toJson(ui.citations)
                )
            }
            val result = com.stressdetector.mobile.util.ChatExportUtil.exportChatToPdf(
                context,
                exportMessages,
                "Session ${_uiState.value.activeChatSessionId}"
            )
            result.onSuccess { onResult(it) }
                .onFailure { onResult("Error: ${it.message ?: "Export failed"}") }
        }
    }

    fun sendMessage(userText: String) {
        sendMessage(userText, "TEXT")
    }

    private fun sendMessage(userText: String, inputMode: String = "TEXT") {
        val rawText = userText.trim()
        if (rawText.isBlank()) return
        if (!sendMutex.tryLock()) {
            _uiState.update {
                it.copy(statusMessage = "Pesan sedang diproses. Tunggu sebentar sebelum mengirim lagi.")
            }
            return
        }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                dispatchMessage(rawText, inputMode)
            } finally {
                sendMutex.unlock()
            }
        }
    }

    private suspend fun dispatchMessage(rawText: String, inputMode: String) {
        when (val interception = safetyInterceptor.intercept(rawText)) {
            is CrisisInterceptionResult.Escalate -> {
                if (crisisEscalationActive && pendingCrisisTurn != null) return
                startCrisisEscalation(rawText, inputMode, interception)
                return
            }
            CrisisInterceptionResult.NoCrisis -> crisisEscalationActive = false
        }
        if (generationJob?.isActive == true || webFlowJob?.isActive == true) {
            _uiState.update {
                it.copy(statusMessage = "Respons sebelumnya masih diproses. Tunggu atau tekan batal sebelum mengirim pesan baru.")
            }
            return
        }
        if (pendingWebRequest != null) {
            _uiState.update { it.copy(statusMessage = "Selesaikan konfirmasi sumber web sebelum mengirim pesan baru.") }
            return
        }

        val userMessage = UiChatMessage(
            id = UUID.randomUUID().toString(),
            sender = "user",
            content = rawText,
            timestampMs = System.currentTimeMillis(),
            inputMode = inputMode
        )
        val dispatchSessionId = _uiState.value.activeChatSessionId
        val dispatchEpoch = turnEpoch
        val consentGranted = aiConsentGranted()
        requireCurrentTurn(dispatchEpoch, dispatchSessionId)
        if (!consentGranted) {
            persist(userMessage, dispatchSessionId)
            requireCurrentTurn(dispatchEpoch, dispatchSessionId)
            _uiState.update {
                it.copy(
                    messages = ChatMessageListReducer.append(it.messages, userMessage),
                    phase = CounselorPhase.ERROR,
                    statusMessage = "Consent AI Counselor belum diberikan. Aktifkan consent di Profile.",
                    lastFailedUserMessage = rawText
                )
            }
            return
        }

        val webMode = _uiState.value.webSearchMode
        turnEpoch++
        if (webMode != WebSearchMode.Disabled) {
            val request = PendingWebRequest(
                rawText,
                inputMode,
                userMessage,
                webMode,
                sessionId = _uiState.value.activeChatSessionId,
                turnEpoch = turnEpoch
            )
            pendingWebRequest = request
            // Local RAG and the approved bundled corpus always get the first chance. Web consent
            // is requested only after both local sources abstain for an evidence-required query.
            beginWebFlow(request)
            return
        }

        startGeneration(
            promptText = rawText,
            rawUserText = rawText,
            userMessage = userMessage,
            persistUserMessage = true,
            inputMode = inputMode
        )
    }

    private fun startCrisisEscalation(rawText: String, inputMode: String, escalation: CrisisInterceptionResult.Escalate) {
        preemptGenerationForCrisis()
        val userTimestamp = System.currentTimeMillis()
        val crisisTimestamp = userTimestamp + 1L
        val sessionId = _uiState.value.activeChatSessionId
        val existingSession = _uiState.value.chatSessions.firstOrNull { it.id == sessionId }
        val userMessage = UiChatMessage(
            id = UUID.randomUUID().toString(),
            sender = USER_SENDER,
            content = rawText,
            timestampMs = userTimestamp,
            inputMode = inputMode
        )
        val crisisMessage = UiChatMessage(
            id = UUID.randomUUID().toString(),
            sender = CRISIS_SYSTEM_SENDER,
            content = escalation.response.text,
            timestampMs = crisisTimestamp,
            inputMode = inputMode,
            promptVersion = escalation.response.templateVersion,
            crisisSupportActions = escalation.response.supportActions
        )
        val turn = CrisisTurn(
            sessionId = sessionId,
            sessionTitle = existingSession?.title
                ?.takeUnless { it == NEW_CHAT_TITLE }
                ?: conversationTitle(rawText),
            sessionStartedAtMillis = existingSession?.startedAtMillis ?: userTimestamp,
            userMessageId = userMessage.id,
            userText = userMessage.content,
            userTimestampMillis = userMessage.timestampMs,
            inputMode = inputMode,
            crisisMessageId = crisisMessage.id,
            crisisText = crisisMessage.content,
            crisisTimestampMillis = crisisMessage.timestampMs,
            templateVersion = escalation.response.templateVersion
        )
        pendingCrisisTurn = turn
        crisisEscalationActive = true
        _uiState.update { state ->
            state.copy(
                messages = ChatMessageListReducer.replace(state.messages, userMessage, crisisMessage),
                isGenerating = false,
                phase = CounselorPhase.CRISIS_ESCALATION,
                isRetrieving = false,
                retrievalMode = null,
                statusMessage = CRISIS_STATUS_MESSAGE,
                runtimeTechnicalMessage = null,
                lastFailedUserMessage = null,
                webConsentRequired = false,
                pendingWebQuery = null,
                crisisPersistenceState = CrisisPersistenceState.Saving
            )
        }
        persistCrisisTurn(turn)
    }

    fun retryCrisisPersistence() {
        pendingCrisisTurn?.let(::persistCrisisTurn)
    }

    private fun preemptGenerationForCrisis() {
        turnEpoch++
        webFlowJob?.cancel()
        webFlowJob = null
        pendingWebRequest = null
        generationJob?.cancel(CancellationException("Crisis escalation preempted counselor generation"))
        generationJob = null
        viewModelScope.launch(ioDispatcher) {
            runCatching { llmEngine.cancelGeneration() }
        }
    }

    private fun persistCrisisTurn(turn: CrisisTurn) {
        viewModelScope.launch(ioDispatcher) {
            val result = crisisTurnRepository.persist(turn)
            if (pendingCrisisTurn?.crisisMessageId != turn.crisisMessageId) return@launch
            result.onSuccess {
                pendingCrisisTurn = null
                _uiState.update { state ->
                    if (state.phase == CounselorPhase.CRISIS_ESCALATION) {
                        state.copy(crisisPersistenceState = CrisisPersistenceState.Idle)
                    } else {
                        state
                    }
                }
                launchCrisisVoiceReply(turn)
            }.onFailure {
                _uiState.update { state ->
                    if (state.phase == CounselorPhase.CRISIS_ESCALATION) {
                        state.copy(
                            statusMessage = CRISIS_PERSISTENCE_FAILURE_MESSAGE,
                            crisisPersistenceState = CrisisPersistenceState.RetryableFailure(
                                CRISIS_PERSISTENCE_FAILURE_MESSAGE
                            )
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    private fun launchCrisisVoiceReply(turn: CrisisTurn) {
        if (turn.inputMode != VOICE_INPUT_MODE || !_uiState.value.voiceReplyEnabled) return
        viewModelScope.launch {
            runCatching { speakIfEnabled(turn.crisisText, turn.inputMode, turn.crisisMessageId) }
                .onFailure {
                    _uiState.update { state ->
                        state.copy(
                            voiceError = "Respons keselamatan tetap tersedia, tetapi suara tidak dapat diputar."
                        )
                    }
                }
        }
    }

    private fun beginWebFlow(request: PendingWebRequest, confirmedQuery: String? = null) {
        if (!isCurrentTurn(request.turnEpoch, request.sessionId)) return
        pendingWebRequest = request
        _uiState.update {
            it.copy(
                isRetrieving = true,
                pendingWebQuery = null,
                webConsentRequired = false,
                messages = ChatMessageListReducer.append(it.messages, request.userMessage),
                statusMessage = if (confirmedQuery == null) "Memeriksa sumber lokal…" else "Mencari sumber web…"
            )
        }
        webFlowJob = viewModelScope.launch(ioDispatcher) {
            runCatching {
                if (request.persistUserMessage) persist(request.userMessage, request.sessionId)
                requireCurrentTurn(request.turnEpoch, request.sessionId)
                processWebFlow(request.copy(persistUserMessage = false), confirmedQuery)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                reportWebFailure(request, error)
            }
        }
    }

    private suspend fun processWebFlow(request: PendingWebRequest, confirmedQuery: String?) {
        if (!aiConsentGranted() || !_uiState.value.isModelReady) {
            handOffWebTurn(request)
            return
        }
        val retrieval = request.localRetrieval ?: retrieveLocal(request.rawText)
        requireCurrentTurn(request.turnEpoch, request.sessionId)
        when {
            retrieval.toDecision(request.rawText).mode != RagMode.UNAVAILABLE -> handOffWebTurn(request, retrieval)
            confirmedQuery == null && request.mode == WebSearchMode.Persistent && _uiState.value.automaticWebAllowed -> {
                val publicQuery = PublicCounselorQuery.fromMessage(request.rawText)
                if (publicQuery == null) {
                    handOffWebTurn(request, retrieval, webError = "public_topic_unavailable")
                } else {
                    _uiState.update { it.copy(statusMessage = "Mencari sumber web…") }
                    fetchWebEvidence(request, retrieval, publicQuery, automatic = true)
                }
            }
            confirmedQuery == null -> offerPublicQuery(request, retrieval)
            else -> {
                fetchWebEvidence(request, retrieval, confirmedQuery, automatic = false)
            }
        }
    }

    private suspend fun fetchWebEvidence(request: PendingWebRequest, retrieval: RagRetrievalResult, query: String, automatic: Boolean) {
        check(webConsentStore.granted.value && (!automatic || _uiState.value.automaticWebAllowed)) { "web_consent_required" }
        val grounding = withTimeoutOrNull(WEB_SEARCH_TIMEOUT_MILLIS) { webGroundingGateway.retrieveAutomatically(query) }
            ?: Result.failure(IllegalStateException("web_search_timeout"))
        requireCurrentTurn(request.turnEpoch, request.sessionId)
        check(webConsentStore.granted.value && aiConsentGranted() && (!automatic || _uiState.value.automaticWebAllowed)) {
            "consent_revoked"
        }
        grounding.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        handOffWebTurn(request, retrieval, grounding.getOrNull(), grounding.exceptionOrNull()?.message)
    }

    private suspend fun offerPublicQuery(request: PendingWebRequest, retrieval: RagRetrievalResult) {
        if (request.persistUserMessage) persist(request.userMessage, request.sessionId)
        withContext(Dispatchers.Main.immediate) {
            requireCurrentTurn(request.turnEpoch, request.sessionId)
            pendingWebRequest = request.copy(localRetrieval = retrieval, persistUserMessage = false)
            _uiState.update {
                it.copy(
                    messages = ChatMessageListReducer.append(it.messages, request.userMessage),
                    isRetrieving = false,
                    webConsentRequired = true,
                    pendingWebQuery = WebQuerySanitizer.sanitize(request.rawText).getOrNull().orEmpty(),
                    statusMessage = "Sumber lokal belum cukup. Periksa pertanyaan publik sebelum mencari web."
                )
            }
        }
    }

    private fun reportWebFailure(request: PendingWebRequest, error: Throwable) {
        viewModelScope.launch {
            if (!isCurrentTurn(request.turnEpoch, request.sessionId)) return@launch
            pendingWebRequest = null
            _uiState.update {
                it.copy(
                    isRetrieving = false,
                    webConsentRequired = false,
                    pendingWebQuery = null,
                    phase = CounselorPhase.RETRIEVAL_UNAVAILABLE,
                    statusMessage = "Pencarian sumber belum tersedia. Coba lagi atau pilih dokumen lokal.",
                    runtimeTechnicalMessage = error.message,
                    lastFailedUserMessage = request.rawText
                )
            }
        }
    }

    private fun handOffWebTurn(
        request: PendingWebRequest,
        retrieval: RagRetrievalResult? = null,
        grounding: WebGroundingResult? = null,
        webError: String? = null
    ) {
        // Ownership and generation setup run together on Main, serialized with cancel/chat navigation.
        viewModelScope.launch {
            if (!isCurrentTurn(request.turnEpoch, request.sessionId)) return@launch
            pendingWebRequest = null
            _uiState.update {
                it.copy(
                    isRetrieving = false,
                    webConsentRequired = false,
                    pendingWebQuery = null,
                    webSearchMode = if (request.mode == WebSearchMode.NextMessage) WebSearchMode.Disabled else request.mode
                )
            }
            startGeneration(
                promptText = request.rawText,
                rawUserText = request.rawText,
                userMessage = request.userMessage,
                persistUserMessage = request.persistUserMessage,
                inputMode = request.inputMode,
                localRetrievalOverride = if (webError == null) retrieval else RagRetrievalResult.Unavailable("web: $webError"),
                webGroundingOverride = grounding,
                webStatusMessage = webError?.let { "Sumber belum dapat dibaca. Kamu tetap bisa bercerita, atau mencoba lagi." }
            )
        }
    }

    private suspend fun retrieveLocal(text: String): RagRetrievalResult = runCatching {
        ragRetriever.retrieveRelevantPassages(text)
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        RagRetrievalResult.Unavailable(error.message ?: "Local retrieval failed")
    }

    private fun isCurrentTurn(epoch: Long, sessionId: String): Boolean =
        turnEpoch == epoch && _uiState.value.activeChatSessionId == sessionId

    private suspend fun requireCurrentTurn(epoch: Long, sessionId: String) {
        currentCoroutineContext().ensureActive()
        if (!isCurrentTurn(epoch, sessionId)) throw CancellationException("Counselor turn no longer owns this session")
    }

    fun sendVoiceMessage(transcript: String) {
        voiceInputEngine?.cancel()
        sendMessage(transcript, "VOICE")
    }

    fun retryLastMessage() {
        val rawText = _uiState.value.lastFailedUserMessage ?: return
        if (_uiState.value.canCancel) return
        val webMode = _uiState.value.webSearchMode
        val existingUserMessage = _uiState.value.messages
            .asReversed()
            .firstOrNull { it.sender == USER_SENDER && it.content == rawText }
        if (existingUserMessage == null) {
            sendMessage(rawText)
        } else if (webMode != WebSearchMode.Disabled) {
            turnEpoch++
            val request = PendingWebRequest(
                rawText = rawText,
                inputMode = existingUserMessage.inputMode,
                userMessage = existingUserMessage,
                mode = webMode,
                persistUserMessage = true,
                sessionId = _uiState.value.activeChatSessionId,
                turnEpoch = turnEpoch
            )
            pendingWebRequest = request
            beginWebFlow(request)
        } else {
            turnEpoch++
            startGeneration(
                promptText = rawText,
                rawUserText = rawText,
                userMessage = existingUserMessage,
                persistUserMessage = true,
                inputMode = "TEXT"
            )
        }
    }

    fun requestWebFallback() {
        if (_uiState.value.canCancel) return
        setWebSearchMode(WebSearchMode.NextMessage)
        retryLastMessage()
    }

    private fun startGeneration(
        promptText: String,
        rawUserText: String,
        userMessage: UiChatMessage?,
        persistUserMessage: Boolean,
        inputMode: String,
        localRetrievalOverride: RagRetrievalResult? = null,
        webGroundingOverride: WebGroundingResult? = null,
        webStatusMessage: String? = null
    ) {
        crisisEscalationActive = false
        val sessionId = _uiState.value.activeChatSessionId
        val epoch = turnEpoch
        val counselorMessageId = UUID.randomUUID().toString()
        val typingMessage = UiChatMessage(
            id = counselorMessageId,
            sender = "counselor",
            content = "",
            timestampMs = System.currentTimeMillis(),
            citations = emptyList()
        )
        _uiState.update {
            it.copy(
                messages = ChatMessageListReducer.append(
                    if (userMessage == null) it.messages else ChatMessageListReducer.append(it.messages, userMessage),
                    typingMessage
                ),
                isGenerating = true,
                isRetrieving = false,
                phase = CounselorPhase.LOADING_MODEL,
                statusMessage = null,
                runtimeTechnicalMessage = null
            )
        }

        generationJob = viewModelScope.launch {
            fun renderFinalAssistant(content: String, citations: List<KnowledgeCitation> = emptyList(), retrievalMode: RagMode? = null) {
                _uiState.update { state ->
                    state.copy(
                        messages = ChatMessageListReducer.replace(
                            state.messages,
                            typingMessage.copy(
                                content = content,
                                citations = citations,
                                retrievalMode = retrievalMode ?: typingMessage.retrievalMode
                            )
                        )
                    )
                }
            }
            fun removeTransientAssistant() {
                _uiState.update { state ->
                    state.copy(messages = ChatMessageListReducer.remove(state.messages, counselorMessageId))
                }
            }
            try {
                if (!aiConsentGranted()) {
                    removeTransientAssistant()
                    if (persistUserMessage && userMessage != null) persist(userMessage, sessionId = sessionId)
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            isRetrieving = false,
                            phase = CounselorPhase.ERROR,
                            statusMessage = "Consent AI Counselor belum diberikan. Aktifkan consent di Profile.",
                            lastFailedUserMessage = rawUserText
                        )
                    }
                    return@launch
                }
                if (persistUserMessage && userMessage != null) persist(userMessage, sessionId = sessionId)

                if (!_uiState.value.isModelReady) {
                    removeTransientAssistant()
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            phase = CounselorPhase.MODEL_MISSING,
                            statusMessage = "Model AI belum tersedia. Unduh model melalui Profile.",
                            lastFailedUserMessage = rawUserText
                        )
                    }
                    return@launch
                }

                val runtimeStatus = runCatching {
                    llmEngine.preflight()
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    LlmRuntimeReadiness.Unavailable(
                        userMessage = "AI lokal belum dapat menjalankan model pada perangkat ini.",
                        technicalMessage = error.message
                    )
                }
                _uiState.update { it.copy(runtimeReadiness = runtimeStatus) }
                if (runtimeStatus !is LlmRuntimeReadiness.Ready) {
                    val message = (runtimeStatus as? LlmRuntimeReadiness.Unavailable)?.userMessage
                        ?: "AI lokal belum siap digunakan."
                    removeTransientAssistant()
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            phase = CounselorPhase.ERROR,
                            runtimeReadiness = runtimeStatus,
                            runtimeTechnicalMessage = (runtimeStatus as? LlmRuntimeReadiness.Unavailable)?.technicalMessage,
                            statusMessage = message,
                            lastFailedUserMessage = rawUserText
                        )
                    }
                    return@launch
                }

                val retrieval = localRetrievalOverride ?: retrieveLocal(promptText)
                requireCurrentTurn(epoch, sessionId)
                val retrievalDecision = resolveWebGrounding(
                    rawUserText = rawUserText,
                    retrieval = retrieval,
                    webGroundingOverride = webGroundingOverride,
                    webStatusMessage = webStatusMessage
                ) ?: run {
                    removeTransientAssistant()
                    return@launch
                }
                if (retrievalDecision.mode == RagMode.UNAVAILABLE) {
                    removeTransientAssistant()
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            phase = CounselorPhase.RETRIEVAL_UNAVAILABLE,
                            retrievalMode = RagMode.UNAVAILABLE,
                            statusMessage = retrievalDecision.statusMessage
                                ?: "Evidence yang dibutuhkan belum tersedia; jawaban tidak dibuat.",
                            runtimeTechnicalMessage = (retrieval as? RagRetrievalResult.Unavailable)?.reason,
                            lastFailedUserMessage = rawUserText
                        )
                    }
                    return@launch
                }
                val stressContext = readStressContext()
                val promptAsset = promptAssetProvider.getCounselorPrompt()
                val persona = personaRepository.get()
                val recentHistory = recentHistory(
                    text = promptText,
                    currentUserMessageId = userMessage?.id
                )
                val rawRequest = CounselorGenerationRequest(
                    promptAsset = promptAsset,
                    userMessage = promptText,
                    ragMode = retrievalDecision.mode,
                    retrievedCitations = retrievalDecision.citations,
                    stressHistoryContext = stressContext.promptText,
                    personaProfile = persona.profile,
                    personaVersion = persona.version.toInt(),
                    recentHistory = recentHistory,
                    webCitations = retrievalDecision.webCitations
                )
                val prepared = promptBuilder.prepare(rawRequest)
                val citationRegistry = prepared.citationRegistry
                val request = rawRequest.copy(
                    citationRegistry = citationRegistry,
                    retrievedCitations = citationRegistry.bindings.map { it.citation },
                    stressHistoryContext = prepared.admittedStressContext,
                    personaProfile = if (prepared.personaIncluded) {
                        rawRequest.personaProfile
                    } else {
                        CounselorPersonaProfile()
                    },
                    webCitations = citationRegistry.webBindings().map { it.citation }
                )
                _uiState.update {
                    it.copy(
                        phase = if (retrievalDecision.mode.isGrounded()) {
                            CounselorPhase.GENERATING
                        } else {
                            CounselorPhase.GENERAL_SAFE
                        },
                        retrievalMode = retrievalDecision.mode,
                        statusMessage = retrievalDecision.statusMessage
                    )
                }

                val responseAssembler = GenerationTextAssembler()
                var generationFailed = false
                var receivedContent = false
                try {
                    llmEngine.generate(request).collect { generation ->
                        requireCurrentTurn(epoch, sessionId)
                        when (generation) {
                            is LlmGenerationState.Content -> {
                                val assembledText = responseAssembler.append(generation)
                                if (assembledText.isNotBlank()) receivedContent = true
                            }
                            is LlmGenerationState.Failed -> {
                                generationFailed = true
                                removeTransientAssistant()
                                _uiState.update {
                                    it.copy(
                                        phase = CounselorPhase.ERROR,
                                        statusMessage = "AI lokal gagal menghasilkan respons. Coba lagi.",
                                        runtimeTechnicalMessage = generation.reason,
                                        lastFailedUserMessage = rawUserText
                                    )
                                }
                            }
                            is LlmGenerationState.Unavailable -> {
                                generationFailed = true
                                removeTransientAssistant()
                                _uiState.update {
                                    it.copy(
                                        phase = CounselorPhase.ERROR,
                                        statusMessage = "AI lokal belum siap digunakan. Coba lagi.",
                                        runtimeTechnicalMessage = generation.readiness.toString(),
                                        lastFailedUserMessage = rawUserText
                                    )
                                }
                            }
                            LlmGenerationState.Cancelled -> {
                                generationFailed = true
                                removeTransientAssistant()
                                _uiState.update {
                                    it.copy(
                                        phase = CounselorPhase.CANCELLED,
                                        statusMessage = "Respons dibatalkan."
                                    )
                                }
                            }
                        }
                    }
                    if (!generationFailed && !receivedContent) {
                        generationFailed = true
                        removeTransientAssistant()
                        _uiState.update {
                            it.copy(
                                phase = CounselorPhase.ERROR,
                                statusMessage = "AI lokal tidak mengembalikan respons. Coba lagi.",
                                lastFailedUserMessage = rawUserText
                            )
                        }
                    }
                    val bufferedText = responseAssembler.currentText()
                    requireCurrentTurn(epoch, sessionId)
                    check(aiConsentGranted()) { "ai_consent_revoked" }
                    check(retrievalDecision.mode != RagMode.WEB_GROUNDED || webConsentStore.granted.value) { "web_consent_revoked" }
                    check(stressContext.sourceSessionIds.isEmpty() || _uiState.value.useStressHistory) { "stress_history_consent_revoked" }
                    if (!generationFailed && bufferedText.isNotBlank() && _uiState.value.phase != CounselorPhase.ERROR) {
                        val initialGuard = outputGuard.validate(
                            text = bufferedText,
                            mode = retrievalDecision.mode,
                            citations = request.retrievedCitations,
                            registry = citationRegistry
                        )
                        var finalPrepared = prepared
                        val guarded = if (initialGuard is GuardedCounselorOutput.Rejected &&
                            initialGuard.reasonCode.startsWith("citation_") &&
                            retrievalDecision.mode.isGrounded()
                        ) {
                            val repaired = repairCitationOutput(request, epoch, sessionId, initialGuard)
                            finalPrepared = repaired.prompt
                            repaired.output
                        } else {
                            initialGuard
                        }
                        when (guarded) {
                            is GuardedCounselorOutput.Accepted -> {
                                val admittedStressContext = stressContext.forPrompt(finalPrepared)
                                val finalEvidence = finalPrepared.citationRegistry.bindings.map { it.citation }
                                val contextReceipt = CounselorContextReceiptFactory.create(
                                    retrieval = if (finalEvidence.isNotEmpty()) {
                                        RagRetrievalResult.Retrieved(finalEvidence, sourceTier = retrievalDecision.sourceTier)
                                    } else {
                                        retrieval
                                    },
                                    policyBinding = retrievalPolicyBinding,
                                    personaVersion = if (finalPrepared.personaIncluded) persona.version.toString() else null,
                                    stressHistorySessionIds = admittedStressContext.sourceSessionIds,
                                    excludedHistoryReasons = admittedStressContext.excludedHistoryReasons,
                                    llmModelVersion = runtimeStatus.runtimeVersion
                                )
                                val finalContent = withDisclaimer(guarded.text)
                                val finalCitations = guarded.citations
                                val persistedMessage = UiChatMessage(
                                    id = counselorMessageId,
                                    sender = "counselor",
                                    content = finalContent,
                                    timestampMs = System.currentTimeMillis(),
                                    citations = finalCitations,
                                    retrievalMode = retrievalDecision.mode
                                )
                                chatDao.persistAssistantTurn(
                                    persistedMessage.toEntity(
                                        sessionId = sessionId,
                                        stressContext = admittedStressContext.persistedJson(contextReceipt),
                                        promptVersion = if (finalPrepared.personaIncluded) {
                                            "${promptAsset.version}+persona-${persona.version}-${persona.profileHash.take(12)}"
                                        } else {
                                            promptAsset.version
                                        },
                                        retrievalMode = retrievalDecision.mode
                                    )
                                ) {
                                    requireCurrentTurn(epoch, sessionId)
                                    check(aiConsentGranted()) { "ai_consent_revoked" }
                                    check(retrievalDecision.mode != RagMode.WEB_GROUNDED || webConsentStore.granted.value) {
                                        "web_consent_revoked"
                                    }
                                    check(stressContext.sourceSessionIds.isEmpty() || _uiState.value.useStressHistory) {
                                        "stress_history_consent_revoked"
                                    }
                                    requireCurrentTurn(epoch, sessionId)
                                }
                                requireCurrentTurn(epoch, sessionId)
                                renderFinalAssistant(finalContent, finalCitations, retrievalDecision.mode)
                                speakIfEnabled(finalContent, inputMode, counselorMessageId)
                                _uiState.update {
                                    it.copy(
                                        phase = CounselorPhase.SUCCESS,
                                        statusMessage = retrievalDecision.statusMessage,
                                        lastFailedUserMessage = null
                                    )
                                }
                            }
                            is GuardedCounselorOutput.Rejected -> {
                                generationFailed = true
                                removeTransientAssistant()
                                _uiState.update {
                                    it.copy(
                                        phase = if (retrievalDecision.mode.isGrounded()) {
                                            CounselorPhase.RETRIEVAL_UNAVAILABLE
                                        } else {
                                            CounselorPhase.ERROR
                                        },
                                        statusMessage = guarded.text,
                                        runtimeTechnicalMessage = guarded.reasonCode,
                                        lastFailedUserMessage = rawUserText
                                    )
                                }
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    if (!crisisEscalationActive && isCurrentTurn(epoch, sessionId)) {
                        removeTransientAssistant()
                        _uiState.update {
                            it.copy(
                                phase = CounselorPhase.CANCELLED,
                                statusMessage = "Respons dibatalkan."
                            )
                        }
                    }
                } catch (error: Throwable) {
                    generationFailed = true
                    if (!crisisEscalationActive && isCurrentTurn(epoch, sessionId)) {
                        removeTransientAssistant()
                        _uiState.update {
                            it.copy(
                                phase = CounselorPhase.ERROR,
                                statusMessage = "AI lokal gagal menghasilkan respons. Coba lagi.",
                                runtimeTechnicalMessage = error.message,
                                lastFailedUserMessage = rawUserText
                            )
                        }
                    }
                } finally {
                    if (isCurrentTurn(epoch, sessionId)) _uiState.update { it.copy(isGenerating = false) }
                }
            } catch (_: CancellationException) {
                if (!crisisEscalationActive && isCurrentTurn(epoch, sessionId)) {
                    removeTransientAssistant()
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            phase = CounselorPhase.CANCELLED,
                            statusMessage = "Respons dibatalkan."
                        )
                    }
                }
            } catch (error: Throwable) {
                if (!crisisEscalationActive && isCurrentTurn(epoch, sessionId)) {
                    removeTransientAssistant()
                    _uiState.update {
                        it.copy(
                            isGenerating = false,
                            phase = CounselorPhase.ERROR,
                            statusMessage = "AI lokal gagal memproses pesan. Coba lagi.",
                            runtimeTechnicalMessage = error.message,
                            lastFailedUserMessage = rawUserText
                        )
                    }
                }
            } finally {
                _uiState.update { state ->
                    if (isCurrentTurn(epoch, sessionId) && state.isGenerating) state.copy(isGenerating = false) else state
                }
            }
        }
    }

    fun cancelGeneration() {
        turnEpoch++
        webFlowJob?.cancel(CancellationException("User cancelled evidence retrieval"))
        webFlowJob = null
        pendingWebRequest = null
        _uiState.update {
            it.copy(
                isRetrieving = false,
                webConsentRequired = false,
                pendingWebQuery = null,
                isGenerating = false,
                phase = CounselorPhase.CANCELLED,
                statusMessage = "Respons dibatalkan.",
                messages = it.messages.filterNot { message -> message.sender == "counselor" && message.content.isBlank() }
            )
        }
        if (generationJob?.isActive == true) {
            llmEngine.cancelGeneration()
            generationJob?.cancel(CancellationException("User cancelled counselor generation"))
        }
        stopVoiceReply()
    }

    private suspend fun stopGenerationAndWait(): Boolean {
        voicePlaybackCoordinator?.stopAndWait()
        turnEpoch++
        val retrievalJob = webFlowJob
        webFlowJob = null
        pendingWebRequest = null
        retrievalJob?.cancel(CancellationException("Counselor session changed"))
        if (retrievalJob != null &&
            withTimeoutOrNull(GENERATION_STOP_TIMEOUT_MS) {
                retrievalJob.join()
                true
            } != true
        ) {
            _uiState.update { it.copy(statusMessage = "Pencarian sebelumnya masih dihentikan. Coba lagi sebentar.") }
            return false
        }
        _uiState.update {
            it.copy(
                phase = if (it.canCancel) CounselorPhase.CANCELLED else it.phase,
                messages = it.messages.filterNot { message -> message.sender == "counselor" && message.content.isBlank() },
                isRetrieving = false,
                isGenerating = false,
                webConsentRequired = false,
                pendingWebQuery = null
            )
        }
        val job = generationJob
        if (job?.isActive != true) {
            generationJob = null
            return true
        }
        llmEngine.cancelGeneration()
        job.cancel(CancellationException("Counselor session changed"))
        val stopped = withTimeoutOrNull(GENERATION_STOP_TIMEOUT_MS) {
            job.join()
            true
        } ?: false
        if (stopped) {
            generationJob = null
            return true
        }
        llmEngine.shutdown()
        _uiState.update {
            it.copy(
                phase = CounselorPhase.ERROR,
                statusMessage = "AI lokal masih menghentikan respons sebelumnya. Coba lagi sebentar.",
                runtimeTechnicalMessage = "Generation cancellation timed out"
            )
        }
        return false
    }

    private suspend fun speakIfEnabled(text: String, inputMode: String, messageId: String) {
        if (inputMode != VOICE_INPUT_MODE || !_uiState.value.voiceReplyEnabled) return
        playVoiceReply(messageId, text)
    }

    fun startVoiceInput() {
        val engine = voiceInputEngine ?: run {
            _uiState.update { it.copy(voiceError = "Voice AI belum dikonfigurasi pada aplikasi ini.") }
            return
        }
        when (_uiState.value.voiceInputState) {
            is VoiceInputState.Initializing,
            VoiceInputState.Listening,
            VoiceInputState.Transcribing
            -> return
            else -> Unit
        }
        voiceInputJob?.cancel()
        voiceInputJob = viewModelScope.launch {
            actionGate.run(ACTION_VOICE_INPUT_START) {
                _uiState.update {
                    it.copy(
                        voiceError = null,
                        voiceInputState = VoiceInputState.Initializing("Menyiapkan mikrofon…")
                    )
                }
                runCatching { engine.start(_uiState.value.voiceLanguage) }
                    .onFailure { error ->
                        _uiState.update {
                            it.copy(
                                voiceError = error.message ?: "Voice input gagal dimulai.",
                                voiceInputState = VoiceInputState.Error(
                                    error.message ?: "Voice input gagal dimulai.",
                                    retryable = true
                                )
                            )
                        }
                    }
            }
        }
    }

    fun stopVoiceInput() {
        if (_uiState.value.voiceInputState !is VoiceInputState.Idle) {
            voiceInputEngine?.stop()
        }
    }

    fun cancelVoiceInput() {
        voiceInputJob?.cancel()
        voiceInputJob = null
        voiceInputEngine?.cancel()
        _uiState.update {
            it.copy(
                voiceInputState = VoiceInputState.Idle,
                voiceTranscript = null,
                voiceError = null
            )
        }
    }

    fun retryVoiceInput() {
        cancelVoiceInput()
        startVoiceInput()
    }

    fun setVoiceSendMode(mode: VoiceSendMode) {
        voiceSettingsRepository?.setSendMode(mode)
            ?: _uiState.update { it.copy(voiceSendMode = mode) }
    }

    fun setVoiceReplyEnabled(enabled: Boolean) {
        voiceSettingsRepository?.setReplyEnabled(enabled)
            ?: _uiState.update { it.copy(voiceReplyEnabled = enabled) }
    }

    fun setVoiceLanguage(language: VoiceLanguage) {
        voiceSettingsRepository?.setLanguage(language)
            ?: _uiState.update { it.copy(voiceLanguage = language) }
    }

    fun stopVoiceReply() {
        viewModelScope.launch {
            runCatching { voicePlaybackCoordinator?.stopAndWait() }
                .onFailure { error ->
                    _uiState.update { it.copy(voiceError = error.message ?: "Voice output gagal dihentikan.") }
                }
        }
    }

    fun toggleVoiceReply(messageId: String, text: String) {
        val coordinator = voicePlaybackCoordinator ?: run {
            _uiState.update {
                it.copy(voiceError = "Voice AI belum tersedia. Unduh speech bundle melalui Profile.")
            }
            return
        }
        viewModelScope.launch {
            runCatching {
                coordinator.toggle(messageId, text, _uiState.value.voiceLanguage)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(voiceError = error.message ?: "Voice output gagal diputar.")
                }
            }
        }
    }

    /** Compatibility entry point for callers that do not yet have a message id. */
    fun speakAssistantReply(text: String) = toggleVoiceReply("manual-voice", text)

    private suspend fun playVoiceReply(messageId: String, text: String) {
        voicePlaybackCoordinator?.toggle(messageId, text, _uiState.value.voiceLanguage)
            ?: _uiState.update { it.copy(voiceError = "Voice AI belum tersedia. Unduh speech bundle melalui Profile.") }
    }

    fun runtimeTechnicalDetails(): String? = _uiState.value.runtimeTechnicalMessage

    private fun withDisclaimer(content: String): String = if (
        content.contains("bukan diagnosis", ignoreCase = true) ||
        content.contains("not a diagnosis", ignoreCase = true)
    ) {
        content
    } else {
        "$content\n\nDukungan umum, bukan diagnosis medis."
    }

    /** One real regeneration; no marker insertion, URL invention, or guard bypass. */
    private suspend fun repairCitationOutput(
        request: CounselorGenerationRequest,
        epoch: Long,
        sessionId: String,
        rejected: GuardedCounselorOutput.Rejected
    ): CitationRepairResult {
        requireCurrentTurn(epoch, sessionId)
        check(aiConsentGranted()) { "ai_consent_revoked" }
        check(request.ragMode != RagMode.WEB_GROUNDED || webConsentStore.granted.value) { "web_consent_revoked" }
        _uiState.update { it.copy(statusMessage = "Memeriksa rujukan jawaban…") }
        val markers = request.citationRegistry.bindings.joinToString("\n") { it.marker }
        val repair = request.copy(
            userMessage = "Jawab pertanyaan dalam 1–2 kalimat dari TEKS evidence, bukan judul/daftar sumber. " +
                "Tambahkan marker lengkap PERSIS pada klaim yang didukungnya. Jangan menambah fakta:\n$markers\n\n" +
                "PERTANYAAN:\n${request.userMessage}",
            recentHistory = emptyList()
        )
        val prompt = promptBuilder.prepare(repair)
        val admittedRequest = repair.copy(
            citationRegistry = prompt.citationRegistry,
            retrievedCitations = prompt.citationRegistry.bindings.map { it.citation },
            stressHistoryContext = prompt.admittedStressContext,
            personaProfile = if (prompt.personaIncluded) repair.personaProfile else CounselorPersonaProfile(),
            webCitations = prompt.citationRegistry.webBindings().map { it.citation }
        )
        val assembler = GenerationTextAssembler()
        var failed = false
        llmEngine.generate(admittedRequest).collect { state ->
            requireCurrentTurn(epoch, sessionId)
            when (state) {
                is LlmGenerationState.Content -> assembler.append(state)
                LlmGenerationState.Cancelled -> throw CancellationException("Citation regeneration cancelled")
                is LlmGenerationState.Failed, is LlmGenerationState.Unavailable -> failed = true
            }
        }
        requireCurrentTurn(epoch, sessionId)
        val output = if (failed || assembler.currentText().isBlank()) {
            rejected
        } else {
            outputGuard.validate(
                assembler.currentText(),
                admittedRequest.ragMode,
                admittedRequest.retrievedCitations,
                prompt.citationRegistry
            )
        }
        return CitationRepairResult(output, prompt)
    }

    private data class CitationRepairResult(val output: GuardedCounselorOutput, val prompt: PreparedCounselorPrompt)

    private suspend fun persist(
        message: UiChatMessage,
        sessionId: String,
        stressContext: String? = null,
        promptVersion: String? = null,
        retrievalMode: RagMode? = null
    ) {
        val timestamp = message.timestampMs
        if (message.sender == "user") {
            val session = _uiState.value.chatSessions.firstOrNull {
                it.id == sessionId
            }
            val title = if (session == null || session.title == "Chat baru") {
                conversationTitle(message.content)
            } else {
                session.title
            }
            chatDao.insertChatSession(
                ChatSessionEntity(
                    chatSessionId = sessionId,
                    title = title,
                    startedAtMillis = session?.startedAtMillis ?: timestamp,
                    lastMessageAtMillis = timestamp
                )
            )
        }
        chatDao.insertMessage(message.toEntity(sessionId, stressContext, promptVersion, retrievalMode))
        chatDao.touchChatSession(sessionId, timestamp)
    }

    private fun UiChatMessage.toEntity(
        sessionId: String,
        stressContext: String? = null,
        promptVersion: String? = null,
        retrievalMode: RagMode? = null
    ) = ChatMessageEntity(
        messageId = id,
        chatSessionId = sessionId,
        sender = sender,
        content = content,
        timestampMs = timestampMs,
        citationsJson = gson.toJson(citations),
        stressContextJson = stressContext,
        promptVersion = promptVersion,
        retrievalMode = retrievalMode?.name,
        inputMode = inputMode
    )

    private fun recentHistory(text: String, currentUserMessageId: String?): List<ConversationTurn> {
        val messages = _uiState.value.messages
            .filter { it.sender == "user" || it.sender == "counselor" }
            .filter { it.content.isNotBlank() }
            .filterNot { currentUserMessageId != null && it.id == currentUserMessageId }
            .let { history ->
                if (currentUserMessageId == null &&
                    history.lastOrNull()?.sender == "user" &&
                    history.lastOrNull()?.content == text
                ) {
                    history.dropLast(1)
                } else {
                    history
                }
            }
            .takeLast(MAX_HISTORY_MESSAGES)
        return messages.map { message ->
            ConversationTurn(
                role = if (message.sender == "user") "user" else "assistant",
                content = message.content,
                sourceId = message.id
            )
        }
    }

    private fun conversationTitle(content: String): String = content
        .replace(Regex("\\s+"), " ")
        .trim()
        .let { normalized ->
            if (normalized.length <= 42) normalized else normalized.take(42).trimEnd() + "…"
        }

    private fun toUiMessage(entity: ChatMessageEntity): UiChatMessage {
        val contextMetadata = parseStressContextMetadata(entity.stressContextJson)
        val retrievalMode = entity.retrievalMode?.let { value ->
            runCatching { RagMode.valueOf(value) }.getOrNull()
        }
        return UiChatMessage(
            id = entity.messageId,
            sender = entity.sender,
            content = entity.content,
            timestampMs = entity.timestampMs,
            citations = parseCitations(entity.citationsJson),
            inputMode = entity.inputMode ?: "TEXT",
            stressContextVersion = contextMetadata?.version,
            stressContextSessionIds = contextMetadata?.sourceSessionIds.orEmpty(),
            promptVersion = entity.promptVersion,
            retrievalMode = retrievalMode,
            crisisSupportActions = if (entity.sender == CRISIS_SYSTEM_SENDER) {
                crisisResponseTemplateProvider.supportActionsFor(entity.promptVersion)
            } else {
                emptyList()
            }
        )
    }

    private fun toUiSession(entity: ChatSessionEntity) = UiChatSession(
        id = entity.chatSessionId,
        title = entity.title,
        startedAtMillis = entity.startedAtMillis,
        lastMessageAtMillis = entity.lastMessageAtMillis
    )

    private fun parseCitations(json: String?): List<KnowledgeCitation> = runCatching {
        if (json.isNullOrBlank()) {
            emptyList()
        } else {
            gson.fromJson<List<KnowledgeCitation>>(
                json,
                object : TypeToken<List<KnowledgeCitation>>() {}.type
            )
        }
    }.getOrDefault(emptyList()).map { citation ->
        // Gson does not apply Kotlin constructor defaults to fields omitted by
        // older citation JSON. The `else` branch also handles that runtime-null
        // value without weakening the domain contract for new citations.
        val sourceType = if (citation.sourceType == CitationSource.WEB) {
            CitationSource.WEB
        } else {
            CitationSource.LOCAL_DOCUMENT
        }
        val sourceTier = when {
            sourceType == CitationSource.WEB -> EvidenceSourceTier.WEB
            citation.sourceTier == EvidenceSourceTier.BUNDLED_LOCAL -> EvidenceSourceTier.BUNDLED_LOCAL
            else -> EvidenceSourceTier.APP_RAG
        }
        citation.copy(sourceType = sourceType, sourceTier = sourceTier)
    }

    private fun parseStressContextMetadata(json: String?): PersistedStressContextMetadata? = runCatching {
        if (json.isNullOrBlank()) {
            null
        } else {
            gson.fromJson<PersistedStressContextMetadata>(
                json,
                PersistedStressContextMetadata::class.java
            )
        }
    }.getOrNull()?.takeIf { it.version.isNotBlank() }

    override fun onCleared() {
        turnEpoch++
        webFlowJob?.cancel()
        voiceInputJob?.cancel()
        generationJob?.cancel()
        messageObservationJob?.cancel()
        llmEngine.cancelGeneration()
        voicePlaybackCoordinator?.close()
        voiceInputEngine?.close()
        llmEngine.shutdown()
        super.onCleared()
    }

    private suspend fun readStressContext(): StressContextPayload {
        return try {
            if (!_uiState.value.useStressHistory) {
                return StressContextPayload(
                    promptText = "Stress history context is disabled by the user.",
                    snapshotJson = gson.toJson(
                        mapOf("version" to "stress-history-disabled", "sourceSessionIds" to emptyList<String>())
                    ),
                    sourceSessionIds = emptyList(),
                    excludedHistoryReasons = emptyList()
                )
            }
            val contextSnapshot = stressHistoryContextProvider.snapshot()
            StressContextPayload(
                promptText = contextSnapshot.toPromptContext(),
                snapshotJson = gson.toJson(contextSnapshot),
                sourceSessionIds = contextSnapshot.sourceSessionIds,
                excludedHistoryReasons = contextSnapshot.entries
                    .filterNot { it.valid }
                    .mapNotNull { it.rejectionReason }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(stressHistoryStatus = "Riwayat stress tidak tersedia: ${e.message ?: "storage error"}")
            }
            StressContextPayload(
                promptText = "Stress history is unavailable because local storage failed. Do not infer stress history.",
                snapshotJson = gson.toJson(
                    mapOf("version" to "stress-history-unavailable", "sourceSessionIds" to emptyList<String>())
                ),
                sourceSessionIds = emptyList(),
                excludedHistoryReasons = listOf("stress_history_storage_unavailable")
            )
        }
    }

    private data class StressContextPayload(
        val promptText: String,
        val snapshotJson: String,
        val sourceSessionIds: List<String>,
        val excludedHistoryReasons: List<String>
    ) {
        fun forPrompt(prompt: PreparedCounselorPrompt): StressContextPayload = if (prompt.admittedStressContext == promptText) {
            this
        } else {
            copy(
                promptText = prompt.admittedStressContext,
                snapshotJson = Gson().toJson(mapOf("admittedPromptText" to prompt.admittedStressContext)),
                sourceSessionIds = emptyList(),
                excludedHistoryReasons = excludedHistoryReasons + "stress_history_prompt_budget_limited"
            )
        }

        fun persistedJson(receipt: CounselorContextReceipt): String = Gson().toJson(
            PersistedCounselorContext(
                version = "counselor-context-envelope-v1",
                sourceSessionIds = sourceSessionIds.distinct().sorted(),
                excludedHistoryReasons = excludedHistoryReasons.distinct().sorted(),
                stressHistorySnapshotJson = snapshotJson,
                receipt = receipt
            )
        )
    }

    private data class PersistedCounselorContext(
        val version: String,
        val sourceSessionIds: List<String>,
        val excludedHistoryReasons: List<String>,
        val stressHistorySnapshotJson: String,
        val receipt: CounselorContextReceipt
    )

    private data class PersistedStressContextMetadata(val version: String = "", val sourceSessionIds: List<String> = emptyList())

    private data class RetrievalDecision(
        val mode: RagMode,
        val citations: List<KnowledgeCitation>,
        val statusMessage: String?,
        val webCitations: List<KnowledgeCitation> = emptyList(),
        val sourceTier: EvidenceSourceTier = EvidenceSourceTier.NONE
    )

    private suspend fun resolveWebGrounding(
        rawUserText: String,
        retrieval: RagRetrievalResult,
        webGroundingOverride: WebGroundingResult?,
        webStatusMessage: String? = null
    ): RetrievalDecision? {
        val localDecision = retrieval.toDecision(rawUserText)
        val grounding = webGroundingOverride ?: return if (webStatusMessage == null) {
            localDecision
        } else {
            localDecision.copy(statusMessage = webStatusMessage)
        }
        val webCitations = grounding.citations
            .filter {
                it.sourceType == CitationSource.WEB &&
                    it.sourceTier == EvidenceSourceTier.WEB &&
                    !it.sourceUrl.isNullOrBlank()
            }
        if (webCitations.isEmpty()) {
            _uiState.update {
                it.copy(
                    isGenerating = false,
                    phase = CounselorPhase.ERROR,
                    statusMessage = "Web Search tidak menemukan citation yang valid.",
                    lastFailedUserMessage = rawUserText
                )
            }
            return null
        }
        return RetrievalDecision(
            mode = RagMode.WEB_GROUNDED,
            citations = (localDecision.citations + webCitations).distinctBy(KnowledgeCitation::citationId),
            statusMessage = "Jawaban menggunakan sumber web yang berhasil dibaca.",
            webCitations = webCitations,
            sourceTier = EvidenceSourceTier.WEB
        )
    }

    private fun RagRetrievalResult.toDecision(userText: String): RetrievalDecision {
        if (this is RagRetrievalResult.Retrieved && citations.isNotEmpty()) {
            val tier = sourceTier.takeUnless { it == EvidenceSourceTier.NONE }
                ?: citations.firstOrNull()?.sourceTier
                ?: EvidenceSourceTier.APP_RAG
            return groundedDecision(tier, citations)
        }

        val requiresEvidence = DocumentGroundingIntentDetector.requiresLocalEvidence(userText)
        return unsupportedEvidenceDecision(requiresEvidence)
    }

    private fun groundedDecision(tier: EvidenceSourceTier, citations: List<KnowledgeCitation>): RetrievalDecision = RetrievalDecision(
        mode = when (tier) {
            EvidenceSourceTier.APP_RAG -> RagMode.GROUNDED
            EvidenceSourceTier.BUNDLED_LOCAL -> RagMode.BUNDLED_GROUNDED
            EvidenceSourceTier.WEB -> RagMode.WEB_GROUNDED
            EvidenceSourceTier.NONE -> RagMode.UNAVAILABLE
        },
        citations = citations,
        statusMessage = when (tier) {
            EvidenceSourceTier.APP_RAG -> "Sumber aktif: dokumen lokal."
            EvidenceSourceTier.BUNDLED_LOCAL -> "Sumber aktif: dataset lokal bawaan."
            EvidenceSourceTier.WEB -> "Sumber aktif: web."
            EvidenceSourceTier.NONE -> "Evidence belum tersedia."
        },
        sourceTier = tier
    )

    private fun unsupportedEvidenceDecision(requiresEvidence: Boolean): RetrievalDecision = RetrievalDecision(
        mode = if (requiresEvidence) RagMode.UNAVAILABLE else RagMode.GENERAL_SAFE,
        citations = emptyList(),
        statusMessage = if (requiresEvidence) {
            "Evidence yang dibutuhkan belum tersedia; jawaban tidak dibuat."
        } else {
            // The saved answer already carries the disclaimer; do not repeat it as a status banner.
            null
        },
        sourceTier = EvidenceSourceTier.NONE
    )

    private fun RagMode.isGrounded(): Boolean = this == RagMode.GROUNDED ||
        this == RagMode.BUNDLED_GROUNDED ||
        this == RagMode.WEB_GROUNDED

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = AppLocator.database(context)
            return CounselorViewModel(
                llmEngine = AppLocator.llmEngine(context),
                ragRetriever = AppLocator.ragRetriever(context),
                chatDao = db.chatDao(),
                modelManager = AppLocator.modelManager(context),
                promptAssetProvider = AssetPromptAssetProvider(context),
                personaRepository = AppLocator.counselorPersonaRepository(context),
                voiceInputEngine = AppLocator.voiceInputEngine(context),
                voiceOutputEngine = AppLocator.voiceOutputEngine(context),
                voiceSettingsRepository = AppLocator.voiceSettingsRepository(context),
                webGroundingGateway = AppLocator.webGroundingGateway(context),
                stressHistoryContextProvider = AppLocator.stressHistoryContextProvider(context),
                retrievalPolicyBinding = AppLocator.retrievalPolicyBinding(context),
                aiConsentGranted = {
                    db.consentDao().getConsent("AI_COUNSELING")?.isGranted == true
                },
                webConsentStore = AppLocator.webConsentStore(context),
                sessionSelectionStore = SharedPreferencesCounselorSessionSelectionStore(context)
            ) as T
        }
    }

    companion object {
        private const val MAX_HISTORY_MESSAGES = 64
        private const val WEB_SEARCH_TIMEOUT_MILLIS = 30_000L
        private const val GENERATION_STOP_TIMEOUT_MS = 5_000L
        private const val ACTION_NEW_CHAT = "chat:new"
        private const val ACTION_DELETE_ALL_CHATS = "chat:delete-all"
        private const val ACTION_VOICE_INPUT_START = "voice-input:start"
        private const val USER_SENDER = "user"
        private const val CRISIS_SYSTEM_SENDER = "crisis_system"
        private const val VOICE_INPUT_MODE = "VOICE"
        private const val NEW_CHAT_TITLE = "Chat baru"
        private const val CRISIS_STATUS_MESSAGE = "Dukungan keselamatan ditampilkan. Anda tidak perlu menunggu AI lokal."
        private const val CRISIS_PERSISTENCE_FAILURE_MESSAGE =
            "Dukungan keselamatan tetap tampil, tetapi belum tersimpan di perangkat. Coba simpan lagi."
        val DEFAULT_PROMPT = PromptAsset(
            id = "counselor_test",
            version = "test",
            systemPrompt = "You are a supportive non-diagnostic counselor.",
            userPromptTemplate = "{user_message}"
        )
    }
}

private data class PendingWebRequest(
    val rawText: String,
    val inputMode: String,
    val userMessage: UiChatMessage,
    val mode: WebSearchMode,
    val sessionId: String,
    val turnEpoch: Long,
    val persistUserMessage: Boolean = true,
    val localRetrieval: RagRetrievalResult? = null
)

private fun Throwable.toSafeMessage(default: String): String = message?.takeIf { it.isNotBlank() && it.length <= 160 } ?: default
