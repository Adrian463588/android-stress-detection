package com.stressdetector.mobile.data

import com.stressdetector.shared.PpgBatch
import com.stressdetector.shared.PpgTransportDiagnostics
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SessionConfig
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RollingPpgBuffer(private val type: PpgType, private val windowSeconds: Int = SessionConfig.INFERENCE_WINDOW_SECONDS) {
    // Lock guards all mutations and iterations of the two deques.
    // append() is called from a Wear receiver thread; snapshot()/recentChartData()
    // are called from the inference polling coroutine — both must not race.
    private val lock = ReentrantLock()

    private val timestamps = ArrayDeque<Long>()
    private val elapsedTimestamps = ArrayDeque<Long>()
    private val values = ArrayDeque<Int>()
    private val batchIds = ArrayDeque<String>()
    private var sessionId: String? = null
    private var sessionElapsedStartMillis = 0L
    private var sessionElapsedEndMillis = 0L

    val size: Int get() = lock.withLock { values.size }
    val requiredSamples: Int get() = type.samplingRateHz * windowSeconds

    /**
     * Readiness is duration-based because wearable cadence is measured from raw
     * timestamps and may be lower than the model's canonical resampling rate.
     * The preprocessor remains responsible for cadence, gap, and sample validation.
     */
    val isReady: Boolean get() = lock.withLock {
        timestamps.size >= 2 && durationMillis() >= requiredDurationMillis
    }

    fun append(batch: PpgBatch): Int {
        require(batch.type == type) { "Expected $type, got ${batch.type}" }
        lock.withLock {
            if (sessionId == null) sessionId = batch.sessionId
            require(sessionId == batch.sessionId) { "${type.name} batch belongs to another session" }
            require(
                batch.timestampsMillis.indices.drop(1).all { index ->
                    batch.timestampsMillis[index] >= batch.timestampsMillis[index - 1]
                }
            ) {
                "Out-of-order ${type.name} timestamps"
            }
            val previousLastTimestamp = timestamps.lastOrNull()
            require(
                previousLastTimestamp == null ||
                    batch.timestampsMillis.firstOrNull()?.let { it >= previousLastTimestamp } == true
            ) {
                "${type.name} batch timestamp regresses from $previousLastTimestamp"
            }
            val firstTimestamp = batch.timestampsMillis.firstOrNull()
            var accepted = 0
            batch.timestampsMillis.forEachIndexed { index, timestamp ->
                // Samsung Health Tracking callbacks may legitimately contain
                // equal timestamps. Keep acquisition evidence intact; canonical
                // resampling owns duplicate collapse later. A strict regression
                // is rejected above so it cannot be ACKed as if samples arrived.
                timestamps.addLast(timestamp)
                elapsedTimestamps.addLast(
                    if (firstTimestamp == null || batch.size == 1) {
                        batch.elapsedEndMillis
                    } else {
                        batch.elapsedStartMillis + (timestamp - firstTimestamp)
                    }.coerceAtLeast(0L)
                )
                values.addLast(batch.rawValues[index])
                batchIds.addLast(batch.effectiveBatchId)
                accepted += 1
            }
            if (accepted > 0) {
                sessionElapsedStartMillis = elapsedTimestamps.firstOrNull() ?: batch.elapsedStartMillis
                sessionElapsedEndMillis = elapsedTimestamps.lastOrNull() ?: batch.elapsedEndMillis
            }
            trim()
            // Derive elapsed bounds from the retained samples. Do not use
            // zero as an "unset" sentinel: a real session starts at 0 ms.
            sessionElapsedStartMillis = elapsedTimestamps.firstOrNull() ?: 0L
            sessionElapsedEndMillis = elapsedTimestamps.lastOrNull() ?: 0L
            return accepted
        }
    }

    fun clear() {
        lock.withLock {
            timestamps.clear()
            elapsedTimestamps.clear()
            values.clear()
            batchIds.clear()
            sessionElapsedStartMillis = 0L
            sessionElapsedEndMillis = 0L
            sessionId = null
        }
    }

    /** Returns [ChartData] for the most recent timestamp-bounded seconds of signal. */
    fun recentChartData(windowSec: Float = 5f): ChartData = lock.withLock {
        val allValues = values.toList()
        val allTimestamps = timestamps.toList()
        if (allValues.isEmpty()) return@withLock ChartData.EMPTY
        val latestTimestamp = allTimestamps.last()
        val cutoff = latestTimestamp - (windowSec * 1_000f).toLong().coerceAtLeast(0L)
        val firstIndex = allTimestamps.indexOfFirst { it >= cutoff }.let { index ->
            if (index < 0) 0 else index
        }
        val tail = allValues.subList(firstIndex, allValues.size)
        val timestampTail = allTimestamps.subList(firstIndex, allTimestamps.size)
        if (tail.isEmpty()) return@withLock ChartData.EMPTY

        val rawMin = tail.min().toFloat()
        val rawMax = tail.max().toFloat()
        val range = rawMax - rawMin
        val normalized = if (range == 0f) {
            FloatArray(tail.size) { 0.5f }
        } else {
            FloatArray(tail.size) { i -> (tail[i].toFloat() - rawMin) / range }
        }

        // Actual duration covered by the tail
        val actualSec = ((timestampTail.last() - timestampTail.first()).coerceAtLeast(0L)) / 1_000f
        ChartData(
            normalized = normalized,
            rawMin = rawMin,
            rawMax = rawMax,
            durationSec = actualSec,
            timestampsMillis = timestampTail.toLongArray(),
            rawValues = FloatArray(tail.size) { tail[it].toFloat() }
        )
    }

    /**
     * Returns an immutable snapshot of the full rolling window.
     * Thread-safe: copies both deques while holding [lock] so that
     * concurrent [append] calls cannot cause ConcurrentModificationException.
     */
    fun snapshot(): WindowSnapshot = lock.withLock {
        WindowSnapshot(
            type = type,
            timestampsMillis = timestamps.toLongArray(),
            rawValues = values.toIntArray(),
            sessionElapsedStartMillis = sessionElapsedStartMillis,
            sessionElapsedEndMillis = sessionElapsedEndMillis,
            elapsedTimestampsMillis = elapsedTimestamps.toLongArray(),
            timestampBatchIds = batchIds.toStringArray(),
            sessionId = sessionId
        )
    }

    /** Must only be called while [lock] is held. */
    private fun trim() {
        val latestTimestamp = timestamps.lastOrNull() ?: return
        val cutoff = latestTimestamp - retentionMillis
        while (timestamps.firstOrNull()?.let { it < cutoff } == true) {
            values.removeFirst()
            batchIds.removeFirst()
            elapsedTimestamps.removeFirst()
            timestamps.removeFirst()
        }
    }

    /** Nominal 180 s data contains N samples over N-1 intervals. */
    private fun durationMillis(): Long = ((timestamps.lastOrNull() ?: 0L) - (timestamps.firstOrNull() ?: 0L)).coerceAtLeast(0L)

    private fun ArrayDeque<Long>.toLongArray(): LongArray {
        val out = LongArray(size)
        forEachIndexed { index, value -> out[index] = value }
        return out
    }

    private fun ArrayDeque<Int>.toIntArray(): IntArray {
        val out = IntArray(size)
        forEachIndexed { index, value -> out[index] = value }
        return out
    }

    private fun ArrayDeque<String>.toStringArray(): Array<String> = Array(size) { index -> elementAt(index) }

    private val requiredDurationMillis: Long =
        ((requiredSamples - 1) * 1_000.0 / type.samplingRateHz).toLong()
    private val retentionMillis: Long = requiredDurationMillis + RETENTION_JITTER_MARGIN_MILLIS

    private companion object {
        const val RETENTION_JITTER_MARGIN_MILLIS = 500L
    }
}

/**
 * Immutable chart payload produced per-poll by [RollingPpgBuffer.recentChartData].
 *
 * @param normalized  0–1 floats ready for canvas rendering.
 * @param rawMin      Minimum raw ADC value in the window (for Y-axis label).
 * @param rawMax      Maximum raw ADC value in the window (for Y-axis label).
 * @param durationSec Actual time span covered, ≤ requested window (e.g. during warm-up).
 */
data class ChartData(
    val normalized: FloatArray,
    val rawMin: Float,
    val rawMax: Float,
    val durationSec: Float,
    val timestampsMillis: LongArray = LongArray(0),
    val rawValues: FloatArray = FloatArray(0)
) {
    val isEmpty: Boolean get() = normalized.isEmpty()

    companion object {
        val EMPTY = ChartData(FloatArray(0), 0f, 0f, 0f)
    }

    // Suppress auto-generated equals/hashCode warnings for FloatArray field
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChartData) return false
        return rawMin == other.rawMin &&
            rawMax == other.rawMax &&
            durationSec == other.durationSec &&
            normalized.contentEquals(other.normalized) &&
            timestampsMillis.contentEquals(other.timestampsMillis) &&
            rawValues.contentEquals(other.rawValues)
    }
    override fun hashCode(): Int {
        var result = normalized.contentHashCode()
        result = 31 * result + timestampsMillis.contentHashCode()
        result = 31 * result + rawValues.contentHashCode()
        return result
    }
}

data class WindowSnapshot(
    val type: PpgType,
    val timestampsMillis: LongArray,
    val rawValues: IntArray,
    val sessionElapsedStartMillis: Long = 0L,
    val sessionElapsedEndMillis: Long = 0L,
    val elapsedTimestampsMillis: LongArray = LongArray(0),
    /** Batch identity for each timestamp when the repository has it. */
    val timestampBatchIds: Array<String> = emptyArray(),
    val sessionId: String? = null,
    val windowId: String? = null,
    /** Transport evidence is observational and never changes model samples. */
    val transportDiagnostics: PpgTransportDiagnostics? = null,
    /** Actual Watch motion evidence, attached only to lease-scoped Home snapshots. */
    val accelerometerSamples: List<com.stressdetector.shared.AccelerometerSample> = emptyList()
) {
    val size: Int get() = rawValues.size
}
