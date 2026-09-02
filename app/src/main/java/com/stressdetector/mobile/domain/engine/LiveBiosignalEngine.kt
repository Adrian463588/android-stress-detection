package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.PpgChartFrame
import com.stressdetector.mobile.data.PpgStreamState
import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.shared.PpgType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Throttled, immutable projection used by Verbatim+ while a session is live.
 * Raw PPG remains owned by [IPpgRepository]; this snapshot only crosses the
 * engine/UI boundary and never replaces persisted samples.
 */
data class LiveBiosignalSnapshot(
    val sessionId: String? = null,
    val sessionGeneration: Long = 0L,
    val monitor: LiveMonitorState = LiveMonitorState(),
    val ppgStream: PpgStreamState = PpgStreamState(),
    val chart: PpgChartFrame? = null,
    val latestBpm: Float? = null,
    val latestBpmByChannel: Map<PpgType, Float> = emptyMap(),
    val divergenceEvents: List<DivergenceEventEntity> = emptyList(),
    val backlogFrames: Int = 0,
    val audioGaps: List<AudioGap> = emptyList()
)

private val EMPTY_LIVE_BIOSIGNAL_STATE = MutableStateFlow(LiveBiosignalSnapshot()).asStateFlow()

/** Outcome of attempting to make the Live Monitor runtime available. */
sealed interface LiveMonitorStartResult {
    /** The accepted PPG model contract was loaded and monitoring is running. */
    data object Started : LiveMonitorStartResult

    /** Monitoring cannot be offered because its model/runtime contract is unavailable. */
    data class Unavailable(val reason: String, val cause: Throwable? = null) : LiveMonitorStartResult

    /** Monitoring failed for a runtime error other than an unavailable model contract. */
    data class Failed(val reason: String, val cause: Throwable? = null) : LiveMonitorStartResult
}

/** Optional typed start boundary for implementations of the lower-level inference engine. */
interface LiveMonitorStartResultProvider {
    fun startMonitoringResult(sessionId: String): LiveMonitorStartResult
}

interface LiveBiosignalEngine {
    /** Binds acquisition elapsed time to the owning phone session before monitoring starts. */
    fun bindSessionTimeline(timeline: SessionTimeline) = Unit
    fun startMonitoring(sessionId: String): LiveMonitorStartResult
    fun stopMonitoring()
    suspend fun stopMonitoringAndDrain() = stopMonitoring()

    /** Single UI projection for live PPG, inference, chart, and diagnostics. */
    val liveState: StateFlow<LiveBiosignalSnapshot>
        get() = EMPTY_LIVE_BIOSIGNAL_STATE
    val divergenceFlow: Flow<DivergenceEventEntity>
    val summaryFlow: Flow<LiveMonitorStopSummary>
}
