package com.stressdetector.mobile.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stressdetector.mobile.BuildConfig
import com.stressdetector.mobile.consent.ConsentRecord
import com.stressdetector.mobile.consent.ConsentRepository
import com.stressdetector.mobile.consent.ConsentScope
import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.domain.engine.AcousticAnalysisSnapshot
import com.stressdetector.mobile.domain.engine.AnnotationRepository
import com.stressdetector.mobile.domain.engine.AudioCaptureState
import com.stressdetector.mobile.domain.engine.AudioInputSource
import com.stressdetector.mobile.domain.engine.AudioIntelligenceEngine
import com.stressdetector.mobile.domain.engine.DomainAnnotation
import com.stressdetector.mobile.domain.engine.LiveBiosignalEngine
import com.stressdetector.mobile.domain.engine.LiveBiosignalSnapshot
import com.stressdetector.mobile.domain.engine.LiveMonitorStopSummary
import com.stressdetector.mobile.domain.engine.PlaybackState
import com.stressdetector.mobile.domain.engine.SessionFinalizationStage
import com.stressdetector.mobile.domain.engine.SessionFinalizationState
import com.stressdetector.mobile.domain.engine.SpeechProcessingState
import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import com.stressdetector.mobile.domain.engine.SpeechSegment
import com.stressdetector.mobile.domain.engine.UnifiedSessionEngine
import com.stressdetector.mobile.domain.engine.UnifiedSessionLiveSnapshot
import com.stressdetector.mobile.domain.engine.UniqueSessionNameGenerator
import com.stressdetector.mobile.domain.engine.VerbatimArtifactService
import com.stressdetector.mobile.domain.engine.VerbatimPlaybackCoordinator
import com.stressdetector.mobile.domain.engine.VerbatimSessionDetail
import com.stressdetector.mobile.domain.engine.VerbatimSessionRepository
import com.stressdetector.mobile.domain.engine.VerbatimSessionSummary
import com.stressdetector.mobile.domain.interaction.IdempotentActionGate
import com.stressdetector.mobile.ml.storage.ModelStorageManager
import com.stressdetector.mobile.ml.storage.ModelStorageState
import com.stressdetector.mobile.ml.storage.SafStorageStatus
import com.stressdetector.mobile.security.PrivacyIdentity
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SessionCommandState {
    data object Idle : SessionCommandState
    data object Starting : SessionCommandState
    data object Recording : SessionCommandState
    data object Pausing : SessionCommandState
    data object Paused : SessionCommandState
    data object Resuming : SessionCommandState
    data object Stopping : SessionCommandState
    data object Finalizing : SessionCommandState
    data class Error(val message: String) : SessionCommandState
}

sealed interface UnifiedSessionEffect {
    data object OpenStartDialog : UnifiedSessionEffect
    data class ShowToast(val message: String) : UnifiedSessionEffect
}

@androidx.compose.runtime.Immutable
data class UnifiedSessionUiState(
    val isSessionActive: Boolean = false,
    val transcriptSegments: List<SpeechSegment> = emptyList(),
    val divergenceEvents: List<DivergenceEventEntity> = emptyList(),
    val annotations: List<DomainAnnotation> = emptyList(),
    val liveBiosignal: LiveBiosignalSnapshot = LiveBiosignalSnapshot(),
    val acousticAnalysis: AcousticAnalysisSnapshot? = null,
    val livePitch: List<com.stressdetector.mobile.domain.engine.LivePitchObservation> = emptyList(),
    val elapsedSeconds: Long = 0L,
    val isModelReady: Boolean = false,
    val speechRuntimeState: SpeechRuntimeState = SpeechRuntimeState.Checking,
    val storageState: ModelStorageState = ModelStorageState(),
    val monitorStatus: String = "UNAVAILABLE: not started",
    val watchStatus: String = "Watch belum terhubung",
    val isPaused: Boolean = false,
    val savedFolderPath: String? = null,
    val playbackFilePath: String? = null,
    val isFinalizing: Boolean = false,
    val selectedLanguage: String = "id",
    val lastMonitorSummary: LiveMonitorStopSummary? = null,
    val sessionHistory: List<VerbatimSessionSummary> = emptyList(),
    val isSessionHistoryLoading: Boolean = true,
    val sessionHistoryError: String? = null,
    val selectedSessionId: String? = null,
    val sessionGeneration: Long = 0L,
    val selectedSession: VerbatimSessionDetail? = null,
    val playbackState: PlaybackState = PlaybackState.Idle,
    val playbackPositionMillis: Long = 0L,
    val playbackDurationMillis: Long = 0L,
    val sessionCommandState: SessionCommandState = SessionCommandState.Idle,
    val finalizationStage: SessionFinalizationStage = SessionFinalizationStage.RECORDING,
    val finalizationEvidencePersisted: Boolean? = null,
    val finalizationWarnings: List<String> = emptyList(),
    val speechProcessingState: SpeechProcessingState = SpeechProcessingState(),
    val isExporting: Boolean = false,
    val isDeletingSession: Boolean = false,
    val isSavingTranscript: Boolean = false,
    val error: String? = null
) {
    /** Pure review projection; business rules remain outside Composables. */
    val cueAnalyses: Map<String, com.stressdetector.mobile.domain.engine.VerbatimCueAnalysis> by lazy {
        val analyzer = com.stressdetector.mobile.domain.engine.VerbatimCueAnalyzer()
        transcriptSegments.associate { it.id to analyzer.analyze(it) }
    }
    val markerPlaybackOffsets: Map<String, Long> by lazy {
        divergenceEvents.mapNotNull { event ->
            com.stressdetector.mobile.domain.engine.TranscriptTimelineLinker.playbackOffset(event, transcriptSegments)
                ?.let { event.eventId to it }
        }.toMap()
    }
    val reviewCues: List<com.stressdetector.mobile.domain.engine.VerbatimCue> by lazy {
        cueAnalyses.values.flatMap { it.cues }
            .sortedWith(compareBy({ it.startOffsetMs }, { it.startChar }, { it.id }))
    }
    val canStartNewSession: Boolean
        get() = !isSessionActive &&
            !isFinalizing &&
            !isExporting &&
            !isSavingTranscript &&
            !isDeletingSession &&
            sessionCommandState.acceptsNewSessionRequest() &&
            speechRuntimeState is SpeechRuntimeState.Ready

    val startBlockMessage: String?
        get() = if (sessionCommandState.acceptsNewSessionRequest()) {
            speechRuntimeState.verbatimStartBlockedMessage()
        } else {
            null
        }

    /** Buffered live updates cannot overwrite saved review edits after finalization. */
    internal fun acceptsLiveSnapshot(snapshot: UnifiedSessionLiveSnapshot): Boolean {
        val liveReview = isSessionActive || isFinalizing || sessionCommandState.locksSessionSelection()
        val ownsSnapshot = selectedSessionId != null &&
            snapshot.sessionId == selectedSessionId &&
            snapshot.transcript.sessionId == selectedSessionId
        return liveReview && ownsSnapshot && isLiveSessionGenerationAccepted(sessionGeneration, snapshot)
    }
}

internal fun SessionCommandState.acceptsNewSessionRequest(): Boolean = this is SessionCommandState.Idle || this is SessionCommandState.Error

internal fun SpeechRuntimeState.verbatimStartBlockedMessage(): String? = when (this) {
    SpeechRuntimeState.Checking -> "Speech bundle sedang diperiksa. Coba lagi sebentar."
    SpeechRuntimeState.Ready -> null
    is SpeechRuntimeState.Unavailable -> reason.ifBlank {
        "Sherpa Speech Bundle belum siap. Unduh atau import melalui Profile."
    }
    is SpeechRuntimeState.Failed -> message.ifBlank {
        "Sherpa runtime gagal diaktifkan. Periksa bundle melalui Profile."
    }
}

internal fun SpeechRuntimeState.verbatimStatusLabel(): String = when (this) {
    SpeechRuntimeState.Checking -> "Memeriksa speech runtime…"
    SpeechRuntimeState.Ready -> "Siap digunakan"
    else -> verbatimStartBlockedMessage().orEmpty()
}

internal fun ModelStorageState.verbatimStatusLabel(): String = when (status) {
    SafStorageStatus.UNSELECTED -> "Folder belum terhubung"
    SafStorageStatus.PREFLIGHTING -> "Memeriksa folder"
    SafStorageStatus.CONNECTED -> "Folder tersambung"
    SafStorageStatus.SYNCING -> "Sinkronisasi berjalan"
    SafStorageStatus.PERMISSION_LOST -> "Izin folder hilang"
    SafStorageStatus.PROVIDER_UNAVAILABLE -> "Folder tidak tersedia"
    SafStorageStatus.UNSUPPORTED_PROVIDER -> "Folder tidak didukung"
    SafStorageStatus.INSUFFICIENT_SPACE -> "Ruang penyimpanan tidak cukup"
}

internal fun ModelStorageState.verbatimRecoveryMessage(): String = when (status) {
    SafStorageStatus.UNSELECTED ->
        "Folder model belum terhubung. Hubungkan melalui Profile agar artefak offline dapat disinkronkan."
    SafStorageStatus.PREFLIGHTING -> "Folder sedang diperiksa. Tunggu sampai pemeriksaan selesai."
    SafStorageStatus.CONNECTED -> "Penyimpanan offline siap digunakan."
    SafStorageStatus.SYNCING ->
        "Artefak offline sedang disinkronkan. Rekaman lokal tetap mengikuti runtime yang tersedia."
    SafStorageStatus.PERMISSION_LOST -> "Izin folder hilang. Hubungkan kembali folder melalui Profile."
    SafStorageStatus.PROVIDER_UNAVAILABLE ->
        "Folder tidak dapat diakses. Periksa penyimpanan lalu hubungkan kembali melalui Profile."
    SafStorageStatus.UNSUPPORTED_PROVIDER -> "Gunakan folder lokal perangkat yang mendukung penyimpanan offline."
    SafStorageStatus.INSUFFICIENT_SPACE -> "Ruang penyimpanan tidak cukup untuk artefak offline."
}

internal fun ModelStorageState.needsVerbatimStorageRecovery(): Boolean = !connected || status != SafStorageStatus.CONNECTED

internal fun VerbatimSessionSummary.drawerStatusLabel(): String = when {
    finalizationStatus.equals("PERSISTING", ignoreCase = true) -> "Menyimpan"
    finalizationStatus.equals("FAILED_WITH_EVIDENCE", ignoreCase = true) -> "Selesai sebagian"
    finalizationStatus.equals("COMPLETED", ignoreCase = true) -> "Tersimpan"
    finalizationStatus.equals("FAILED", ignoreCase = true) -> "Gagal menyimpan"
    else -> "Perlu ditinjau"
}

internal fun VerbatimSessionSummary.drawerStorageLabel(): String = when {
    artifactFolderPath.isNotBlank() -> "artefak siap"
    audioFilePath.isNotBlank() -> "audio tersimpan · artefak belum siap"
    else -> "artefak belum tersedia"
}

@OptIn(FlowPreview::class)
@Suppress("LongParameterList")
class UnifiedSessionViewModel(
    context: android.content.Context,
    private val unifiedSessionEngine: UnifiedSessionEngine,
    private val audioIntelligenceEngine: AudioIntelligenceEngine,
    private val liveBiosignalEngine: LiveBiosignalEngine,
    private val modelManager: com.stressdetector.mobile.ml.llm.ModelManager,
    private val consentRepository: ConsentRepository,
    private val artifactService: VerbatimArtifactService,
    private val sessionRepository: VerbatimSessionRepository,
    private val playbackCoordinator: VerbatimPlaybackCoordinator,
    private val annotationRepository: AnnotationRepository,
    private val modelStorageManager: ModelStorageManager? = null
) : ViewModel() {
    private val applicationContext = context.applicationContext

    private val _uiState = MutableStateFlow(UnifiedSessionUiState())
    val uiState: StateFlow<UnifiedSessionUiState> = _uiState.asStateFlow()
    private val _effects = MutableSharedFlow<UnifiedSessionEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<UnifiedSessionEffect> = _effects.asSharedFlow()

    private var timerJob: kotlinx.coroutines.Job? = null
    private var selectedSessionJob: Job? = null
    private var playbackLoadJob: Job? = null
    private var preparedSessionKey: String? = null
    private var loadingSessionKey: String? = null
    private var playbackRequestId = 0L
    private var historyJob: Job? = null
    private val actionGate = IdempotentActionGate<String>()
    private val sessionNameGenerator = UniqueSessionNameGenerator()
    private val reservedSessionNames = mutableSetOf<String>()
    private var pendingAudioInput: AudioInputSource = AudioInputSource.Microphone
    private var pendingTextExport: Pair<VerbatimSessionDetail, List<DomainAnnotation>>? = null
    private var pendingZipExport: VerbatimSessionSummary? = null

    init {
        checkModelState()
        refreshSpeechRuntime()

        modelStorageManager?.let { storage ->
            viewModelScope.launch {
                storage.state.collect { state ->
                    _uiState.update { it.copy(storageState = state) }
                }
            }
            viewModelScope.launch(Dispatchers.IO) {
                storage.reconcile()
            }
        }

        viewModelScope.launch {
            unifiedSessionEngine.sessionError.collect { error ->
                if (error != null) {
                    val runtimeMessage = (error as? com.stressdetector.mobile.domain.engine.SessionOperationError.StartFailed)
                        ?.let { audioIntelligenceEngine.speechRuntimeState.value.verbatimStartBlockedMessage() }
                    if (runtimeMessage != null) {
                        _effects.emit(UnifiedSessionEffect.ShowToast(runtimeMessage))
                    }
                    _uiState.update {
                        it.copy(
                            sessionCommandState = SessionCommandState.Error(error.toString()),
                            isFinalizing = false,
                            error = error.toString()
                        )
                    }
                }
            }
        }

        observeSessionHistory()

        viewModelScope.launch {
            playbackCoordinator.state.collect { state ->
                _uiState.update { it.copy(playbackState = state) }
            }
        }

        viewModelScope.launch {
            playbackCoordinator.positionMillis.collect { position ->
                _uiState.update { it.copy(playbackPositionMillis = position) }
            }
        }

        viewModelScope.launch {
            playbackCoordinator.durationMillis.collect { duration ->
                _uiState.update { it.copy(playbackDurationMillis = duration) }
            }
        }

        viewModelScope.launch {
            unifiedSessionEngine.monitorStatus.collect { status ->
                _uiState.update { it.copy(monitorStatus = status) }
            }
        }

        viewModelScope.launch {
            unifiedSessionEngine.watchSessionState.collect { state ->
                _uiState.update { it.copy(watchStatus = watchStatusText(state)) }
            }
        }

        viewModelScope.launch {
            unifiedSessionEngine.lastArtifacts.collect { artifacts ->
                if (artifacts == null) return@collect
                _uiState.update {
                    it.copy(
                        savedFolderPath = artifacts.folderPath,
                        isFinalizing = false,
                        sessionCommandState = if (it.isSessionActive) {
                            it.sessionCommandState
                        } else {
                            SessionCommandState.Idle
                        }
                    )
                }
            }
        }

        viewModelScope.launch {
            unifiedSessionEngine.finalizationStage.collect { stage ->
                _uiState.update { current -> current.copy(finalizationStage = stage) }
            }
        }

        viewModelScope.launch {
            unifiedSessionEngine.finalizationState.collect { state ->
                when (state) {
                    SessionFinalizationState.Idle -> Unit
                    is SessionFinalizationState.Completed -> {
                        _uiState.update { current ->
                            current.copy(
                                isFinalizing = false,
                                sessionCommandState = if (current.isSessionActive) {
                                    current.sessionCommandState
                                } else {
                                    SessionCommandState.Idle
                                },
                                finalizationEvidencePersisted = true,
                                finalizationWarnings = emptyList(),
                                error = null
                            )
                        }
                        selectSession(state.sessionId)
                    }
                    is SessionFinalizationState.Failed -> {
                        _uiState.update { current ->
                            current.copy(
                                isFinalizing = false,
                                sessionCommandState = SessionCommandState.Error(state.message),
                                finalizationEvidencePersisted = state.evidencePersisted,
                                finalizationWarnings = state.warnings,
                                error = state.message
                            )
                        }
                        if (state.evidencePersisted) selectSession(state.sessionId)
                        _uiState.update { current ->
                            if (!state.evidencePersisted || current.selectedSessionId == state.sessionId) {
                                current.copy(error = state.message)
                            } else {
                                current
                            }
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            unifiedSessionEngine.isSessionActive.collect { isActive ->
                _uiState.update { current ->
                    current.copy(
                        isSessionActive = isActive,
                        sessionCommandState = when {
                            isActive && current.sessionCommandState !is SessionCommandState.Paused ->
                                SessionCommandState.Recording
                            !isActive && current.sessionCommandState is SessionCommandState.Stopping ->
                                SessionCommandState.Finalizing
                            !isActive &&
                                !current.isFinalizing &&
                                current.sessionCommandState !is SessionCommandState.Starting -> SessionCommandState.Idle
                            else -> current.sessionCommandState
                        }
                    )
                }
                if (isActive) {
                    startTimer()
                } else {
                    stopTimer()
                }
            }
        }

        viewModelScope.launch {
            unifiedSessionEngine.liveSessionState
                .sample(LIVE_SESSION_UI_SAMPLE_MILLIS)
                .collect { live -> applyLiveSessionSnapshot(live) }
        }

        viewModelScope.launch {
            liveBiosignalEngine.summaryFlow.collect { summary ->
                _uiState.update { it.copy(lastMonitorSummary = summary) }
            }
        }

        viewModelScope.launch {
            audioIntelligenceEngine.speechRuntimeState.collect { state ->
                _uiState.update { it.copy(speechRuntimeState = state) }
            }
        }

        viewModelScope.launch {
            audioIntelligenceEngine.captureState.collect { state ->
                _uiState.update { current ->
                    current.copy(
                        isPaused = state is AudioCaptureState.Paused,
                        sessionCommandState = when (state) {
                            is AudioCaptureState.Recording -> SessionCommandState.Recording
                            is AudioCaptureState.Paused -> SessionCommandState.Paused
                            is AudioCaptureState.Failed -> SessionCommandState.Error(state.message)
                            else -> current.sessionCommandState
                        }
                    )
                }
            }
        }
    }

    private fun observeSessionHistory() {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            _uiState.update { it.copy(isSessionHistoryLoading = true, sessionHistoryError = null) }
            sessionRepository.observeSessions()
                .catch { error ->
                    _uiState.update {
                        it.copy(
                            isSessionHistoryLoading = false,
                            sessionHistoryError = error.message ?: "Daftar sesi tidak dapat dimuat."
                        )
                    }
                }
                .collect { sessions ->
                    val current = _uiState.value
                    _uiState.update {
                        it.copy(
                            sessionHistory = sessions,
                            isSessionHistoryLoading = false,
                            sessionHistoryError = null
                        )
                    }
                    if (!current.sessionCommandState.locksSessionSelection() &&
                        !current.isSessionActive &&
                        current.selectedSessionId == null
                    ) {
                        sessions.firstOrNull()?.sessionId?.let(::selectSession)
                    }
                }
        }
    }

    fun refreshSessionHistory() {
        observeSessionHistory()
    }

    private fun applyLiveSessionSnapshot(live: UnifiedSessionLiveSnapshot) {
        _uiState.update { current ->
            if (!current.acceptsLiveSnapshot(live)) {
                current
            } else {
                val safeBiosignal = live.biosignal.takeIf { it.sessionId == live.sessionId }
                    ?: LiveBiosignalSnapshot(sessionId = live.sessionId)
                current.copy(
                    transcriptSegments = live.transcript.segments,
                    speechProcessingState = live.transcript.processing,
                    liveBiosignal = safeBiosignal,
                    acousticAnalysis = live.acousticAnalysis,
                    livePitch = live.livePitch,
                    sessionGeneration = live.generation,
                    divergenceEvents = safeBiosignal.divergenceEvents
                )
            }
        }
    }

    private fun checkModelState() {
        viewModelScope.launch(Dispatchers.IO) {
            val readiness = modelManager.refreshReadiness()
            val ready = readiness is com.stressdetector.mobile.ml.llm.ModelReadiness.Ready
            _uiState.update { it.copy(isModelReady = ready) }
            // Gemma is an optional end-of-session summary capability; it must not block recording.
        }

        viewModelScope.launch {
            modelManager.isReady.collect { isReady ->
                _uiState.update { it.copy(isModelReady = isReady) }
            }
        }
    }

    /** Called by the screen lifecycle and after returning from Profile. */
    fun refreshSpeechRuntime() {
        if (unifiedSessionEngine.isSessionActive.value) return
        viewModelScope.launch(Dispatchers.IO) {
            actionGate.run(ACTION_SPEECH_RUNTIME_REFRESH) {
                refreshSpeechRuntimeForStart()
            }
        }
    }

    fun refreshStorage() {
        val storage = modelStorageManager ?: return
        viewModelScope.launch(Dispatchers.IO) {
            storage.reconcile()
        }
    }

    /** Routes every new-recording entry point through one readiness decision. */
    fun requestNewSession(audioInput: AudioInputSource = AudioInputSource.Microphone) {
        viewModelScope.launch {
            actionGate.run(ACTION_SESSION_START_REQUEST) {
                val current = _uiState.value
                if (!current.sessionCommandState.acceptsNewSessionRequest() ||
                    !VerbatimWorkspace(current).canManageStoredSessions
                ) {
                    return@run
                }
                val blockedMessage = current.speechRuntimeState.verbatimStartBlockedMessage()
                if (blockedMessage == null) {
                    pendingAudioInput = audioInput
                    _effects.emit(UnifiedSessionEffect.OpenStartDialog)
                } else {
                    _effects.emit(UnifiedSessionEffect.ShowToast(blockedMessage))
                }
            }
        }
    }

    private suspend fun refreshSpeechRuntimeForStart(): SpeechRuntimeState {
        _uiState.update { it.copy(speechRuntimeState = SpeechRuntimeState.Checking) }
        val state = runCatching { audioIntelligenceEngine.refreshSpeechRuntime() }
            .getOrElse { error ->
                SpeechRuntimeState.Failed(
                    error.message ?: "Speech runtime gagal diperiksa."
                )
            }
        _uiState.update { it.copy(speechRuntimeState = state) }
        return state
    }

    private fun watchStatusText(state: com.stressdetector.mobile.domain.engine.WatchSessionState): String = when (state) {
        com.stressdetector.mobile.domain.engine.WatchSessionState.Idle -> "Watch belum terhubung"
        com.stressdetector.mobile.domain.engine.WatchSessionState.Connecting -> "Menghubungkan Watch"
        com.stressdetector.mobile.domain.engine.WatchSessionState.Ready -> "Watch siap"
        com.stressdetector.mobile.domain.engine.WatchSessionState.Streaming -> "Watch streaming"
        com.stressdetector.mobile.domain.engine.WatchSessionState.Stopping -> "Menghentikan Watch"
        is com.stressdetector.mobile.domain.engine.WatchSessionState.Error -> "Watch error: ${state.message}"
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                val elapsed = unifiedSessionEngine.currentElapsedMillis() / 1_000L
                _uiState.update { it.copy(elapsedSeconds = elapsed) }
                delay(1_000L)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    fun startSession(sessionName: String = "Session", monitoringEnabled: Boolean = false) {
        viewModelScope.launch {
            actionGate.run(ACTION_SESSION_START) {
                if (unifiedSessionEngine.isSessionActive.value ||
                    !VerbatimWorkspace(_uiState.value).canManageStoredSessions
                ) {
                    return@run
                }

                val speechRuntime = refreshSpeechRuntimeForStart()
                speechRuntime.verbatimStartBlockedMessage()?.let { message ->
                    _effects.emit(UnifiedSessionEffect.ShowToast(message))
                    return@run
                }

                val sessionId = UUID.randomUUID().toString()
                val uniqueSessionName = synchronized(reservedSessionNames) {
                    sessionNameGenerator.next(
                        requestedName = sessionName,
                        existingNames = _uiState.value.sessionHistory.map { it.sessionName } + reservedSessionNames
                    ).also(reservedSessionNames::add)
                }
                initializeSessionSelection(sessionId)
                val userId = PrivacyIdentity.LOCAL_PROFILE_ID
                runCatching {
                    val audioInput = pendingAudioInput
                    val usePpg = monitoringEnabled && audioInput is AudioInputSource.Microphone
                    applicationContext.startForegroundService(createStartIntent(sessionId, uniqueSessionName, audioInput, usePpg))
                    pendingAudioInput = AudioInputSource.Microphone
                }.onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    pendingAudioInput = AudioInputSource.Microphone
                    consentRepository.revokeSession(userId, sessionId)
                    _uiState.update {
                        it.copy(
                            sessionCommandState = SessionCommandState.Error(error.message ?: "Sesi tidak dapat dimulai."),
                            error = error.message ?: "Consent could not be persisted securely."
                        )
                    }
                }
            }
        }
    }

    private fun initializeSessionSelection(sessionId: String) {
        // Prevent a History collector from overwriting this live generation.
        selectedSessionJob?.cancel()
        selectedSessionJob = null
        _uiState.update {
            it.copy(
                selectedSessionId = sessionId,
                sessionGeneration = 0L,
                selectedSession = null,
                transcriptSegments = emptyList(),
                divergenceEvents = emptyList(),
                annotations = emptyList(),
                liveBiosignal = LiveBiosignalSnapshot(sessionId = sessionId),
                acousticAnalysis = null,
                livePitch = emptyList(),
                finalizationEvidencePersisted = null,
                finalizationWarnings = emptyList(),
                savedFolderPath = null,
                playbackFilePath = null,
                playbackState = PlaybackState.Idle,
                speechProcessingState = SpeechProcessingState(sessionId = sessionId),
                elapsedSeconds = 0L,
                sessionCommandState = SessionCommandState.Starting,
                error = null
            )
        }
    }

    private suspend fun createStartIntent(
        sessionId: String,
        name: String,
        input: AudioInputSource,
        usePpg: Boolean
    ): android.content.Intent {
        val recording = grantSessionConsent(sessionId, ConsentScope.VERBATIM_RECORDING)
        val monitoring = if (usePpg) grantSessionConsent(sessionId, ConsentScope.LIVE_MONITORING) else null
        return android.content.Intent(applicationContext, com.stressdetector.mobile.service.UnifiedSessionService::class.java).apply {
            action = com.stressdetector.mobile.service.UnifiedSessionService.ACTION_START_SESSION
            putExtra(com.stressdetector.mobile.service.UnifiedSessionService.EXTRA_COMMAND_ID, UUID.randomUUID().toString())
            putExtra(com.stressdetector.mobile.service.UnifiedSessionService.EXTRA_SESSION_ID, sessionId)
            putExtra(com.stressdetector.mobile.service.UnifiedSessionService.EXTRA_USER_ID, PrivacyIdentity.LOCAL_PROFILE_ID)
            com.stressdetector.mobile.service.UnifiedSessionService.putConsentToken(this, "recording", recording)
            monitoring?.let { com.stressdetector.mobile.service.UnifiedSessionService.putConsentToken(this, "monitoring", it) }
            putExtra(com.stressdetector.mobile.service.UnifiedSessionService.EXTRA_MONITORING_ENABLED, usePpg)
            putExtra(com.stressdetector.mobile.service.UnifiedSessionService.EXTRA_SESSION_NAME, name)
            putExtra(com.stressdetector.mobile.service.UnifiedSessionService.EXTRA_SESSION_LANGUAGE, _uiState.value.selectedLanguage)
            if (input is AudioInputSource.Imported) {
                putExtra(
                    com.stressdetector.mobile.service.UnifiedSessionService.EXTRA_AUDIO_URI,
                    input.uriString
                )
            }
        }
    }

    private suspend fun grantSessionConsent(sessionId: String, scope: ConsentScope) = consentRepository.grant(
        ConsentRecord(
            consentId = UUID.randomUUID().toString(),
            userId = PrivacyIdentity.LOCAL_PROFILE_ID,
            sessionId = sessionId,
            scope = scope,
            actor = PrivacyIdentity.LOCAL_ACTOR,
            timestampUtc = Instant.now(),
            appVersion = BuildConfig.VERSION_NAME,
            policyVersion = CONSENT_POLICY_VERSION,
            isMultiParty = true
        )
    )

    fun stopSession() {
        viewModelScope.launch {
            actionGate.run(ACTION_SESSION_STOP) {
                if (!VerbatimWorkspace(_uiState.value).canStop) return@run
                _uiState.update {
                    it.copy(isFinalizing = true, sessionCommandState = SessionCommandState.Stopping)
                }
                sendSessionAction(com.stressdetector.mobile.service.UnifiedSessionService.ACTION_STOP_SESSION)
            }
        }
    }

    fun pauseSession() {
        viewModelScope.launch {
            actionGate.run(ACTION_SESSION_PAUSE) {
                if (_uiState.value.sessionCommandState !is SessionCommandState.Recording) return@run
                _uiState.update { it.copy(sessionCommandState = SessionCommandState.Pausing) }
                sendSessionAction(com.stressdetector.mobile.service.UnifiedSessionService.ACTION_PAUSE_SESSION)
            }
        }
    }

    fun resumeSession() {
        viewModelScope.launch {
            actionGate.run(ACTION_SESSION_RESUME) {
                if (_uiState.value.sessionCommandState !is SessionCommandState.Paused) return@run
                _uiState.update { it.copy(sessionCommandState = SessionCommandState.Resuming) }
                sendSessionAction(com.stressdetector.mobile.service.UnifiedSessionService.ACTION_RESUME_SESSION)
            }
        }
    }

    fun setSessionLanguage(languageCode: String) {
        if (languageCode == "id" || languageCode == "en") {
            _uiState.update { it.copy(selectedLanguage = languageCode) }
        }
    }

    fun cycleSpeakerLabel(segmentId: String) {
        if (!VerbatimWorkspace(_uiState.value).canEditTranscript) return
        val segment = _uiState.value.transcriptSegments.firstOrNull { it.id == segmentId } ?: return
        val label = when (segment.speakerId) {
            "Speaker_0" -> "Psychologist"
            "Speaker_1" -> "Patient"
            "Psychologist" -> "Patient"
            else -> "Psychologist"
        }
        if (unifiedSessionEngine.isSessionActive.value) {
            unifiedSessionEngine.updateSpeakerLabel(segmentId, label)
        } else {
            saveTranscriptReview(
                segmentId,
                save = { sessionId -> sessionRepository.updateSpeakerLabel(sessionId, segmentId, label) },
                applyEdit = { it.copy(speakerId = label) }
            )
        }
    }

    fun updateTranscriptText(segmentId: String, text: String) {
        if (text.isBlank() || !VerbatimWorkspace(_uiState.value).canEditTranscript) return
        if (unifiedSessionEngine.isSessionActive.value) {
            unifiedSessionEngine.updateTranscriptText(segmentId, text)
        } else {
            saveTranscriptReview(
                segmentId,
                save = { sessionId -> sessionRepository.updateTranscriptText(sessionId, segmentId, text) },
                applyEdit = { it.copy(editedText = text) }
            )
        }
    }

    private fun saveTranscriptReview(
        segmentId: String,
        save: suspend (String) -> Result<Unit>,
        applyEdit: (SpeechSegment) -> SpeechSegment
    ) {
        val sessionId = _uiState.value.selectedSessionId ?: return
        if (_uiState.value.isSavingTranscript) return
        _uiState.update { it.copy(isSavingTranscript = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                save(sessionId).onSuccess {
                    _uiState.update { state ->
                        if (state.selectedSessionId != sessionId) {
                            state
                        } else {
                            val updated = state.transcriptSegments.map { segment ->
                                if (segment.id == segmentId) applyEdit(segment) else segment
                            }
                            state.copy(transcriptSegments = updated, selectedSession = state.selectedSession?.copy(transcript = updated))
                        }
                    }
                }.onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    _uiState.update { it.copy(error = error.message ?: "Edit transcript gagal disimpan.") }
                }
            } finally {
                _uiState.update { it.copy(isSavingTranscript = false) }
            }
        }
    }

    fun addManualAnnotation(text: String) {
        val state = _uiState.value
        val normalized = text.trim()
        if (!VerbatimWorkspace(state).canAnnotate || normalized.isBlank()) return
        val sessionId = state.selectedSessionId ?: return
        val timestampMs = if (state.isSessionActive) {
            unifiedSessionEngine.currentElapsedMillis()
        } else {
            state.playbackPositionMillis
        }
        val annotation = DomainAnnotation(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            timestampMs = timestampMs.coerceAtLeast(0L),
            text = normalized,
            author = "user"
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { unifiedSessionEngine.saveAnnotation(annotation) }
                .onSuccess {
                    _uiState.update { current ->
                        current.copy(annotations = (current.annotations + annotation).sortedBy { it.timestampMs })
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message ?: "Marker manual gagal disimpan.") }
                }
        }
    }

    fun deleteManualAnnotation(annotationId: String) {
        if (!VerbatimWorkspace(_uiState.value).canAnnotate) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { annotationRepository.deleteAnnotation(annotationId) }
                .onSuccess {
                    _uiState.update { current ->
                        current.copy(annotations = current.annotations.filterNot { it.id == annotationId })
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message ?: "Marker manual gagal dihapus.") }
                }
        }
    }

    fun prepareAudioPlayback() {
        val state = _uiState.value
        if (!VerbatimWorkspace(state).canPrepareAudio) return
        val summary = state.selectedSession?.summary
            ?: state.sessionHistory.firstOrNull { it.sessionId == state.selectedSessionId }
        if (summary == null) {
            _uiState.update { it.copy(error = "Pilih sesi rekaman terlebih dahulu.") }
            return
        }
        loadPlayback(summary, force = true)
    }

    fun selectSession(sessionId: String) {
        if (!VerbatimWorkspace(_uiState.value).canManageStoredSessions) {
            _uiState.update { it.copy(error = "Selesaikan rekaman aktif sebelum membuka sesi lain.") }
            return
        }
        selectedSessionJob?.cancel()
        _uiState.update {
            it.copy(
                selectedSessionId = sessionId,
                sessionGeneration = 0L,
                selectedSession = null,
                transcriptSegments = emptyList(),
                divergenceEvents = emptyList(),
                annotations = emptyList(),
                liveBiosignal = LiveBiosignalSnapshot(sessionId = sessionId),
                acousticAnalysis = null,
                livePitch = emptyList(),
                savedFolderPath = null,
                playbackFilePath = null,
                playbackState = PlaybackState.Preparing,
                playbackPositionMillis = 0L,
                playbackDurationMillis = 0L,
                error = null
            )
        }
        playbackLoadJob?.cancel()
        playbackRequestId++
        preparedSessionKey = null
        loadingSessionKey = null
        selectedSessionJob = viewModelScope.launch {
            var evidenceKey: String? = null
            var prosodyBySegment = emptyMap<String, com.stressdetector.mobile.domain.engine.VerbatimProsodyEvidence>()
            sessionRepository.observeSession(sessionId).collect { detail ->
                if (_uiState.value.selectedSessionId != sessionId || _uiState.value.isSessionActive) {
                    return@collect
                }
                if (detail == null) {
                    _uiState.update {
                        it.copy(
                            selectedSessionId = null,
                            selectedSession = null,
                            transcriptSegments = emptyList(),
                            divergenceEvents = emptyList(),
                            annotations = emptyList(),
                            savedFolderPath = null,
                            playbackFilePath = null,
                            playbackState = PlaybackState.Idle,
                            playbackPositionMillis = 0L,
                            playbackDurationMillis = 0L,
                            error = "Sesi rekaman tidak ditemukan."
                        )
                    }
                    return@collect
                }
                val archiveKey = "${detail.summary.artifactFolderPath}|${detail.summary.finalizationStatus}"
                if (evidenceKey != archiveKey) {
                    val restored = withContext(Dispatchers.IO) {
                        artifactService.restoreProsody(PrivacyIdentity.LOCAL_PROFILE_ID, detail)
                    }
                    prosodyBySegment = restored.transcript.mapNotNull { segment -> segment.prosody?.let { segment.id to it } }.toMap()
                    evidenceKey = archiveKey
                }
                val reviewed = detail.copy(transcript = detail.transcript.map { it.copy(prosody = prosodyBySegment[it.id]) })
                _uiState.update {
                    it.copy(
                        selectedSessionId = sessionId,
                        selectedSession = reviewed,
                        transcriptSegments = reviewed.transcript,
                        divergenceEvents = detail.divergenceEvents,
                        annotations = annotationRepository.getAnnotationsForSession(sessionId),
                        savedFolderPath = detail.summary.artifactFolderPath.ifBlank { detail.summary.audioFilePath }
                    )
                }
                val audioKey = "${detail.summary.sessionId}|${detail.summary.audioFilePath}|${detail.summary.artifactFolderPath}"
                // Failed-with-evidence archives may still contain usable audio. The loader verifies it;
                // missing/corrupt artifacts become an explicit playback error rather than endless Preparing.
                if (audioKey != preparedSessionKey && audioKey != loadingSessionKey) {
                    loadPlayback(detail.summary)
                }
            }
        }
    }

    private fun loadPlayback(summary: VerbatimSessionSummary, force: Boolean = false) {
        val audioKey = playbackKey(summary)
        if (force) {
            preparedSessionKey = null
            loadingSessionKey = null
        }
        if (!force && (audioKey == preparedSessionKey || audioKey == loadingSessionKey)) return
        loadingSessionKey = audioKey
        playbackLoadJob?.cancel()
        val requestId = ++playbackRequestId
        playbackLoadJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(playbackState = PlaybackState.Preparing, error = null) }
            loadPlaybackWithRetry(summary)
                .onSuccess { file ->
                    if (requestId == playbackRequestId && _uiState.value.selectedSessionId == summary.sessionId) {
                        preparedSessionKey = audioKey
                        loadingSessionKey = null
                        _uiState.update { it.copy(playbackFilePath = file.absolutePath, error = null) }
                    }
                }
                .onFailure { error ->
                    if (requestId == playbackRequestId) {
                        loadingSessionKey = null
                        preparedSessionKey = null
                    }
                    if (requestId == playbackRequestId && _uiState.value.selectedSessionId == summary.sessionId) {
                        _uiState.update {
                            it.copy(
                                playbackFilePath = null,
                                playbackState = PlaybackState.Error(error.message ?: "Audio tidak dapat dipersiapkan."),
                                error = error.message ?: "Audio tidak dapat dipersiapkan."
                            )
                        }
                    }
                }
        }
    }

    private suspend fun loadPlaybackWithRetry(summary: VerbatimSessionSummary): Result<java.io.File> {
        var lastFailure: Result<java.io.File> = Result.failure(
            IllegalStateException("Audio tidak dapat dipersiapkan.")
        )
        repeat(PLAYBACK_LOAD_ATTEMPTS) { attempt ->
            lastFailure = playbackCoordinator.load(summary)
            if (lastFailure.isSuccess || !isTransientPlaybackFailure(lastFailure.exceptionOrNull())) {
                return lastFailure
            }
            delay(PLAYBACK_RETRY_DELAYS_MILLIS[attempt])
        }
        return lastFailure
    }

    private fun playbackKey(summary: VerbatimSessionSummary): String =
        "${summary.sessionId}|${summary.audioFilePath}|${summary.artifactFolderPath}"

    private fun isTransientPlaybackFailure(error: Throwable?): Boolean {
        val message = error?.message.orEmpty()
        return message.contains("kosong", ignoreCase = true) ||
            message.contains("missing", ignoreCase = true) ||
            message.contains("tidak tersedia", ignoreCase = true)
    }

    fun playAudio() = runPlaybackCommand("play") { playbackCoordinator.play() }

    fun pauseAudio() = runPlaybackCommand("pause") { playbackCoordinator.pause() }

    fun stopAudio() = runPlaybackCommand("stop") { playbackCoordinator.stop() }

    fun seekAudio(positionMillis: Long) = runPlaybackCommand("seek") {
        playbackCoordinator.seekTo(positionMillis)
    }

    /** Marker action is atomic: prepare if needed, seek, then start playback. */
    fun playFromMarker(positionMillis: Long) = runPlaybackCommand("seek-and-play") {
        playbackCoordinator.seekAndPlay(positionMillis)
    }

    private fun runPlaybackCommand(commandName: String, command: suspend () -> Result<Unit>) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            actionGate.run("playback:$commandName") {
                if (!VerbatimWorkspace(_uiState.value).canReviewAudio) return@run
                command().onFailure { error ->
                    _uiState.update { it.copy(error = error.message ?: "Operasi playback gagal.") }
                }
            }
        }
    }

    fun prepareZipExport(): String? {
        val state = _uiState.value
        if (!VerbatimWorkspace(state).canExportZip) return null
        pendingZipExport = state.selectedSession?.summary
        return pendingZipExport?.sessionName?.let { exportFileName(it, "zip") }
    }

    fun exportLastSession(destination: android.net.Uri?) {
        val frozen = pendingZipExport
        pendingZipExport = null
        if (destination == null || frozen == null) return
        val state = _uiState.value
        val folder = frozen.artifactFolderPath
        if (!VerbatimWorkspace(state).canManageStoredSessions || folder.isBlank()) {
            _uiState.update { it.copy(error = "Pilih sesi dengan artifact yang sudah tersimpan.") }
            return
        }
        _uiState.update { it.copy(isExporting = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            actionGate.run(ACTION_SESSION_EXPORT) {
                try {
                    runCatching {
                        artifactService.exportSessionBundle(
                            PrivacyIdentity.LOCAL_PROFILE_ID,
                            folder,
                            destination,
                            applicationContext
                        )
                    }.onSuccess {
                        _effects.emit(UnifiedSessionEffect.ShowToast("Arsip ZIP berhasil diekspor."))
                    }.onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        _uiState.update { it.copy(error = error.message ?: "Ekspor ZIP gagal.") }
                    }
                } finally {
                    _uiState.update { it.copy(isExporting = false) }
                }
            }
        }
    }

    /** Freeze the selected review before SAF opens; a later selection cannot change its contents. */
    fun prepareTranscriptExport(): String? {
        val state = _uiState.value
        val detail = state.selectedSession
        val hasFinalTranscript = detail?.transcript?.any { it.isFinal && it.displayText.isNotBlank() } == true
        val blocked = when {
            state.isSavingTranscript -> "Tunggu sampai edit transkrip selesai disimpan sebelum ekspor TXT."
            !VerbatimWorkspace(state).canExportText || !hasFinalTranscript -> "Pilih sesi dengan transkrip final untuk ekspor TXT."
            else -> null
        }
        if (blocked != null) {
            _uiState.update { it.copy(error = blocked) }
            return null
        }
        pendingTextExport = checkNotNull(detail) to state.annotations.toList()
        return exportFileName(detail.summary.sessionName, "txt")
    }

    private fun exportFileName(name: String, extension: String): String = name.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
        .take(MAX_EXPORT_NAME_LENGTH).ifBlank { "verbatim" } + ".$extension"

    fun exportTranscript(destination: android.net.Uri?) {
        val frozen = pendingTextExport
        pendingTextExport = null
        if (destination == null || frozen == null) return
        if (!VerbatimWorkspace(_uiState.value).canManageStoredSessions) {
            _uiState.update { it.copy(error = "Selesaikan operasi sesi aktif sebelum ekspor TXT.") }
            return
        }
        _uiState.update { it.copy(isExporting = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            actionGate.run(ACTION_SESSION_EXPORT) {
                try {
                    runCatching {
                        artifactService.exportTranscript(frozen.first, frozen.second, destination, applicationContext)
                        _effects.emit(UnifiedSessionEffect.ShowToast("Transkrip TXT dan keterangan marker berhasil diekspor."))
                    }.onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        _uiState.update { it.copy(error = error.message ?: "Ekspor TXT gagal.") }
                    }
                } finally {
                    _uiState.update { it.copy(isExporting = false) }
                }
            }
        }
    }

    fun deleteLastSession() {
        deleteSession(
            _uiState.value.selectedSession?.summary?.sessionId
                ?: _uiState.value.sessionHistory.firstOrNull()?.sessionId
        )
    }

    fun deleteSession(sessionId: String?) {
        if (sessionId.isNullOrBlank() || !VerbatimWorkspace(_uiState.value).canManageStoredSessions) return
        val session = _uiState.value.sessionHistory.firstOrNull { it.sessionId == sessionId }
        val folder = session?.artifactFolderPath.orEmpty()
        if (session == null) return
        runArchiveDeletion("session:delete:$sessionId") {
            playbackCoordinator.stop()
            val deleted = runCatching {
                when {
                    folder.isNotBlank() -> artifactService.deleteSession(PrivacyIdentity.LOCAL_PROFILE_ID, folder)
                    session.audioFilePath.isNotBlank() -> artifactService.deleteArtifact(
                        PrivacyIdentity.LOCAL_PROFILE_ID,
                        session.audioFilePath
                    )
                    else -> true
                }
            }.getOrDefault(false)
            val repositoryDeleted = deleted && sessionRepository.deleteSession(sessionId).isSuccess
            val annotationsDeleted = repositoryDeleted &&
                runCatching {
                    annotationRepository.deleteAnnotationsForSession(sessionId)
                }.isSuccess
            val sessionFullyDeleted = repositoryDeleted && annotationsDeleted
            if (sessionFullyDeleted) {
                playbackCoordinator.clearCache(sessionId)
            }
            _uiState.update {
                it.copy(
                    savedFolderPath = if (sessionFullyDeleted) null else folder,
                    selectedSessionId = if (sessionFullyDeleted && it.selectedSessionId == sessionId) null else it.selectedSessionId,
                    selectedSession = if (sessionFullyDeleted && it.selectedSessionId == sessionId) null else it.selectedSession,
                    transcriptSegments = if (sessionFullyDeleted &&
                        it.selectedSessionId == sessionId
                    ) {
                        emptyList()
                    } else {
                        it.transcriptSegments
                    },
                    divergenceEvents = if (sessionFullyDeleted &&
                        it.selectedSessionId == sessionId
                    ) {
                        emptyList()
                    } else {
                        it.divergenceEvents
                    },
                    annotations = if (sessionFullyDeleted && it.selectedSessionId == sessionId) emptyList() else it.annotations,
                    playbackFilePath = null,
                    playbackState = if (sessionFullyDeleted) PlaybackState.Idle else it.playbackState,
                    playbackPositionMillis = if (sessionFullyDeleted) 0L else it.playbackPositionMillis,
                    playbackDurationMillis = if (sessionFullyDeleted) 0L else it.playbackDurationMillis,
                    error = if (sessionFullyDeleted) null else "Artifact atau marker sesi tidak dapat dihapus."
                )
            }
        }
    }

    fun deleteAllSessions() {
        if (!VerbatimWorkspace(_uiState.value).canManageStoredSessions) return
        val sessions = _uiState.value.sessionHistory
        runArchiveDeletion(ACTION_SESSION_DELETE_ALL) {
            playbackCoordinator.stop()
            val artifactsDeleted = sessions.all { session ->
                runCatching {
                    if (session.artifactFolderPath.isNotBlank()) {
                        artifactService.deleteSession(
                            PrivacyIdentity.LOCAL_PROFILE_ID,
                            session.artifactFolderPath
                        )
                    } else if (session.audioFilePath.isNotBlank()) {
                        artifactService.deleteArtifact(
                            PrivacyIdentity.LOCAL_PROFILE_ID,
                            session.audioFilePath
                        )
                    } else {
                        true
                    }
                }.getOrDefault(false)
            }
            val annotationsDeleted = artifactsDeleted &&
                sessions.all { session ->
                    runCatching { annotationRepository.deleteAnnotationsForSession(session.sessionId) }.isSuccess
                }
            val deleted = annotationsDeleted && sessionRepository.deleteAllSessions().isSuccess
            if (deleted) playbackCoordinator.clearCache()
            _uiState.update {
                it.copy(
                    selectedSessionId = if (deleted) null else it.selectedSessionId,
                    selectedSession = if (deleted) null else it.selectedSession,
                    transcriptSegments = if (deleted) emptyList() else it.transcriptSegments,
                    divergenceEvents = if (deleted) emptyList() else it.divergenceEvents,
                    annotations = if (deleted) emptyList() else it.annotations,
                    playbackFilePath = if (deleted) null else it.playbackFilePath,
                    playbackState = if (deleted) PlaybackState.Idle else it.playbackState,
                    playbackPositionMillis = if (deleted) 0L else it.playbackPositionMillis,
                    playbackDurationMillis = if (deleted) 0L else it.playbackDurationMillis,
                    error = if (deleted) null else "Sebagian sesi tidak dapat dihapus."
                )
            }
        }
    }

    fun reportError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    private fun runArchiveDeletion(key: String, delete: suspend () -> Unit) {
        _uiState.update { it.copy(isDeletingSession = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runCatching { actionGate.run(key) { delete() } }.onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    _uiState.update { it.copy(error = error.message ?: "Penghapusan sesi gagal; tinjau artefak yang tersisa.") }
                }
            } finally {
                _uiState.update { it.copy(isDeletingSession = false) }
            }
        }
    }

    private fun sendSessionAction(action: String) {
        try {
            val intent = android.content.Intent(
                applicationContext,
                com.stressdetector.mobile.service.UnifiedSessionService::class.java
            ).apply {
                this.action = action
                putExtra(com.stressdetector.mobile.service.UnifiedSessionService.EXTRA_COMMAND_ID, UUID.randomUUID().toString())
            }
            applicationContext.startService(intent)
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    sessionCommandState = SessionCommandState.Error(e.message ?: "Perintah sesi gagal dikirim."),
                    isFinalizing = false,
                    error = e.message ?: "Perintah sesi gagal dikirim."
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopTimer()
        playbackCoordinator.clearCache()
        playbackCoordinator.close()
    }

    class Factory(private val context: android.content.Context) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val appCtx = context.applicationContext
            return UnifiedSessionViewModel(
                appCtx,
                com.stressdetector.mobile.di.AppLocator.unifiedSessionEngine(appCtx),
                com.stressdetector.mobile.di.AppLocator.audioIntelligenceEngine(appCtx),
                com.stressdetector.mobile.di.AppLocator.liveBiosignalEngine(appCtx),
                com.stressdetector.mobile.di.AppLocator.modelManager(appCtx),
                com.stressdetector.mobile.di.AppLocator.consentRepository(appCtx),
                com.stressdetector.mobile.di.AppLocator.verbatimArtifactService(appCtx),
                com.stressdetector.mobile.di.AppLocator.unifiedSessionRepository(appCtx),
                com.stressdetector.mobile.domain.engine.VerbatimPlaybackCoordinator(
                    com.stressdetector.mobile.di.AppLocator.verbatimArtifactService(appCtx),
                    com.stressdetector.mobile.di.AppLocator.audioPlaybackEngine(appCtx)
                ),
                com.stressdetector.mobile.di.AppLocator.annotationRepository(appCtx),
                modelStorageManager = com.stressdetector.mobile.di.AppLocator.modelStorageManager(appCtx)
            ) as T
        }
    }

    private companion object {
        const val CONSENT_POLICY_VERSION = "clinical-consent-v1"
        const val ACTION_SESSION_START = "session:start"
        const val ACTION_SESSION_START_REQUEST = "session:start-request"
        const val ACTION_SESSION_STOP = "session:stop"
        const val ACTION_SESSION_PAUSE = "session:pause"
        const val ACTION_SESSION_RESUME = "session:resume"
        const val ACTION_SESSION_EXPORT = "session:export"
        const val ACTION_SESSION_DELETE_ALL = "session:delete-all"
        const val ACTION_SPEECH_RUNTIME_REFRESH = "speech-runtime:refresh"
        const val PLAYBACK_LOAD_ATTEMPTS = 3
        val PLAYBACK_RETRY_DELAYS_MILLIS = longArrayOf(150L, 350L, 750L)
        const val LIVE_SESSION_UI_SAMPLE_MILLIS = 200L
        const val MAX_EXPORT_NAME_LENGTH = 80
    }
}

internal fun SessionCommandState.locksSessionSelection(): Boolean = when (this) {
    SessionCommandState.Idle, is SessionCommandState.Error -> false
    else -> true
}

internal fun isLiveSessionGenerationAccepted(expectedGeneration: Long, snapshot: UnifiedSessionLiveSnapshot): Boolean {
    // The engine publishes a session-owned empty snapshot immediately after
    // creating the session timeline. Accept that generation-zero snapshot so
    // live transcript/processing state can render during the first frames. A
    // non-empty biosignal snapshot must still carry the same positive
    // generation; this keeps stale sessions fail-closed.
    if (snapshot.generation <= 0L) {
        return expectedGeneration == 0L &&
            snapshot.biosignal.sessionId == snapshot.sessionId
    }
    if (snapshot.biosignal.sessionGeneration != snapshot.generation) return false
    return expectedGeneration == 0L || expectedGeneration == snapshot.generation
}
