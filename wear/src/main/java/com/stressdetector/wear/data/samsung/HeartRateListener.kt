package com.stressdetector.wear.data.samsung

import android.os.Handler
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import com.stressdetector.shared.SensorTimestampContract
import com.stressdetector.shared.SensorTimestampNormalizer
import com.stressdetector.shared.SessionIdentity
import com.stressdetector.shared.WearHeartRateBatch
import java.util.concurrent.atomic.AtomicBoolean

internal data class HeartRateRawReading(
    val timestampMillis: Long,
    val bpm: Int,
    val heartRateStatus: Int,
    val ibiMillisList: List<Int> = emptyList(),
    val ibiStatusList: List<Int> = emptyList()
)

/**
 * Pure extractor for Samsung Health Sensor SDK Heart Rate & IBI readings.
 * Only active in Verbatim+ counseling mode; never replaces or feeds PPG inference.
 */
internal object SamsungHeartRateExtractor {
    const val MIN_PLAUSIBLE_BPM = 25
    const val MAX_PLAUSIBLE_BPM = 240

    fun selectSupportedTrackerType(supportedTypes: Collection<HealthTrackerType>): HealthTrackerType? = when {
        HealthTrackerType.HEART_RATE_CONTINUOUS in supportedTypes -> HealthTrackerType.HEART_RATE_CONTINUOUS
        HealthTrackerType.HEART_RATE in supportedTypes -> HealthTrackerType.HEART_RATE
        else -> null
    }

    fun parseReadings(
        readings: List<HeartRateRawReading>,
        identity: SessionIdentity,
        generation: Long,
        sessionStartEpochMillis: Long,
        normalizer: SensorTimestampNormalizer = SensorTimestampNormalizer(
            SensorTimestampContract.SAMSUNG_HEALTH_EPOCH_MILLIS
        )
    ): List<WearHeartRateBatch> {
        if (!identity.isCanonical || sessionStartEpochMillis <= 0L) return emptyList()
        return readings.mapNotNull { reading ->
            if (reading.bpm !in MIN_PLAUSIBLE_BPM..MAX_PLAUSIBLE_BPM) return@mapNotNull null
            if (reading.heartRateStatus < 0) return@mapNotNull null
            val normalizedEpoch = runCatching { normalizer.normalize(reading.timestampMillis) }.getOrNull()
                ?: return@mapNotNull null
            val elapsedMillis = (normalizedEpoch - sessionStartEpochMillis).coerceAtLeast(0L)
            WearHeartRateBatch(
                sessionId = identity.sessionId,
                sessionNonce = identity.nonce,
                generation = generation,
                sampleTimestampElapsedMillis = elapsedMillis,
                sampleTimestampEpochMillis = normalizedEpoch,
                bpm = reading.bpm,
                heartRateStatus = reading.heartRateStatus,
                ibiMillisList = reading.ibiMillisList.take(WearHeartRateBatch.MAX_IBI_ENTRIES),
                ibiStatusList = reading.ibiStatusList.take(WearHeartRateBatch.MAX_IBI_ENTRIES)
            )
        }
    }
}

/**
 * Optional, capability-gated Samsung Heart Rate & IBI tracker listener for [VERBATIM_AROUSAL].
 * Failures are isolated and never interrupt PPG acquisition.
 */
internal class HeartRateListener(
    private val tracker: HealthTracker,
    private val sensorHandler: Handler,
    private val identity: SessionIdentity,
    private val generation: Long,
    private val sessionStartEpochMillis: Long,
    private val onBatch: (WearHeartRateBatch) -> Unit
) {
    private val started = AtomicBoolean(false)
    private val normalizer = SensorTimestampNormalizer(SensorTimestampContract.SAMSUNG_HEALTH_EPOCH_MILLIS)

    private val eventListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(list: MutableList<DataPoint>) {
            val raw = list.mapNotNull { point ->
                val bpm = runCatching { point.getValue(ValueKey.HeartRateSet.HEART_RATE) }.getOrNull()
                    ?: return@mapNotNull null
                val status = runCatching { point.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) }.getOrNull()
                    ?: -1
                val ibis = runCatching { point.getValue(ValueKey.HeartRateSet.IBI_LIST) }.getOrNull().orEmpty()
                val ibiStatuses = runCatching { point.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) }
                    .getOrNull()
                    .orEmpty()
                HeartRateRawReading(
                    timestampMillis = point.timestamp,
                    bpm = bpm,
                    heartRateStatus = status,
                    ibiMillisList = ibis,
                    ibiStatusList = ibiStatuses
                )
            }
            SamsungHeartRateExtractor.parseReadings(
                readings = raw,
                identity = identity,
                generation = generation,
                sessionStartEpochMillis = sessionStartEpochMillis,
                normalizer = normalizer
            ).forEach(onBatch)
        }

        override fun onFlushCompleted() = Unit

        override fun onError(error: HealthTracker.TrackerError) = Unit
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        sensorHandler.post {
            if (!started.get()) return@post
            runCatching { tracker.setEventListener(eventListener) }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        sensorHandler.post {
            runCatching { tracker.unsetEventListener() }
        }
    }
}
