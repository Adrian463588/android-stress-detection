package com.stressdetector.wear.sensor

import com.samsung.android.service.health.tracking.HealthTracker

/** Shared callback adapter for the three PDSatu-compatible PPG trackers. */
open class PpgIndividualListener(
    private val spec: PpgTrackerSpec,
    private val onSamples: (List<PpgSensorSample>) -> Unit,
    private val onError: (String) -> Unit
) : BaseSamsungListener() {
    init {
        setTrackerEventListener(object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints: List<com.samsung.android.service.health.tracking.data.DataPoint>) {
                val samples = dataPoints.mapNotNull { point ->
                    val value = runCatching { spec.valueReader(point) }.getOrNull()
                    if (value == null) {
                        onError("${spec.channel.assetSuffix} value unavailable")
                        return@mapNotNull null
                    }
                    val status = runCatching { spec.statusReader(point) }.getOrNull()
                    PpgSensorSample(
                        timestamp = point.timestamp,
                        value = value,
                        statusCode = status,
                        statusMeaning = PpgSensorSample.statusMeaningFor(status),
                        accepted = PpgStatusPolicy.isAccepted(status)
                    )
                }
                if (samples.isNotEmpty()) onSamples(samples)
            }

            override fun onFlushCompleted() = Unit

            override fun onError(error: HealthTracker.TrackerError) {
                onError("${spec.channel.assetSuffix} tracker error: $error")
            }
        })
    }
}
