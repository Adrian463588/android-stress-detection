package com.stressdetector.wear.sensor

/**
 * Immutable value copied from one Samsung tracker callback.
 *
 * Samsung exposes a per-point status alongside the raw ADC value.  Keeping it
 * at the callback boundary prevents the transport layer from treating sensor
 * conflicts/errors as valid physiology.  Status zero is the documented
 * normal value for the individual PPG trackers. A missing status is kept as
 * unavailable and is rejected; it must never become an implicit good sample.
 */
data class PpgSensorSample(
    val timestamp: Long,
    val value: Int,
    val statusCode: Int? = null,
    val statusMeaning: String = statusMeaningFor(statusCode),
    val accepted: Boolean = statusCode == NORMAL_STATUS
) {
    init {
        require(!accepted || statusCode == NORMAL_STATUS) {
            "only an explicit normal Samsung status may be accepted"
        }
    }

    companion object {
        const val NORMAL_STATUS: Int = 0
        private const val SENSOR_ERROR_STATUS: Int = -1
        private const val HIGHER_PRIORITY_STATUS: Int = -999
        private const val UNSUPPORTED_STATUS: Int = 500

        fun statusMeaningFor(statusCode: Int?): String = when (statusCode) {
            null -> "status_unavailable"
            NORMAL_STATUS -> "normal"
            SENSOR_ERROR_STATUS -> "sensor_error"
            HIGHER_PRIORITY_STATUS -> "higher_priority_sensor"
            UNSUPPORTED_STATUS -> "status_unsupported_by_watch_software"
            else -> "sensor_status_$statusCode"
        }
    }
}
