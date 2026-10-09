package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.consent.ConsentRecord
import com.stressdetector.mobile.consent.ConsentScope
import com.stressdetector.mobile.consent.InMemoryConsentRepository
import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.data.repository.UnifiedSessionRepository
import com.stressdetector.shared.WearSessionMode
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class UnifiedSessionEngineTest {
    @Test
    fun `first capture event is delivered and late partial cannot replace finalized live evidence`() = runBlocking {
        val sessionId = "live-first"
        val final = liveSegment(sessionId)
        val audio = FailingStopAudioEngine(sessionId, initialSegment = final)
        val engine = startLiveEngine(audio, FakeLiveBiosignalEngine(sessionId), testTimeline(sessionId))
        try {
            withTimeout(5_000L) { engine.liveSessionState.first { it.transcript.segments.size == 1 } }
            engine.updateSpeakerLabel(final.id, "Patient")
            engine.updateTranscriptText(final.id, "sepertinya besok")
            audio.events.emit(final.copy(rawText = "mungkin", isFinal = false))
            audio.events.emit(final.copy(id = "next", startOffsetMs = 2_000L, endOffsetMs = 3_000L))
            val live = withTimeout(5_000L) { engine.liveSessionState.first { it.transcript.segments.size == 2 } }
            val retained = live.transcript.segments.first { it.id == final.id }
            assertTrue(retained.isFinal)
            assertEquals("Patient", retained.speakerId)
            assertEquals("mungkin besok", retained.rawText)
            assertEquals("sepertinya besok", retained.displayText)
            assertEquals(1, VerbatimCueAnalyzer().analyze(retained).cues.size)
            assertEquals(live.transcript.segments, engine.liveTranscriptState.value.segments)
        } finally {
            engine.stopSession()
        }
    }

    @Test
    fun `concurrent live publications preserve transcript and deduplicated ordered independent channel markers`() = runBlocking {
        val sessionId = "live-publish"
        val audio = FailingStopAudioEngine(sessionId)
        val biosignal = FakeLiveBiosignalEngine(sessionId)
        val engine = startLiveEngine(audio, biosignal, testTimeline(sessionId), monitoringEnabled = true)
        val green = liveMarker(sessionId, "green", 1_000L, "GREEN")
        val ir = liveMarker(sessionId, "ir", 3_000L, "IR")
        try {
            coroutineScope {
                launch { audio.events.emit(liveSegment(sessionId)) }
                launch {
                    audio.acousticEvents.emit(liveAcoustic(sessionId, 1_500L, 2_000L))
                    audio.acousticEvents.emit(liveAcoustic(sessionId, 1_500L, 2_500L))
                }
                launch {
                    biosignal.liveState.value = biosignal.liveState.value.copy(
                        divergenceEvents = listOf(ir, green, green, green.copy(eventId = "old", sessionId = "old-session"))
                    )
                }
            }
            val live = withTimeout(5_000L) {
                engine.liveSessionState.first {
                    it.transcript.segments.size == 1 &&
                        it.biosignal.divergenceEvents.size == 3 &&
                        it.acousticAnalysis?.endOffsetMs == 2_500L
                }
            }
            assertEquals(listOf("green", "audio-episode", "ir"), live.biosignal.divergenceEvents.map { it.eventId })
            assertEquals(listOf("GREEN", "AUDIO", "IR"), live.biosignal.divergenceEvents.map { it.channel })
            assertTrue(live.biosignal.divergenceEvents.all { it.sessionId == sessionId })
        } finally {
            engine.stopSession()
        }
    }

    @Test
    fun `audio frames after stop cutoff do not enter live marker or analysis state`() = runBlocking {
        val sessionId = "live-cutoff"
        val now = AtomicLong(0L)
        val timeline = SessionTimeline.start(sessionId, utcNowMillis = { 0L }, monotonicNowMillis = now::get)
        val audio = FailingStopAudioEngine(sessionId)
        val engine = startLiveEngine(audio, FakeLiveBiosignalEngine(sessionId), timeline)
        audio.onStop = {
            audio.acousticEvents.emit(liveAcoustic(sessionId, 6_000L, 7_000L).copy(markerEpisodeId = "too-late"))
            audio.acousticEvents.emit(liveAcoustic(sessionId, 3_000L, 4_000L))
            withTimeout(5_000L) { engine.liveSessionState.first { it.acousticAnalysis?.endOffsetMs == 4_000L } }
        }
        now.set(5_000L)
        engine.stopSession()
        val live = engine.liveSessionState.value
        assertEquals(4_000L, live.acousticAnalysis?.endOffsetMs)
        assertEquals(listOf("audio-episode"), live.biosignal.divergenceEvents.map { it.eventId })
        assertTrue(live.biosignal.divergenceEvents.all { it.contextWindowEndElapsedMillis <= 5_000L })
    }

    @Test
    fun `audio only session requires no monitoring consent and never starts Watch or ONNX monitor`() = runBlocking {
        val consent = InMemoryConsentRepository()
        val sessionId = "audio-only"
        val userId = "local-user"
        val audio = FailingStopAudioEngine(sessionId)
        val watch = FakeWatchSessionCoordinator()
        val biosignal = FakeLiveBiosignalEngine(sessionId)
        val engine = UnifiedSessionEngine(
            mock(UnifiedSessionRepository::class.java),
            audio,
            biosignal,
            watch,
            consent,
            mock(VerbatimSessionFinalizer::class.java),
            EmptyAnnotationRepository,
            timelineFactory = { id -> SessionTimeline.start(id, utcNowMillis = { 0L }, monotonicNowMillis = { 0L }) }
        )
        val recording = consent.grant(consentRecord(userId, sessionId, ConsentScope.VERBATIM_RECORDING))
        engine.startSession(sessionId, userId, recording, monitoringEnabled = false)
        assertTrue(engine.isSessionActive.value)
        assertTrue(engine.monitorStatus.value.startsWith("NOT_REQUESTED"))
        assertEquals(0, watch.startCalls)
        assertEquals(0, biosignal.startCalls)
        engine.stopSession()
        assertEquals(0, watch.stopCalls)
    }

    @Test
    fun `double stop after audio failure is idempotent and retains failure evidence`() = runBlocking {
        val consent = InMemoryConsentRepository()
        val sessionId = "session-failure"
        val userId = "local-user"
        val audio = FailingStopAudioEngine(sessionId)
        val watch = FakeWatchSessionCoordinator()
        val engine = UnifiedSessionEngine(
            repository = mock(UnifiedSessionRepository::class.java),
            audioEngine = audio,
            biosignalEngine = FakeLiveBiosignalEngine(sessionId),
            watchSessionCoordinator = watch,
            consentRepository = consent,
            finalizer = mock(VerbatimSessionFinalizer::class.java),
            annotationRepository = EmptyAnnotationRepository,
            timelineFactory = { id -> SessionTimeline.start(id, utcNowMillis = { 0L }, monotonicNowMillis = { 0L }) }
        )
        val recordingConsent = consent.grant(consentRecord(userId, sessionId, ConsentScope.VERBATIM_RECORDING))
        val monitoringConsent = consent.grant(consentRecord(userId, sessionId, ConsentScope.LIVE_MONITORING))
        engine.startSession(sessionId, userId, recordingConsent, monitoringConsent)

        engine.stopSession()
        engine.stopSession()

        assertEquals(1, audio.stopCalls)
        assertEquals(1, watch.stopCalls)
        assertEquals(SessionFinalizationStage.FAILED_WITH_EVIDENCE, engine.finalizationStage.value)
        val failed = engine.finalizationState.value as SessionFinalizationState.Failed
        assertTrue(failed.evidencePersisted)
        assertTrue(failed.message.contains("audio could not be drained"))
    }

    @Test
    fun `persisted audio path uses finalized artifact audio path`() {
        val recorded = RecordedAudio(
            sessionId = "session-1",
            encryptedRelativePath = "verbatim/session-1/audio.wav.enc",
            startedAtUtcMillis = 1L,
            endedAtUtcMillis = 2L,
            durationMs = 1L,
            sampleRateHz = 16_000
        )
        val artifacts = VerbatimSessionArtifacts(
            folderPath = "verbatim_sessions/Session_01-01-2025_00-00-00",
            audioPath = "verbatim_sessions/Session_01-01-2025_00-00-00/audio.wav.enc",
            transcriptPath = "verbatim_sessions/Session_01-01-2025_00-00-00/live_transcript.jsonl.enc",
            divergencePath = "verbatim_sessions/Session_01-01-2025_00-00-00/divergence_events.jsonl.enc",
            summaryPath = "verbatim_sessions/Session_01-01-2025_00-00-00/ai_summary.txt.enc",
            summaryStatus = "GENERATED"
        )

        assertEquals(artifacts.audioPath, UnifiedSessionEngine.persistedAudioPath(recorded, artifacts))
    }

    @Test
    fun `persisted audio path falls back to staging path when finalization fails`() {
        val recorded = RecordedAudio(
            sessionId = "session-1",
            encryptedRelativePath = "verbatim/session-1/audio.wav.enc",
            startedAtUtcMillis = 1L,
            endedAtUtcMillis = 2L,
            durationMs = 1L,
            sampleRateHz = 16_000
        )

        assertEquals(recorded.encryptedRelativePath, UnifiedSessionEngine.persistedAudioPath(recorded, null))
    }

    private fun consentRecord(userId: String, sessionId: String, scope: ConsentScope) = ConsentRecord(
        consentId = "$sessionId-${scope.name}",
        userId = userId,
        sessionId = sessionId,
        scope = scope,
        actor = "test",
        timestampUtc = Instant.EPOCH,
        appVersion = "test",
        policyVersion = "test",
        isMultiParty = false
    )

    private suspend fun startLiveEngine(
        audio: FailingStopAudioEngine,
        biosignal: FakeLiveBiosignalEngine,
        timeline: SessionTimeline,
        monitoringEnabled: Boolean = false
    ): UnifiedSessionEngine {
        val consent = InMemoryConsentRepository()
        val sessionId = timeline.sessionId
        val userId = "local-user"
        val engine = UnifiedSessionEngine(
            mock(UnifiedSessionRepository::class.java),
            audio,
            biosignal,
            FakeWatchSessionCoordinator(),
            consent,
            mock(VerbatimSessionFinalizer::class.java),
            EmptyAnnotationRepository,
            timelineFactory = { timeline }
        )
        val recording = consent.grant(consentRecord(userId, sessionId, ConsentScope.VERBATIM_RECORDING))
        val monitoring = if (monitoringEnabled) consent.grant(consentRecord(userId, sessionId, ConsentScope.LIVE_MONITORING)) else null
        engine.startSession(sessionId, userId, recording, monitoring, monitoringEnabled = monitoringEnabled)
        return engine
    }

    private fun testTimeline(sessionId: String) = SessionTimeline.start(sessionId, utcNowMillis = { 0L }, monotonicNowMillis = { 0L })

    private fun liveSegment(sessionId: String) = SpeechSegment(
        id = "utterance", speakerId = "Speaker_0", startOffsetMs = 0L, endOffsetMs = 1_000L,
        startTimestampUtc = 0L, endTimestampUtc = 1_000L, rawText = "mungkin besok", confidence = 0.9f,
        language = "id", sessionId = sessionId, isFinal = true
    )

    private fun liveMarker(sessionId: String, id: String, offset: Long, channel: String) = DivergenceEventEntity(
        eventId = id, sessionId = sessionId, timestampMs = offset, delta = null, currentProbability = null,
        baselineProbability = null, copyText = "Unit marker fixture", channel = channel,
        markerElapsedMillis = offset, episodeStartElapsedMillis = offset, episodeEndElapsedMillis = offset,
        contextWindowEndElapsedMillis = offset
    )

    private fun liveAcoustic(sessionId: String, start: Long, end: Long) = AcousticAnalysisSnapshot(
        sessionId = sessionId, startOffsetMs = start, endOffsetMs = end, rmsDb = -12f, baselineRmsDb = -22f,
        zeroCrossingRate = 0.1f, clippingRatio = 0f, finiteSampleRatio = 1f, qualityScore = 0.9f,
        baselineDeviationDb = 10f, markerType = SessionMarkerType.ACOUSTIC_DIVERGENCE,
        markerTransition = SessionMarkerType.ACOUSTIC_DIVERGENCE, markerEpisodeId = "audio-episode",
        markerEpisodeStartOffsetMs = start, evidenceConfidence = 0.9f
    )

    private class FailingStopAudioEngine(private val sessionId: String, private val initialSegment: SpeechSegment? = null) :
        AudioIntelligenceEngine {
        override val captureState: StateFlow<AudioCaptureState> = MutableStateFlow(AudioCaptureState.Idle)
        override val speechRuntimeState: StateFlow<SpeechRuntimeState> = MutableStateFlow(SpeechRuntimeState.Ready)
        val events = MutableSharedFlow<SpeechSegment>()
        override val transcriptFlow: Flow<SpeechSegment> = events
        val acousticEvents = MutableSharedFlow<AcousticAnalysisSnapshot>()
        override val acousticAnalysisFlow: Flow<AcousticAnalysisSnapshot> = acousticEvents
        var onStop: suspend () -> Unit = {}
        var stopCalls = 0

        override suspend fun startRecording(
            sessionId: String,
            userId: String,
            languageCode: String
        ): AudioOperationResult<AudioCaptureStart> {
            initialSegment?.let {
                check(events.subscriptionCount.value == 1) { "Capture started before the live transcript subscriber." }
                events.emit(it)
            }
            return AudioOperationResult.Success(AudioCaptureStart(0L, 0L))
        }

        override suspend fun pauseRecording() = AudioOperationResult.Success(Unit)
        override suspend fun resumeRecording() = AudioOperationResult.Success(Unit)

        override suspend fun stopRecording(): AudioOperationResult<RecordedAudio> = AudioOperationResult.Failure("audio drain failed")

        override suspend fun stopRecordingWithTranscript(): AudioOperationResult<AudioRecordingStopResult> {
            stopCalls++
            onStop()
            return AudioOperationResult.Failure("audio drain failed")
        }
    }

    private class FakeLiveBiosignalEngine(sessionId: String) : LiveBiosignalEngine {
        var startCalls = 0
        override val liveState: MutableStateFlow<LiveBiosignalSnapshot> = MutableStateFlow(
            LiveBiosignalSnapshot(sessionId = sessionId)
        )
        override val divergenceFlow: Flow<com.stressdetector.mobile.data.db.DivergenceEventEntity> = emptyFlow()
        override val summaryFlow: Flow<LiveMonitorStopSummary> = emptyFlow()
        override fun bindSessionTimeline(timeline: SessionTimeline) {
            liveState.value = liveState.value.copy(sessionId = timeline.sessionId, sessionGeneration = timeline.generation)
        }
        override fun startMonitoring(sessionId: String): LiveMonitorStartResult {
            startCalls++
            return LiveMonitorStartResult.Started
        }
        override fun stopMonitoring() = Unit
    }

    private class FakeWatchSessionCoordinator : WatchSessionCoordinator {
        override val state: StateFlow<WatchSessionState> = MutableStateFlow(WatchSessionState.Ready)
        var stopCalls = 0
        var startCalls = 0
        override suspend fun start(sessionId: String, mode: WearSessionMode): Result<Unit> {
            startCalls++
            return Result.success(Unit)
        }
        override suspend fun stop(sessionId: String, completionExpected: Boolean): Result<Unit> {
            stopCalls++
            return Result.success(Unit)
        }
        override suspend fun awaitDrain(sessionId: String, timeoutMillis: Long) = Result.success(Unit)
    }

    private data object EmptyAnnotationRepository : AnnotationRepository {
        override suspend fun getAnnotationsForSession(sessionId: String) = emptyList<DomainAnnotation>()
        override suspend fun addAnnotation(annotation: DomainAnnotation) = Unit
        override suspend fun deleteAnnotation(annotationId: String) = Unit
        override suspend fun deleteAnnotationsForSession(sessionId: String) = Unit
    }
}
