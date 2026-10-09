package com.stressdetector.mobile

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stressdetector.inference.ModelContractStatus
import com.stressdetector.inference.ModelSessionContract
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
import com.stressdetector.mobile.diagnostics.DebugLivePpgWindowEvidence
import com.stressdetector.mobile.diagnostics.debugCaptureRawValuesSha256
import com.stressdetector.mobile.domain.CalendarDayItem
import com.stressdetector.mobile.domain.CoreCoverageDiagnostics
import com.stressdetector.mobile.domain.CorePpgSnapshot
import com.stressdetector.mobile.domain.CorePpgWindowSlicer
import com.stressdetector.mobile.domain.DailySummary
import com.stressdetector.mobile.domain.FinalizationEvaluationProgress
import com.stressdetector.mobile.domain.FinalizationRequest
import com.stressdetector.mobile.domain.FinalizationSnapshot
import com.stressdetector.mobile.domain.FinalizationSnapshotInput
import com.stressdetector.mobile.domain.FinalizationSnapshotWindows
import com.stressdetector.mobile.domain.FinalizeStressSessionUseCase
import com.stressdetector.mobile.domain.FinalizedSessionReport
import com.stressdetector.mobile.domain.FinalizedWindowReport
import com.stressdetector.mobile.domain.HomeFinalizationResult
import com.stressdetector.mobile.domain.HomeSessionFinalizer
import com.stressdetector.mobile.domain.HomeSessionSnapshot
import com.stressdetector.mobile.domain.IStressInferenceUseCase
import com.stressdetector.mobile.domain.ModelGateState
import com.stressdetector.mobile.domain.ModelGateStatus
import com.stressdetector.mobile.domain.PpgChartData
import com.stressdetector.mobile.domain.PpgProcessingSnapshot
import com.stressdetector.mobile.domain.PredictionAvailability
import com.stressdetector.mobile.domain.PredictionWindowStats
import com.stressdetector.mobile.domain.ReportCalculator
import com.stressdetector.mobile.domain.StressAggregator
import com.stressdetector.mobile.domain.StressSessionFinalizationOutcome
import com.stressdetector.mobile.domain.StressSessionFinalizationState
import com.stressdetector.mobile.domain.WeeklyTrendItem
import com.stressdetector.mobile.domain.engine.WatchSessionCoordinator
import com.stressdetector.mobile.domain.history.HistoryChartArtifactStore
import com.stressdetector.mobile.domain.history.saveCharts
import com.stressdetector.mobile.domain.predictionOrNull
import com.stressdetector.mobile.domain.reminder.AiReminderCoordinator
import com.stressdetector.mobile.signal.BpmSummaryAccumulator
import com.stressdetector.mobile.signal.PpgBpmEstimator
import com.stressdetector.mobile.signal.PpgPreprocessResult
import com.stressdetector.mobile.signal.PreparedPpgWindow
import com.stressdetector.mobile.signal.PreparedPpgWindowSlicer
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.PredictionWindowConfig
import com.stressdetector.shared.SessionConfig
import com.stressdetector.shared.StressInferenceTrace
import com.stressdetector.shared.StressPrediction
import com.stressdetector.shared.WearSessionMode
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

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
    val greenMotionAnnotations: List<com.stressdetector.mobile.signal.MotionAnnotation> = emptyList(),
    val irMotionAnnotations: List<com.stressdetector.mobile.signal.MotionAnnotation> = emptyList(),
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
    val modelGate: ModelGateState = ModelGateState(),
    /** Debug-only, explicit opt-in capture of acknowledged Watch batches. */
    val debugLivePpgCaptureEnabled: Boolean = false,
    val finalizationState: StressSessionFinalizationState = StressSessionFinalizationState.Idle,
    val finalizationSnapshotSessionId: String? = null,
    val pendingPersistence: Boolean = false,
    val lastError: String? = null,
    val sessionNotice: String? = null
)

private fun MobileUiState.acceptsLiveChartFrame(frameSessionId: String?): Boolean = isRecording &&
    finalizationState == StressSessionFinalizationState.Idle &&
    finalizationSnapshotSessionId == null &&
    frameSessionId != null &&
    frameSessionId == sessionId

internal fun MobileUiState.withLiveChartFrame(
    frameSessionId: String?,
    charts: Triple<PpgChartData, PpgChartData, PpgChartData>
): MobileUiState = if (acceptsLiveChartFrame(frameSessionId)) {
    copy(greenChart = charts.first, irChart = charts.second, redChart = charts.third)
} else {
    this
}

internal fun finalHomeChannelChart(
    rejectionReason: String?,
    cleanedCore: () -> PpgChartData,
    rawDiagnostic: () -> PpgChartData
): PpgChartData = if (rejectionReason == null) {
    cleanedCore()
} else {
    rawDiagnostic().copy(
        mode = com.stressdetector.mobile.domain.PpgChartMode.RAW_DIAGNOSTIC,
        statusMessage = "RAW / DIAGNOSTIC: $rejectionReason"
    )
}

@Suppress("LongParameterList")
class StressViewModel(
    application: Application,
    private val inference: IStressInferenceUseCase,
    private val ppgRepository: IPpgRepository,
    private val wearConnectionRepository: IWearConnectionRepository,
    private val watchSessionCoordinator: WatchSessionCoordinator,
    private val dao: com.stressdetector.mobile.data.db.StressSessionDao,
    private val aiReminderCoordinator: AiReminderCoordinator? = null
) : AndroidViewModel(application) {
    private val mutableUiState = MutableStateFlow(MobileUiState())
    val uiState: StateFlow<MobileUiState> = mutableUiState.asStateFlow()
    private val homeFinalizer = HomeSessionFinalizer(
        dao = dao,
        evidenceMode = if (BuildConfig.RESEARCH_MODEL_MODE) "research" else "internal"
    )
    private val finalizeStressSession = FinalizeStressSessionUseCase(watchSessionCoordinator)
    private val historyChartArtifactStore = HistoryChartArtifactStore(application) {
        AppLocator.userBackupService(application).requestBackup()
    }
    private var finalizationJob: Job? = null
    private var sessionClockJob: Job? = null
    private var connectionProbeJob: Job? = null
    private val sessionClock = MonotonicSessionClock()
    private val finalizationRequested = mutableSetOf<String>()
    private val pendingFinalizationSnapshots = mutableMapOf<String, FinalizationSnapshot>()
    private val pendingEvaluations = mutableMapOf<String, EvaluatedSession>()
    private val protocolRejectedStops = mutableSetOf<String>()

    init {
        val applicationContext = getApplication<Application>()
        mutableUiState.update { current ->
            current.copy(
                debugLivePpgCaptureEnabled = AppLocator
                    .debugLivePpgCaptureSettings(applicationContext)
                    .isEnabled
            )
        }
        viewModelScope.launch(Dispatchers.Default) {
            val gate = readModelGate()
            mutableUiState.update { current -> current.copy(modelGate = gate) }
        }
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
                if (!mutableUiState.value.acceptsLiveChartFrame(frame.sessionId)) return@collect
                val charts = kotlinx.coroutines.withContext(Dispatchers.Default) {
                    Triple(
                        inference.preview(frame.greenSnapshot),
                        inference.preview(frame.irSnapshot),
                        inference.preview(frame.redSnapshot)
                    )
                }
                mutableUiState.update { current ->
                    current.withLiveChartFrame(frame.sessionId, charts)
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

    private data class CoreEvaluation(val green: CoreChannelEvaluation, val ir: CoreChannelEvaluation, val redDiagnosticChart: PpgChartData)

    private data class EvaluatedSession(val snapshot: FinalizationSnapshot, val endedAtMillis: Long, val core: CoreEvaluation)

    private data class ChannelEvaluationPlan(
        val config: PredictionWindowConfig,
        val coreSlicer: CorePpgWindowSlicer,
        val preparedSlicer: PreparedPpgWindowSlicer
    )

    private sealed interface ChannelEvaluationPlanResult {
        data class Ready(val plan: ChannelEvaluationPlan) : ChannelEvaluationPlanResult
        data class Unavailable(val reason: String) : ChannelEvaluationPlanResult
    }

    private suspend fun evaluateCoreChannel(
        channel: PpgType,
        fullSession: WindowSnapshot,
        transportMissingSequenceCount: Long,
        onProgress: (completedWindows: Int, totalWindows: Int) -> Unit
    ): CoreChannelEvaluation = when (val plan = channelEvaluationPlan(channel)) {
        is ChannelEvaluationPlanResult.Unavailable -> unavailableChannel(
            channel,
            fullSession,
            plan.reason
        )
        is ChannelEvaluationPlanResult.Ready -> evaluateCoreWithPlan(
            channel,
            fullSession,
            transportMissingSequenceCount,
            onProgress,
            plan.plan
        )
    }

    private fun channelEvaluationPlan(channel: PpgType): ChannelEvaluationPlanResult =
        when (val status = inference.modelContractStatus(channel)) {
            is ModelContractStatus.Ready -> planForContract(status.contract)
            is ModelContractStatus.Unavailable -> ChannelEvaluationPlanResult.Unavailable(status.reason)
        }

    private fun planForContract(modelContract: ModelSessionContract): ChannelEvaluationPlanResult = runCatching {
        PredictionWindowConfig(
            sessionSeconds = SessionConfig.SESSION_DURATION_SECONDS,
            edgeTrimSeconds = (SessionConfig.SESSION_DURATION_SECONDS - modelContract.analysisSeconds) / 2,
            analysisSeconds = modelContract.analysisSeconds,
            modelWindowSeconds = modelContract.windowSeconds,
            strideSeconds = modelContract.strideSeconds
        )
    }.fold(
        onSuccess = { config ->
            if (config.expectedModelWindows == modelContract.expectedWindowsPerSession) {
                ChannelEvaluationPlanResult.Ready(
                    ChannelEvaluationPlan(
                        config = config,
                        coreSlicer = CorePpgWindowSlicer(config),
                        preparedSlicer = PreparedPpgWindowSlicer(config)
                    )
                )
            } else {
                ChannelEvaluationPlanResult.Unavailable("manifest_expected_window_count_mismatch")
            }
        },
        onFailure = {
            ChannelEvaluationPlanResult.Unavailable("manifest_window_contract_invalid")
        }
    )

    private suspend fun evaluateCoreWithPlan(
        channel: PpgType,
        fullSession: WindowSnapshot,
        transportMissingSequenceCount: Long,
        onProgress: (completedWindows: Int, totalWindows: Int) -> Unit,
        plan: ChannelEvaluationPlan
    ): CoreChannelEvaluation {
        val core = plan.coreSlicer.slice(fullSession, transportMissingSequenceCount)
        if (!core.isComplete) {
            val reason = core.rejectionReason ?: "core_coverage_insufficient"
            return rejectedCoreChannel(fullSession, core, reason, "RAW / DIAGNOSTIC: $reason")
        }
        return evaluatePreparedCore(channel, fullSession, core, onProgress, plan)
    }

    private suspend fun evaluatePreparedCore(
        channel: PpgType,
        fullSession: WindowSnapshot,
        core: CorePpgSnapshot,
        onProgress: (completedWindows: Int, totalWindows: Int) -> Unit,
        plan: ChannelEvaluationPlan
    ): CoreChannelEvaluation {
        // Edge samples [0,30) and [270,300] are audit-only. They must not poison
        // core preprocessing or cause a valid core to fail from a late tail batch.
        return when (val result = inference.prepareSession(requireNotNull(core.coreWindow))) {
            is PpgPreprocessResult.Ready -> aggregatePreparedCore(
                channel,
                fullSession,
                core,
                result.window,
                onProgress,
                plan
            )
            is PpgPreprocessResult.WarmingUp -> {
                val reason = "session_preprocessing_warming_up:${result.currentSamples}/${result.requiredSamples}"
                rejectedCoreChannel(
                    fullSession,
                    core,
                    reason,
                    "RAW / DIAGNOSTIC: session preprocessing warming up"
                )
            }
            is PpgPreprocessResult.Rejected -> {
                val diagnostics = result.diagnostics
                Log.i(
                    TAG,
                    "core_rejected channel=${channel.assetSuffix} reason=${result.reason} " +
                        "samples=${diagnostics?.observedSampleCount ?: 0} " +
                        "maxGapMs=${diagnostics?.maxGapMillis ?: 0} " +
                        "trueGaps=${diagnostics?.trueGapCount ?: 0} " +
                        "sequenceLoss=${core.diagnostics?.transportMissingSequenceCount ?: 0}"
                )
                rejectedCoreChannel(fullSession, core, result.reason, "RAW / DIAGNOSTIC: ${result.reason}")
            }
        }
    }

    private suspend fun aggregatePreparedCore(
        channel: PpgType,
        fullSession: WindowSnapshot,
        core: CorePpgSnapshot,
        preparedSession: PreparedPpgWindow,
        onProgress: (completedWindows: Int, totalWindows: Int) -> Unit,
        plan: ChannelEvaluationPlan
    ): CoreChannelEvaluation {
        val aggregator = StressAggregator(plan.config)
        val windows = buildList {
            plan.preparedSlicer.modelWindows(preparedSession).forEachIndexed { index, window ->
                currentCoroutineContext().ensureActive()
                add(inference.processPrepared(window).also(::logProcessing))
                onProgress(index + 1, plan.config.expectedModelWindows)
                yield()
            }
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
        val rejectionReason = if (aggregate == null) {
            diagnosticRejection ?: aggregation.rejectionReason
                ?: "insufficient_valid_core_windows:${aggregation.validWindowCount}/${aggregation.expectedWindowCount}"
        } else {
            null
        }
        val bpmAccumulator = BpmSummaryAccumulator()
        if (windows.any { it.availability is PredictionAvailability.Ready }) {
            val bpmEstimator = PpgBpmEstimator(BPM_OBSERVATION_SECONDS)
            plan.preparedSlicer.bpmWindows(preparedSession, BPM_OBSERVATION_SECONDS)
                .mapNotNull(bpmEstimator::estimate)
                .forEach(bpmAccumulator::add)
        }
        return CoreChannelEvaluation(
            aggregate = aggregate,
            availability = aggregate?.let { PredictionAvailability.Ready(it, windowStats) }
                ?: PredictionAvailability.Rejected(
                    reason = requireNotNull(rejectionReason),
                    sampleCount = fullSession.size,
                    windowStats = windowStats
                ),
            windows = windows,
            chart = finalHomeChannelChart(
                rejectionReason = rejectionReason,
                cleanedCore = { inference.chartForPrepared(preparedSession) },
                rawDiagnostic = { inference.preview(fullSession) }
            ),
            coreDiagnostics = core.diagnostics,
            bpmSummary = bpmAccumulator.summary()
        )
    }

    private fun rejectedCoreChannel(
        fullSession: WindowSnapshot,
        core: CorePpgSnapshot,
        reason: String,
        statusMessage: String
    ): CoreChannelEvaluation = CoreChannelEvaluation(
        aggregate = null,
        availability = PredictionAvailability.Rejected(
            reason = reason,
            sampleCount = fullSession.size,
            windowStats = PredictionWindowStats(expectedWindowCount = core.expectedModelWindows)
        ),
        windows = emptyList(),
        chart = inference.preview(fullSession).copy(
            mode = com.stressdetector.mobile.domain.PpgChartMode.RAW_DIAGNOSTIC,
            statusMessage = statusMessage
        ),
        coreDiagnostics = core.diagnostics
    )

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

    private suspend fun evaluateCorePredictions(
        snapshot: FinalizationSnapshot,
        onProgress: (FinalizationEvaluationProgress) -> Unit
    ): CoreEvaluation = coroutineScope {
        // Core [30s,270s) evidence determines prediction eligibility. Terminal
        // transport is saved separately, so a late/missing tail ACK cannot
        // overwrite a fully verified core as generic invalid.
        val progress = linkedMapOf<PpgType, com.stressdetector.mobile.domain.FinalizationChannelProgress>()
        fun report(channel: PpgType, completed: Int, total: Int) {
            val snapshotProgress = synchronized(progress) {
                progress[channel] = com.stressdetector.mobile.domain.FinalizationChannelProgress(completed, total)
                progress.toMap()
            }
            onProgress(
                FinalizationEvaluationProgress(
                    channel = channel,
                    completedWindows = completed,
                    totalWindows = total,
                    channels = snapshotProgress
                )
            )
        }

        report(PpgType.GREEN, completed = 0, total = 0)
        report(PpgType.IR, completed = 0, total = 0)
        val green = async(Dispatchers.Default) {
            evaluateCoreChannel(
                PpgType.GREEN,
                snapshot.greenWindow,
                snapshot.streamState.missingGreenSequenceCount
            ) { completed, total -> report(PpgType.GREEN, completed, total) }
        }
        val ir = async(Dispatchers.Default) {
            evaluateCoreChannel(
                PpgType.IR,
                snapshot.irWindow,
                snapshot.streamState.missingIrSequenceCount
            ) { completed, total -> report(PpgType.IR, completed, total) }
        }
        CoreEvaluation(
            green = green.await(),
            ir = ir.await(),
            redDiagnosticChart = inference.preview(snapshot.redWindow).copy(
                mode = com.stressdetector.mobile.domain.PpgChartMode.RAW_DIAGNOSTIC,
                statusMessage = "RAW / DIAGNOSTIC: Red tidak digunakan model"
            )
        )
    }

    private fun logProcessing(snapshot: com.stressdetector.mobile.domain.PpgProcessingSnapshot) {
        val prepared = snapshot.preparedWindow
        val prediction = snapshot.availability.predictionOrNull()
        Log.i(
            TAG,
            "channel=${snapshot.channel.assetSuffix} raw=${snapshot.rawSampleCount} " +
                "durationMs=${snapshot.durationMillis} ${prepared.processingLogFields()} " +
                "quality=${snapshot.quality?.tier ?: "unavailable"} " +
                "status=${snapshot.availability::class.simpleName} " +
                "reason=${snapshot.rejectionReason ?: "none"} " +
                "${prediction.processingLogFields()} ${snapshot.inferenceTrace.processingLogFields()} " +
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
                when (inference.modelContractStatus(channel)) {
                    is ModelContractStatus.Ready ->
                        runCatching { inference.requireConfigured(setOf(channel)) }.isSuccess
                    is ModelContractStatus.Unavailable -> false
                }
            }
            if (configuredChannels.isEmpty()) {
                val gate = readModelGate()
                mutableUiState.value = mutableUiState.value.copy(
                    modelGate = gate.copy(
                        status = ModelGateStatus.UNAVAILABLE,
                        reason = gate.reason ?: "model_runtime_unavailable"
                    ),
                    lastError = null
                )
                return@launch
            }
            mutableUiState.update { current ->
                current.copy(modelGate = ModelGateState(ModelGateStatus.READY))
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
                greenMotionAnnotations = emptyList(),
                irMotionAnnotations = emptyList(),
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
                finalizationState = StressSessionFinalizationState.Idle,
                finalizationSnapshotSessionId = null,
                pendingPersistence = false,
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
        if (connectionProbeJob?.isActive == true) return
        val probeJob = viewModelScope.launch {
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
        connectionProbeJob = probeJob
        probeJob.invokeOnCompletion {
            if (connectionProbeJob === probeJob) connectionProbeJob = null
        }
    }

    /** Re-checks the prerequisite when Home becomes visible without interrupting a session. */
    fun onHomeResumed() {
        if (mutableUiState.value.isRecording || finalizationJob?.isActive == true) return
        refreshWatchConnection()
    }

    fun stopWatchTracking() {
        viewModelScope.launch {
            val sessionId = mutableUiState.value.sessionId
            if (sessionId == null) return@launch
            requestFinalization(sessionId, force = true, completionReason = "user_stop")
        }
    }

    /** Retries persistence from the same frozen evaluation before re-reading the Watch. */
    fun retryFinalization() {
        val sessionId = mutableUiState.value.sessionId ?: return
        val pendingEvaluation = pendingEvaluations[sessionId]
        if (pendingEvaluation != null) {
            if (finalizationJob?.isActive != true) {
                finalizationJob = viewModelScope.launch {
                    publishFinalizationState(StressSessionFinalizationState.Persisting)
                    when (
                        val result = persistEvaluatedSession(
                            sessionId = sessionId,
                            startedAtMillis = mutableUiState.value.startedAtMillis,
                            completionReason = "persistence_retry",
                            evaluated = pendingEvaluation,
                            transportWarning = null
                        )
                    ) {
                        is HomeFinalizationResult.Stored -> {
                            applyStoredSession(sessionId, pendingEvaluation, null)
                            pendingFinalizationSnapshots.remove(sessionId)
                            pendingEvaluations.remove(sessionId)
                            val insufficientReason = insufficientSignalReason(pendingEvaluation)
                            publishFinalizationState(
                                insufficientReason?.let(StressSessionFinalizationState::InsufficientSignal)
                                    ?: StressSessionFinalizationState.Completed
                            )
                        }
                        is HomeFinalizationResult.Failed -> {
                            mutableUiState.update { it.copy(pendingPersistence = true) }
                            publishFinalizationState(
                                StressSessionFinalizationState.ResultReady(
                                    persisted = false,
                                    warning = result.reason
                                )
                            )
                        }
                    }
                }
            }
        } else {
            finalizationRequested.remove(sessionId)
            requestFinalization(sessionId, force = true, completionReason = "manual_retry")
        }
    }

    fun setPermissionsGranted(granted: Boolean) {
        mutableUiState.value = mutableUiState.value.copy(
            permissionsGranted = granted,
            lastError = if (granted) null else "Permissions required before watch connection."
        )
        if (granted) {
            onHomeResumed()
        }
    }

    /** Enables private raw-Watch capture only in a debuggable APK. */
    fun setDebugLivePpgCaptureEnabled(enabled: Boolean) {
        val accepted = AppLocator
            .debugLivePpgCaptureSettings(getApplication<Application>())
            .setEnabled(enabled)
        mutableUiState.update { current ->
            current.copy(debugLivePpgCaptureEnabled = accepted)
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
        mutableUiState.update { state -> state.resetHomeLiveProjection() }
    }

    private fun MobileUiState.resetHomeLiveProjection(): MobileUiState = resetSampleCounters()
        .resetSessionTracking()
        .resetPredictions()
        .resetCharts()
        .copy(
            finalizationState = StressSessionFinalizationState.Idle,
            finalizationSnapshotSessionId = null,
            pendingPersistence = false,
            sessionNotice = null,
            lastError = null
        )

    private fun MobileUiState.resetSampleCounters(): MobileUiState = copy(
        greenSamples = 0,
        irSamples = 0,
        redDiagnosticSamples = 0,
        totalGreenSamples = 0,
        totalIrSamples = 0,
        totalRedDiagnosticSamples = 0
    )

    private fun MobileUiState.resetSessionTracking(): MobileUiState = copy(
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
        highestRedDiagnosticSequence = 0L
    )

    private fun MobileUiState.resetPredictions(): MobileUiState = copy(
        primaryPrediction = null,
        irPrediction = null,
        greenMotionAnnotations = emptyList(),
        irMotionAnnotations = emptyList(),
        primaryPredictionState = PredictionAvailability.WarmingUp(
            currentSamples = 0,
            requiredSamples = requiredRawSamples(PpgType.GREEN)
        ),
        irPredictionState = PredictionAvailability.WarmingUp(
            currentSamples = 0,
            requiredSamples = requiredRawSamples(PpgType.IR)
        ),
        greenCoreDiagnostics = null,
        irCoreDiagnostics = null
    )

    private fun MobileUiState.resetCharts(): MobileUiState = copy(
        greenChart = PpgChartData.empty("Menunggu data sensor"),
        irChart = PpgChartData.empty("Menunggu data sensor"),
        redChart = PpgChartData.empty("Menunggu data diagnostik")
    )

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
        val pendingSnapshot = pendingFinalizationSnapshots[sessionId]
        val repositoryState = ppgRepository.state.value
        if (pendingSnapshot == null &&
            (repositoryState.sessionId != sessionId || !repositoryState.isHomeStressSession())
        ) {
            return
        }
        if (mutableUiState.value.savedSessionIds.contains(sessionId)) return
        if (!force && !finalizationRequested.add(sessionId)) return
        if (force) finalizationRequested.add(sessionId)
        if (finalizationJob?.isActive == true) return

        if (pendingSnapshot == null) {
            ppgRepository.markSessionElapsed(
                sessionId = sessionId,
                elapsedMillis = sessionClock.elapsed(sessionId),
                completionReason = completionReason
            )
        }
        sessionClock.stop(sessionId)
        sessionClockJob?.cancel()
        val startedAtMillis = mutableUiState.value.startedAtMillis

        finalizationJob = viewModelScope.launch {
            try {
                val outcome = finalizeStressSession.execute(
                    request = FinalizationRequest(
                        sessionId = sessionId,
                        completionExpected = completionReason == "auto_session_deadline"
                    ),
                    onState = ::publishFinalizationState,
                    evaluate = { progress ->
                        val snapshot = pendingFinalizationSnapshots[sessionId]
                            ?: freezeFinalizationSnapshot(sessionId).also {
                                pendingFinalizationSnapshots[sessionId] = it
                            }
                        mutableUiState.update { current ->
                            if (current.sessionId == sessionId) {
                                current.copy(finalizationSnapshotSessionId = sessionId)
                            } else {
                                current
                            }
                        }
                        val core = kotlinx.coroutines.withContext(Dispatchers.Default) {
                            evaluateCorePredictions(snapshot, onProgress = progress)
                        }
                        EvaluatedSession(
                            snapshot = snapshot,
                            endedAtMillis = System.currentTimeMillis(),
                            core = core
                        )
                    },
                    insufficientSignalReason = ::insufficientSignalReason,
                    persist = { evaluated, transportWarning ->
                        persistEvaluatedSession(
                            sessionId = sessionId,
                            startedAtMillis = startedAtMillis,
                            completionReason = completionReason,
                            evaluated = evaluated,
                            transportWarning = transportWarning
                        )
                    },
                    evaluationFingerprint = { evaluated -> evaluated.snapshot.sourceFingerprint },
                    onEvaluationReady = { evaluated ->
                        pendingEvaluations[sessionId] = evaluated
                        applyEvaluatedSession(evaluated, persisted = false, warning = "Hasil dihitung; menyimpan sesi…")
                    }
                )
                handleFinalizationOutcome(sessionId, outcome)
            } finally {
                wearConnectionRepository.markRecording(false)
            }
        }
    }

    private fun freezeFinalizationSnapshot(sessionId: String): FinalizationSnapshot {
        val state = ppgRepository.state.value
        require(state.sessionId == sessionId) { "finalization_snapshot_session_mismatch" }
        return FinalizationSnapshot.from(
            FinalizationSnapshotInput(
                sessionId = sessionId,
                streamState = state,
                windows = FinalizationSnapshotWindows(
                    green = ppgRepository.fullGreenWindow(),
                    ir = ppgRepository.fullIrWindow(),
                    red = ppgRepository.fullRedWindow()
                ),
                capturedAtMonotonicMillis = android.os.SystemClock.elapsedRealtime()
            )
        )
    }

    private suspend fun persistEvaluatedSession(
        sessionId: String,
        startedAtMillis: Long,
        completionReason: String,
        evaluated: EvaluatedSession,
        transportWarning: String?
    ): HomeFinalizationResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
        homeFinalizer.finalize(
            snapshot = sessionSnapshot(
                sessionId = sessionId,
                startedAtMillis = startedAtMillis,
                completionReason = completionReason,
                evaluated = evaluated,
                transportWarning = transportWarning
            ),
            aggregatedGreen = evaluated.core.green.aggregate,
            aggregatedIr = evaluated.core.ir.aggregate,
            report = sessionReport(evaluated.core)
        )
    }

    private fun publishFinalizationState(state: StressSessionFinalizationState) {
        mutableUiState.update { current ->
            val terminalReason = when (state) {
                is StressSessionFinalizationState.Failed -> "finalization_failed:${state.reason}"
                is StressSessionFinalizationState.TimedOut -> "finalization_timeout:${state.stage.name}"
                else -> null
            }
            current.copy(
                isRecording = false,
                finalizationState = state,
                sessionNotice = null,
                lastError = when (state) {
                    is StressSessionFinalizationState.Failed -> "Stress session finalization failed: ${state.reason}"
                    is StressSessionFinalizationState.TimedOut -> "Stress session finalization timed out: ${state.stage.name}"
                    else -> null
                },
                primaryPredictionState = current.primaryPredictionState.takeUnless {
                    terminalReason != null && it is PredictionAvailability.WarmingUp
                } ?: PredictionAvailability.Unavailable(terminalReason.orEmpty()),
                irPredictionState = current.irPredictionState.takeUnless {
                    terminalReason != null && it is PredictionAvailability.WarmingUp
                } ?: PredictionAvailability.Unavailable(terminalReason.orEmpty())
            )
        }
    }

    private fun insufficientSignalReason(evaluated: EvaluatedSession): String? = evaluated.core.let { core ->
        if (core.green.aggregate != null || core.ir.aggregate != null) {
            null
        } else {
            listOfNotNull(
                (core.green.availability as? PredictionAvailability.Rejected)?.reason,
                (core.ir.availability as? PredictionAvailability.Rejected)?.reason
            ).distinct().joinToString("; ").ifBlank { "both_channels_unavailable" }
        }
    }

    private fun sessionSnapshot(
        sessionId: String,
        startedAtMillis: Long,
        completionReason: String,
        evaluated: EvaluatedSession,
        transportWarning: String?
    ): HomeSessionSnapshot {
        val state = evaluated.snapshot.streamState
        val core = evaluated.core
        return HomeSessionSnapshot(
            sessionId = sessionId,
            startedAtMillis = startedAtMillis.takeIf { it > 0L } ?: evaluated.endedAtMillis - state.elapsedMillis,
            endedAtMillis = evaluated.endedAtMillis,
            durationMillis = state.sessionElapsedMillis,
            observedDataDurationMillis = state.observedDataElapsedMillis,
            missingTailMillis = state.missingTailMillis,
            sessionComplete = state.sessionElapsedMillis >= SessionConfig.SESSION_DURATION_MILLIS,
            coreEligible = core.green.aggregate != null || core.ir.aggregate != null,
            transportIncomplete = !state.transportComplete || transportWarning != null,
            transportFailureReason = state.transportFailureReason ?: transportWarning,
            completionReason = state.completionReason ?: completionReason,
            greenSampleCount = state.totalGreenSamples,
            irSampleCount = state.totalIrSamples,
            greenState = core.green.availability,
            irState = core.ir.availability,
            transportError = transportWarning
        )
    }

    private fun sessionReport(core: CoreEvaluation): FinalizedSessionReport = FinalizedSessionReport(
        expectedWindowCount = maxOf(
            core.green.availability.predictionWindowStats().expectedWindowCount,
            core.ir.availability.predictionWindowStats().expectedWindowCount
        ),
        windows = buildList {
            core.green.windows.forEachIndexed { index, window ->
                add(FinalizedWindowReport(PpgType.GREEN, index, window))
            }
            core.ir.windows.forEachIndexed { index, window ->
                add(FinalizedWindowReport(PpgType.IR, index, window))
            }
        },
        selectedChannel = core.green.aggregate?.sensor ?: core.ir.aggregate?.sensor,
        rejectionReason = listOfNotNull(
            if (core.green.aggregate?.label != null &&
                core.ir.aggregate?.label != null &&
                core.green.aggregate.label != core.ir.aggregate.label
            ) {
                "CHANNEL_DISAGREEMENT"
            } else {
                null
            },
            if (core.green.aggregate == null && core.ir.aggregate != null) "IR_USED_GREEN_UNAVAILABLE" else null,
            (core.green.availability as? PredictionAvailability.Rejected)?.reason,
            (core.ir.availability as? PredictionAvailability.Rejected)?.reason
        ).distinct().joinToString("; ").ifBlank { null },
        greenBpmSummary = core.green.bpmSummary,
        irBpmSummary = core.ir.bpmSummary
    )

    private fun handleFinalizationOutcome(sessionId: String, outcome: StressSessionFinalizationOutcome<EvaluatedSession>) {
        when (outcome) {
            is StressSessionFinalizationOutcome.Completed -> {
                applyStoredSession(sessionId, outcome.evaluation, outcome.trace.transportWarning)
                pendingFinalizationSnapshots.remove(sessionId)
                pendingEvaluations.remove(sessionId)
            }
            is StressSessionFinalizationOutcome.InsufficientSignal -> {
                applyStoredSession(sessionId, outcome.evaluation, outcome.trace.transportWarning)
                pendingFinalizationSnapshots.remove(sessionId)
                pendingEvaluations.remove(sessionId)
            }
            is StressSessionFinalizationOutcome.PersistencePending -> {
                pendingFinalizationSnapshots[sessionId] = outcome.evaluation.snapshot
                pendingEvaluations[sessionId] = outcome.evaluation
                mutableUiState.update { it.copy(pendingPersistence = true) }
                applyEvaluatedSession(
                    evaluated = outcome.evaluation,
                    persisted = false,
                    warning = null
                )
            }
            is StressSessionFinalizationOutcome.Failed -> Unit
            is StressSessionFinalizationOutcome.TimedOut -> Unit
        }
        if (outcome is StressSessionFinalizationOutcome.PersistencePending) {
            Log.w(TAG, "finalization_outcome=persistence_pending error=${outcome.reason.take(MAX_FAILURE_REASON_LOG_LENGTH)}")
        }
        Log.i(TAG, finalizationTraceLog(outcome.trace))
    }

    private fun applyEvaluatedSession(evaluated: EvaluatedSession, persisted: Boolean, warning: String?) {
        mutableUiState.update { current ->
            current.copy(
                primaryPrediction = evaluated.core.green.aggregate,
                irPrediction = evaluated.core.ir.aggregate,
                primaryPredictionState = evaluated.core.green.availability,
                irPredictionState = evaluated.core.ir.availability,
                greenCoreDiagnostics = evaluated.core.green.coreDiagnostics,
                irCoreDiagnostics = evaluated.core.ir.coreDiagnostics,
                greenMotionAnnotations = evaluated.core.green.windows.mapNotNull { it.preparedWindow?.motionAnnotation },
                irMotionAnnotations = evaluated.core.ir.windows.mapNotNull { it.preparedWindow?.motionAnnotation },
                greenChart = evaluated.core.green.chart,
                irChart = evaluated.core.ir.chart,
                redChart = evaluated.core.redDiagnosticChart,
                finalizationState = if (persisted) StressSessionFinalizationState.Completed else current.finalizationState,
                sessionNotice = warning
            )
        }
    }

    private fun applyStoredSession(sessionId: String, evaluated: EvaluatedSession, transportWarning: String?) {
        applyEvaluatedSession(
            evaluated = evaluated,
            persisted = true,
            warning = transportWarning?.let { "Transport warning: $it" }
        )
        mutableUiState.update { current ->
            current.copy(savedSessionIds = current.savedSessionIds + sessionId, pendingPersistence = false)
        }
        scheduleOptionalSessionArtifacts(
            sessionId = sessionId,
            sessionNonce = evaluated.snapshot.streamState.sessionNonce,
            core = evaluated.core
        )
    }

    private fun scheduleOptionalSessionArtifacts(sessionId: String, sessionNonce: String, core: CoreEvaluation) {
        viewModelScope.launch(Dispatchers.IO) {
            val capture = AppLocator.debugLivePpgCapture(getApplication<Application>())
            val snapshots = core.green.windows + core.ir.windows
            val traces = buildList {
                snapshots.mapNotNullTo(this) { it.inferenceTrace }
                core.green.aggregate?.inferenceTrace?.let(::add)
                core.ir.aggregate?.inferenceTrace?.let(::add)
            }
            capture.recordInferenceTraces(sessionId, traces)
            capture.recordWindowEvidence(
                sessionId,
                snapshots.mapIndexedNotNull { index, snapshot ->
                    snapshot.toDebugLivePpgWindowEvidence(index)
                }
            )
            if (!capture.markSessionFinalized(sessionId, sessionNonce)) {
                Log.w(TAG, "Debug capture session not found for finalization: ${sessionId.hashCode()}")
            }
            runCatching { aiReminderCoordinator?.onSessionFinalized(sessionId) }
                .onFailure { error -> Log.w(TAG, "Reminder update unavailable", error) }
            val chartResult = historyChartArtifactStore.saveCharts(
                sessionId = sessionId,
                charts = mapOf(PpgType.GREEN to core.green.chart, PpgType.IR to core.ir.chart)
            )
            chartResult.exceptionOrNull()?.let { error ->
                Log.w(TAG, "History cleaned-core chart artifact unavailable", error)
                historyChartArtifactStore.saveUnavailable(
                    sessionId = sessionId,
                    reason = "chart_write_failed:${error::class.simpleName}"
                )
            }
        }
    }

    private fun PpgProcessingSnapshot.toDebugLivePpgWindowEvidence(index: Int): DebugLivePpgWindowEvidence? {
        val prepared = preparedWindow ?: return null
        return DebugLivePpgWindowEvidence(
            channel = channel.assetSuffix,
            windowId = prepared.windowId ?: "${prepared.sessionId ?: "session"}_${channel.assetSuffix}_$index",
            timestampsMillis = prepared.timestampsMillis.toList(),
            rawValues = prepared.rawValues.toList(),
            rawValuesSha256 = debugCaptureRawValuesSha256(prepared.rawValues),
            filteredValues = prepared.filteredValues.toList(),
            filteredValuesSha256 = prepared.filteredValuesSha256,
            observedMask = prepared.observedMask.toList(),
            repairedShortGapMask = prepared.repairedShortGapMask.toList(),
            gridResampledMask = prepared.gridResampledMask.toList(),
            trueGapCount = prepared.trueGapCount,
            unresolvedSampleCount = prepared.unresolvedSampleCount,
            inferenceTrace = inferenceTrace
        )
    }

    private fun finalizationTraceLog(trace: com.stressdetector.mobile.domain.StressSessionFinalizationTrace): String =
        "finalization_trace session=${trace.sessionId.hashCode()} totalMs=${trace.totalElapsedMillis} " +
            "stages=${trace.stageDurations.joinToString(",") { "${it.stage.name}:${it.elapsedMillis}" }} " +
            "transportWarning=${trace.transportWarning ?: "none"} " +
            "snapshot=${trace.snapshotFingerprint ?: "none"}"

    private fun MobileUiState.withReport(history: List<StressSessionEntity>): MobileUiState = copy(
        selectedDaySummary = ReportCalculator.dailySummary(selectedReportDateMillis, history),
        selectedDayHistory = ReportCalculator.sessionsForDay(selectedReportDateMillis, history),
        calendarDays = ReportCalculator.monthCalendar(reportMonthMillis, history)
    )

    private fun requiredRawSamples(channel: PpgType): Int = when (val status = inference.modelContractStatus(channel)) {
        is ModelContractStatus.Ready -> channel.sourceRateHz * status.contract.windowSeconds
        is ModelContractStatus.Unavailable -> 0
    }

    private fun readModelGate(): ModelGateState = runCatching {
        listOf(PpgType.GREEN, PpgType.IR).map { channel ->
            channel to inference.modelContractStatus(channel)
        }
    }.fold(
        onSuccess = { statuses ->
            if (statuses.any { it.second is ModelContractStatus.Ready }) {
                ModelGateState(ModelGateStatus.READY)
            } else {
                unavailableModelGate(statuses)
            }
        },
        onFailure = { error ->
            ModelGateState(
                status = ModelGateStatus.UNAVAILABLE,
                reason = error.message?.takeIf(String::isNotBlank) ?: "model_contract_unavailable"
            )
        }
    )

    private fun unavailableModelGate(statuses: List<Pair<PpgType, ModelContractStatus>>): ModelGateState = ModelGateState(
        status = ModelGateStatus.UNAVAILABLE,
        reason = statuses
            .mapNotNull { (channel, status) ->
                (status as? ModelContractStatus.Unavailable)?.let {
                    "${channel.assetSuffix}:${it.reason}"
                }
            }
            .joinToString("; ")
            .ifBlank { "model_contract_unavailable" }
    )

    override fun onCleared() {
        finalizationJob?.cancel()
        sessionClockJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TAG = "HomePpgProcessing"
        private const val MAX_FAILURE_REASON_LOG_LENGTH = 160
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
                    dao = StressDatabase.get(application).stressSessionDao(),
                    aiReminderCoordinator = AppLocator.aiReminderCoordinator(application)
                ) as T
            }
        }
    }
}

private fun PreparedPpgWindow?.processingLogFields(): String {
    val window = this ?: return "clean=0 observed=0 interpolated=0 repairedShortGap=0 trueGaps=0 unresolved=0 gridResampled=0"
    return "clean=${window.size} observed=${window.observedSampleCount} " +
        "interpolated=${window.interpolatedSampleCount} " +
        "repairedShortGap=${window.repairedShortGapSampleCount} " +
        "trueGaps=${window.trueGapCount} unresolved=${window.unresolvedSampleCount} " +
        "gridResampled=${window.gridResampledSampleCount} " +
        "motion=${window.motionAnnotation.motionAnnotation} " +
        "motionMethod=${window.motionAnnotation.motionMethod} " +
        "motionSamples=${window.motionAnnotation.sampleCount}"
}

private fun StressPrediction?.processingLogFields(): String {
    val prediction = this ?: return "model=none rawProbability=none calibratedProbability=none"
    return "model=${prediction.modelVersion ?: "none"} " +
        "rawProbability=${prediction.rawStressProbability ?: "none"} " +
        "calibratedProbability=${prediction.stressProbability}"
}

private fun StressInferenceTrace?.processingLogFields(): String {
    val trace = this ?: return "peakCount=0 validIbi=0 adjacentIbi=0"
    return "peakCount=${trace.peakCount} validIbi=${trace.validIbiCount} " +
        "adjacentIbi=${trace.adjacentIbiCount}"
}

internal fun PpgStreamState.isHomeStressSession(): Boolean = sessionMode == WearSessionMode.HOME_STRESS

private fun PredictionAvailability.predictionWindowStats(): PredictionWindowStats = when (this) {
    is PredictionAvailability.WarmingUp -> windowStats
    is PredictionAvailability.Ready -> windowStats
    is PredictionAvailability.Rejected -> windowStats
    is PredictionAvailability.Unavailable -> windowStats
    is PredictionAvailability.InferenceError -> windowStats
}
