package com.stressdetector.shared

/** Observed Watch accelerometer axes in m/s² on the session's monotonic timeline. */
data class AccelerometerSample(val sensorTimestampNanos: Long, val elapsedMillis: Long, val x: Float, val y: Float, val z: Float) {
    init {
        require(sensorTimestampNanos > 0L) { "accelerometer timestamp must be positive" }
        require(elapsedMillis >= 0L) { "accelerometer elapsed time must be non-negative" }
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "accelerometer axes must be finite" }
    }
}
