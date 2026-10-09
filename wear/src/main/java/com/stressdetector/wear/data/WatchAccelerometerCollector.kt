package com.stressdetector.wear.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.stressdetector.shared.AccelerometerSample
import com.stressdetector.shared.PpgBatch
import java.util.ArrayDeque

/** Observed Android accelerometer samples, aligned to the PPG session's monotonic start. */
internal class WatchAccelerometerCollector(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val samples = ArrayDeque<AccelerometerSample>()
    private var startNanos: Long = 0L
    private var registered = false

    @Synchronized
    fun start(sessionStartNanos: Long): Boolean {
        reset()
        startNanos = sessionStartNanos
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            Log.w(TAG, "ACCEL_UNAVAILABLE: no Android accelerometer")
            return false
        }
        registered = runCatching { manager?.registerListener(this, sensor, SAMPLE_PERIOD_MICROS) == true }
            .getOrDefault(false)
        Log.i(TAG, if (registered) "ACCEL_REGISTERED" else "ACCEL_UNAVAILABLE: registration failed")
        return registered
    }

    @Synchronized
    fun stop() {
        if (registered) {
            manager?.unregisterListener(this)
            val observed = samples.toList()
            val maxGapMillis = observed.zipWithNext().maxOfOrNull { (first, second) ->
                second.elapsedMillis - first.elapsedMillis
            } ?: 0L
            Log.i(
                TAG,
                "ACCEL_STOP samples=${observed.size} " +
                    "firstElapsed=${observed.firstOrNull()?.elapsedMillis ?: -1L} " +
                    "lastElapsed=${observed.lastOrNull()?.elapsedMillis ?: -1L} " +
                    "maxGapMillis=$maxGapMillis"
            )
        }
        registered = false
    }

    @Synchronized
    fun reset() {
        stop()
        samples.clear()
        startNanos = 0L
    }

    @Synchronized
    fun samplesBetween(startMillis: Long, endMillis: Long): List<AccelerometerSample> = samples.asSequence()
        .filter { it.elapsedMillis in startMillis..endMillis }
        .take(PpgBatch.MAX_ACCELEROMETER_SAMPLES)
        .toList()

    @Synchronized
    override fun onSensorChanged(event: SensorEvent) {
        if (!registered || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val observed = observedMotionSample(startNanos, event.timestamp, event.values) ?: return
        samples.addLast(observed)
        while (samples.size > MAX_RETAINED_SAMPLES) samples.removeFirst()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val TAG = "WatchMotion"
        const val SAMPLE_PERIOD_MICROS = 40_000 // Requested 25 Hz; actual cadence is measured from timestamps.
        const val MAX_RETAINED_SAMPLES = 10_000 // Bounded across a five-minute Home session.
    }
}

/** Pure timeline conversion used by the sensor callback; invalid observations are dropped. */
internal fun observedMotionSample(startNanos: Long, eventNanos: Long, values: FloatArray): AccelerometerSample? = when {
    eventNanos < startNanos || values.size < ACCEL_AXIS_COUNT -> null
    !values[0].isFinite() || !values[1].isFinite() || !values[2].isFinite() -> null
    else -> AccelerometerSample(eventNanos, (eventNanos - startNanos) / NANOS_PER_MILLI, values[0], values[1], values[2])
}

private const val ACCEL_AXIS_COUNT = 3
private const val NANOS_PER_MILLI = 1_000_000L
