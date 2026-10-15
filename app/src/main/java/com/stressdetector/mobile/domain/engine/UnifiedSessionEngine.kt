package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.consent.ConsentRepository
import com.stressdetector.mobile.consent.ConsentScope
import com.stressdetector.mobile.consent.ConsentToken
import com.stressdetector.mobile.consent.ConsentValidationException
import com.stressdetector.mobile.data.IPpgRepository
import com.stressdetector.mobile.data.PpgSessionBusyException
import com.stressdetector.mobile.data.repository.UnifiedSessionRepository
import com.stressdetector.shared.PpgBatchPolicy
import com.stressdetector.shared.WearSessionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class SessionOperationError {
    data class ConsentRejected(val reason: ConsentValidationException) : SessionOperationError()
    data class StartFailed(val message: String) : SessionOperationError()
}

private fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}

sealed interface SessionFinalizationState {
    data object Idle : SessionFinalizationState

    data class Completed(val sessionId: String, val artifacts: VerbatimSessionArtifacts) : SessionFinalizationState

    data class Failed(
        val sessionId: String,
        val message: String,
        val evidencePersisted: Boolean = false,
        val warnings: List<String> = emptyList()
    ) : SessionFinalizationState
}

/** Detailed, additive lifecycle for crash-safe finalization. */
enum class SessionFinalizationStage {
    RECORDING,
    STOP_REQUESTED,
    AUDIO_DRAINING,
    PPG_DRAINING,
    SNAPSHOT_CAPTURED,
    PERSISTING,
    COMPLETED,
    FAILED_WITH_EVIDENCE
}

@Suppress("LongParameterList")
class UnifiedSessionEngine(
    private val repository: UnifiedSessionRepository,
    private val audioEngine: AudioIntelligenceEngine,
    private val biosignalEngine: LiveBiosignalEngine,
    private val watchSessionCoordinator: WatchSessionCoordinator,
    private val consentRepository: ConsentRepository,
    private val finalizer: VerbatimSessionFinalizer,
    private val annotationRepository: AnnotationRepository,
    private val timelineFactory: (String) -> SessionTimeline = { sessionId -> SessionTimeline.start(sessionId) },
    private val ppgRepository: IPpgRepository? = null,
    private val verbatimPpgArchive: VerbatimPpgArchive? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Serializes the complete session lifecycle, including drain and finalization. */
    private val sessionLifecycleMutex = Mutex()
    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()
    private val _sessionError = MutableStateFlow<SessionOperationError?>(null)
    val sessionError: StateFlow<SessionOperationError?> = _sessionError.asStateFlow()
    private val _lastArtifacts = MutableStateFlow<VerbatimSessionArtifacts?>(null)
    val lastArtifacts: StateFlow<VerbatimSessionArtifacts?> = _lastArtifacts.asStateFlow()
    private val _finalizationState = MutableStateFlow<SessionFinalizationState>(SessionFinalizationState.Idle)
    val finalizationState: StateFlow<SessionFinalizationState> = _finalizationState.asStateFlow()
    private val _finalizationStage = MutableStateFlow(SessionFinalizationStage.RECORDING)
    val finalizationStage: StateFlow<SessionFinalizationStage> = _finalizationStage.asStateFlow()
    private val _monitorStatus = MutableStateFlow("UNAVAILABLE: not started")
    val monitorStatus: StateFlow<String> = _monitorStatus.asStateFlow()
    private val _liveTranscriptState = MutableStateFlow(LiveTranscriptState())
    val liveTranscriptState: StateFlow<LiveTranscriptState> = _liveTranscriptState.asStateFlow()

    /** Single biosignal projection consumed by Verbatim+ UI and finalization. */
    val liveBiosignalState: StateFlow<LiveBiosignalSnapshot> = biosignalEngine.liveState
    private val _liveSessionState = MutableStateFlow(UnifiedSessionLiveSnapshot())
    val liveSessionState: StateFlow<UnifiedSessionLiveSnapshot> = _liveSessionState.asStateFlow()
    val watchSessionState: StateFlow<WatchSessionState> = watchSessionCoordinator.state

    private var currentSessionId: String? = null
    private var currentUserId: String? = null
    private var currentSessionName: String = "Session"
    private var transcriptJob: Job? = null
    private var speechProcessingJob: Job? = null
    private var divergenceJob: Job? = null
    private var acousticJob: Job? = null
    private var pitchJob: Job? = null
    private var livePitchHistory = emptyList<LivePitchObservation>()
    private var watchSessionActive = false
    private var biosignalMonitoringActive = false

    @Volatile private var acceptingSessionEvents = false

    @Volatile private var sessionStopping = false

    @Volatile private var sessionStopCutoff: SessionStopCutoff? = null
    private var currentTimeline: SessionTimeline? = null
    private val transcriptSegments = mutableListOf<SpeechSegment>()
    private val divergenceEvents = mutableListOf<com.stressdetector.mobile.data.db.DivergenceEventEntity>()
    private val acousticEvents = mutableListOf<com.stressdetector.mobile.data.db.DivergenceEventEntity>()

    @Volatile private var latestAcousticAnalysis: AcousticAnalysisSnapshot? = null
    private var ppgArchiveResult: VerbatimPpgArchiveResult = VerbatimPpgArchiveResult.unavailable(
        "archive_not_started"
    )
    private val liveTranscriptReducer = LiveTranscriptReducer()
    private val livePublicationLock = Any()
    private var currentMonitorStatus = "NOT_STARTED"
    private val prosodyDetector = VerbatimProsodyMarkerDetector()
    private val analyzedProsodySegments = mutableSetOf<String>()

    @Suppress("LongParameterList")
    suspend fun startSession(
        sessionId: String,
        userId: String,
        recordingConsent: ConsentToken,
        monitoringConsent: ConsentToken? = null,
        sessionName: String = "Session",
        languageCode: String = "id",
        audioInput: AudioInputSource = AudioInputSource.Microphone,
        monitoringEnabled: Boolean = monitoringConsent != null
    ) = sessionLifecycleMutex.withLock {
        startSessionInternal(
            sessionId = sessionId,
            userId = userId,
            recordingConsent = recordingConsent,
            monitoringConsent = monitoringConsent,
            sessionName = sessionName,
            languageCode = languageCode,
            audioInput = audioInput,
            monitoringEnabled = monitoringEnabled
        )
    }

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "ThrowsCount")
    private suspend fun startSessionInternal(
        sessionId: String,
        userId: String,
        recordingConsent: ConsentToken,
        monitoringConsent: ConsentToken?,
        sessionName: String,
        languageCode: String,
        audioInput: AudioInputSource,
        monitoringEnabled: Boolean
    ) {
        check(!_isSessionActive.value && !sessionStopping) { "A clinical session is already active or finalizing." }
        _sessionError.value = null
        _finalizationState.value = SessionFinalizationState.Idle
        _finalizationStage.value = SessionFinalizationStage.RECORDING
        try {
            consentRepository.require(recordingConsent, userId, sessionId, ConsentScope.VERBATIM_RECORDING)
            if (monitoringEnabled) {
                require(audioInput is AudioInputSource.Microphone) { "Audio historis tidak boleh dipasangkan dengan PPG live baru." }
                consentRepository.require(checkNotNull(monitoringConsent), userId, sessionId, ConsentScope.LIVE_MONITORING)
            }

            // An external/replayed service Intent must not get far enough to
            // create a Room session or start the Watch when Sherpa is absent.
            when (val speechRuntime = audioEngine.refreshSpeechRuntime()) {
                SpeechRuntimeState.Ready -> Unit
                SpeechRuntimeState.Checking -> error(
                    "Speech bundle sedang diperiksa. Coba lagi sebentar."
                )
                is SpeechRuntimeState.Unavailable -> error(speechRuntime.reason)
                is SpeechRuntimeState.Failed -> error(speechRuntime.message)
            }

            val timeline = timelineFactory(sessionId)
            repository.startSession(
                sessionId,
                timeline.startedAtUtcMillis,
                consentGranted = true,
                sessionName = sessionName
            )
            currentSessionId = sessionId
            currentUserId = userId
            currentSessionName = sessionName
            currentTimeline = timeline
            watchSessionActive = false
            biosignalMonitoringActive = false
            acceptingSessionEvents = true
            sessionStopCutoff = null
            transcriptSegments.clear()
            divergenceEvents.clear()
            acousticEvents.clear()
            prosodyDetector.reset()
            analyzedProsodySegments.clear()
            latestAcousticAnalysis = null
            livePitchHistory = emptyList()
            ppgArchiveResult = VerbatimPpgArchiveResult.unavailable("archive_not_started")
            liveTranscriptReducer.reset()
            _liveTranscriptState.value = LiveTranscriptState(sessionId = sessionId)
            _liveSessionState.value = UnifiedSessionLiveSnapshot(
                sessionId = sessionId,
                transcript = _liveTranscriptState.value,
                updatedAtMillis = System.currentTimeMillis()
            )
            _lastArtifacts.value = null
            currentMonitorStatus = "UNAVAILABLE: not started"
            _monitorStatus.value = currentMonitorStatus
            _isSessionActive.value = true
            val watchStart = if (monitoringEnabled) {
                watchSessionCoordinator.start(sessionId, WearSessionMode.VERBATIM_AROUSAL)
            } else {
                Result.failure(IllegalStateException("PPG_DISABLED: sesi audio-only"))
            }
            if (watchStart.isSuccess) {
                watchSessionActive = true
                val stream = ppgRepository?.state?.value
                val archiveStart = if (stream?.sessionId == sessionId &&
                    stream.sessionMode == WearSessionMode.VERBATIM_AROUSAL &&
                    !stream.sessionNonce.isNullOrBlank()
                ) {
                    verbatimPpgArchive?.begin(
                        sessionId = sessionId,
                        sessionNonce = stream.sessionNonce,
                        userId = userId
                    ) ?: Result.failure(IllegalStateException("PPG archive is not configured"))
                } else {
                    Result.failure(IllegalStateException("PPG archive session identity unavailable"))
                }
                if (archiveStart.isFailure) {
                    currentMonitorStatus = "STARTED; PPG_ARCHIVE_UNAVAILABLE"
                }
                // The phone repository is now bound to the same lease that
                // owns the Watch tracker. Starting the monitor after that
                // lease prevents a Home buffer or a prior Verbatim session
                // from leaking samples into this live transcript.
                biosignalEngine.bindSessionTimeline(timeline)
                when (val monitorStart = biosignalEngine.startMonitoring(sessionId)) {
                    LiveMonitorStartResult.Started -> {
                        biosignalMonitoringActive = true
                        currentMonitorStatus = "STARTED; WAITING_FOR_PPG"
                    }
                    is LiveMonitorStartResult.Unavailable ->
                        currentMonitorStatus = "UNAVAILABLE: ${monitorStart.reason}"
                    is LiveMonitorStartResult.Failed ->
                        currentMonitorStatus = "FAILED: ${monitorStart.reason}"
                }
            } else {
                val watchFailure = watchStart.exceptionOrNull()
                if (watchFailure is PpgSessionBusyException) {
                    error(
                        when (watchFailure.activeLease.mode) {
                            WearSessionMode.HOME_STRESS -> "BUSY_BY_HOME"
                            WearSessionMode.VERBATIM_AROUSAL -> "BUSY_BY_VERBATIM"
                        }
                    )
                }
                currentMonitorStatus = if (monitoringEnabled) {
                    "UNAVAILABLE: ${watchFailure?.message ?: "Watch session could not start"}"
                } else {
                    "NOT_REQUESTED: audio-only"
                }
            }
            _monitorStatus.value = currentMonitorStatus

            // Collectors are scoped to the valid session identity; they do not
            // wait for a correlated Watch pong. Stale frames are still rejected
            // by the repository/session generation checks.
            transcriptJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                audioEngine.transcriptFlow.collect { segment ->
                    if (acceptsTranscriptEvent(segment, sessionId, timeline)) {
                        upsertTranscript(segment, sessionId)
                    }
                }
            }
            speechProcessingJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                audioEngine.speechProcessingState.collect { processing ->
                    if (acceptingSessionEvents && currentSessionId == sessionId && processing.sessionId == sessionId) {
                        _liveTranscriptState.update { current ->
                            if (current.sessionId == sessionId) current.copy(processing = processing) else current
                        }
                        publishLiveSessionState(sessionId)
                    }
                }
            }
            acousticJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                audioEngine.acousticAnalysisFlow.collect { analysis ->
                    if (!acceptsAudioDiagnostic(analysis.sessionId, analysis.endOffsetMs, sessionId, timeline)) return@collect
                    latestAcousticAnalysis = analysis
                    analysis.markerTransition?.let { marker ->
                        val event = analysis.toDivergenceEntity(marker, timeline)
                        synchronized(acousticEvents) {
                            acousticEvents.removeAll { it.eventId == event.eventId }
                            acousticEvents.add(event)
                        }
                    }
                    publishLiveSessionState(sessionId)
                }
            }
            pitchJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                audioEngine.livePitchFlow.collect { observation ->
                    synchronized(livePublicationLock) {
                        if (acceptsAudioDiagnostic(observation.sessionId, observation.endOffsetMs, sessionId, timeline)) {
                            livePitchHistory = appendLivePitch(livePitchHistory, observation)
                            publishLiveSessionState(sessionId)
                        }
                    }
                }
            }
            divergenceJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                biosignalEngine.liveState.collect { snapshot ->
                    if ((!acceptingSessionEvents && sessionStopCutoff == null) ||
                        currentSessionId != sessionId ||
                        snapshot.sessionId != sessionId ||
                        snapshot.sessionGeneration != timeline.generation
                    ) {
                        return@collect
                    }
                    val events = sessionStopCutoff
                        ?.takeIf { it.sessionId == sessionId && it.generation == timeline.generation }
                        ?.filter(snapshot.divergenceEvents)
                        ?: snapshot.divergenceEvents
                    synchronized(divergenceEvents) {
                        divergenceEvents.clear()
                        divergenceEvents.addAll(events)
                    }
                    publishLiveSessionState(sessionId)
                }
            }

            when (val audioStart = audioEngine.startRecording(sessionId, userId, languageCode, audioInput)) {
                is AudioOperationResult.Success ->
                    timeline.markAudioStartedAt(audioStart.value.startedAtMonotonicMillis)
                is AudioOperationResult.Unavailable -> error(audioStart.reason)
                is AudioOperationResult.Failure -> throw IllegalStateException(audioStart.message, audioStart.cause)
            }
            publishLiveSessionState(sessionId)
        } catch (error: ConsentValidationException) {
            _sessionError.value = SessionOperationError.ConsentRejected(error)
            throw error
        } catch (error: Exception) {
            withContext(NonCancellable) {
                acceptingSessionEvents = false
                runCatching { audioEngine.stopRecording() }
                if (watchSessionActive) {
                    runCatching { watchSessionCoordinator.stop(sessionId, completionExpected = false) }
                    runCatching { watchSessionCoordinator.awaitDrain(sessionId, WATCH_DRAIN_TIMEOUT_MILLIS) }
                }
                runCatching {
                    withContext(Dispatchers.IO) {
                        verbatimPpgArchive?.abort("session_start_failed")
                    }
                }
                if (biosignalMonitoringActive) {
                    runCatching {
                        withContext(Dispatchers.Default) {
                            biosignalEngine.stopMonitoringAndDrain()
                        }
                    }
                }
                cancelCollectors()
                consentRepository.revokeSession(userId, sessionId)
                runCatching { repository.deleteSession(sessionId) }
                _isSessionActive.value = false
                currentSessionId = null
                currentUserId = null
                currentTimeline = null
                watchSessionActive = false
                biosignalMonitoringActive = false
                sessionStopping = false
            }
            _sessionError.value = SessionOperationError.StartFailed(error.message ?: "Session could not start.")
            throw error
        }
    }

    suspend fun stopSession() = sessionLifecycleMutex.withLock {
        val sessionId = currentSessionId
        try {
            // Final evidence must survive UI navigation, backgrounding, and caller cancellation.
            withContext(NonCancellable) { stopSessionInternal() }
        } catch (error: Exception) {
            val failedSessionId = sessionId.orEmpty()
            val message = "Session finalization failed safely: ${error.message ?: error::class.java.simpleName}"
            _sessionError.value = SessionOperationError.StartFailed(message)
            _finalizationStage.value = SessionFinalizationStage.FAILED_WITH_EVIDENCE
            _finalizationState.value = SessionFinalizationState.Failed(
                sessionId = failedSessionId,
                message = message,
                evidencePersisted = false,
                warnings = listOf("unexpected_finalization:${error.message ?: "unknown"}")
            )
            clearSessionOwnership()
        }
    }

    private suspend fun stopSessionInternal() {
        if (!_isSessionActive.value || sessionStopping) return

        val sessionId = currentSessionId ?: return
        val userId = currentUserId ?: return

        sessionStopping = true
        _finalizationStage.value = SessionFinalizationStage.STOP_REQUESTED
        val timeline = currentTimeline
        sessionStopCutoff = timeline?.let {
            SessionStopCutoff(
                sessionId = sessionId,
                generation = it.generation,
                elapsedMillis = it.currentElapsedMillis()
            )
        }
        var stopResult: AudioRecordingStopResult? = null
        var stopFailure: Throwable? = null
        var transportWarning: String? = null
        var drainedBiosignal: LiveBiosignalSnapshot? = null
        try {
            // Audio and Watch are independent evidence sources. Failure to
            // drain one subsystem must never prevent a best-effort stop of the
            // other subsystem.
            _finalizationStage.value = SessionFinalizationStage.AUDIO_DRAINING
            stopResult = runCatching {
                when (val audioStop = audioEngine.stopRecordingWithTranscript()) {
                    is AudioOperationResult.Success -> audioStop.value
                    is AudioOperationResult.Unavailable -> error(audioStop.reason)
                    is AudioOperationResult.Failure -> throw IllegalStateException(audioStop.message, audioStop.cause)
                }
            }.onFailure { error ->
                error.rethrowIfCancellation()
                stopFailure = error
            }.getOrNull()

            if (watchSessionActive) {
                val watchStop = runCatching { watchSessionCoordinator.stop(sessionId) }
                    .getOrElse { error ->
                        error.rethrowIfCancellation()
                        transportWarning = "stop_failed:${error.message ?: "watch stop failed"}"
                        Result.failure(error)
                    }
                watchStop.exceptionOrNull()?.let { error ->
                    transportWarning = "stop_failed:${error.message ?: "watch stop failed"}"
                }
                if (watchStop.isSuccess) {
                    val drain = runCatching {
                        watchSessionCoordinator.awaitDrain(sessionId, WATCH_DRAIN_TIMEOUT_MILLIS)
                    }.getOrElse { error ->
                        error.rethrowIfCancellation()
                        Result.failure(error)
                    }
                    drain.exceptionOrNull()?.let { error ->
                        transportWarning = "incomplete_transport:${error.message ?: "watch drain timed out"}"
                    }
                }
            }
        } finally {
            _finalizationStage.value = SessionFinalizationStage.PPG_DRAINING
            ppgArchiveResult = runCatching {
                withContext(Dispatchers.IO) {
                    verbatimPpgArchive?.drainAndFinalize()
                        ?: VerbatimPpgArchiveResult.unavailable("archive_not_configured")
                }
            }.onFailure { error ->
                error.rethrowIfCancellation()
                transportWarning = "ppg_archive_failed:${error.message ?: "unknown error"}"
            }.getOrElse { error ->
                VerbatimPpgArchiveResult.unavailable(error.message ?: "archive_failed")
            }
            if (biosignalMonitoringActive) {
                runCatching {
                    withContext(Dispatchers.Default) {
                        biosignalEngine.stopMonitoringAndDrain()
                    }
                }.onFailure(Throwable::rethrowIfCancellation)
            }
            drainedBiosignal = runCatching { captureDrainedBiosignal(sessionId, timeline) }
                .onFailure { error ->
                    error.rethrowIfCancellation()
                    transportWarning = "biosignal_snapshot_failed:${error.message ?: "unknown error"}"
                }
                .getOrNull()
            acceptingSessionEvents = false
            _finalizationStage.value = SessionFinalizationStage.SNAPSHOT_CAPTURED
            runCatching { cancelCollectors() }
                .onFailure { error ->
                    error.rethrowIfCancellation()
                    transportWarning = "collector_stop_failed:${error.message ?: "unknown error"}"
                }
            runCatching { consentRepository.revokeSession(userId, sessionId) }
                .onFailure(Throwable::rethrowIfCancellation)
            val finalMonitorStatus = if (!watchSessionActive) {
                "$currentMonitorStatus; AUDIO_STOPPED"
            } else if (transportWarning == null) {
                "STOPPED"
            } else {
                "STOPPED; $transportWarning"
            }
            currentMonitorStatus = finalMonitorStatus.take(MAX_MONITOR_STATUS_LENGTH)
            _monitorStatus.value = currentMonitorStatus
            _isSessionActive.value = false
            watchSessionActive = false
            biosignalMonitoringActive = false
        }
        if (stopFailure != null) {
            val fallbackTranscript = synchronized(transcriptSegments) {
                transcriptSegments
                    .filter { it.sessionId == sessionId && it.isFinal }
                    .associateBy(SpeechSegment::id)
                    .values
                    .sortedWith(compareBy(SpeechSegment::startOffsetMs, SpeechSegment::endOffsetMs, SpeechSegment::id))
            }
            val fallbackEvents = (
                synchronized(divergenceEvents) { divergenceEvents.toList() } +
                    synchronized(acousticEvents) { acousticEvents.toList() }
                )
                .associateBy { it.eventId }
                .values
                .sortedWith(compareBy({ it.episodeStartElapsedMillis }, { it.eventId }))
            _finalizationStage.value = SessionFinalizationStage.PERSISTING
            val persistenceFailures = mutableListOf<String>()
            val transcriptPersisted = runCatching { repository.saveTranscript(sessionId, fallbackTranscript) }
                .onFailure {
                    it.rethrowIfCancellation()
                    persistenceFailures += "transcript:${it.message ?: "failed"}"
                }
            val eventsPersisted = runCatching { repository.replaceDivergenceEvents(sessionId, fallbackEvents) }
                .onFailure {
                    it.rethrowIfCancellation()
                    persistenceFailures += "events:${it.message ?: "failed"}"
                }
            val sessionEnd = runCatching {
                repository.endSession(
                    sessionId = sessionId,
                    timestampMs = System.currentTimeMillis(),
                    summaryStatus = "ERROR_AUDIO_DRAIN_FAILED",
                    finalizationStatus = "FAILED_WITH_EVIDENCE"
                )
            }.onFailure {
                it.rethrowIfCancellation()
                persistenceFailures += "session:${it.message ?: "failed"}"
            }
            val message = "Session audio could not be drained: ${stopFailure?.message ?: "unknown error"}"
            _sessionError.value = SessionOperationError.StartFailed(message)
            _finalizationStage.value = SessionFinalizationStage.FAILED_WITH_EVIDENCE
            _finalizationState.value = SessionFinalizationState.Failed(
                sessionId = sessionId,
                message = message,
                evidencePersisted = transcriptPersisted.isSuccess || eventsPersisted.isSuccess,
                warnings = persistenceFailures
            )
            clearSessionOwnership()
            return
        }
        val completedStop = checkNotNull(stopResult)
        val audio = completedStop.audio
        val finalizedTranscript = completedStop.finalTranscript
            .filter { it.sessionId == sessionId }
            .filter(SpeechSegment::isFinal)
            .filter { segment ->
                val cutoff = sessionStopCutoff
                cutoff == null ||
                    timeline == null ||
                    (
                        cutoff.sessionId == sessionId &&
                            cutoff.generation == timeline.generation &&
                            cutoff.accepts(segment, timeline)
                        )
            }
            .map { segment ->
                recordProsodyMarker(segment)
                liveTranscriptReducer.applyUserOverrides(segment)
            }
            .associateBy(SpeechSegment::id)
            .values
            .sortedWith(compareBy(SpeechSegment::startOffsetMs, SpeechSegment::endOffsetMs, SpeechSegment::id))
        synchronized(transcriptSegments) {
            transcriptSegments.clear()
            transcriptSegments.addAll(finalizedTranscript)
        }
        _liveTranscriptState.update { current ->
            if (current.sessionId != sessionId) {
                current
            } else {
                current.copy(
                    segments = finalizedTranscript,
                    processing = current.processing.copy(
                        sessionId = sessionId,
                        audioGaps = completedStop.audioGaps,
                        droppedFrameCount = completedStop.droppedFrameCount,
                        isDraining = false
                    )
                )
            }
        }
        publishLiveSessionState(sessionId)
        val finalBiosignal = (drainedBiosignal ?: biosignalEngine.liveState.value).takeIf { snapshot ->
            snapshot.sessionId == sessionId &&
                (timeline == null || snapshot.sessionGeneration == timeline.generation)
        }
        val physiologicalDivergences = finalBiosignal?.divergenceEvents.orEmpty()
            .let { events ->
                sessionStopCutoff
                    ?.takeIf { it.sessionId == sessionId && (timeline == null || it.generation == timeline.generation) }
                    ?.filter(events)
                    ?: events
            }
        val finalAudioDivergences = synchronized(acousticEvents) { acousticEvents.toList() }
            .let { events ->
                sessionStopCutoff
                    ?.takeIf { it.sessionId == sessionId && (timeline == null || it.generation == timeline.generation) }
                    ?.filter(events)
                    ?: events
            }
        val finalDivergences = (physiologicalDivergences + finalAudioDivergences)
            .associateBy { it.eventId }
            .values
            .sortedWith(compareBy({ it.episodeStartElapsedMillis }, { it.eventId }))
        val linkedDivergences = finalDivergences.map { event ->
            val linkedId = TranscriptTimelineLinker.link(
                event = DivergenceEvent(
                    id = event.eventId,
                    sessionId = event.sessionId,
                    timestampMs = event.timestampMs,
                    currentStressProbability = event.currentProbability,
                    baselineStressProbability = event.baselineProbability,
                    divergenceDelta = event.delta,
                    isElevatedArousal = true,
                    channel = runCatching { com.stressdetector.shared.PpgType.valueOf(event.channel) }
                        .getOrDefault(com.stressdetector.shared.PpgType.GREEN),
                    markerElapsedMillis = event.markerElapsedMillis,
                    episodeStartElapsedMillis = event.episodeStartElapsedMillis,
                    episodeEndElapsedMillis = event.episodeEndElapsedMillis,
                    contextWindowStartElapsedMillis = event.contextWindowStartElapsedMillis,
                    contextWindowEndElapsedMillis = event.contextWindowEndElapsedMillis,
                    confidence = event.confidence,
                    signalQuality = event.signalQuality,
                    sourceWindowId = event.sourceWindowId,
                    transcriptSegmentId = event.transcriptSegmentId,
                    markerType = runCatching { PhysiologicalMarkerType.valueOf(event.markerType) }
                        .getOrDefault(PhysiologicalMarkerType.MODEL_AROUSAL),
                    bpm = event.bpm,
                    baselineBpm = event.baselineBpm,
                    peakBpm = event.peakBpm,
                    bpmDelta = event.bpmDelta,
                    bpmTrend = event.bpmTrend,
                    sourceChannels = event.sourceChannels
                        .split(',')
                        .mapNotNull { name -> runCatching { com.stressdetector.shared.PpgType.valueOf(name) }.getOrNull() }
                        .toSet()
                        .ifEmpty {
                            setOf(
                                runCatching { com.stressdetector.shared.PpgType.valueOf(event.channel) }
                                    .getOrDefault(com.stressdetector.shared.PpgType.GREEN)
                            )
                        },
                    markerConfigVersion = event.markerConfigVersion
                ),
                transcript = finalizedTranscript,
                toleranceMillis = TRANSCRIPT_LINK_TOLERANCE_MILLIS,
                timeline = timeline
            )
            event.copy(transcriptSegmentId = linkedId)
        }
        synchronized(divergenceEvents) {
            divergenceEvents.clear()
            divergenceEvents.addAll(linkedDivergences)
        }
        _finalizationStage.value = SessionFinalizationStage.PERSISTING
        var finalizationFailure: Throwable? = null
        val artifacts = runCatching {
            withContext(Dispatchers.IO) {
                finalizer.finalize(
                    userId = userId,
                    sessionId = sessionId,
                    customSessionName = currentSessionName,
                    recordedAudio = audio,
                    transcriptSegments = finalizedTranscript,
                    divergenceEvents = linkedDivergences,
                    monitorStatus = currentMonitorStatus,
                    annotations = annotationRepository.getAnnotationsForSession(sessionId),
                    ppgArchive = ppgArchiveResult,
                    audioStartSessionElapsedMillis = timeline?.audioStartElapsedMillisOrNull()
                )
            }
        }.onFailure { error ->
            error.rethrowIfCancellation()
            finalizationFailure = error
        }.getOrNull()
        _lastArtifacts.value = artifacts
        val persistenceFailures = mutableListOf<String>()
        val transcriptPersisted = runCatching {
            repository.saveTranscript(sessionId, synchronized(transcriptSegments) { transcriptSegments.toList() })
        }.onFailure { error ->
            error.rethrowIfCancellation()
            persistenceFailures += "transcript:${error.message ?: "failed"}"
        }
        val eventsPersisted = runCatching {
            repository.replaceDivergenceEvents(sessionId, linkedDivergences)
        }.onFailure { error ->
            error.rethrowIfCancellation()
            persistenceFailures += "events:${error.message ?: "failed"}"
        }
        val processingError = completedStop.processingError
        processingError?.let { error ->
            currentMonitorStatus = ("$currentMonitorStatus; AUDIO_PROCESSING_DEGRADED: $error")
                .take(MAX_MONITOR_STATUS_LENGTH)
            _monitorStatus.value = currentMonitorStatus
        }
        val evidenceComplete = artifacts != null && transcriptPersisted.isSuccess && eventsPersisted.isSuccess
        val persistenceComplete = evidenceComplete && processingError == null && artifacts.warnings.isEmpty()
        val sessionEnd = runCatching {
            repository.endSession(
                sessionId,
                System.currentTimeMillis(),
                persistedAudioPath(audio, artifacts),
                artifactFolderPath = artifacts?.folderPath.orEmpty(),
                summaryStatus = when {
                    processingError != null -> "ERROR_AUDIO_PROCESSING"
                    else -> artifacts?.summaryStatus ?: "ERROR_FINALIZATION_FAILED"
                },
                audioGaps = completedStop.audioGaps,
                finalizationStatus = if (persistenceComplete) "COMPLETED" else "FAILED_WITH_EVIDENCE"
            )
        }.onFailure { error ->
            error.rethrowIfCancellation()
            persistenceFailures += "session:${error.message ?: "failed"}"
        }
        if (persistenceComplete && sessionEnd.isSuccess) {
            _finalizationStage.value = SessionFinalizationStage.COMPLETED
            _finalizationState.value = SessionFinalizationState.Completed(sessionId, artifacts)
        } else {
            val reasons = buildList {
                finalizationFailure?.let { add("artifacts:${it.message ?: "failed"}") }
                processingError?.let { add("audio_processing:$it") }
                addAll(artifacts?.warnings.orEmpty())
                addAll(persistenceFailures)
            }
            val message = "Session finalization completed with retained evidence: ${reasons.joinToString().ifBlank { "unknown error" }}"
            _sessionError.value = SessionOperationError.StartFailed(message)
            _finalizationStage.value = SessionFinalizationStage.FAILED_WITH_EVIDENCE
            _finalizationState.value = SessionFinalizationState.Failed(
                sessionId = sessionId,
                message = message,
                evidencePersisted = artifacts != null || transcriptPersisted.isSuccess || eventsPersisted.isSuccess,
                warnings = reasons
            )
        }
        clearSessionOwnership()
    }

    suspend fun pauseSession() = sessionLifecycleMutex.withLock {
        pauseSessionInternal()
    }

    private suspend fun pauseSessionInternal() {
        if (!_isSessionActive.value) return
        when (val result = audioEngine.pauseRecording()) {
            is AudioOperationResult.Success -> Unit
            is AudioOperationResult.Unavailable -> throw IllegalStateException(result.reason)
            is AudioOperationResult.Failure -> throw IllegalStateException(result.message, result.cause)
        }
    }

    suspend fun resumeSession() = sessionLifecycleMutex.withLock {
        resumeSessionInternal()
    }

    private suspend fun resumeSessionInternal() {
        if (!_isSessionActive.value) return
        when (val result = audioEngine.resumeRecording()) {
            is AudioOperationResult.Success -> Unit
            is AudioOperationResult.Unavailable -> throw IllegalStateException(result.reason)
            is AudioOperationResult.Failure -> throw IllegalStateException(result.message, result.cause)
        }
    }

    fun updateSpeakerLabel(segmentId: String, label: String) {
        if (label.isBlank()) return
        val sessionId = currentSessionId ?: return
        val hasSegment = synchronized(transcriptSegments) {
            transcriptSegments.any { it.id == segmentId }
        }
        if (!hasSegment) return
        liveTranscriptReducer.setSpeakerOverride(segmentId, label)
        synchronized(transcriptSegments) {
            val index = transcriptSegments.indexOfFirst { it.id == segmentId }
            if (index < 0) return
            transcriptSegments[index] = transcriptSegments[index].copy(speakerId = label)
        }
        _liveTranscriptState.update { current ->
            if (current.sessionId != sessionId) {
                current
            } else {
                current.copy(
                    segments = current.segments.map { segment ->
                        if (segment.id == segmentId) segment.copy(speakerId = label) else segment
                    }
                )
            }
        }
        publishLiveSessionState(sessionId)
    }

    fun updateTranscriptText(segmentId: String, text: String) {
        val sessionId = currentSessionId ?: return
        val hasSegment = synchronized(transcriptSegments) {
            transcriptSegments.any { it.id == segmentId }
        }
        if (!hasSegment) return
        liveTranscriptReducer.setTextOverride(segmentId, text)
        synchronized(transcriptSegments) {
            val index = transcriptSegments.indexOfFirst { it.id == segmentId }
            if (index < 0) return
            transcriptSegments[index] = transcriptSegments[index].copy(editedText = text)
        }
        _liveTranscriptState.update { current ->
            if (current.sessionId != sessionId) {
                current
            } else {
                current.copy(
                    segments = current.segments.map { segment ->
                        if (segment.id == segmentId) segment.copy(editedText = text) else segment
                    }
                )
            }
        }
        publishLiveSessionState(sessionId)
    }

    fun currentElapsedMillis(): Long = currentTimeline?.currentElapsedMillis() ?: 0L

    /** Serializes annotation persistence with stop/finalization so no note is lost from the export snapshot. */
    suspend fun saveAnnotation(annotation: DomainAnnotation) = sessionLifecycleMutex.withLock {
        annotationRepository.addAnnotation(annotation)
    }

    fun reportOperationError(message: String) {
        _sessionError.value = SessionOperationError.StartFailed(message)
    }

    private fun upsertTranscript(segment: SpeechSegment, expectedSessionId: String) {
        if (!acceptingSessionEvents ||
            currentSessionId != expectedSessionId ||
            segment.sessionId.isBlank() ||
            segment.sessionId != expectedSessionId
        ) {
            return
        }
        _liveTranscriptState.update { current ->
            liveTranscriptReducer.accept(current, segment, expectedSessionId)
        }
        synchronized(transcriptSegments) {
            transcriptSegments.clear()
            transcriptSegments.addAll(_liveTranscriptState.value.segments)
        }
        if (segment.isFinal) recordProsodyMarker(segment)
        publishLiveSessionState(expectedSessionId)
    }

    private fun recordProsodyMarker(segment: SpeechSegment) {
        val timeline = currentTimeline ?: return
        val marker = if (analyzedProsodySegments.add(segment.id)) prosodyDetector.analyze(segment) else null
        if (marker == null) return
        val start = timeline.audioOffsetToSessionElapsed(marker.startOffsetMs)
        val end = timeline.audioOffsetToSessionElapsed(marker.endOffsetMs)
        val event = com.stressdetector.mobile.data.db.DivergenceEventEntity(
            eventId = "${segment.sessionId}:opensmile:${segment.id}",
            sessionId = segment.sessionId,
            timestampMs = start,
            delta = null,
            currentProbability = null,
            baselineProbability = null,
            copyText = "openSMILE eGeMAPSv02: rata-rata pitch voiced %.2f semitone; baseline pembicara %.2f; selisih %+.2f. ".format(
                java.util.Locale.ROOT,
                marker.pitchSemitones,
                marker.baselineSemitones,
                marker.pitchSemitones - marker.baselineSemitones
            ) + "Perubahan prosodi untuk ditinjau, bukan prediksi stres atau desepsi.",
            channel = "AUDIO",
            markerElapsedMillis = start,
            episodeStartElapsedMillis = start,
            episodeEndElapsedMillis = end,
            contextWindowStartElapsedMillis = start,
            contextWindowEndElapsedMillis = end,
            confidence = segment.confidence,
            signalQuality = segment.confidence,
            sourceWindowId = segment.id,
            transcriptSegmentId = segment.id,
            markerType = "PROSODY_DIVERGENCE",
            sourceChannels = "AUDIO",
            markerConfigVersion = marker.evidence.version,
            audioAnalysisVersion = marker.evidence.version
        )
        synchronized(acousticEvents) { acousticEvents.add(event) }
    }

    private fun acceptsTranscriptEvent(segment: SpeechSegment, expectedSessionId: String, timeline: SessionTimeline): Boolean {
        if ((!acceptingSessionEvents && sessionStopCutoff == null) ||
            currentSessionId != expectedSessionId ||
            segment.sessionId != expectedSessionId
        ) {
            return false
        }
        val cutoff = sessionStopCutoff ?: return true
        return cutoff.sessionId == expectedSessionId &&
            cutoff.generation == timeline.generation &&
            cutoff.accepts(segment, timeline)
    }

    private fun captureDrainedBiosignal(sessionId: String, timeline: SessionTimeline?): LiveBiosignalSnapshot? {
        val snapshot = biosignalEngine.liveState.value.takeIf {
            it.sessionId == sessionId && (timeline == null || it.sessionGeneration == timeline.generation)
        } ?: return null
        val events = sessionStopCutoff
            ?.takeIf { it.sessionId == sessionId && (timeline == null || it.generation == timeline.generation) }
            ?.filter(snapshot.divergenceEvents)
            ?: snapshot.divergenceEvents
        synchronized(divergenceEvents) {
            divergenceEvents.clear()
            divergenceEvents.addAll(events)
        }
        publishLiveSessionState(sessionId)
        return snapshot.copy(divergenceEvents = events)
    }

    /** Read authoritative state inside one publication boundary, never a caller's stale snapshot. */
    private fun publishLiveSessionState(sessionId: String? = currentSessionId) = synchronized(livePublicationLock) {
        if (sessionId == null || currentSessionId != sessionId) return@synchronized
        val transcript = _liveTranscriptState.value
        val generation = currentTimeline?.takeIf { it.sessionId == sessionId }?.generation ?: 0L
        val safeBiosignal = liveBiosignalState.value.takeIf {
            it.sessionId == sessionId && (generation == 0L || it.sessionGeneration == generation)
        } ?: LiveBiosignalSnapshot(sessionId = sessionId, sessionGeneration = generation)
        val audioEvents = synchronized(acousticEvents) { acousticEvents.toList() }
        val combinedEvents = (safeBiosignal.divergenceEvents + audioEvents)
            .filter { it.sessionId == sessionId }
            .associateBy { it.eventId }.values
            .sortedWith(compareBy({ it.episodeStartElapsedMillis }, { it.eventId }))
            .let { sessionStopCutoff?.filter(it) ?: it }
        val projectedBiosignal = safeBiosignal.copy(divergenceEvents = combinedEvents)
        _liveSessionState.value = UnifiedSessionLiveSnapshot(
            sessionId = sessionId,
            generation = generation,
            biosignal = projectedBiosignal,
            acousticAnalysis = latestAcousticAnalysis,
            livePitch = livePitchHistory,
            audioStartSessionElapsedMs = currentTimeline?.audioStartElapsedMillisOrNull(),
            transcript = transcript,
            backlogFrames = transcript.processing.backlogFrames,
            audioGaps = transcript.processing.audioGaps,
            updatedAtMillis = System.currentTimeMillis()
        )
    }

    private suspend fun cancelCollectors() {
        transcriptJob?.cancelAndJoin()
        speechProcessingJob?.cancelAndJoin()
        divergenceJob?.cancelAndJoin()
        acousticJob?.cancelAndJoin()
        pitchJob?.cancelAndJoin()
        transcriptJob = null
        speechProcessingJob = null
        divergenceJob = null
        acousticJob = null
        pitchJob = null
    }

    private fun clearSessionOwnership() {
        currentSessionId = null
        currentUserId = null
        currentTimeline = null
        acceptingSessionEvents = false
        sessionStopCutoff = null
        sessionStopping = false
    }

    private fun acceptsAudioDiagnostic(
        eventSessionId: String,
        endOffsetMs: Long,
        expectedSessionId: String,
        timeline: SessionTimeline
    ): Boolean {
        if (!acceptingSessionEvents && sessionStopCutoff == null) return false
        val ownsSession = currentSessionId == expectedSessionId &&
            eventSessionId == expectedSessionId &&
            currentTimeline?.generation == timeline.generation
        return ownsSession && (sessionStopCutoff?.acceptsAudioOffset(eventSessionId, endOffsetMs, timeline) ?: true)
    }

    private fun AcousticAnalysisSnapshot.toDivergenceEntity(
        marker: SessionMarkerType,
        timeline: SessionTimeline
    ): com.stressdetector.mobile.data.db.DivergenceEventEntity = com.stressdetector.mobile.data.db.DivergenceEventEntity(
        eventId = markerEpisodeId ?: "${sessionId}_audio_${marker.name}_$startOffsetMs",
        sessionId = sessionId,
        timestampMs = timeline.audioOffsetToSessionElapsed(markerEpisodeStartOffsetMs ?: startOffsetMs),
        delta = baselineDeviationDb
            ?.let { (it / ACOUSTIC_DELTA_NORMALIZER_DB).coerceIn(-1f, 1f) },
        currentProbability = null,
        baselineProbability = null,
        copyText = when (marker) {
            SessionMarkerType.ACOUSTIC_DIVERGENCE ->
                "Perubahan energi suara terhadap baseline akustik; bukan indikator kebohongan."
            SessionMarkerType.LOW_AUDIO_QUALITY ->
                "Kualitas audio rendah pada frame ini; tinjau rekaman sebelum menarik kesimpulan."
            else -> "Marker audio untuk ditinjau bersama konteks transkrip."
        },
        channel = "AUDIO",
        markerElapsedMillis = timeline.audioOffsetToSessionElapsed(markerEpisodeStartOffsetMs ?: startOffsetMs),
        episodeStartElapsedMillis = timeline.audioOffsetToSessionElapsed(markerEpisodeStartOffsetMs ?: startOffsetMs),
        episodeEndElapsedMillis = timeline.audioOffsetToSessionElapsed(endOffsetMs),
        contextWindowStartElapsedMillis = timeline.audioOffsetToSessionElapsed(markerEpisodeStartOffsetMs ?: startOffsetMs),
        contextWindowEndElapsedMillis = timeline.audioOffsetToSessionElapsed(endOffsetMs),
        confidence = evidenceConfidence,
        signalQuality = qualityScore,
        sourceWindowId = "${sessionId}_audio_${startOffsetMs}_$endOffsetMs",
        markerType = marker.name,
        sourceChannels = "AUDIO",
        markerConfigVersion = analysisVersion,
        audioRmsDb = rmsDb,
        audioBaselineRmsDb = baselineRmsDb,
        audioBaselineDeviationDb = baselineDeviationDb,
        audioZeroCrossingRate = zeroCrossingRate,
        audioClippingRatio = clippingRatio,
        audioFiniteSampleRatio = finiteSampleRatio,
        audioAnalysisVersion = analysisVersion
    )

    companion object {
        private const val WATCH_DRAIN_TIMEOUT_MILLIS = PpgBatchPolicy.PHONE_DRAIN_TIMEOUT_MILLIS
        private const val TRANSCRIPT_LINK_TOLERANCE_MILLIS = 1_500L
        private const val ACOUSTIC_DELTA_NORMALIZER_DB = 20f
        private const val MAX_MONITOR_STATUS_LENGTH = 240

        internal fun persistedAudioPath(recordedAudio: RecordedAudio, artifacts: VerbatimSessionArtifacts?): String =
            artifacts?.audioPath?.takeIf { it.isNotBlank() } ?: recordedAudio.encryptedRelativePath
    }
}
