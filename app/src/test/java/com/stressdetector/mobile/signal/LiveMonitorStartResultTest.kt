package com.stressdetector.mobile.signal

import android.content.Context
import com.stressdetector.mobile.data.IPpgRepository
import com.stressdetector.mobile.data.PpgChartFrame
import com.stressdetector.mobile.data.PpgStreamState
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.data.db.RealtimeInferenceRecordEntity
import com.stressdetector.mobile.data.repository.LiveMonitorRecordStore
import com.stressdetector.mobile.domain.engine.DivergenceEvent
import com.stressdetector.mobile.domain.engine.LiveChannelState
import com.stressdetector.mobile.domain.engine.LiveMonitorStartResult
import com.stressdetector.mobile.domain.engine.LiveMonitorStartResultProvider
import com.stressdetector.mobile.domain.engine.LiveMonitorState
import com.stressdetector.mobile.domain.engine.LiveMonitorStopSummary
import com.stressdetector.mobile.domain.engine.RealtimeInferenceEngine
import com.stressdetector.mobile.domain.engine.SessionTimeline
import com.stressdetector.shared.PpgType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock

class LiveMonitorStartResultTest {

    @Test
    fun emptyLiveMonitorStateIsFailClosedBeforeAnyPpgWindow() {
        val state = LiveMonitorState()

        assertEquals(0f, state.currentQualityScore, 0f)
        assertFalse(state.isWindowValid)
        assertEquals(null, state.latestPrediction)
    }

    @Test
    fun blockedModelIndexIsReportedAsUnavailableWithoutThrowing() {
        val engine = RealtimeInferenceEngineImpl(
            context = mock(Context::class.java),
            contractProvider = { error("Canonical model index has no accepted release") }
        )

        val result = engine.startMonitoringResult("session-1")

        assertTrue(result is LiveMonitorStartResult.Unavailable)
        assertTrue((result as LiveMonitorStartResult.Unavailable).reason.contains("accepted release"))
        assertFalse(engine.startMonitoring("session-1"))
        assertEquals(null, engine.stopMonitoring())
    }

    @Test
    fun startedResultIsPropagatedAndStopIsIdempotent() {
        val realtime = FakeRealtimeInferenceEngine(LiveMonitorStartResult.Started)
        val ppgRepository = testPpgRepository()
        val engine = RealLiveBiosignalEngine(
            realtimeInferenceEngine = realtime,
            ppgRepository = ppgRepository,
            recordStore = mock(LiveMonitorRecordStore::class.java),
            config = testLiveMonitorConfig()
        )
        engine.bindSessionTimeline(testTimeline())

        assertSame(LiveMonitorStartResult.Started, engine.startMonitoring("session-1"))

        engine.stopMonitoring()
        engine.stopMonitoring()

        runBlocking {
            withTimeout(1_000L) {
                while (realtime.stopCalls == 0) delay(5L)
            }
        }
        assertEquals(1, realtime.stopCalls)
    }

    @Test
    fun unavailableResultDoesNotStartOrStopTheInferenceLifecycle() {
        val realtime = FakeRealtimeInferenceEngine(
            LiveMonitorStartResult.Unavailable("model_index.json is unavailable")
        )
        val engine = RealLiveBiosignalEngine(
            realtimeInferenceEngine = realtime,
            ppgRepository = testPpgRepository(),
            recordStore = mock(LiveMonitorRecordStore::class.java),
            config = testLiveMonitorConfig()
        )
        engine.bindSessionTimeline(testTimeline())

        val result = engine.startMonitoring("session-1")
        engine.stopMonitoring()

        assertTrue(result is LiveMonitorStartResult.Unavailable)
        assertEquals(0, realtime.stopCalls)
    }

    @Test
    fun partialPpgIsProcessedForLiveBpmBeforeCanonicalModelWindowIsFilled() = runBlocking {
        val realtime = FakeRealtimeInferenceEngine(LiveMonitorStartResult.Started)
        val engine = RealLiveBiosignalEngine(
            realtimeInferenceEngine = realtime,
            ppgRepository = testPpgRepository(),
            recordStore = mock(LiveMonitorRecordStore::class.java),
            config = testLiveMonitorConfig().copy(inferenceStepSeconds = 1)
        )
        engine.bindSessionTimeline(testTimeline())

        assertSame(LiveMonitorStartResult.Started, engine.startMonitoring("session-1"))

        withTimeout(2_500L) {
            while (realtime.processCalls == 0) delay(10L)
        }

        engine.stopMonitoringAndDrain()
        assertTrue(realtime.processCalls > 0)
    }

    @Test
    fun lateVerbatimInferenceAndMarkerArePersistedAfterFiveMinutes() = runBlocking {
        val realtime = FakeRealtimeInferenceEngine(LiveMonitorStartResult.Started).apply {
            stateAfterProcess = LiveMonitorState(
                channelStates = mapOf(
                    PpgType.GREEN to LiveChannelState(
                        channel = PpgType.GREEN,
                        qualityScore = 0.9f,
                        lastWindowEndElapsedMillis = 301_000L
                    )
                ),
                activeDivergences = listOf(
                    DivergenceEvent(
                        id = "marker-after-five-minutes",
                        sessionId = "session-1",
                        timestampMs = 600_000L,
                        currentStressProbability = 0.8f,
                        baselineStressProbability = 0.3f,
                        divergenceDelta = 0.5f,
                        isElevatedArousal = true,
                        markerElapsedMillis = 600_000L
                    )
                )
            )
        }
        val recordStore = RecordingLiveMonitorRecordStore()
        val engine = RealLiveBiosignalEngine(
            realtimeInferenceEngine = realtime,
            ppgRepository = testPpgRepository(),
            recordStore = recordStore,
            config = testLiveMonitorConfig()
        )
        engine.bindSessionTimeline(testTimeline())

        assertSame(LiveMonitorStartResult.Started, engine.startMonitoring("session-1"))
        engine.stopMonitoringAndDrain()

        assertEquals(listOf(301_000L), recordStore.inferences.map(RealtimeInferenceRecordEntity::timestampMs))
        assertEquals(
            listOf(600_000L),
            recordStore.divergences.map(DivergenceEventEntity::markerElapsedMillis)
        )
    }
}

private fun testPpgRepository(): IPpgRepository = mock(IPpgRepository::class.java).also { repository ->
    Mockito.`when`(repository.state).thenReturn(
        MutableStateFlow(
            PpgStreamState(
                sessionId = "session-1",
                sessionReady = true,
                sessionReadyGreen = true,
                sessionReadyIr = true
            )
        )
    )
    Mockito.`when`(repository.chartFrames).thenReturn(MutableStateFlow(PpgChartFrame()))
    Mockito.`when`(repository.greenWindow()).thenReturn(WindowSnapshot(PpgType.GREEN, longArrayOf(), intArrayOf()))
    Mockito.`when`(repository.irWindow()).thenReturn(WindowSnapshot(PpgType.IR, longArrayOf(), intArrayOf()))
}

private fun testTimeline(): SessionTimeline = SessionTimeline.start(
    sessionId = "session-1",
    utcNowMillis = { 0L },
    monotonicNowMillis = { 0L }
)

private class FakeRealtimeInferenceEngine(private val startResult: LiveMonitorStartResult) :
    RealtimeInferenceEngine,
    LiveMonitorStartResultProvider {
    private val mutableState = MutableStateFlow(LiveMonitorState())
    override val monitorState: StateFlow<LiveMonitorState> = mutableState
    var stopCalls: Int = 0
        private set

    @Volatile
    var processCalls: Int = 0
        private set

    var stateAfterProcess: LiveMonitorState? = null

    override fun startMonitoring(sessionId: String): Boolean = startResult is LiveMonitorStartResult.Started

    override fun startMonitoringResult(sessionId: String): LiveMonitorStartResult = startResult

    override fun processPpgWindows(windows: Map<PpgType, WindowSnapshot>) {
        processCalls++
        stateAfterProcess?.let { mutableState.value = it }
    }

    override fun stopMonitoring(): LiveMonitorStopSummary? {
        stopCalls++
        return null
    }
}

private class RecordingLiveMonitorRecordStore : LiveMonitorRecordStore {
    val inferences = mutableListOf<RealtimeInferenceRecordEntity>()
    val divergences = mutableListOf<DivergenceEventEntity>()

    override suspend fun recordInference(record: RealtimeInferenceRecordEntity) {
        inferences += record
    }

    override suspend fun recordDivergence(event: DivergenceEventEntity) {
        divergences += event
    }

    override suspend fun completeSession(summary: LiveMonitorStopSummary) = Unit
}
