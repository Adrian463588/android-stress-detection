package com.stressdetector.mobile.ui.home

import com.stressdetector.mobile.MobileUiState
import com.stressdetector.mobile.data.ReachabilityProbeStatus
import com.stressdetector.mobile.data.WatchConnectionStatus
import com.stressdetector.mobile.data.WearConnectionState
import com.stressdetector.mobile.data.WearReachabilityPhase
import com.stressdetector.mobile.domain.FinalizationChannelProgress
import com.stressdetector.mobile.domain.FinalizationStage
import com.stressdetector.mobile.domain.ModelGateState
import com.stressdetector.mobile.domain.ModelGateStatus
import com.stressdetector.mobile.domain.PredictionAvailability
import com.stressdetector.mobile.domain.StressSessionFinalizationState
import com.stressdetector.mobile.signal.MotionAnnotation
import com.stressdetector.shared.ModelDecisionPolicy
import com.stressdetector.shared.PermissionStatus
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SignalQualitySummary
import com.stressdetector.shared.StressPrediction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiStateTest {
    @Test
    fun motionDetailDistinguishesMissingWindowFromMissingAccelerometer() {
        assertEquals("Tidak ada window yang dievaluasi", motionEvidenceLabel(emptyList()))
        assertEquals("Akselerometer tidak tersedia untuk window yang dievaluasi", motionEvidenceLabel(listOf(MotionAnnotation())))
    }

    private val readyModelGate = ModelGateState(ModelGateStatus.READY)
    private val granted = PermissionStatus(
        allGranted = true,
        missingPermissions = emptyList(),
        shouldShowRationale = false
    )

    @Test
    fun activeRecordingIsNotRenderedAsDisconnected() {
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                isRecording = true,
                connectionState = WearConnectionState(
                    status = WatchConnectionStatus.RECORDING,
                    reachabilityPhase = WearReachabilityPhase.STREAMING,
                    nodeCount = 1,
                    lastNodeIds = listOf("watch")
                ),
                appliedBatchCount = 1,
                observedDataElapsedMillis = 1_000L
            ),
            granted
        )

        assertEquals(HomeSessionStatus.RECORDING, state.status)
        assertTrue(state.transport.hasAppEvidence)
        assertEquals("Watch terhubung · streaming", state.transport.statusText)
    }

    @Test
    fun activeFinalizationStateIsNotError() {
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                finalizationState = StressSessionFinalizationState.Draining
            ),
            granted
        )

        assertEquals(HomeSessionStatus.FINALIZING, state.status)
        assertEquals(null, state.error)
        assertEquals("Menyelesaikan batch Watch…", state.finalizationStatus?.label)
        assertEquals(null, state.finalizationStatus?.totalWindows)
    }

    @Test
    fun acceptedPpgAndGapUseRealReceiptWhileSessionClockKeepsRunning() {
        val recording = MobileUiState(
            modelGate = readyModelGate,
            isRecording = true,
            elapsedMillis = 180_000L,
            progress = 0.6f,
            totalGreenSamples = 100,
            totalIrSamples = 80,
            missingSequenceCount = 2L,
            connectionState = WearConnectionState(
                reachabilityPhase = WearReachabilityPhase.STREAMING,
                lastStreamingEvidenceMillis = 100_000L
            )
        )

        val receiving = HomeUiState.from(recording, granted, nowMillis = 103_000L)
        val stale = HomeUiState.from(recording, granted, nowMillis = 106_000L)

        assertEquals("PPG diterima · ada jeda dalam sesi", receiving.recordingSignalLabel)
        assertEquals("Data PPG belum diterima lagi", stale.recordingSignalLabel)
        val disconnected = HomeUiState.from(
            recording.copy(connectionState = recording.connectionState.copy(reachabilityPhase = WearReachabilityPhase.OFFLINE)),
            granted,
            nowMillis = 103_000L
        )
        assertEquals("Watch terputus · PPG saat ini tidak tersedia", disconnected.recordingSignalLabel)
        assertEquals(180_000L, stale.elapsedMillis)
        assertEquals(0.6f, stale.progress)
    }

    @Test
    fun waitingGameRequiresFrozenSnapshotForTheActiveEvaluatingSession() {
        val evaluating = MobileUiState(
            sessionId = "session-a",
            finalizationSnapshotSessionId = "session-a",
            finalizationState = StressSessionFinalizationState.Evaluating(null, 0, 0),
            modelGate = readyModelGate
        )

        assertTrue(HomeUiState.from(evaluating, granted).canPlayWaitingGame)
        assertTrue(!HomeUiState.from(evaluating.copy(finalizationSnapshotSessionId = "session-b"), granted).canPlayWaitingGame)
        val draining = HomeUiState.from(evaluating.copy(finalizationState = StressSessionFinalizationState.Draining), granted)
        val completed = HomeUiState.from(evaluating.copy(finalizationState = StressSessionFinalizationState.Completed), granted)
        assertTrue(!draining.canPlayWaitingGame)
        assertTrue(!completed.canPlayWaitingGame)
    }

    @Test
    fun completedPredictionDoesNotKeepWaitingGameAvailable() {
        val prediction = StressPrediction(
            sensor = PpgType.GREEN,
            label = "NORMAL",
            stressProbability = 0.2f,
            confidence = 0.8f,
            quality = SignalQualitySummary(true, "good", "usable", 4_500, 0.0, 0.0, 0),
            generatedAtMillis = 1L,
            decisionPolicy = ModelDecisionPolicy("test", 0.5f, "test")
        )
        val completed = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                sessionId = "session-a",
                finalizationState = StressSessionFinalizationState.Completed,
                primaryPrediction = prediction,
                primaryPredictionState = PredictionAvailability.Ready(prediction)
            ),
            granted
        )

        assertEquals(HomeSessionStatus.RESULT_READY, completed.status)
        assertTrue(!completed.canPlayWaitingGame)
        assertTrue(
            !HomeUiState.from(
                MobileUiState(
                    modelGate = readyModelGate,
                    sessionId = "session-b",
                    finalizationState = StressSessionFinalizationState.InsufficientSignal("quality")
                ),
                granted
            ).canPlayWaitingGame
        )
    }

    @Test
    fun evaluationShowsReportedWindowCountWithoutWeightedPercentage() {
        val evaluating = StressSessionFinalizationState.Evaluating(
            channel = PpgType.IR,
            completedWindows = 2,
            totalWindows = 7,
            channels = mapOf(
                PpgType.GREEN to FinalizationChannelProgress(3, 7),
                PpgType.IR to FinalizationChannelProgress(2, 7)
            )
        )

        val status = HomeUiState.from(
            MobileUiState(modelGate = readyModelGate, finalizationState = evaluating),
            granted
        ).finalizationStatus

        assertEquals("Memeriksa kualitas dan model IR…", status?.label)
        assertEquals(5, status?.completedWindows)
        assertEquals(14, status?.totalWindows)
    }

    @Test
    fun evaluationUsesReportedProgressBeforePerChannelMapExists() {
        val status = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                finalizationState = StressSessionFinalizationState.Evaluating(PpgType.GREEN, 2, 7)
            ),
            granted
        ).finalizationStatus

        assertEquals(2, status?.completedWindows)
        assertEquals(7, status?.totalWindows)
    }

    @Test
    fun transportNoticeAfterCompletionDoesNotLeaveScreenFinalizing() {
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                finalizationState = StressSessionFinalizationState.Completed,
                sessionNotice = "Transport warning: terminal receipt missing"
            ),
            granted
        )

        assertTrue(state.status != HomeSessionStatus.FINALIZING)
        assertEquals("Transport warning: terminal receipt missing", state.notice)
    }

    @Test
    fun timedOutFinalizationExposesRetryableError() {
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                elapsedMillis = 180_000L,
                finalizationState = StressSessionFinalizationState.TimedOut(FinalizationStage.EVALUATE)
            ),
            granted
        )

        assertEquals(HomeSessionStatus.ERROR, state.status)
        assertTrue(state.canRetryFinalization)
        assertTrue(state.notice?.contains("EVALUATE") == true)
    }

    @Test
    fun persistencePendingShowsResultAndRetryWithoutWarmingUp() {
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                finalizationState = StressSessionFinalizationState.ResultReady(
                    persisted = false,
                    warning = "persistence_timeout"
                ),
                primaryPredictionState = PredictionAvailability.Unavailable("persistence_pending"),
                irPredictionState = PredictionAvailability.Unavailable("persistence_pending")
            ),
            granted
        )

        assertTrue(state.canRetryFinalization)
        assertTrue(state.notice?.contains("persistence_timeout") == true)
        assertTrue(state.status != HomeSessionStatus.FINALIZING)
        assertTrue(state.greenPrediction.availability !is PredictionAvailability.WarmingUp)
    }

    @Test
    fun explicitInsufficientSignalFinalizationDoesNotRenderStressResult() {
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                finalizationState = StressSessionFinalizationState.InsufficientSignal("ppg_true_gap_detected")
            ),
            granted
        )

        assertEquals(HomeSessionStatus.INSUFFICIENT_SIGNAL, state.status)
        assertEquals("Sinyal tidak cukup untuk menentukan hasil. Periksa detail teknis.", state.notice)
    }

    @Test
    fun insufficientSignalWithPendingPersistenceOffersManualRetry() {
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                finalizationState = StressSessionFinalizationState.InsufficientSignal("core_coverage_insufficient"),
                pendingPersistence = true
            ),
            granted
        )

        assertEquals(HomeSessionStatus.INSUFFICIENT_SIGNAL, state.status)
        assertTrue(state.canRetryFinalization)
        assertEquals("Sesi belum tersimpan. Ulangi finalisasi.", state.notice)
    }

    @Test
    fun transportTelemetryShowsCumulativeSamplesInsteadOfRollingBufferCapacity() {
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                greenSamples = 4_500,
                irSamples = 4_500,
                redDiagnosticSamples = 4_500,
                totalGreenSamples = 7_600,
                totalIrSamples = 7_601,
                totalRedDiagnosticSamples = 7_599
            ),
            granted
        )

        assertEquals(7_600, state.greenSamples)
        assertEquals(7_601, state.irSamples)
        assertEquals(7_600, state.transport.greenSamples)
        assertEquals(7_601, state.transport.irSamples)
        assertEquals(7_599, state.transport.redDiagnosticSamples)
    }

    @Test
    fun `both quality rejections expose insufficient signal state`() {
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                primaryPredictionState = PredictionAvailability.Rejected(
                    reason = "quality gate",
                    sampleCount = 12
                ),
                irPredictionState = PredictionAvailability.Rejected(
                    reason = "quality gate",
                    sampleCount = 24
                )
            ),
            PermissionStatus(true, emptyList(), false)
        )

        assertEquals(HomeSessionStatus.INSUFFICIENT_SIGNAL, state.status)
    }

    @Test
    fun disconnectedWatchExposesBlockingPrerequisiteInsteadOfReady() {
        val state = HomeUiState.from(
            MobileUiState(modelGate = readyModelGate, connectionState = WearConnectionState()),
            granted
        )

        assertEquals(HomeSessionStatus.PREREQUISITE_REQUIRED, state.status)
        assertTrue(!state.canStartSession)
        assertEquals("Watch tidak tersedia", state.primaryStatusLabel)
    }

    @Test
    fun invalidModelBlocksHomeEvenWhenWatchIsReachable() {
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = ModelGateState(ModelGateStatus.UNAVAILABLE, "research_holdout_access_forbidden"),
                connectionState = WearConnectionState(
                    status = WatchConnectionStatus.APP_REACHABLE,
                    reachabilityPhase = WearReachabilityPhase.APP_REACHABLE,
                    lastPongMillis = System.currentTimeMillis(),
                    lastPongNodeId = "watch",
                    lastNodeIds = listOf("watch")
                )
            ),
            granted
        )

        assertEquals(HomeSessionStatus.MODEL_UNAVAILABLE, state.status)
        assertEquals("Model belum siap", state.primaryStatusLabel)
        assertTrue(!state.canStartSession)
    }

    @Test
    fun nodeOnlyStatusExplainsThatTheWatchAppHasNotResponded() {
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                connectionState = WearConnectionState(
                    status = WatchConnectionStatus.NODE_ONLY,
                    reachabilityPhase = WearReachabilityPhase.NODE_DISCOVERED,
                    nodeCount = 1,
                    lastNodeIds = listOf("watch")
                )
            ),
            granted
        )

        assertEquals("Watch ditemukan — aplikasi belum merespons", state.primaryStatusLabel)
        assertEquals("Watch ditemukan — aplikasi belum merespons", state.transport.statusText)
    }

    @Test
    fun stalePongDoesNotRenderWatchAsReady() {
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                connectionState = WearConnectionState(
                    status = WatchConnectionStatus.APP_REACHABLE,
                    reachabilityPhase = WearReachabilityPhase.APP_REACHABLE,
                    lastPongMillis = System.currentTimeMillis() - 20_000L,
                    lastPongNodeId = "watch",
                    lastNodeIds = listOf("watch")
                )
            ),
            granted
        )

        assertEquals(HomeSessionStatus.PREREQUISITE_REQUIRED, state.status)
        assertEquals("Watch ditemukan — aplikasi belum merespons", state.primaryStatusLabel)
        assertEquals("Watch ditemukan — aplikasi belum merespons", state.transport.statusText)
        assertEquals("Respons aplikasi Watch sudah kedaluwarsa. Coba koneksi lagi.", state.connectionGuidance)
    }

    @Test
    fun checkingStatusExposesOneBusyProbeState() {
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                connectionState = WearConnectionState(
                    status = WatchConnectionStatus.NODE_ONLY,
                    reachabilityPhase = WearReachabilityPhase.NODE_DISCOVERED,
                    probeStatus = ReachabilityProbeStatus.CHECKING,
                    nodeCount = 1,
                    lastNodeIds = listOf("watch")
                )
            ),
            granted
        )

        assertEquals("Memeriksa koneksi…", state.primaryStatusLabel)
        assertEquals("Memeriksa koneksi", state.transport.statusText)
        assertEquals(
            "Memeriksa aplikasi Watch. Tunggu sebentar atau coba lagi setelah pemeriksaan selesai.",
            state.connectionGuidance
        )
    }

    @Test
    fun freshReachableWatchAllowsHomeStart() {
        val state = HomeUiState.from(
            MobileUiState(
                modelGate = readyModelGate,
                connectionState = WearConnectionState(
                    status = WatchConnectionStatus.APP_REACHABLE,
                    reachabilityPhase = WearReachabilityPhase.APP_REACHABLE,
                    lastPongMillis = System.currentTimeMillis(),
                    lastPongNodeId = "watch",
                    lastNodeIds = listOf("watch")
                )
            ),
            granted
        )

        assertEquals(HomeSessionStatus.READY, state.status)
        assertTrue(state.canStartSession)
    }
}
