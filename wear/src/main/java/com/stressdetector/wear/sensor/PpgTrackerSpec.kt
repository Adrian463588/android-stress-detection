package com.stressdetector.wear.sensor

import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import com.stressdetector.shared.PpgType

/**
 * Compatibility descriptor for one individual Samsung PPG tracker.
 *
 * The individual tracker APIs are deprecated by Samsung, but this descriptor
 * keeps the PDSatu contract explicit and prevents three listeners from
 * drifting apart. No continuous-tracker fallback is selected here.
 */
data class PpgTrackerSpec(
    val channel: PpgType,
    val trackerType: HealthTrackerType,
    val valueKeyName: String,
    val statusKeyName: String,
    val expectedRateHz: Double,
    val batchSize: Int,
    val valueReader: (DataPoint) -> Int?,
    val statusReader: (DataPoint) -> Int?
) {
    init {
        require(channel.isAcquisitionChannel) { "tracker must be an acquisition channel" }
        require(expectedRateHz.isFinite() && expectedRateHz > 0.0) { "invalid tracker rate" }
        require(batchSize > 0) { "invalid tracker batch size" }
    }

    @Suppress("DEPRECATION")
    companion object {
        val green = PpgTrackerSpec(
            channel = PpgType.GREEN,
            trackerType = HealthTrackerType.PPG_GREEN,
            valueKeyName = "PpgGreenSet.PPG_GREEN",
            statusKeyName = "PpgGreenSet.STATUS",
            expectedRateHz = 25.0,
            batchSize = 300,
            valueReader = { point -> runCatching { point.getValue(ValueKey.PpgGreenSet.PPG_GREEN) }.getOrNull() },
            statusReader = { point -> runCatching { point.getValue(ValueKey.PpgGreenSet.STATUS) }.getOrNull() }
        )

        val ir = PpgTrackerSpec(
            channel = PpgType.IR,
            trackerType = HealthTrackerType.PPG_IR,
            valueKeyName = "PpgIrSet.PPG_IR",
            statusKeyName = "PpgIrSet.STATUS",
            expectedRateHz = 100.0,
            batchSize = 300,
            valueReader = { point -> runCatching { point.getValue(ValueKey.PpgIrSet.PPG_IR) }.getOrNull() },
            statusReader = { point -> runCatching { point.getValue(ValueKey.PpgIrSet.STATUS) }.getOrNull() }
        )

        val red = PpgTrackerSpec(
            channel = PpgType.RED,
            trackerType = HealthTrackerType.PPG_RED,
            valueKeyName = "PpgRedSet.PPG_RED",
            statusKeyName = "PpgRedSet.STATUS",
            expectedRateHz = 100.0,
            batchSize = 300,
            valueReader = { point -> runCatching { point.getValue(ValueKey.PpgRedSet.PPG_RED) }.getOrNull() },
            statusReader = { point -> runCatching { point.getValue(ValueKey.PpgRedSet.STATUS) }.getOrNull() }
        )

        val individual: List<PpgTrackerSpec> = listOf(green, ir, red)
    }
}

/** Pure Samsung status gate; status 0 is the only accepted status. */
object PpgStatusPolicy {
    const val NORMAL_STATUS: Int = 0

    fun isAccepted(statusCode: Int?): Boolean = statusCode == NORMAL_STATUS

    fun rejectionReason(statusCode: Int?): String = if (isAccepted(statusCode)) {
        ""
    } else {
        PpgSensorSample.statusMeaningFor(statusCode)
    }
}
