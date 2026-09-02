package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.consent.ConsentRecord
import com.stressdetector.mobile.consent.ConsentScope
import com.stressdetector.mobile.consent.InMemoryConsentRepository
import com.stressdetector.mobile.data.repository.UnifiedSessionRepository
import com.stressdetector.shared.WearSessionMode
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class UnifiedSessionEngineTest {
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

    private class FailingStopAudioEngine(private val sessionId: String) : AudioIntelligenceEngine {
        override val captureState: StateFlow<AudioCaptureState> = MutableStateFlow(AudioCaptureState.Idle)
        override val speechRuntimeState: StateFlow<SpeechRuntimeState> = MutableStateFlow(SpeechRuntimeState.Ready)
        override val transcriptFlow: Flow<SpeechSegment> = emptyFlow()
        var stopCalls = 0

        override suspend fun startRecording(sessionId: String, userId: String, languageCode: String) =
            AudioOperationResult.Success(AudioCaptureStart(0L, 0L))

        override suspend fun pauseRecording() = AudioOperationResult.Success(Unit)
        override suspend fun resumeRecording() = AudioOperationResult.Success(Unit)

        override suspend fun stopRecording(): AudioOperationResult<RecordedAudio> = AudioOperationResult.Failure("audio drain failed")

        override suspend fun stopRecordingWithTranscript(): AudioOperationResult<AudioRecordingStopResult> {
            stopCalls++
            return AudioOperationResult.Failure("audio drain failed")
        }
    }

    private class FakeLiveBiosignalEngine(sessionId: String) : LiveBiosignalEngine {
        override val liveState: StateFlow<LiveBiosignalSnapshot> = MutableStateFlow(
            LiveBiosignalSnapshot(sessionId = sessionId)
        )
        override val divergenceFlow: Flow<com.stressdetector.mobile.data.db.DivergenceEventEntity> = emptyFlow()
        override val summaryFlow: Flow<LiveMonitorStopSummary> = emptyFlow()
        override fun startMonitoring(sessionId: String) = LiveMonitorStartResult.Started
        override fun stopMonitoring() = Unit
    }

    private class FakeWatchSessionCoordinator : WatchSessionCoordinator {
        override val state: StateFlow<WatchSessionState> = MutableStateFlow(WatchSessionState.Ready)
        var stopCalls = 0
        override suspend fun start(sessionId: String, mode: WearSessionMode) = Result.success(Unit)
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
