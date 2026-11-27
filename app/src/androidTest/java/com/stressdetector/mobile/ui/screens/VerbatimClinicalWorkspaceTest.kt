package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.data.WatchConnectionStatus
import com.stressdetector.mobile.data.WearConnectionState
import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.domain.engine.AcousticAnalysisSnapshot
import com.stressdetector.mobile.domain.engine.BpmSignalStatus
import com.stressdetector.mobile.domain.engine.BpmSource
import com.stressdetector.mobile.domain.engine.LiveBiosignalSnapshot
import com.stressdetector.mobile.domain.engine.LiveBpmChannelSnapshot
import com.stressdetector.mobile.domain.engine.LiveMonitorState
import com.stressdetector.mobile.domain.engine.LivePitchObservation
import com.stressdetector.mobile.domain.engine.LivePitchStatus
import com.stressdetector.mobile.domain.engine.LiveTemperatureSnapshot
import com.stressdetector.mobile.domain.engine.SkinTemperatureObservation
import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import com.stressdetector.mobile.domain.engine.SpeechSegment
import com.stressdetector.mobile.domain.engine.ThermalSignalStatus
import com.stressdetector.mobile.domain.engine.VerbatimSessionDetail
import com.stressdetector.mobile.domain.engine.VerbatimSessionSummary
import com.stressdetector.mobile.domain.engine.WatchWearerRole
import com.stressdetector.mobile.ui.StressDetectorTheme
import com.stressdetector.mobile.ui.viewmodels.SessionCommandState
import com.stressdetector.mobile.ui.viewmodels.UnifiedSessionUiState
import com.stressdetector.shared.PpgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VerbatimClinicalWorkspaceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun setupSurfaceSupportsLightThemeConsentGateAndMinimumTouchTargets() {
        var started = false
        var micPermissionRequests = 0
        compose.setContent {
            StressDetectorTheme(darkTheme = false) {
                var consent by remember { mutableStateOf(false) }
                var name by remember { mutableStateOf("Klien 01") }
                var watchMonitoring by remember { mutableStateOf(true) }
                val uiState = UnifiedSessionUiState(
                    speechRuntimeState = SpeechRuntimeState.Ready,
                    wearConnectionState = WearConnectionState(
                        status = WatchConnectionStatus.APP_REACHABLE,
                        lastPongNodeId = "GW5-Demo"
                    ),
                    sessionHistory = listOf(
                        VerbatimSessionSummary(
                            sessionId = "s-prev",
                            sessionName = "Sesi Sebelumnya",
                            startedAtMillis = 1_700_000_000_000L,
                            endedAtMillis = 1_700_000_060_000L,
                            durationSeconds = 60L,
                            audioFilePath = "",
                            artifactFolderPath = "",
                            finalizationStatus = "COMPLETED",
                            summaryStatus = "READY"
                        )
                    )
                )
                SessionContent(
                    uiState = uiState,
                    modifier = Modifier,
                    onOpenDrawer = {},
                    onStartOrStop = { started = true },
                    onPauseOrResume = {},
                    onPlay = {},
                    onPausePlayback = {},
                    onStopPlayback = {},
                    onSeekAudio = {},
                    onPlayFromMarker = {},
                    onRetryPlayback = {},
                    onExport = {},
                    onExportText = {},
                    onDelete = {},
                    onOpenProfile = {},
                    onRefreshReadiness = {},
                    onCycleSpeaker = {},
                    onEditTranscript = { _, _ -> },
                    onRequestAddManualAnnotation = {},
                    onDeleteManualAnnotation = {},
                    microphoneGranted = false,
                    microphonePermanentlyDenied = false,
                    onRequestMicrophonePermission = { micPermissionRequests++ },
                    setupSessionName = name,
                    onSetupSessionNameChange = { name = it },
                    setupMonitoringEnabled = watchMonitoring,
                    onSetupMonitoringEnabledChange = { watchMonitoring = it },
                    setupConsentChecked = consent,
                    onSetupConsentCheckedChange = { consent = it },
                    onStartSetupSession = { started = true }
                )
            }
        }

        compose.onNodeWithText("Kesiapan perangkat").assertIsDisplayed()
        compose.onNodeWithText("Izinkan").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Mikrofon").performScrollTo().performClick()
        assertTrue(micPermissionRequests == 2)
        compose.onNodeWithText("Mulai sesi").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Mulai sesi").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Sesi Sebelumnya").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Persetujuan perekaman telah diberikan.").performScrollTo().performClick()
        compose.onNodeWithText("Mulai sesi").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Verbatim+").assertIsDisplayed()
        assertTrue(started)
    }

    @Test
    fun setupProgressiveDisclosureHidesWearerAndAllowsStartWhenWatchDisconnected() {
        var selectedRole = WatchWearerRole.UNSPECIFIED
        compose.setContent {
            StressDetectorTheme(darkTheme = true) {
                var consent by remember { mutableStateOf(true) }
                var watchMonitoring by remember { mutableStateOf(false) }
                var wearerRole by remember { mutableStateOf(WatchWearerRole.UNSPECIFIED) }
                SessionContent(
                    uiState = UnifiedSessionUiState(
                        speechRuntimeState = SpeechRuntimeState.Ready,
                        watchWearerRole = wearerRole,
                        wearConnectionState = WearConnectionState(status = WatchConnectionStatus.DISCONNECTED)
                    ),
                    modifier = Modifier,
                    onOpenDrawer = {},
                    onStartOrStop = {},
                    onPauseOrResume = {},
                    onPlay = {},
                    onPausePlayback = {},
                    onStopPlayback = {},
                    onSeekAudio = {},
                    onPlayFromMarker = {},
                    onRetryPlayback = {},
                    onExport = {},
                    onExportText = {},
                    onDelete = {},
                    onOpenProfile = {},
                    onRefreshReadiness = {},
                    onCycleSpeaker = {},
                    onEditTranscript = { _, _ -> },
                    onRequestAddManualAnnotation = {},
                    onDeleteManualAnnotation = {},
                    microphoneGranted = true,
                    setupMonitoringEnabled = watchMonitoring,
                    onSetupMonitoringEnabledChange = { watchMonitoring = it },
                    setupConsentChecked = consent,
                    onSetupConsentCheckedChange = { consent = it },
                    onSetWatchWearerRole = {
                        wearerRole = it
                        selectedRole = it
                    }
                )
            }
        }

        compose.onNodeWithText("Verbatim+").assertIsDisplayed()
        compose.onNodeWithText("Dipakai oleh").assertDoesNotExist()
        compose.onNodeWithText("Mulai sesi").performScrollTo().assertIsEnabled()

        compose.onNodeWithContentDescription("Aktifkan pemantauan Galaxy Watch").performScrollTo().performClick()
        compose.onNodeWithText("Dipakai oleh").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Belum terhubung").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Klien").performScrollTo().performClick()
        compose.onNodeWithText("Psikolog").performClick()
        assertEquals(WatchWearerRole.PSYCHOLOGIST, selectedRole)
        compose.onNodeWithText("Mulai sesi").performScrollTo().assertIsEnabled()
    }

    @Test
    fun setupSurfaceShowsSettingsRecoveryOnlyWhenMicrophonePermanentlyDenied() {
        var recoveryClicks = 0
        compose.setContent {
            StressDetectorTheme(darkTheme = true) {
                SessionContent(
                    uiState = UnifiedSessionUiState(speechRuntimeState = SpeechRuntimeState.Ready),
                    modifier = Modifier,
                    onOpenDrawer = {},
                    onStartOrStop = {},
                    onPauseOrResume = {},
                    onPlay = {},
                    onPausePlayback = {},
                    onStopPlayback = {},
                    onSeekAudio = {},
                    onPlayFromMarker = {},
                    onRetryPlayback = {},
                    onExport = {},
                    onExportText = {},
                    onDelete = {},
                    onOpenProfile = {},
                    onRefreshReadiness = {},
                    onCycleSpeaker = {},
                    onEditTranscript = { _, _ -> },
                    onRequestAddManualAnnotation = {},
                    onDeleteManualAnnotation = {},
                    microphoneGranted = false,
                    microphonePermanentlyDenied = true,
                    onRequestMicrophonePermission = { recoveryClicks++ }
                )
            }
        }

        compose.onNodeWithText("Izinkan").assertDoesNotExist()
        compose.onNodeWithText("Buka Setelan").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(recoveryClicks == 1)
    }

    @Test
    fun liveSessionDisplaysActualBpmSourceAndKeepsTechnicalDetailsCollapsedByDefault() {
        val bpmPreview = LiveBpmChannelSnapshot(
            source = BpmSource.PPG_GREEN,
            observedBpm = 84f,
            baselineBpm = 72f,
            deltaBpm = 12f,
            qualityScore = 0.91f,
            status = BpmSignalStatus.SIGNAL_VALID
        )
        val liveState = UnifiedSessionUiState(
            isSessionActive = true,
            sessionCommandState = SessionCommandState.Recording,
            elapsedSeconds = 128L,
            selectedSessionId = "live-1",
            speechRuntimeState = SpeechRuntimeState.Ready,
            acousticAnalysis = AcousticAnalysisSnapshot(
                sessionId = "live-1",
                startOffsetMs = 126_000L,
                endOffsetMs = 128_000L,
                rmsDb = -22.0f,
                baselineRmsDb = -28.0f,
                zeroCrossingRate = 0.08f,
                clippingRatio = 0f,
                finiteSampleRatio = 1f,
                qualityScore = 0.94f,
                baselineDeviationDb = 6.0f,
                markerType = null,
                markerTransition = null,
                evidenceConfidence = 0.9f,
                waveform = listOf(0.1f, 0.4f, 0.25f, 0.7f, 0.2f)
            ),
            livePitch = listOf(
                LivePitchObservation("live-1", 126_000L, 127_000L, 130.0, LivePitchStatus.AVAILABLE, 12, 20),
                LivePitchObservation("live-1", 127_000L, 128_000L, 142.0, LivePitchStatus.AVAILABLE, 14, 20)
            ),
            liveBiosignal = LiveBiosignalSnapshot(
                sessionId = "live-1",
                monitor = LiveMonitorState(
                    isMonitoring = true,
                    liveBpmBySource = mapOf(BpmSource.PPG_GREEN to bpmPreview),
                    activeBpmPreview = bpmPreview,
                    latestBpm = 84f,
                    latestBpmByChannel = mapOf(PpgType.GREEN to 84f)
                )
            )
        )

        compose.setContent {
            StressDetectorTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                    Box(Modifier.width(360.dp)) {
                        SessionContent(
                            uiState = liveState,
                            modifier = Modifier,
                            onOpenDrawer = {},
                            onStartOrStop = {},
                            onPauseOrResume = {},
                            onPlay = {},
                            onPausePlayback = {},
                            onStopPlayback = {},
                            onSeekAudio = {},
                            onPlayFromMarker = {},
                            onRetryPlayback = {},
                            onExport = {},
                            onExportText = {},
                            onDelete = {},
                            onOpenProfile = {},
                            onRefreshReadiness = {},
                            onCycleSpeaker = {},
                            onEditTranscript = { _, _ -> },
                            onRequestAddManualAnnotation = {},
                            onDeleteManualAnnotation = {}
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("Audio langsung").assertIsDisplayed()
        compose.onNodeWithText("RMS -22.0 dBFS").assertIsDisplayed()
        compose.onNodeWithText("142").assertIsDisplayed()
        compose.onNodeWithText("Tandai").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Stop & simpan").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Detail akustik"))
        compose.onNodeWithText("Detail akustik").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Baseline RMS", substring = true))
        compose.onNodeWithText("Baseline RMS", substring = true).assertIsDisplayed()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("84"))
        compose.onNodeWithText("84").assertIsDisplayed()
        compose.onNodeWithText("+12 BPM").assertIsDisplayed()
        compose.onNodeWithText("LIVE PPG / AROUSAL").assertDoesNotExist()

        compose.onNodeWithText("Detail teknis", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("LIVE PPG / AROUSAL").assertExists()
    }

    @Test
    fun reviewTimelineExposesCompactContextMenuForRelabelAndTtsPlayback() {
        var relabeledTo: String? = null
        var ttsTriggeredFor: String? = null
        val summary = VerbatimSessionSummary(
            sessionId = "rev-tts",
            sessionName = "Klien 02",
            startedAtMillis = 1_700_000_000_000L,
            endedAtMillis = 1_700_000_030_000L,
            durationSeconds = 30L,
            audioFilePath = "",
            artifactFolderPath = "",
            finalizationStatus = "COMPLETED",
            summaryStatus = "READY"
        )
        val segment = SpeechSegment(
            id = "seg-tts",
            speakerId = "Speaker_0",
            startOffsetMs = 4_000L,
            endOffsetMs = 9_000L,
            startTimestampUtc = 1_700_000_004_000L,
            endTimestampUtc = 1_700_000_009_000L,
            rawText = "Saya merasa tegang sejak kemarin.",
            confidence = 0.88f,
            sessionId = "rev-tts"
        )
        compose.setContent {
            StressDetectorTheme(darkTheme = true) {
                SessionContent(
                    uiState = UnifiedSessionUiState(
                        selectedSessionId = "rev-tts",
                        selectedSession = VerbatimSessionDetail(summary, listOf(segment), emptyList()),
                        transcriptSegments = listOf(segment)
                    ),
                    modifier = Modifier,
                    onOpenDrawer = {},
                    onStartOrStop = {},
                    onPauseOrResume = {},
                    onPlay = {},
                    onPausePlayback = {},
                    onStopPlayback = {},
                    onSeekAudio = {},
                    onPlayFromMarker = {},
                    onRetryPlayback = {},
                    onExport = {},
                    onExportText = {},
                    onDelete = {},
                    onOpenProfile = {},
                    onRefreshReadiness = {},
                    onCycleSpeaker = {},
                    onEditTranscript = { _, _ -> },
                    onRequestAddManualAnnotation = {},
                    onDeleteManualAnnotation = {},
                    onSetSpeakerLabel = { _, label -> relabeledTo = label },
                    onToggleSpeakTts = { id, _ -> ttsTriggeredFor = id }
                )
            }
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Saya merasa tegang sejak kemarin."))
        compose.onNodeWithText("Pembicara 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Aksi ujaran 0:04").performClick()
        compose.onNodeWithText("Bacakan teks (TTS lokal)").performClick()
        assertEquals("seg-tts", ttsTriggeredFor)

        compose.onNodeWithContentDescription("Aksi ujaran 0:04").performClick()
        compose.onNodeWithText("Tandai sebagai Klien").performClick()
        assertEquals("Patient", relabeledTo)
    }

    @Test
    fun reviewAndMarkerDetailSheetLinkContextWithoutDiagnosticClaim() {
        val summary = VerbatimSessionSummary(
            sessionId = "rev-1",
            sessionName = "Klien 01",
            startedAtMillis = 1_700_000_000_000L,
            endedAtMillis = 1_700_000_045_000L,
            durationSeconds = 45L,
            audioFilePath = "",
            artifactFolderPath = "",
            finalizationStatus = "COMPLETED",
            summaryStatus = "READY",
            audioDurationMs = 45_000L
        )
        val segment = SpeechSegment(
            id = "seg-1",
            speakerId = "Klien",
            startOffsetMs = 18_000L,
            endOffsetMs = 24_000L,
            startTimestampUtc = 1_700_000_018_000L,
            endTimestampUtc = 1_700_000_024_000L,
            rawText = "Akhir-akhir ini sulit beristirahat.",
            confidence = 0.9f,
            sessionId = "rev-1"
        )
        val event = DivergenceEventEntity(
            eventId = "ev-1",
            sessionId = "rev-1",
            timestampMs = 1_700_000_020_000L,
            delta = 12f,
            currentProbability = null,
            baselineProbability = null,
            copyText = "Denyut nadi meningkat +12 BPM",
            channel = "GREEN",
            markerElapsedMillis = 20_000L,
            episodeStartElapsedMillis = 18_000L,
            episodeEndElapsedMillis = 24_000L,
            transcriptSegmentId = "seg-1",
            markerType = "BPM_RISE",
            bpm = 84f,
            baselineBpm = 72f,
            bpmDelta = 12f,
            signalQuality = 0.92f,
            unit = "BPM"
        )

        compose.setContent {
            StressDetectorTheme(darkTheme = true) {
                var selectedMarker by remember { mutableStateOf<DivergenceEventEntity?>(null) }
                val uiState = UnifiedSessionUiState(
                    selectedSessionId = "rev-1",
                    selectedSession = VerbatimSessionDetail(summary, listOf(segment), listOf(event)),
                    transcriptSegments = listOf(segment),
                    divergenceEvents = listOf(event),
                    selectedMarkerForDetail = selectedMarker
                )
                SessionContent(
                    uiState = uiState,
                    modifier = Modifier,
                    onOpenDrawer = {},
                    onStartOrStop = {},
                    onPauseOrResume = {},
                    onPlay = {},
                    onPausePlayback = {},
                    onStopPlayback = {},
                    onSeekAudio = {},
                    onPlayFromMarker = {},
                    onRetryPlayback = {},
                    onExport = {},
                    onExportText = {},
                    onDelete = {},
                    onOpenProfile = {},
                    onRefreshReadiness = {},
                    onCycleSpeaker = {},
                    onEditTranscript = { _, _ -> },
                    onRequestAddManualAnnotation = {},
                    onDeleteManualAnnotation = {},
                    onOpenMarkerDetail = { selectedMarker = it }
                )
                uiState.selectedMarkerForDetail?.let { detail ->
                    VerbatimMarkerDetailSheet(
                        marker = detail,
                        linkedSegment = segment,
                        playbackOffsetMs = 18_000L,
                        canAnnotate = true,
                        onDismiss = { selectedMarker = null },
                        onPlayFromMarker = {},
                        onSavePsychologistNote = {}
                    )
                }
            }
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Timeline sesi multimodal"))
        compose.onNodeWithText("Timeline sesi multimodal").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Akhir-akhir ini sulit beristirahat."))
        compose.onNodeWithText("Akhir-akhir ini sulit beristirahat.").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Denyut nadi meningkat +12 BPM"))
        compose.onNodeWithText("Denyut nadi meningkat +12 BPM").performClick()

        compose.onNodeWithText("Catatan psikolog").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Observasi akustik dan fisiologis membantu", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun livePhysiologicalPanelDisplaysCompactSkinTempTileAndDetailsInNarrow320dpViewport() {
        val bpmPreview = LiveBpmChannelSnapshot(
            source = BpmSource.PPG_GREEN,
            observedBpm = 82f,
            baselineBpm = 74f,
            deltaBpm = 8f,
            qualityScore = 0.9f,
            status = BpmSignalStatus.SIGNAL_VALID
        )
        val tempPoints = listOf(
            SkinTemperatureObservation(
                sampleId = "t1",
                sessionId = "live-temp",
                generation = 1L,
                sequenceNumber = 1L,
                sensorTimestampMs = 1_760_000_000_000L,
                receivedTimestampMs = 1_760_000_000_050L,
                elapsedMillis = 10_000L,
                clockUncertaintyMs = 250L,
                skinTemperatureC = 33.4f,
                ambientTemperatureC = 26.2f,
                measurementStatus = 0,
                isValid = true,
                wearerRole = "CLIENT",
                source = "WATCH_SDK_SKIN_TEMP"
            ),
            SkinTemperatureObservation(
                sampleId = "t2",
                sessionId = "live-temp",
                generation = 1L,
                sequenceNumber = 2L,
                sensorTimestampMs = 1_760_000_060_000L,
                receivedTimestampMs = 1_760_000_060_050L,
                elapsedMillis = 70_000L,
                clockUncertaintyMs = 250L,
                skinTemperatureC = 32.8f,
                ambientTemperatureC = 26.2f,
                measurementStatus = 0,
                isValid = true,
                wearerRole = "CLIENT",
                source = "WATCH_SDK_SKIN_TEMP"
            )
        )
        val tempSnap = LiveTemperatureSnapshot(
            status = ThermalSignalStatus.ACTIVE,
            currentSkinTempC = 32.8f,
            baselineSkinTempC = 33.4f,
            deltaC = -0.6f,
            ambientTempC = 26.2f,
            slopeCPerMin = -0.35f,
            minSkinTempC = 32.8f,
            maxSkinTempC = 33.4f,
            meanSkinTempC = 33.1f,
            medianSkinTempC = 33.1f,
            validSampleCount = 2,
            totalSampleCount = 2,
            measurementStatusCode = 0,
            lastUpdatedElapsedMillis = 70_000L,
            recentPoints = tempPoints
        )
        val liveState = UnifiedSessionUiState(
            isSessionActive = true,
            sessionCommandState = SessionCommandState.Recording,
            elapsedSeconds = 70L,
            selectedSessionId = "live-temp",
            speechRuntimeState = SpeechRuntimeState.Ready,
            liveBiosignal = LiveBiosignalSnapshot(
                sessionId = "live-temp",
                monitor = LiveMonitorState(
                    isMonitoring = true,
                    liveBpmBySource = mapOf(BpmSource.PPG_GREEN to bpmPreview),
                    activeBpmPreview = bpmPreview
                ),
                liveTemperature = tempSnap
            )
        )

        compose.setContent {
            StressDetectorTheme(darkTheme = false) {
                Box(Modifier.width(320.dp)) {
                    PhysiologicalMonitoringPanel(liveState.liveBiosignal)
                }
            }
        }

        compose.onNodeWithText("Respons fisiologis").assertIsDisplayed()
        compose.onNodeWithText("Suhu kulit").assertIsDisplayed()
        compose.onNodeWithText("32.8").assertIsDisplayed()
        compose.onNodeWithText("↓ 0.6 °C baseline").assertIsDisplayed()
        compose.onNodeWithText("82").assertIsDisplayed()

        compose.onNodeWithText("Detail fisiologis").performClick()
        compose.onNodeWithText("Sekitar 26.2 °C", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Laju -0.35 °C/mnt", substring = true).assertIsDisplayed()
    }

    @Test
    fun reviewAndThermalMarkerDetailRenderTemperatureCurveAndNonCausalDisclaimer() {
        val summary = VerbatimSessionSummary(
            sessionId = "rev-th",
            sessionName = "Klien Termal",
            startedAtMillis = 1_760_000_000_000L,
            endedAtMillis = 1_760_000_180_000L,
            durationSeconds = 180L,
            audioFilePath = "",
            artifactFolderPath = "",
            finalizationStatus = "COMPLETED",
            summaryStatus = "READY",
            audioDurationMs = 180_000L
        )
        val segment = SpeechSegment(
            id = "seg-th",
            speakerId = "Klien",
            startOffsetMs = 70_000L,
            endOffsetMs = 76_000L,
            startTimestampUtc = 1_760_000_070_000L,
            endTimestampUtc = 1_760_000_076_000L,
            rawText = "Saya merasa tidak nyaman membahas ini.",
            confidence = 0.9f,
            sessionId = "rev-th"
        )
        val thermalMarker = DivergenceEventEntity(
            eventId = "rev-th:thermal:1",
            sessionId = "rev-th",
            timestampMs = 70_000L,
            delta = -0.6f,
            currentProbability = null,
            baselineProbability = null,
            copyText = "Suhu kulit menurun (-0.6 °C terhadap baseline)",
            channel = "SKIN_TEMPERATURE",
            markerElapsedMillis = 70_000L,
            episodeStartElapsedMillis = 65_000L,
            episodeEndElapsedMillis = 95_000L,
            transcriptSegmentId = "seg-th",
            markerType = "THERMAL_DIVERGENCE",
            sourceChannels = "SKIN_TEMPERATURE",
            markerConfigVersion = "thermal-median-v1",
            baselineValue = 33.4f,
            observedValue = 32.8f,
            deltaValue = -0.6f,
            unit = "°C",
            thermalDirection = "DECREASE",
            ambientTemperatureC = 26.2f,
            ambientDeltaC = 0.0f,
            slopeCelsiusPerMin = -0.40f
        )
        val samples = listOf(
            SkinTemperatureObservation("t1", "rev-th", 1L, 1L, 10L, 20L, 10_000L, 250L, 33.4f, 26.2f, 0, true, "CLIENT", "WATCH"),
            SkinTemperatureObservation("t2", "rev-th", 1L, 2L, 30L, 40L, 75_000L, 250L, 32.8f, 26.2f, 0, true, "CLIENT", "WATCH")
        )

        compose.setContent {
            StressDetectorTheme(darkTheme = true) {
                var selectedMarker by remember { mutableStateOf<DivergenceEventEntity?>(null) }
                val uiState = UnifiedSessionUiState(
                    selectedSessionId = "rev-th",
                    selectedSession = VerbatimSessionDetail(summary, listOf(segment), listOf(thermalMarker), samples),
                    transcriptSegments = listOf(segment),
                    divergenceEvents = listOf(thermalMarker),
                    skinTemperatureSamples = samples,
                    selectedMarkerForDetail = selectedMarker
                )
                SessionContent(
                    uiState = uiState,
                    modifier = Modifier,
                    onOpenDrawer = {},
                    onStartOrStop = {},
                    onPauseOrResume = {},
                    onPlay = {},
                    onPausePlayback = {},
                    onStopPlayback = {},
                    onSeekAudio = {},
                    onPlayFromMarker = {},
                    onRetryPlayback = {},
                    onExport = {},
                    onExportText = {},
                    onDelete = {},
                    onOpenProfile = {},
                    onRefreshReadiness = {},
                    onCycleSpeaker = {},
                    onEditTranscript = { _, _ -> },
                    onRequestAddManualAnnotation = {},
                    onDeleteManualAnnotation = {},
                    onOpenMarkerDetail = { selectedMarker = it }
                )
                uiState.selectedMarkerForDetail?.let { detail ->
                    VerbatimMarkerDetailSheet(
                        marker = detail,
                        linkedSegment = segment,
                        playbackOffsetMs = 70_000L,
                        canAnnotate = true,
                        onDismiss = { selectedMarker = null },
                        onPlayFromMarker = {},
                        onSavePsychologistNote = {}
                    )
                }
            }
        }

        compose.onNodeWithText("Median suhu kulit").assertIsDisplayed()
        compose.onNodeWithText("33.1 °C").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Suhu kulit menurun (-0.6 °C terhadap baseline)"))
        compose.onNodeWithText("Suhu kulit menurun (-0.6 °C terhadap baseline)").performClick()

        compose.onNodeWithText("Baseline suhu").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Suhu sekitar").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("hubungan sebab-akibat belum diketahui", substring = true).performScrollTo().assertIsDisplayed()
    }
}
