package com.stressdetector.wear.data.samsung

import android.os.Handler
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import com.stressdetector.shared.SensorTimestampContract
import com.stressdetector.shared.SensorTimestampNormalizer
import com.stressdetector.shared.SkinTemperatureReading
import java.util.concurrent.atomic.AtomicBoolean

internal data class SkinTemperatureRawReading(
    val timestampMillis: Long,
    val skinTemperatureC: Float?,
    val ambientTemperatureC: Float?,
    val status: Int
)

/**
 * Pure extractor for Samsung Health Sensor SDK continuous skin temperature.
 *
 * Strictly selects [HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS]; never polls
 * `SKIN_TEMPERATURE_ON_DEMAND` as a continuous fallback, never fabricates samples,
 * and preserves non-normal status readings with `isValid = false` for audit.
 */
internal object SamsungSkinTemperatureExtractor {
    const val MIN_PLAUSIBLE_SKIN_C = 15.0f
    const val MAX_PLAUSIBLE_SKIN_C = 45.0f
    const val MIN_PLAUSIBLE_AMBIENT_C = -20.0f
    const val MAX_PLAUSIBLE_AMBIENT_C = 60.0f

    fun selectSupportedTrackerType(supportedTypes: Collection<HealthTrackerType>): HealthTrackerType? =
        if (HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS in supportedTypes) {
            HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS
        } else {
            null
        }

    fun parseReadings(
        readings: List<SkinTemperatureRawReading>,
        sessionStartEpochMillis: Long,
        normalizer: SensorTimestampNormalizer = SensorTimestampNormalizer(
            SensorTimestampContract.SAMSUNG_HEALTH_EPOCH_MILLIS
        ),
        clockUncertaintyMillis: Long? = null
    ): List<SkinTemperatureReading> {
        if (sessionStartEpochMillis <= 0L || readings.isEmpty()) return emptyList()
        return readings.mapNotNull { reading ->
            val normalizedEpoch = runCatching { normalizer.normalize(reading.timestampMillis) }.getOrNull()
                ?: return@mapNotNull null
            val elapsedMillis = (normalizedEpoch - sessionStartEpochMillis).coerceAtLeast(0L)
            val finiteSkin = reading.skinTemperatureC?.takeIf { it.isFinite() }
            val finiteAmbient = reading.ambientTemperatureC?.takeIf { it.isFinite() }
            val skinPlausible = finiteSkin != null && finiteSkin in MIN_PLAUSIBLE_SKIN_C..MAX_PLAUSIBLE_SKIN_C
            val ambientValid = finiteAmbient?.takeIf { it in MIN_PLAUSIBLE_AMBIENT_C..MAX_PLAUSIBLE_AMBIENT_C }
            val statusValid = reading.status == SkinTemperatureReading.STATUS_SUCCESS
            val isValid = statusValid && skinPlausible
            SkinTemperatureReading(
                sensorTimestampEpochMillis = normalizedEpoch,
                elapsedMillis = elapsedMillis,
                skinTemperatureC = finiteSkin,
                ambientTemperatureC = ambientValid,
                status = reading.status,
                isValid = isValid,
                clockUncertaintyMillis = clockUncertaintyMillis
            )
        }
    }
}

/**
 * Optional, capability-gated Samsung continuous skin temperature listener for `VERBATIM_AROUSAL`.
 * Failures and unsupported hardware states are isolated and never interrupt PPG or HR acquisition.
 */
internal class SkinTemperatureListener(
    private val tracker: HealthTracker,
    private val sensorHandler: Handler,
    private val sessionStartEpochMillis: Long,
    private val onReadings: (List<SkinTemperatureReading>) -> Unit,
    private val onStatusUpdate: (String) -> Unit = {}
) {
    private val started = AtomicBoolean(false)
    private val normalizer = SensorTimestampNormalizer(SensorTimestampContract.SAMSUNG_HEALTH_EPOCH_MILLIS)

    private val eventListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(list: MutableList<DataPoint>) {
            val raw = list.map { point ->
                val skin = runCatching {
                    point.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
                }.getOrNull()
                val ambient = runCatching {
                    point.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
                }.getOrNull()
                val status = runCatching {
                    point.getValue(ValueKey.SkinTemperatureSet.STATUS)
                }.getOrNull() ?: Int.MIN_VALUE
                SkinTemperatureRawReading(
                    timestampMillis = point.timestamp,
                    skinTemperatureC = skin,
                    ambientTemperatureC = ambient,
                    status = status
                )
            }
            val parsed = SamsungSkinTemperatureExtractor.parseReadings(
                readings = raw,
                sessionStartEpochMillis = sessionStartEpochMillis,
                normalizer = normalizer
            )
            if (parsed.isNotEmpty()) {
                onReadings(parsed)
            }
        }

        override fun onFlushCompleted() = Unit

        override fun onError(error: HealthTracker.TrackerError) {
            onStatusUpdate("SKIN_TEMP_ERROR:${error.name}")
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        sensorHandler.post {
            if (!started.get()) return@post
            runCatching { tracker.setEventListener(eventListener) }
                .onSuccess { onStatusUpdate("SKIN_TEMP_ACTIVE") }
                .onFailure { error ->
                    started.set(false)
                    onStatusUpdate("SKIN_TEMP_START_FAILED:${error.message ?: "unknown"}")
                }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        sensorHandler.post {
            runCatching { tracker.unsetEventListener() }
        }
    }
}
