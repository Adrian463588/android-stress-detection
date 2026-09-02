package com.stressdetector.shared

/**
 * Explicit timestamp contract for Samsung Health Tracker samples.
 *
 * The session elapsed clock is supplied separately by the Watch monotonic
 * clock. This class only normalizes the sensor timestamp retained for audit
 * and transport; it never invents a timestamp when the source is invalid.
 */
enum class SensorTimestampUnit {
    MILLIS,
    MICROS,
    NANOS
}

enum class SensorTimestampTimebase {
    EPOCH,
    MONOTONIC
}

data class SensorTimestampContract(val unit: SensorTimestampUnit, val timebase: SensorTimestampTimebase) {
    companion object {
        /** Samsung Health Tracker DataPoint.timestamp contract used by this app. */
        val SAMSUNG_HEALTH_EPOCH_MILLIS = SensorTimestampContract(
            unit = SensorTimestampUnit.MILLIS,
            timebase = SensorTimestampTimebase.EPOCH
        )
    }
}

/** Pure, stateful-per-channel validator for source timestamps. */
class SensorTimestampNormalizer(private val contract: SensorTimestampContract) {
    private var previousNormalizedMillis: Long? = null

    fun normalize(rawTimestamp: Long): Long {
        require(rawTimestamp >= 0L) { "Sensor timestamp must be non-negative" }
        val normalizedMillis = when (contract.unit) {
            SensorTimestampUnit.MILLIS -> rawTimestamp
            SensorTimestampUnit.MICROS -> rawTimestamp / 1_000L
            SensorTimestampUnit.NANOS -> rawTimestamp / 1_000_000L
        }
        require(normalizedMillis >= 0L) { "Sensor timestamp conversion overflow" }
        if (contract.timebase == SensorTimestampTimebase.EPOCH) {
            require(normalizedMillis in MIN_EPOCH_MILLIS..MAX_EPOCH_MILLIS) {
                "Sensor timestamp is outside the configured epoch-millis range"
            }
        }
        require(previousNormalizedMillis?.let { normalizedMillis >= it } != false) {
            "Sensor timestamps must be monotonic after normalization"
        }
        previousNormalizedMillis = normalizedMillis
        return normalizedMillis
    }

    fun reset() {
        previousNormalizedMillis = null
    }

    private companion object {
        // Reject seconds, vendor-relative ticks, and accidental nanoseconds
        // under the explicit Samsung epoch-millis contract.
        const val MIN_EPOCH_MILLIS = 946_684_800_000L // 2000-01-01
        const val MAX_EPOCH_MILLIS = 4_102_444_800_000L // 2100-01-01
    }
}
