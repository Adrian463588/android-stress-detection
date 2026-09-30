package com.stressdetector.mobile.domain

import com.stressdetector.mobile.data.db.StressSessionDao
import com.stressdetector.mobile.data.db.StressSessionEntity
import com.stressdetector.mobile.data.db.StressSessionReportEntity
import com.stressdetector.mobile.data.db.StressWindowEvaluationEntity
import com.stressdetector.mobile.domain.engine.BpmSummary
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SessionConfig
import com.stressdetector.shared.SignalQualitySummary
import com.stressdetector.shared.StressLabel
import com.stressdetector.shared.StressPrediction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class HomeSessionSnapshot(
    val sessionId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val durationMillis: Long,
    val observedDataDurationMillis: Long = durationMillis,
    val missingTailMillis: Long = 0L,
    val sessionComplete: Boolean = durationMillis >= SessionConfig.SESSION_DURATION_MILLIS,
    /** Core coverage/SQA/model result is independent from terminal transport diagnostics. */
    val coreEligible: Boolean = false,
    val transportIncomplete: Boolean = false,
    val transportFailureReason: String? = null,
    val completionReason: String? = null,
    val greenSampleCount: Int,
    val irSampleCount: Int,
    val greenState: PredictionAvailability,
    val irState: PredictionAvailability,
    val transportError: String? = null
)

data class FinalizedWindowReport(val channel: PpgType, val windowIndex: Int, val snapshot: PpgProcessingSnapshot)

data class FinalizedSessionReport(
    val expectedWindowCount: Int,
    val windows: List<FinalizedWindowReport>,
    val selectedChannel: PpgType?,
    val rejectionReason: String?,
    val greenBpmSummary: BpmSummary? = null,
    val irBpmSummary: BpmSummary? = null
)

sealed interface HomeFinalizationResult {
    data class Stored(val entity: StressSessionEntity, val green: StressPrediction?, val ir: StressPrediction?) : HomeFinalizationResult

    data class Failed(val reason: String) : HomeFinalizationResult
}

/** Owns the final inference/persistence boundary for a Home stress session. */
class HomeSessionFinalizer(private val dao: StressSessionDao, private val evidenceMode: String = "internal") {
    init {
        require(evidenceMode in setOf("internal", "production", "research")) {
            "unsupported_evidence_mode"
        }
    }

    private val mutex = Mutex()

    suspend fun finalize(
        snapshot: HomeSessionSnapshot,
        aggregatedGreen: StressPrediction?,
        aggregatedIr: StressPrediction?,
        report: FinalizedSessionReport? = null
    ): HomeFinalizationResult = mutex.withLock {
        runCatching {
            // Core evidence owns prediction validity. A late or incomplete
            // terminal receipt is retained as a transport warning and must not
            // erase a valid Green/IR prediction already computed from [30,270).
            val canPredict = snapshot.coreEligible
            // Finalizer persists only evidence evaluated by the core-window path.
            // It must never fall back to the rolling buffer or raw samples.
            val green = aggregatedGreen?.takeIf { canPredict }
            val ir = aggregatedIr?.takeIf { canPredict }
            val primary = green ?: ir
            val quality = primary?.quality ?: SignalQualitySummary(
                isUsable = false,
                tier = "invalid",
                reason = snapshot.transportFailureReason
                    ?: snapshot.transportError
                    ?: snapshot.completionReason?.takeUnless { snapshot.sessionComplete }
                    ?: reason(snapshot.greenState, snapshot.irState),
                sampleCount = maxOf(snapshot.greenSampleCount, snapshot.irSampleCount),
                missingRatio = 1.0,
                flatlineRatio = 1.0,
                timestampGapCount = 0
            )
            val entity = StressSessionEntity(
                sessionId = snapshot.sessionId,
                startedAtMillis = snapshot.startedAtMillis,
                endedAtMillis = snapshot.endedAtMillis,
                durationMillis = snapshot.durationMillis,
                primarySensor = primary?.sensor?.assetSuffix ?: PpgType.GREEN.assetSuffix,
                finalLabel = primary?.let { StressLabel.displayName(it.label) }
                    ?: if (snapshot.transportIncomplete) "Transport incomplete" else "Signal unavailable",
                confidence = primary?.confidence?.takeIf(Float::isFinite) ?: 0f,
                qualityTier = quality.tier,
                stressProbability = primary?.stressProbability
                    ?.takeIf { it.isFinite() && it in 0f..1f }
                    ?: Float.NaN,
                greenSampleCount = snapshot.greenSampleCount,
                irSampleCount = snapshot.irSampleCount,
                errorReason = primary?.quality?.takeIf { !it.isUsable }?.reason
                    ?: quality.takeIf { !it.isUsable }?.reason,
                transportIncomplete = snapshot.transportIncomplete,
                transportFailureReason = snapshot.transportFailureReason ?: snapshot.transportError
            )
            val reportEntity = report?.toEntity(snapshot, entity)
            if (reportEntity == null) {
                dao.insert(entity)
            } else {
                dao.insertWithReport(
                    session = entity,
                    report = reportEntity,
                    evaluations = report.windows.mapIndexed { index, window ->
                        window.toEntity(snapshot.sessionId)
                    }
                )
            }
            HomeFinalizationResult.Stored(entity, green, ir)
        }.getOrElse { error ->
            HomeFinalizationResult.Failed(error.message ?: "Home session persistence failed")
        }
    }

    private fun reason(greenState: PredictionAvailability, irState: PredictionAvailability): String = listOf(greenState, irState)
        .mapNotNull { state ->
            when (state) {
                is PredictionAvailability.WarmingUp -> "window incomplete (${state.currentSamples}/${state.requiredSamples})"
                is PredictionAvailability.Rejected -> state.reason
                is PredictionAvailability.Unavailable -> state.reason
                is PredictionAvailability.InferenceError -> state.reason
                is PredictionAvailability.Ready -> null
            }
        }.distinct().joinToString("; ").ifBlank { "No valid PPG prediction" }

    private fun FinalizedSessionReport.toEntity(snapshot: HomeSessionSnapshot, entity: StressSessionEntity): StressSessionReportEntity =
        StressSessionReportEntity(
            sessionId = snapshot.sessionId,
            sessionDurationSeconds = (snapshot.durationMillis / 1_000L).toInt(),
            coreStartSeconds = 30,
            coreEndSeconds = 270,
            analysisDurationSeconds = 240,
            expectedWindowCount = expectedWindowCount,
            greenAttemptedWindows = windows.count { it.channel == PpgType.GREEN },
            greenValidWindows = windows.count {
                it.channel == PpgType.GREEN && it.snapshot.availability is PredictionAvailability.Ready
            },
            irAttemptedWindows = windows.count { it.channel == PpgType.IR },
            irValidWindows = windows.count {
                it.channel == PpgType.IR && it.snapshot.availability is PredictionAvailability.Ready
            },
            selectedChannel = selectedChannel?.assetSuffix,
            modelVersion = windows.asSequence()
                .filter { it.channel == selectedChannel }
                .mapNotNull { it.snapshot.availability.predictionOrNull()?.modelVersion }
                .firstOrNull(),
            preprocessingFingerprint = windows.asSequence()
                .filter { it.channel == selectedChannel }
                .map { it.snapshot.processingFingerprint }
                .distinct()
                .singleOrNull(),
            evidenceMode = evidenceMode,
            reportStatus = if (selectedChannel != null &&
                windows.any {
                    it.channel == selectedChannel && it.snapshot.availability is PredictionAvailability.Ready
                }
            ) {
                "available"
            } else {
                "unavailable"
            },
            rejectionReason = rejectionReason ?: entity.errorReason,
            greenMeanBpm = greenBpmSummary?.meanBpm,
            greenMedianBpm = greenBpmSummary?.medianBpm,
            greenMinBpm = greenBpmSummary?.minBpm,
            greenMaxBpm = greenBpmSummary?.maxBpm,
            greenBpmObservationCount = greenBpmSummary?.observationCount ?: 0,
            greenValidPeakCount = greenBpmSummary?.validPeakCount ?: 0,
            irMeanBpm = irBpmSummary?.meanBpm,
            irMedianBpm = irBpmSummary?.medianBpm,
            irMinBpm = irBpmSummary?.minBpm,
            irMaxBpm = irBpmSummary?.maxBpm,
            irBpmObservationCount = irBpmSummary?.observationCount ?: 0,
            irValidPeakCount = irBpmSummary?.validPeakCount ?: 0,
            createdAtMillis = snapshot.endedAtMillis
        )

    private fun FinalizedWindowReport.toEntity(sessionId: String): StressWindowEvaluationEntity {
        val prediction = snapshot.availability.predictionOrNull()
        val prepared = snapshot.preparedWindow
        val status = when (snapshot.availability) {
            is PredictionAvailability.Ready -> "valid"
            is PredictionAvailability.WarmingUp -> "warming_up"
            is PredictionAvailability.Rejected -> "invalid"
            is PredictionAvailability.Unavailable -> "unavailable"
            is PredictionAvailability.InferenceError -> "error"
        }
        return StressWindowEvaluationEntity(
            sessionId = sessionId,
            channel = channel.assetSuffix,
            windowIndex = windowIndex,
            windowId = "${sessionId}_${channel.assetSuffix}_$windowIndex",
            startElapsedMillis = prepared?.sessionElapsedStartMillis,
            endElapsedMillis = prepared?.sessionElapsedEndMillis,
            status = status,
            label = prediction?.label,
            stressProbability = prediction?.stressProbability,
            confidence = prediction?.confidence,
            qualityTier = snapshot.quality?.tier,
            qualityScore = snapshot.quality?.qualityScore,
            sampleCount = prepared?.size ?: snapshot.rawSampleCount,
            interpolatedSampleCount = prepared?.interpolatedSampleCount ?: 0,
            rejectionReason = snapshot.rejectionReason ?: prediction?.quality?.reason,
            modelVersion = prediction?.modelVersion,
            preprocessingFingerprint = snapshot.processingFingerprint
        )
    }
}
