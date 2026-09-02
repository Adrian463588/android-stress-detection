package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.domain.PredictionAvailability
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.StressPrediction
import kotlinx.coroutines.flow.StateFlow

/** Marker sources are physiological review aids, never a claim about truthfulness or diagnosis. */
enum class PhysiologicalMarkerType {
    /** Current channel rises against its own preceding temporal baseline. */
    TEMPORAL_BASELINE_AROUSAL,

    /** Green and IR disagree at the same observation time; no fusion is performed. */
    CHANNEL_AGREEMENT,

    /** BPM trajectory marker; [DivergenceEvent.bpmTrend] carries the direction. */
    BPM_TRAJECTORY,

    // Persisted values retained for backwards-compatible history rendering.
    MODEL_AROUSAL,
    BPM_RISE,
    BPM_RECOVERY
}

/**
 * Domain model for a real-time physiological marker.
 *
 * `DivergenceEvent` remains as a compatibility name for persisted-session
 * callers while the feature uses the clinically safer marker terminology.
 */
data class DivergenceEvent(
    val id: String,
    val sessionId: String,
    val timestampMs: Long,
    /** Nullable when the marker is BPM/acoustic evidence without a model probability. */
    val currentStressProbability: Float?,
    val baselineStressProbability: Float?,
    val divergenceDelta: Float?,
    val isElevatedArousal: Boolean,
    val copyText: String = "Physiological arousal marker",
    val channel: PpgType = PpgType.GREEN,
    val markerElapsedMillis: Long = timestampMs,
    val episodeStartElapsedMillis: Long = markerElapsedMillis,
    val episodeEndElapsedMillis: Long = markerElapsedMillis,
    val contextWindowStartElapsedMillis: Long = 0L,
    val contextWindowEndElapsedMillis: Long = markerElapsedMillis,
    val confidence: Float = 0f,
    val signalQuality: Float = 0f,
    val sourceWindowId: String? = null,
    val transcriptSegmentId: String? = null,
    val markerType: PhysiologicalMarkerType = PhysiologicalMarkerType.TEMPORAL_BASELINE_AROUSAL,
    val bpm: Float? = null,
    val baselineBpm: Float? = null,
    val peakBpm: Float? = null,
    val bpmDelta: Float? = null,
    val bpmTrend: String? = null,
    val sourceChannels: Set<PpgType> = setOf(channel),
    val markerConfigVersion: String = "physiological-markers-v1"
)

typealias PhysiologicalMarker = DivergenceEvent

/** Readiness dimensions intentionally kept independent for the live UI. */
enum class AcquisitionReadiness {
    CONNECTING,
    STREAMING,
    DRAINING,
    OFFLINE
}

enum class SensorQualityReadiness {
    NO_DATA,
    COLLECTING,
    GOOD,
    GAP,
    INVALID_STATUS
}

enum class BpmReadiness {
    WARMING_UP,
    READY,
    INSUFFICIENT_SIGNAL
}

enum class StressWindowReadiness {
    WARMING_UP,
    READY,
    INSUFFICIENT_SIGNAL,
    REJECTED
}

data class SignalReadinessState(
    val acquisition: AcquisitionReadiness = AcquisitionReadiness.OFFLINE,
    val sensorQuality: SensorQualityReadiness = SensorQualityReadiness.NO_DATA,
    val bpm: BpmReadiness = BpmReadiness.WARMING_UP,
    val stressModel: StressWindowReadiness = StressWindowReadiness.WARMING_UP
) {
    companion object {
        /** Projects only observed quality/rejection evidence; no readiness is fabricated. */
        fun from(availability: PredictionAvailability, qualityScore: Float, rejectionReason: String?): SignalReadinessState {
            val hasQuality = qualityScore > 0f
            val rejection = rejectionReason.orEmpty()
            val invalidStatus = rejection.contains("status", ignoreCase = true)
            val gap = rejection.contains("gap", ignoreCase = true)
            return SignalReadinessState(
                acquisition = acquisitionReadiness(availability, hasQuality),
                sensorQuality = sensorQualityReadiness(availability, hasQuality, invalidStatus, gap),
                bpm = bpmReadiness(availability, invalidStatus, gap),
                stressModel = stressReadiness(availability)
            )
        }

        private fun acquisitionReadiness(availability: PredictionAvailability, hasQuality: Boolean): AcquisitionReadiness = when {
            availability is PredictionAvailability.Unavailable || availability is PredictionAvailability.InferenceError ->
                AcquisitionReadiness.OFFLINE
            availability is PredictionAvailability.WarmingUp && !hasQuality -> AcquisitionReadiness.CONNECTING
            else -> AcquisitionReadiness.STREAMING
        }

        private fun sensorQualityReadiness(
            availability: PredictionAvailability,
            hasQuality: Boolean,
            invalidStatus: Boolean,
            gap: Boolean
        ): SensorQualityReadiness = when {
            invalidStatus -> SensorQualityReadiness.INVALID_STATUS
            gap -> SensorQualityReadiness.GAP
            hasQuality -> SensorQualityReadiness.GOOD
            availability is PredictionAvailability.WarmingUp -> SensorQualityReadiness.COLLECTING
            else -> SensorQualityReadiness.NO_DATA
        }

        private fun bpmReadiness(availability: PredictionAvailability, invalidStatus: Boolean, gap: Boolean): BpmReadiness = when {
            invalidStatus || gap -> BpmReadiness.INSUFFICIENT_SIGNAL
            availability is PredictionAvailability.Ready -> BpmReadiness.READY
            availability is PredictionAvailability.Unavailable ||
                availability is PredictionAvailability.InferenceError ||
                availability is PredictionAvailability.Rejected -> BpmReadiness.INSUFFICIENT_SIGNAL
            else -> BpmReadiness.WARMING_UP
        }

        private fun stressReadiness(availability: PredictionAvailability): StressWindowReadiness = when (availability) {
            is PredictionAvailability.WarmingUp -> StressWindowReadiness.WARMING_UP
            is PredictionAvailability.Ready -> StressWindowReadiness.READY
            is PredictionAvailability.Rejected -> StressWindowReadiness.REJECTED
            is PredictionAvailability.Unavailable,
            is PredictionAvailability.InferenceError -> StressWindowReadiness.INSUFFICIENT_SIGNAL
        }
    }
}

data class LiveChannelState(
    val channel: PpgType,
    val availability: PredictionAvailability = PredictionAvailability.WarmingUp(0, 0),
    val prediction: StressPrediction? = null,
    val qualityScore: Float = 0f,
    val lastWindowEndElapsedMillis: Long? = null,
    val rejectionReason: String? = null
) {
    val readiness: SignalReadinessState
        get() = SignalReadinessState.from(availability, qualityScore, rejectionReason)
}

/**
 * Immutable summary of observed, quality-approved BPM estimates.
 *
 * Green and IR summaries are intentionally kept in separate map entries. An
 * empty channel has no summary entry; null/default BPM values are never
 * fabricated.
 */
data class BpmSummary(
    val meanBpm: Float?,
    val medianBpm: Float?,
    val minBpm: Float?,
    val maxBpm: Float?,
    val observationCount: Int,
    val validPeakCount: Int
)

/**
 * Domain model for real-time monitoring state.
 */
data class LiveMonitorState(
    val isMonitoring: Boolean = false,
    val elapsedSeconds: Long = 0L,
    /** No sensor window exists until the active session accepts one. */
    val currentQualityScore: Float = 0f,
    val isWindowValid: Boolean = false,
    val latestPrediction: StressPrediction? = null,
    /** Latest validated BPM estimate available to the live UI, when exposed by the marker backend. */
    val latestBpm: Float? = null,
    /** Per-channel BPM snapshots; Green and IR must never be merged implicitly. */
    val latestBpmByChannel: Map<PpgType, Float> = emptyMap(),
    /** Running observed-only summary per channel. */
    val bpmSummaryByChannel: Map<PpgType, BpmSummary> = emptyMap(),
    val activeDivergences: List<DivergenceEvent> = emptyList(),
    val errorMessage: String? = null,
    val requiredChannels: Set<PpgType> = emptySet(),
    val missingChannels: Set<PpgType> = emptySet(),
    val rejectionReason: String? = null,
    val latestWindowTimestampMs: Long? = null,
    val predictionAvailability: PredictionAvailability = PredictionAvailability.WarmingUp(0, 0),
    val channelStates: Map<PpgType, LiveChannelState> = emptyMap()
)

data class LiveMonitorStopSummary(
    val sessionId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val acceptedWindowCount: Int,
    val rejectedWindowCount: Int,
    val divergenceCount: Int,
    val averageStressProbability: Float?,
    /** Final observed-only BPM evidence; absent channels remain absent. */
    val bpmSummaryByChannel: Map<PpgType, BpmSummary> = emptyMap()
)

/**
 * Interface-First real-time sliding-window PPG inference engine.
 */
interface RealtimeInferenceEngine {
    val monitorState: StateFlow<LiveMonitorState>
    fun startMonitoring(sessionId: String): Boolean
    fun processPpgWindows(windows: Map<PpgType, WindowSnapshot>)
    fun stopMonitoring(): LiveMonitorStopSummary?
}

/**
 * Interface-First shared Annotation Repository for Verbatim & Live Monitor annotations.
 */
data class DomainAnnotation(
    val id: String,
    val sessionId: String,
    val timestampMs: Long,
    val text: String,
    val author: String, // "user" or "ai_suggestion"
    val citationId: String? = null
)

interface AnnotationRepository {
    suspend fun getAnnotationsForSession(sessionId: String): List<DomainAnnotation>
    suspend fun addAnnotation(annotation: DomainAnnotation)
    suspend fun deleteAnnotation(annotationId: String)
    suspend fun deleteAnnotationsForSession(sessionId: String)
}
