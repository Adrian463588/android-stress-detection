package com.stressdetector.mobile

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stressdetector.mobile.data.IPpgRepository
import com.stressdetector.mobile.data.IWearConnectionRepository
import com.stressdetector.mobile.data.MonotonicSessionClock
import com.stressdetector.mobile.data.PpgSessionBusyException
import com.stressdetector.mobile.data.PpgStreamState
import com.stressdetector.mobile.data.WearConnectionState
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.data.db.StressDatabase
import com.stressdetector.mobile.data.db.StressSessionEntity
import com.stressdetector.mobile.di.AppLocator
import com.stressdetector.mobile.domain.CalendarDayItem
import com.stressdetector.mobile.domain.CoreCoverageDiagnostics
import com.stressdetector.mobile.domain.CorePpgWindowSlicer
import com.stressdetector.mobile.domain.DailySummary
import com.stressdetector.mobile.domain.FinalizedSessionReport
import com.stressdetector.mobile.domain.FinalizedWindowReport
import com.stressdetector.mobile.domain.HomeFinalizationResult
import com.stressdetector.mobile.domain.HomeSessionFinalizer
import com.stressdetector.mobile.domain.HomeSessionSnapshot
import com.stressdetector.mobile.domain.IStressInferenceUseCase
import com.stressdetector.mobile.domain.PpgChartData
import com.stressdetector.mobile.domain.PpgProcessingSnapshot
import com.stressdetector.mobile.domain.PredictionAvailability
import com.stressdetector.mobile.domain.PredictionWindowStats
import com.stressdetector.mobile.domain.ReportCalculator
import com.stressdetector.mobile.domain.StressAggregator
import com.stressdetector.mobile.domain.WeeklyTrendItem
import com.stressdetector.mobile.domain.engine.WatchSessionCoordinator
import com.stressdetector.mobile.domain.history.HistoryChartArtifactStore
import com.stressdetector.mobile.domain.history.saveCharts
import com.stressdetector.mobile.domain.predictionOrNull
import com.stressdetector.mobile.signal.BpmSummaryAccumulator
import com.stressdetector.mobile.signal.PpgBpmEstimator
import com.stressdetector.mobile.signal.PpgPreprocessResult
import com.stressdetector.mobile.signal.PreparedPpgWindowSlicer
import com.stressdetector.shared.PpgBatchPolicy
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.PredictionWindowConfig
import com.stressdetector.shared.SessionConfig
import com.stressdetector.shared.StressPrediction
import com.stressdetector.shared.WearSessionMode
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MobileUiState(
    val greenSamples: Int = 0,
    val irSamples: Int = 0,
    val redDiagnosticSamples: Int = 0,
    val totalGreenSamples: Int = 0,
    val totalIrSamples: Int = 0,
    val totalRedDiagnosticSamples: Int = 0,
    val sessionId: String? = null,
    val startedAtMillis: Long = 0L,
    val isRecording: Boolean = false,
    val elapsedMillis: Long = 0L,
    val progress: Float = 0f,
    val permissionsGranted: Boolean = false,
    val connectionState: WearConnectionState = WearConnectionState(),
    val terminalReceiptReceived: Boolean = false,
    val transportComplete: Boolean = false,
    val transportIncomplete: Boolean = false,
    val transportFailureReason: String? = null,
    val observedDataElapsedMillis: Long = 0L,
    val appliedBatchCount: Int = 0,
    val duplicateBatchCount: Int = 0,
    val outOfOrderBatchCount: Int = 0,
    val missingSequenceCount: Long = 0L,
    val highestGreenSequence: Long = 0L,
    val highestIrSequence: Long = 0L,
    val highestRedDiagnosticSequence: Long = 0L,
    val savedSessionIds: Set<String> = emptySet(),
    val primaryPrediction: StressPrediction? = null,
    val irPrediction: StressPrediction? = null,
    val primaryPredictionState: PredictionAvailability = PredictionAvailability.WarmingUp(
        currentSamples = 0,
        requiredSamples = PpgType.GREEN.samplingRateHz * SessionConfig.INFERENCE_WINDOW_SECONDS
    ),
    val irPredictionState: PredictionAvailability = PredictionAvailability.WarmingUp(
        currentSamples = 0,
        requiredSamples = PpgType.IR.sourceRateHz * SessionConfig.INFERENCE_WINDOW_SECONDS
    ),
    val greenCoreDiagnostics: CoreCoverageDiagnostics? = null,
    val irCoreDiagnostics: CoreCoverageDiagnostics? = null,
    val greenChart: PpgChartData = PpgChartData.empty("Menunggu data sensor"),
    val irChart: PpgChartData = PpgChartData.empty("Menunggu data sensor"),
    val redChart: PpgChartData = PpgChartData.empty("Menunggu data diagnostik"),
    val history: List<StressSessionEntity> = emptyList(),
    val todaySummary: DailySummary = DailySummary(0, 0, 0),
    val weeklyTrend: List<WeeklyTrendItem> = emptyList(),
    val reportMonthMillis: Long = System.currentTimeMillis(),
    val selectedReportDateMillis: Long = System.currentTimeMillis(),
    val selectedDaySummary: DailySummary = DailySummary(0, 0, 0),
    val selectedDayHistory: List<StressSessionEntity> = emptyList(),
    val calendarDays: List<CalendarDayItem> = emptyList(),
    val lastError: String? = null,
    val sessionNotice: String? = null
)

class StressViewModel(
    application: Application,
    private val inference: IStressInferenceUseCase,
    private val ppgRepository: IPpgRepository,
    private val wearConnectionRepository: IWearConnectionRepository,
    private val watchSessionCoordinator: WatchSessionCoordinator,
    private val dao: com.stressdetector.mobile.data.db.StressSessionDao
) : AndroidViewModel(application) {
    private val mutableUiState = MutableStateFlow(MobileUiState())
    val uiState: StateFlow<MobileUiState> = mutableUiState.asStateFlow()
    private val homeFinalizer = HomeSessionFinalizer(
        dao = dao,
        evidenceMode = if (BuildConfig.RESEARCH_MODEL_MODE) "research" else "production"
    )
    private val historyChartArtifactStore = HistoryChartArtifactStore(application)
    private var finalizationJob: Job? = null
    private var sessionClockJob: Job? = null
    private val sessionClock = MonotonicSessionClock()
    private val finalizationRequested = mutableSetOf<String>()
    private val protocolRejectedStops = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            ppgRepository.state.collect { state ->
                // Repository is shared with Verbatim+. Home must never render
                // or finalize another feature's Watch session.
                if (!state.isHomeStressSession()) {
                    clearHomeLiveProjection()
                    return@collect
                }
                if (state.protocolRejected) {
                    sessionClock.stop(state.sessionId.orEmpty())
                    sessionClockJob?.cancel()
                    wearConnectionRepository.markRecording(false)
                    state.sessionId?.takeIf(protocolRejectedStops::add)?.let { rejectedSessionId ->
                        runCatching {
                            watchSessionCoordinator.stop(rejectedSessionId, completionExpected = false)
                        }
                    }
                    val reason = state.protocolRejectionReason
                        ?: "protocol_mismatch: update phone and Watch app, then refresh connection"
                    mutableUiState.update {
                        it.copy(
                            isRecording = false,
                            terminalReceiptReceived = false,
                            transportComplete = false,
                            transportIncomplete = true,
                            transportFailureReason = "protocol_mismatch",
                            primaryPrediction = null,
                            irPrediction = null,
                            primaryPredictionState = PredictionAvailability.Unavailable("protocol_mismatch"),
                            irPredictionState = PredictionAvailability.Unavailable("protocol_mismatch"),
                            greenCoreDiagnostics = null,
                            irCoreDiagnostics = null,
                            greenChart = PpgChartData.empty("PROTOCOL_MISMATCH / refresh lalu update phone dan Watch"),
                            irChart = PpgChartData.empty("PROTOCOL_MISMATCH / refresh lalu update phone dan Watch"),
                            redChart = PpgChartData.empty("PROTOCOL_MISMATCH / refresh lalu update phone dan Watch"),
                            lastError = reason,
                            sessionNotice = "Versi protokol phone dan Watch berbeda. Perbarui kedua aplikasi lalu refresh koneksi."
                        )
                    }
                    return@collect
                }
                val progress = (state.elapsedMillis.toFloat() / SessionConfig.SESSION_DURATION_MILLIS).coerceIn(0f, 1f)
                val currentUi = mutableUiState.value
                val greenReadiness = (currentUi.primaryPredictionState as? PredictionAvailability.WarmingUp)
                    ?.let { warming ->
                        warming.copy(
                            currentSamples = state.greenSamples.coerceIn(0, warming.requiredSamples.coerceAtLeast(0))
                        )
                    }
                    ?: currentUi.primaryPredictionState
                val irReadiness = (currentUi.irPredictionState as? PredictionAvailability.WarmingUp)
                    ?.let { warming ->
                        warming.copy(
                            currentSamples = state.irSamples.coerceIn(0, warming.requiredSamples.coerceAtLeast(0))
                        )
                    }
                    ?: currentUi.irPredictionState
                mutableUiState.value = currentUi.copy(
                    greenSamples = state.greenSamples,
                    irSamples = state.irSamples,
                    redDiagnosticSamples = state.redSamples,
                    totalGreenSamples = state.totalGreenSamples,
                    totalIrSamples = state.totalIrSamples,
                    totalRedDiagnosticSamples = state.totalRedSamples,
                    sessionId = state.sessionId,
                    elapsedMillis = state.elapsedMillis,
                    progress = progress,
                    terminalReceiptReceived = state.terminalReceiptReceived,
                    transportComplete = state.transportComplete,
                    transportIncomplete = state.transportIncomplete,
                    transportFailureReason = state.transportFailureReason,
                    observedDataElapsedMillis = state.observedDataElapsedMillis,
                    appliedBatchCount = state.appliedBatchCount,
                    duplicateBatchCount = state.duplicateBatchCount,
                    outOfOrderBatchCount = state.outOfOrderBatchCount,
                    missingSequenceCount = state.missingSequenceCount,
                    highestGreenSequence = state.highestGreenSequence,
                    highestIrSequence = state.highestIrSequence,
                    highestRedDiagnosticSequence = state.highestRedSequence,
                    primaryPredictionState = greenReadiness,
                    irPredictionState = irReadiness,
                    lastError = state.lastError
                )
                if (state.isComplete) requestFinalization(state.sessionId)
            }
        }
        viewModelScope.launch {
            ppgRepository.chartFrames.collect { frame ->
                if (frame.capturedAtMillis <= 0L) return@collect
                val current = ppgRepository.state.value
                if (!current.isHomeStressSession() ||
                    current.protocolRejected ||
                    frame.sessionId == null ||
                    frame.sessionId != current.sessionId ||
                    frame.sessionNonce != current.sessionNonce ||
                    frame.protocolVersion != current.protocolVersion
                ) {
                    return@collect
                }
                val charts = kotlinx.coroutines.withContext(Dispatchers.Default) {
                    Triple(
                        inference.preview(frame.greenSnapshot),
                        inference.preview(frame.irSnapshot),
                        inference.preview(frame.redSnapshot)
                    )
                }
                mutableUiState.update {
                    it.copy(
                        greenChart = charts.first,
                        irChart = charts.second,
                        redChart = charts.third
                    )
                }
            }
        }
        viewModelScope.launch {
            wearConnectionRepository.state.collect { connection ->
                mutableUiState.value = mutableUiState.value.copy(connectionState = connection)
            }
        }
        viewModelScope.launch {
            dao.latest().collect { history ->
                val current = mutableUiState.value
                mutableUiState.value = current.copy(
                    history = history,
                    todaySummary = ReportCalculator.todaySummary(System.currentTimeMillis(), history),
                    weeklyTrend = ReportCalculator.weeklyTrend(System.currentTimeMillis(), history)
                ).withReport(history)
            }
        }
    }

    private data class CoreChannelEvaluation(
        val aggregate: StressPrediction?,
        val availability: PredictionAvailability,
        val windows: List<PpgProcessingSnapshot>,
        val chart: PpgChartData,
        val coreDiagnostics: CoreCoverageDiagnostics? = null,
        val bpmSummary: com.stressdetector.mobile.domain.engine.BpmSummary? = null
    )

    private data class CoreEvaluation(val green: CoreChannelEvaluation, val ir: CoreChannelEvaluation)

    private fun evaluateCoreChannel(
        channel: PpgType,
        fullSession: WindowSnapshot,
        transportMissingSequenceCount: Long
    ): CoreChannelEvaluation {
        val modelContract = inference.modelContract(channel)
            ?: return unavailableChannel(
                channel = channel,
                fullSession = fullSession,
                reason = "model_contract_unavailable"
            )
        val predictionConfig = runCatching {
            PredictionWindowConfig(
                sessionSeconds = SessionConfig.SESSION_DURATION_SECONDS,
                edgeTrimSeconds = (SessionConfig.SESSION_DURATION_SECONDS - modelContract.analysisSeconds) / 2,
                analysisSeconds = modelContract.analysisSeconds,
                modelWindowSeconds = modelContract.windowSeconds,
                strideSeconds = modelContract.strideSeconds
            )
        }.getOrElse {
            return unavailableChannel(
                channel = channel,
                fullSession = fullSession,
                reason = "manifest_window_contract_invalid"
            )
        }
        if (predictionConfig.expectedModelWindows != modelContract.expectedWindowsPerSession) {
            return unavailableChannel(
                channel = channel,
                fullSession = fullSession,
                reason = "manifest_expected_window_count_mismatch"
            )
        }
        val coreWindowSlicer = CorePpgWindowSlicer(predictionConfig)
        val preparedWindowSlicer = PreparedPpgWindowSlicer(predictionConfig)
        val aggregator = StressAggregator(predictionConfig)
        val core = coreWindowSlicer.slice(fullSession, transportMissingSequenceCount)
        if (!core.isComplete) {
            return CoreChannelEvaluation(
                aggregate = null,
                availability = PredictionAvailability.Rejected(
                    reason = core.rejectionReason ?: "core_coverage_insufficient",
                    sampleCount = fullSession.size,
                    windowStats = PredictionWindowStats(
                        expectedWindowCount = core.expectedModelWindows
                    )
                ),
                windows = emptyList(),
                chart = inference.preview(fullSession).copy(
                    mode = com.stressdetector.mobile.domain.PpgChartMode.RAW_DIAGNOSTIC,
                    statusMessage = "RAW / DIAGNOSTIC: ${core.rejectionReason ?: "core_coverage_insufficient"}"
                ),
                coreDiagnostics = core.diagnostics
            )
        }

        // Edge samples [0,30) and [270,300] are audit-only. They must not
        // poison core preprocessing or make an otherwise valid model window
        // fail because a terminal Watch batch arrived late.
        val preparedSession = when (val result = inference.prepareSession(requireNotNull(core.coreWindow))) {
            is PpgPreprocessResult.Ready -> result.window
            is PpgPreprocessResult.WarmingUp -> {
                return CoreChannelEvaluation(
                    aggregate = null,
                    availability = PredictionAvailability.Rejected(
                        reason = "session_preprocessing_warming_up:${result.currentSamples}/${result.requiredSamples}",
                        sampleCount = fullSession.size,
                        windowStats = PredictionWindowStats(
                            expectedWindowCount = core.expectedModelWindows
                        )
                    ),
                    windows = emptyList(),
                    chart = inference.preview(fullSession).copy(
                        mode = com.stressdetector.mobile.domain.PpgChartMode.RAW_DIAGNOSTIC,
                        statusMessage = "RAW / DIAGNOSTIC: session preprocessing warming up"
                    ),
                    coreDiagnostics = core.diagnostics
                )
            }
            is PpgPreprocessResult.Rejected -> {
                return CoreChannelEvaluation(
                    aggregate = null,
                    availability = PredictionAvailability.Rejected(
                        result.reason,
                        fullSession.size,
                        PredictionWindowStats(expectedWindowCount = core.expectedModelWindows)
                    ),
                    windows = emptyList(),
                    chart = inference.preview(fullSession).copy(
                        mode = com.stressdetector.mobile.domain.PpgChartMode.RAW_DIAGNOSTIC,
                        statusMessage = "RAW / DIAGNOSTIC: ${result.reason}"
                    ),
                    coreDiagnostics = core.diagnostics
                )
            }
        }
        val windows = preparedWindowSlicer.modelWindows(preparedSession).map { window ->
            inference.processPrepared(window).also(::logProcessing)
        }
        windows.mapNotNull { it.availability.predictionOrNull() }
            .filter { it.sensor == channel }
            .forEach(aggregator::addPrediction)

        val aggregation = aggregator.aggregate(channel, attemptedWindowCount = windows.size)
        val aggregate = aggregation.prediction
        val windowStats = PredictionWindowStats(
            attemptedWindowCount = aggregation.attemptedWindowCount,
            validWindowCount = aggregation.validWindowCount,
            expectedWindowCount = aggregation.expectedWindowCount
        )
        val diagnosticRejection = if (aggregation.validWindowCount == 0) {
            dominantRejectionReason(windows)
        } else {
            null
        }
        val bpmAccumulator = BpmSummaryAccumulator()
        if (windows.any { it.availability is PredictionAvailability.Ready }) {
            val bpmEstimator = PpgBpmEstimator(BPM_OBSERVATION_SECONDS)
            preparedWindowSlicer.bpmWindows(preparedSession, BPM_OBSERVATION_SECONDS)
                .mapNotNull(bpmEstimator::estimate)
                .forEach(bpmAccumulator::add)
        }
        return CoreChannelEvaluation(
            aggregate = aggregate,
            availability = aggregate?.let { PredictionAvailability.Ready(it, windowStats) }
                ?: PredictionAvailability.Rejected(
                    reason = diagnosticRejection
                        ?: aggregation.rejectionReason
                        ?: "insufficient_valid_core_windows:${aggregation.validWindowCount}/${aggregation.expectedWindowCount}",
                    sampleCount = fullSession.size,
                    windowStats = windowStats
                ),
            windows = windows,
            chart = inference.chartForPrepared(preparedSession),
            coreDiagnostics = core.diagnostics,
            bpmSummary = bpmAccumulator.summary()
        )
    }

    private fun unavailableChannel(channel: PpgType, fullSession: WindowSnapshot, reason: String): CoreChannelEvaluation =
        CoreChannelEvaluation(
            aggregate = null,
            availability = PredictionAvailability.Unavailable(reason),
            windows = emptyList(),
            chart = inference.preview(fullSession).copy(
                mode = com.stressdetector.mobile.domain.PpgChartMode.RAW_DIAGNOSTIC,
                statusMessage = "${channel.assetSuffix}: $reason"
            )
        )

    private fun dominantRejectionReason(windows: List<PpgProcessingSnapshot>): String? {
        val counts = linkedMapOf<String, Int>()
        windows.forEach { window ->
            window.rejectionReason?.let { reason ->
                counts[reason] = counts.getOrDefault(reason, 0) + 1
            }
        }
        var dominantReason: String? = null
        var dominantCount = 0
        counts.forEach { (reason, count) ->
            if (count > dominantCount) {
                dominantReason = reason
                dominantCount = count
            }
        }
        return dominantReason
    }

    private fun evaluateCorePredictions(state: PpgStreamState): CoreEvaluation {
        // Core [30s,270s) evidence determines prediction eligibility. Terminal
        // transport is saved separately, so a late/missing tail ACK cannot
        // overwrite a fully verified core as generic invalid.
        val green = evaluateCoreChannel(
            PpgType.GREEN,
            ppgRepository.fullGreenWindow(),
            state.missingGreenSequenceCount
        )
        val ir = evaluateCoreChannel(
            PpgType.IR,
            ppgRepository.fullIrWindow(),
            state.missingIrSequenceCount
        )
        return CoreEvaluation(green, ir)
    }

    private fun logProcessing(snapshot: com.stressdetector.mobile.domain.PpgProcessingSnapshot) {
        val preparedSamples = snapshot.preparedWindow?.size ?: 0
        val interpolated = snapshot.preparedWindow?.interpolatedSampleCount ?: 0
        Log.i(
            TAG,
            "channel=${snapshot.channel.assetSuffix} raw=${snapshot.rawSampleCount} " +
                "durationMs=${snapshot.durationMillis} clean=$preparedSamples interpolated=$interpolated " +
                "quality=${snapshot.quality?.tier ?: "unavailable"} " +
                "status=${snapshot.availability::class.simpleName} " +
                "reason=${snapshot.rejectionReason ?: "none"} " +
                "model=${snapshot.availability.predictionOrNull()?.modelVersion ?: "none"} " +
                "rawProbability=${snapshot.availability.predictionOrNull()?.rawStressProbability ?: "none"} " +
                "calibratedProbability=${snapshot.availability.predictionOrNull()?.stressProbability ?: "none"} " +
                "fingerprint=${snapshot.processingFingerprint}"
        )
    }

    fun startWatchTracking() {
        viewModelScope.launch {
            if (finalizationJob?.isActive == true) {
                mutableUiState.value = mutableUiState.value.copy(
                    lastError = "Previous stress session is still finalizing. Please try again shortly."
                )
                return@launch
            }
            if (!mutableUiState.value.permissionsGranted) {
                mutableUiState.value = mutableUiState.value.copy(lastError = "Please grant app permissions before connecting to the watch.")
                return@launch
            }
            val configuredChannels = listOf(PpgType.GREEN, PpgType.IR).filter { channel ->
                inference.modelContract(channel) != null &&
                    runCatching { inference.requireConfigured(setOf(channel)) }.isSuccess
            }
            if (configuredChannels.isEmpty()) {
                mutableUiState.value = mutableUiState.value.copy(
                    lastError = "Model belum siap; tidak ada kanal Green/IR Android-ready."
                )
                return@launch
            }
            val connection = wearConnectionRepository.waitForReachable(getApplication<Application>())
            if (!connection.canStartSession()) {
                mutableUiState.value = mutableUiState.value.copy(lastError = null)
                return@launch
            }

            val sessionId = "session-${UUID.randomUUID()}"
            val watchResult = watchSessionCoordinator.start(sessionId, WearSessionMode.HOME_STRESS)
            if (watchResult.isFailure) {
                val reason = when (val failure = watchResult.exceptionOrNull()) {
                    is PpgSessionBusyException -> when (failure.activeLease.mode) {
                        WearSessionMode.HOME_STRESS -> "BUSY_BY_HOME"
                        WearSessionMode.VERBATIM_AROUSAL -> "BUSY_BY_VERBATIM"
                    }
                    else -> failure?.message ?: "Watch session could not start"
                }
                mutableUiState.value = mutableUiState.value.copy(
                    lastError = reason
                )
                return@launch
            }

            val startedAtMillis = System.currentTimeMillis()
            wearConnectionRepository.markRecording(true)
            mutableUiState.value = mutableUiState.value.copy(
                isRecording = true,
                sessionId = sessionId,
                startedAtMillis = startedAtMillis,
                primaryPrediction = null,
                irPrediction = null,
                primaryPredictionState = PredictionAvailability.WarmingUp(
                    currentSamples = 0,
                    requiredSamples = requiredRawSamples(PpgType.GREEN)
                ),
                irPredictionState = PredictionAvailability.WarmingUp(
                    currentSamples = 0,
                    requiredSamples = requiredRawSamples(PpgType.IR)
                ),
                greenCoreDiagnostics = null,
                irCoreDiagnostics = null,
                greenChart = PpgChartData.empty("Menunggu data sensor"),
                irChart = PpgChartData.empty("Menunggu data sensor"),
                redChart = PpgChartData.empty("Menunggu data diagnostik"),
                lastError = null,
                sessionNotice = null
            )
            sessionClock.start(sessionId)
            ppgRepository.markSessionElapsed(sessionId, 0L, "session_started")
            startSessionClock(sessionId)
        }
    }

    /** Refreshes the Watch prerequisite without creating a stress session. */
    fun refreshWatchConnection() {
        viewModelScope.launch {
            if (!mutableUiState.value.permissionsGranted) {
                mutableUiState.value = mutableUiState.value.copy(
                    lastError = "Please grant app permissions before connecting to the watch."
                )
                return@launch
            }
            wearConnectionRepository.waitForReachable(getApplication<Application>())
            mutableUiState.update { current ->
                if (current.isRecording) current else current.copy(lastError = null)
            }
        }
    }

    fun stopWatchTracking() {
        viewModelScope.launch {
            val sessionId = mutableUiState.value.sessionId
            if (sessionId == null) return@launch
            requestFinalization(sessionId, force = true, completionReason = "user_stop")
        }
    }

    /** Retries persistence/inference for the current session without starting a new Watch session. */
    fun retryFinalization() {
        val sessionId = mutableUiState.value.sessionId ?: return
        finalizationRequested.remove(sessionId)
        requestFinalization(sessionId, force = true, completionReason = "manual_retry")
    }

    fun setPermissionsGranted(granted: Boolean) {
        mutableUiState.value = mutableUiState.value.copy(
            permissionsGranted = granted,
            lastError = if (granted) null else "Permissions required before watch connection."
        )
        if (granted) {
            viewModelScope.launch {
                wearConnectionRepository.refreshAndPing(getApplication<Application>())
            }
        }
    }

    private fun clearHomeLiveProjection() {
        val current = mutableUiState.value
        if (current.sessionId == null &&
            !current.isRecording &&
            current.greenSamples == 0 &&
            current.irSamples == 0 &&
            current.redDiagnosticSamples == 0 &&
            current.greenChart.isEmpty &&
            current.irChart.isEmpty &&
            current.redChart.isEmpty
        ) {
            return
        }
        sessionClockJob?.cancel()
        mutableUiState.update {
            it.copy(
                greenSamples = 0,
                irSamples = 0,
                redDiagnosticSamples = 0,
                totalGreenSamples = 0,
                totalIrSamples = 0,
                totalRedDiagnosticSamples = 0,
                sessionId = null,
                startedAtMillis = 0L,
                isRecording = false,
                elapsedMillis = 0L,
                progress = 0f,
                terminalReceiptReceived = false,
                transportComplete = false,
                transportIncomplete = false,
                transportFailureReason = null,
                observedDataElapsedMillis = 0L,
                appliedBatchCount = 0,
                duplicateBatchCount = 0,
                outOfOrderBatchCount = 0,
                missingSequenceCount = 0L,
                highestGreenSequence = 0L,
                highestIrSequence = 0L,
                highestRedDiagnosticSequence = 0L,
                primaryPrediction = null,
                irPrediction = null,
                primaryPredictionState = PredictionAvailability.WarmingUp(
                    currentSamples = 0,
                    requiredSamples = requiredRawSamples(PpgType.GREEN)
                ),
                irPredictionState = PredictionAvailability.WarmingUp(
                    currentSamples = 0,
                    requiredSamples = requiredRawSamples(PpgType.IR)
                ),
                greenCoreDiagnostics = null,
                irCoreDiagnostics = null,
                greenChart = PpgChartData.empty("Menunggu data sensor"),
                irChart = PpgChartData.empty("Menunggu data sensor"),
                redChart = PpgChartData.empty("Menunggu data diagnostik"),
                sessionNotice = null,
                lastError = null
            )
        }
    }

    fun selectReportDate(dateMillis: Long) {
        mutableUiState.value = mutableUiState.value.copy(selectedReportDateMillis = dateMillis)
            .withReport(mutableUiState.value.history)
    }

    fun changeReportMonth(delta: Int) {
        val nextMonth = ReportCalculator.addMonths(mutableUiState.value.reportMonthMillis, delta)
        mutableUiState.value = mutableUiState.value.copy(reportMonthMillis = nextMonth)
            .withReport(mutableUiState.value.history)
    }

    fun selectToday() {
        val now = System.currentTimeMillis()
        mutableUiState.value = mutableUiState.value.copy(
            reportMonthMillis = now,
            selectedReportDateMillis = now
        ).withReport(mutableUiState.value.history)
    }

    private fun startSessionClock(sessionId: String) {
        sessionClockJob?.cancel()
        sessionClockJob = viewModelScope.launch {
            while (isActive && mutableUiState.value.sessionId == sessionId && mutableUiState.value.isRecording) {
                val elapsed = sessionClock.elapsed(sessionId)
                ppgRepository.markSessionElapsed(sessionId, elapsed)
                if (sessionClock.isComplete(sessionId)) {
                    requestFinalization(sessionId, completionReason = "auto_session_deadline")
                    break
                }
                delay(SESSION_CLOCK_TICK_MILLIS)
            }
        }
    }

    private fun requestFinalization(sessionId: String?, force: Boolean = false, completionReason: String = "session_finalized") {
        if (sessionId.isNullOrBlank()) return
        if (ppgRepository.state.value.protocolRejected) return
        if (ppgRepository.state.value.sessionId != sessionId ||
            !ppgRepository.state.value.isHomeStressSession()
        ) {
            return
        }
        if (mutableUiState.value.savedSessionIds.contains(sessionId)) return
        if (!force && !finalizationRequested.add(sessionId)) return
        if (force) finalizationRequested.add(sessionId)
        if (finalizationJob?.isActive == true) return

        ppgRepository.markSessionElapsed(
            sessionId = sessionId,
            elapsedMillis = sessionClock.elapsed(sessionId),
            completionReason = completionReason
        )
        sessionClock.stop(sessionId)
        sessionClockJob?.cancel()

        finalizationJob = viewModelScope.launch {
            try {
                val stopResult = watchSessionCoordinator.stop(
                    sessionId = sessionId,
                    completionExpected = completionReason == "auto_session_deadline"
                )
                val drainResult = if (
                    stopResult.isSuccess &&
                    watchSessionCoordinator.state.value == com.stressdetector.mobile.domain.engine.WatchSessionState.Stopping
                ) {
                    watchSessionCoordinator.awaitDrain(sessionId, WATCH_DRAIN_TIMEOUT_MILLIS)
                } else {
                    Result.success(Unit)
                }
                val transportError = stopResult.exceptionOrNull()?.message
                    ?: drainResult.exceptionOrNull()?.message
                wearConnectionRepository.markRecording(false)
                mutableUiState.value = mutableUiState.value.copy(
                    isRecording = false,
                    lastError = transportError,
                    sessionNotice = "Finalizing stress session…"
                )

                val state = ppgRepository.state.value
                val endedAtMillis = System.currentTimeMillis()
                val coreEvaluation = kotlinx.coroutines.withContext(Dispatchers.Default) {
                    evaluateCorePredictions(state)
                }
                mutableUiState.update {
                    it.copy(
                        primaryPrediction = coreEvaluation.green.aggregate,
                        irPrediction = coreEvaluation.ir.aggregate,
                        primaryPredictionState = coreEvaluation.green.availability,
                        irPredictionState = coreEvaluation.ir.availability,
                        greenCoreDiagnostics = coreEvaluation.green.coreDiagnostics,
                        irCoreDiagnostics = coreEvaluation.ir.coreDiagnostics,
                        greenChart = coreEvaluation.green.chart,
                        irChart = coreEvaluation.ir.chart
                    )
                }
                val snapshot = HomeSessionSnapshot(
                    sessionId = sessionId,
                    startedAtMillis = mutableUiState.value.startedAtMillis.takeIf { it > 0L }
                        ?: endedAtMillis - state.elapsedMillis,
                    endedAtMillis = endedAtMillis,
                    durationMillis = state.sessionElapsedMillis,
                    observedDataDurationMillis = state.observedDataElapsedMillis,
                    missingTailMillis = state.missingTailMillis,
                    sessionComplete = state.sessionElapsedMillis >= SessionConfig.SESSION_DURATION_MILLIS,
                    coreEligible = coreEvaluation.green.aggregate != null || coreEvaluation.ir.aggregate != null,
                    transportIncomplete = !state.transportComplete || transportError != null,
                    transportFailureReason = state.transportFailureReason ?: transportError,
                    completionReason = state.completionReason ?: completionReason,
                    greenSampleCount = state.totalGreenSamples,
                    irSampleCount = state.totalIrSamples,
                    greenState = coreEvaluation.green.availability,
                    irState = coreEvaluation.ir.availability,
                    transportError = transportError
                )
                val sessionReport = FinalizedSessionReport(
                    expectedWindowCount = maxOf(
                        coreEvaluation.green.availability.predictionWindowStats().expectedWindowCount,
                        coreEvaluation.ir.availability.predictionWindowStats().expectedWindowCount
                    ),
                    windows = buildList {
                        coreEvaluation.green.windows.forEachIndexed { index, window ->
                            add(FinalizedWindowReport(PpgType.GREEN, index, window))
                        }
                        coreEvaluation.ir.windows.forEachIndexed { index, window ->
                            add(FinalizedWindowReport(PpgType.IR, index, window))
                        }
                    },
                    selectedChannel = coreEvaluation.green.aggregate?.sensor
                        ?: coreEvaluation.ir.aggregate?.sensor,
                    rejectionReason = listOfNotNull(
                        (coreEvaluation.green.availability as? PredictionAvailability.Rejected)?.reason,
                        (coreEvaluation.ir.availability as? PredictionAvailability.Rejected)?.reason
                    ).distinct().joinToString("; ").ifBlank { null },
                    greenBpmSummary = coreEvaluation.green.bpmSummary,
                    irBpmSummary = coreEvaluation.ir.bpmSummary
                )
                // Persist the clinical session report before attempting any optional
                // chart I/O. A chart failure must never discard a valid prediction.
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    homeFinalizer.finalize(
                        snapshot = snapshot,
                        aggregatedGreen = coreEvaluation.green.aggregate,
                        aggregatedIr = coreEvaluation.ir.aggregate,
                        report = sessionReport
                    )
                }
                when (result) {
                    is HomeFinalizationResult.Stored -> {
                        val chartResult = historyChartArtifactStore.saveCharts(
                            sessionId = sessionId,
                            charts = mapOf(
                                PpgType.GREEN to coreEvaluation.green.chart,
                                PpgType.IR to coreEvaluation.ir.chart
                            )
                        )
                        chartResult.exceptionOrNull()?.let { error ->
                            Log.w(TAG, "History cleaned-core chart artifact unavailable", error)
                            historyChartArtifactStore.saveUnavailable(
                                sessionId = sessionId,
                                reason = "chart_write_failed:${error::class.simpleName}"
                            )
                        }
                        mutableUiState.value = mutableUiState.value.copy(
                            primaryPrediction = result.green,
                            irPrediction = result.ir,
                            savedSessionIds = mutableUiState.value.savedSessionIds + sessionId,
                            lastError = transportError,
                            sessionNotice = null
                        )
                    }
                    is HomeFinalizationResult.Failed -> {
                        mutableUiState.value = mutableUiState.value.copy(
                            lastError = "Failed to save session: ${result.reason}",
                            sessionNotice = null
                        )
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                finalizationRequested.remove(sessionId)
                wearConnectionRepository.markRecording(false)
                mutableUiState.update {
                    it.copy(
                        isRecording = false,
                        lastError = "Stress session finalization failed: ${error.message ?: error::class.simpleName}",
                        sessionNotice = null
                    )
                }
            }
        }
    }

    private fun MobileUiState.withReport(history: List<StressSessionEntity>): MobileUiState = copy(
        selectedDaySummary = ReportCalculator.dailySummary(selectedReportDateMillis, history),
        selectedDayHistory = ReportCalculator.sessionsForDay(selectedReportDateMillis, history),
        calendarDays = ReportCalculator.monthCalendar(reportMonthMillis, history)
    )

    private fun requiredRawSamples(channel: PpgType): Int = channel.sourceRateHz * (inference.modelContract(channel)?.windowSeconds ?: 0)

    override fun onCleared() {
        finalizationJob?.cancel()
        sessionClockJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TAG = "HomePpgProcessing"
        private const val WATCH_DRAIN_TIMEOUT_MILLIS = PpgBatchPolicy.PHONE_DRAIN_TIMEOUT_MILLIS
        private const val SESSION_CLOCK_TICK_MILLIS = 250L
        private const val BPM_OBSERVATION_SECONDS = 30
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                return StressViewModel(
                    application = application,
                    inference = AppLocator.stressInferenceUseCase(application),
                    ppgRepository = AppLocator.ppgRepository,
                    wearConnectionRepository = AppLocator.wearConnectionRepository,
                    watchSessionCoordinator = AppLocator.watchSessionCoordinator(application),
                    dao = StressDatabase.get(application).stressSessionDao()
                ) as T
            }
        }
    }
}

internal fun PpgStreamState.isHomeStressSession(): Boolean = sessionMode == WearSessionMode.HOME_STRESS

private fun PredictionAvailability.predictionWindowStats(): PredictionWindowStats = when (this) {
    is PredictionAvailability.WarmingUp -> windowStats
    is PredictionAvailability.Ready -> windowStats
    is PredictionAvailability.Rejected -> windowStats
    is PredictionAvailability.Unavailable -> windowStats
    is PredictionAvailability.InferenceError -> windowStats
}
