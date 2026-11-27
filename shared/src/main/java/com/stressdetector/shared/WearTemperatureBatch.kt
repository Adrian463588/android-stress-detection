package com.stressdetector.shared

/**
 * Raw Samsung Health Sensor SDK skin temperature observation paired with ambient context
 * and native timestamp provenance. Never fused into PPG stress inference.
 */
data class SkinTemperatureReading(
    val sensorTimestampEpochMillis: Long,
    val elapsedMillis: Long,
    val skinTemperatureC: Float?,
    val ambientTemperatureC: Float?,
    val status: Int,
    val isValid: Boolean,
    val source: String = SOURCE_WATCH_SDK_SKIN_TEMP,
    val clockUncertaintyMillis: Long? = null
) {
    init {
        require(elapsedMillis >= 0L) { "elapsedMillis must be non-negative" }
        require(skinTemperatureC == null || skinTemperatureC.isFinite()) {
            "skinTemperatureC must be finite when present"
        }
        require(ambientTemperatureC == null || ambientTemperatureC.isFinite()) {
            "ambientTemperatureC must be finite when present"
        }
        require(source.isNotBlank()) { "source must not be blank" }
        require(clockUncertaintyMillis == null || clockUncertaintyMillis >= 0L) {
            "clockUncertaintyMillis must be non-negative when present"
        }
    }

    companion object {
        const val SOURCE_WATCH_SDK_SKIN_TEMP = "WATCH_SDK_SKIN_TEMP"
        const val STATUS_SUCCESS = 0
    }
}

/**
 * Immutable session-scoped Wear Data Layer packet for Watch skin & ambient temperature.
 */
data class WearTemperatureBatch(
    val sessionId: String,
    val sessionNonce: String = "",
    val generation: Long = 0L,
    val sequenceNumber: Long = 1L,
    val peerNodeId: String = "",
    val readings: List<SkinTemperatureReading>,
    val sentAtEpochMillis: Long = 0L
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(generation >= 0L) { "generation must be non-negative" }
        require(sequenceNumber >= 1L) { "sequenceNumber must be >= 1" }
        require(readings.isNotEmpty()) { "readings must not be empty" }
        require(readings.size <= MAX_READINGS_PER_BATCH) { "readings exceed max batch limit" }
    }

    companion object {
        const val MAX_READINGS_PER_BATCH = 64
    }
}
