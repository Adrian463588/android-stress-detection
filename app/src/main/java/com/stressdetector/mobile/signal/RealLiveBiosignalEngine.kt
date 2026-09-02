package com.stressdetector.mobile.signal

import com.stressdetector.mobile.data.IPpgRepository
import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.data.db.RealtimeInferenceRecordEntity
import com.stressdetector.mobile.data.repository.LiveMonitorRecordStore
import com.stressdetector.mobile.domain.engine.DivergenceEvent
import com.stressdetector.mobile.domain.engine.LiveBiosignalEngine
import com.stressdetector.mobile.domain.engine.LiveBiosignalSnapshot
import com.stressdetector.mobile.domain.engine.LiveMonitorStartResult
import com.stressdetector.mobile.domain.engine.LiveMonitorStartResultProvider
import com.stressdetector.mobile.domain.engine.LiveMonitorStopSummary
import com.stressdetector.mobile.domain.engine.PhysiologicalMarkerType
import com.stressdetector.mobile.domain.engine.RealtimeInferenceEngine
import com.stressdetector.mobile.domain.engine.SessionMarkerType
import com.stressdetector.mobile.domain.engine.SessionTimeline
import com.stressdetector.shared.PpgType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class RealLiveBiosignalEngine(
    private val realtimeInferenceEngine: RealtimeInferenceEngine,
    private val ppgRepository: IPpgRepository,
    private val recordStore: LiveMonitorRecordStore,
    private val config: LiveMonitorConfig
) : LiveBiosignalEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitoringJob: Job? = null
    private var snapshotJob: Job? = null
    private val mutableDivergences = MutableSharedFlow<DivergenceEventEntity>()
    private val mutableSummaries = MutableSharedFlow<LiveMonitorStopSummary>(replay = 1)
    private val mutableLiveState = MutableStateFlow(LiveBiosignalSnapshot())
    private val persistedDivergences = mutableMapOf<String, DivergenceEventEntity>()
    private val persistedInferenceTimestamps = mutableMapOf<PpgType, Long>()
    private var activeSessionId: String? = null
    private var activeTimeline: SessionTimeline? = null
    private val lifecycleLock = Any()
    private var stopCompletion: CompletableDeferred<Unit>? = null
    override val liveState: StateFlow<LiveBiosignalSnapshot> = mutableLiveState.asStateFlow()
    override val divergenceFlow: Flow<DivergenceEventEntity> = mutableDivergences.asSharedFlow()
    override val summaryFlow: Flow<LiveMonitorStopSummary> = mutableSummaries.asSharedFlow()

    override fun bindSessionTimeline(timeline: SessionTimeline) {
        synchronized(lifecycleLock) {
            check(activeSessionId == null || activeSessionId == timeline.sessionId) {
                "Live Monitor timeline belongs to another session"
            }
            activeTimeline = timeline
        }
    }

    override fun startMonitoring(sessionId: String): LiveMonitorStartResult {
        if (stopCompletion != null) {
            return LiveMonitorStartResult.Failed("Previous Live Monitor session is still draining")
        }
        if (monitoringJob?.isActive == true) {
            return if (activeSessionId == sessionId) {
                LiveMonitorStartResult.Started
            } else {
                LiveMonitorStartResult.Failed(
                    "Live Monitor masih digunakan oleh sesi lain."
                )
            }
        }
        if (monitoringJob != null) {
            return LiveMonitorStartResult.Failed("Previous Live Monitor lifecycle has not been stopped")
        }

        val timeline = synchronized(lifecycleLock) { activeTimeline }
            ?.takeIf { it.sessionId == sessionId }
            ?: return LiveMonitorStartResult.Failed("Session timeline is not bound")
        val initialStream = ppgRepository.state.value
        if (initialStream.protocolRejected) {
            return LiveMonitorStartResult.Unavailable(
                initialStream.lastError ?: "Watch session was rejected by the protocol"
            )
        }
        if (initialStream.sessionId != null && initialStream.sessionId != sessionId) {
            return LiveMonitorStartResult.Unavailable(
                "Watch session does not match active phone session"
            )
        }
        val readyReceiptMillis = initialStream.sessionReadyReceivedAtMonotonicMillis
            .takeIf { it > 0L }
        if (readyReceiptMillis != null) {
            timeline.bindPpgClock(
                ppgElapsedMillis = initialStream.sessionReadyElapsedMillis,
                receivedAtMonotonicMillis = readyReceiptMillis
            )
        }

        val startResult = runCatching {
            val typedEngine = realtimeInferenceEngine as? LiveMonitorStartResultProvider
            typedEngine?.startMonitoringResult(sessionId)
                ?: if (realtimeInferenceEngine.startMonitoring(sessionId)) {
                    LiveMonitorStartResult.Started
                } else {
                    LiveMonitorStartResult.Failed("Realtime inference engine rejected the start request")
                }
        }.getOrElse { error ->
            LiveMonitorStartResult.Failed(
                reason = error.message ?: "Realtime inference engine failed to start",
                cause = error
            )
        }
        if (startResult !is LiveMonitorStartResult.Started) {
            synchronized(lifecycleLock) { activeTimeline = null }
            return startResult
        }
        activeSessionId = sessionId
        synchronized(lifecycleLock) {
            persistedInferenceTimestamps.clear()
            persistedDivergences.clear()
        }
        publishLiveSnapshot(sessionId)
        snapshotJob?.cancel()
        snapshotJob = scope.launch {
            combine(
                ppgRepository.state,
                ppgRepository.chartFrames,
                realtimeInferenceEngine.monitorState
            ) { stream, chart, monitor -> Triple(stream, chart, monitor) }
                .sample(LIVE_STATE_SAMPLE_MILLIS)
                .collect { (stream, chart, monitor) ->
                    publishLiveSnapshot(sessionId, stream, chart, monitor)
                }
        }
        monitoringJob = scope.launch {
            // Process immediately at the normal cadence: realtime BPM/arousal uses
            // short, quality-gated real windows, while the canonical ONNX path
            // reports WarmingUp until its full 180-second input is available.
            while (isActive) {
                delay(config.inferenceStepSeconds * 1000L)
                processAndPersist(sessionId)
            }
        }
        return LiveMonitorStartResult.Started
    }

    override fun stopMonitoring() {
        requestStop()?.takeIf(StopRequest::isOwner)?.let { request ->
            scope.launch { runStop(request) }
        }
    }

    override suspend fun stopMonitoringAndDrain() {
        val request = requestStop() ?: return
        if (request.isOwner) {
            runStop(request)
        } else {
            request.completion.await()
        }
    }

    private fun requestStop(): StopRequest? = synchronized(lifecycleLock) {
        stopCompletion?.let { return StopRequest(it, isOwner = false) }
        if (monitoringJob == null) return null
        val completion = CompletableDeferred<Unit>()
        stopCompletion = completion
        StopRequest(completion, isOwner = true)
    }

    private suspend fun runStop(request: StopRequest) {
        try {
            stopMonitoringAndDrainInternal()
            request.completion.complete(Unit)
        } catch (error: Throwable) {
            request.completion.completeExceptionally(error)
            throw error
        } finally {
            synchronized(lifecycleLock) {
                if (stopCompletion === request.completion) stopCompletion = null
            }
        }
    }

    private suspend fun stopMonitoringAndDrainInternal() {
        val job = synchronized(lifecycleLock) {
            monitoringJob.also { monitoringJob = null }
        } ?: return
        job.cancel()
        job.join()
        snapshotJob?.cancel()
        snapshotJob?.join()
        snapshotJob = null
        processAndPersist(currentSessionId())
        realtimeInferenceEngine.stopMonitoring()?.let { summary ->
            recordStore.completeSession(summary)
            mutableSummaries.emit(summary)
        }
        publishLiveSnapshot(currentSessionId())
        synchronized(lifecycleLock) {
            persistedDivergences.clear()
            persistedInferenceTimestamps.clear()
            activeTimeline = null
        }
        activeSessionId = null
    }

    private data class StopRequest(val completion: CompletableDeferred<Unit>, val isOwner: Boolean)

    private suspend fun processAndPersist(sessionId: String) {
        val stream = ppgRepository.state.value
        if (activeSessionId != sessionId ||
            stream.sessionId != sessionId ||
            stream.protocolRejected
        ) {
            return
        }
        val timeline = synchronized(lifecycleLock) { activeTimeline }
            ?.takeIf { it.sessionId == sessionId }
            ?: return
        val available = mapOf(
            PpgType.GREEN to ppgRepository.greenWindow(),
            PpgType.IR to ppgRepository.irWindow()
        )
        realtimeInferenceEngine.processPpgWindows(available)
        val state = realtimeInferenceEngine.monitorState.value
        state.channelStates.forEach { (channel, channelState) ->
            val sourceTimestamp = channelState.lastWindowEndElapsedMillis ?: return@forEach
            if (sourceTimestamp <= (persistedInferenceTimestamps[channel] ?: Long.MIN_VALUE)) return@forEach
            val timestamp = timeline.ppgElapsedToSessionElapsed(sourceTimestamp)
            val prediction = channelState.prediction
            recordStore.recordInference(
                RealtimeInferenceRecordEntity(
                    recordId = "${sessionId}_${channel.name}_$timestamp",
                    sessionId = sessionId,
                    timestampMs = timestamp,
                    stressProbability = prediction?.stressProbability,
                    label = prediction?.label ?: "REJECTED",
                    qualityScore = channelState.qualityScore,
                    isValidWindow = prediction != null,
                    channel = channel.name
                )
            )
            persistedInferenceTimestamps[channel] = sourceTimestamp
        }
        state.activeDivergences.forEach { divergence ->
            if (divergence.sessionId != sessionId) return@forEach
            val entity = divergence.toEntity(timeline)
            val isNew = synchronized(lifecycleLock) {
                if (persistedDivergences[entity.eventId] == entity) {
                    false
                } else {
                    persistedDivergences[entity.eventId] = entity
                    true
                }
            }
            if (isNew) {
                recordStore.recordDivergence(entity)
                mutableDivergences.emit(entity)
            }
        }
        publishLiveSnapshot(sessionId)
    }

    private fun publishLiveSnapshot(
        sessionId: String?,
        stream: com.stressdetector.mobile.data.PpgStreamState = ppgRepository.state.value,
        chart: com.stressdetector.mobile.data.PpgChartFrame = ppgRepository.chartFrames.value,
        monitor: com.stressdetector.mobile.domain.engine.LiveMonitorState = realtimeInferenceEngine.monitorState.value
    ) {
        val timeline = synchronized(lifecycleLock) { activeTimeline }
        val ownsSession = sessionId != null &&
            stream.sessionId == sessionId &&
            timeline?.sessionId == sessionId &&
            chart.sessionId == sessionId &&
            chart.generation == stream.generation
        val activeEntities = monitor.activeDivergences
            .asSequence()
            .filter { it.sessionId == sessionId }
            .map { it.toEntity(timeline) }
            .toList()
        mutableLiveState.value = LiveBiosignalSnapshot(
            sessionId = sessionId,
            sessionGeneration = timeline?.generation ?: 0L,
            monitor = monitor,
            ppgStream = if (ownsSession) stream else com.stressdetector.mobile.data.PpgStreamState(sessionId = sessionId),
            chart = chart.takeIf { ownsSession },
            latestBpm = monitor.latestBpm,
            latestBpmByChannel = monitor.latestBpmByChannel,
            divergenceEvents = synchronized(lifecycleLock) {
                (persistedDivergences.values + activeEntities)
                    .associateBy(DivergenceEventEntity::eventId)
                    .values
                    .sortedBy { it.timestampMs }
                    .toList()
            }
        )
    }

    private fun DivergenceEvent.toEntity(timeline: SessionTimeline?): DivergenceEventEntity = DivergenceEventEntity(
        eventId = id,
        sessionId = sessionId,
        timestampMs = timeline?.ppgElapsedToSessionElapsed(timestampMs) ?: timestampMs,
        delta = divergenceDelta,
        currentProbability = currentStressProbability,
        baselineProbability = baselineStressProbability,
        copyText = copyText,
        channel = channel.name,
        markerElapsedMillis = timeline?.ppgElapsedToSessionElapsed(markerElapsedMillis) ?: markerElapsedMillis,
        episodeStartElapsedMillis = timeline?.ppgElapsedToSessionElapsed(episodeStartElapsedMillis)
            ?: episodeStartElapsedMillis,
        episodeEndElapsedMillis = timeline?.ppgElapsedToSessionElapsed(episodeEndElapsedMillis)
            ?: episodeEndElapsedMillis,
        contextWindowStartElapsedMillis = timeline?.ppgElapsedToSessionElapsed(contextWindowStartElapsedMillis)
            ?: contextWindowStartElapsedMillis,
        contextWindowEndElapsedMillis = timeline?.ppgElapsedToSessionElapsed(contextWindowEndElapsedMillis)
            ?: contextWindowEndElapsedMillis,
        confidence = confidence,
        signalQuality = signalQuality,
        sourceWindowId = sourceWindowId,
        transcriptSegmentId = transcriptSegmentId,
        markerType = if (markerType == PhysiologicalMarkerType.MODEL_AROUSAL) {
            SessionMarkerType.STRESS_ELEVATED.name
        } else {
            markerType.name
        },
        bpm = bpm,
        baselineBpm = baselineBpm,
        peakBpm = peakBpm,
        bpmDelta = bpmDelta,
        bpmTrend = bpmTrend,
        sourceChannels = sourceChannels.sortedBy { it.ordinal }.joinToString(",") { it.name },
        markerConfigVersion = markerConfigVersion
    )

    private fun currentSessionId(): String = checkNotNull(activeSessionId) {
        "Live Monitor session is not available"
    }

    private companion object {
        const val LIVE_STATE_SAMPLE_MILLIS = 200L
    }
}

/** Direct PDSatu transport has no SESSION_READY gate; session identity is gate. */
internal fun com.stressdetector.mobile.data.PpgStreamState.readyForLiveSession(sessionId: String): Boolean =
    this.sessionId == sessionId && !protocolRejected
