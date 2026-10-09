package com.stressdetector.wear.data

import android.content.Context
import android.util.Log
import com.stressdetector.shared.PpgBatchPolicy
import com.stressdetector.shared.PpgTerminalReceipt
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SensorTimestampContract
import com.stressdetector.shared.SensorTimestampNormalizer
import com.stressdetector.shared.SessionIdentity
import com.stressdetector.wear.sensor.PpgSensorSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Immutable sample copied from a Samsung callback before background I/O. */
data class PpgRawSample(
    val type: PpgType,
    val timestamp: Long,
    val value: Int,
    val statusCode: Int? = null,
    val statusMeaning: String = PpgSensorSample.statusMeaningFor(statusCode),
    val accepted: Boolean = statusCode == PpgSensorSample.NORMAL_STATUS
) {
    init {
        require(!accepted || statusCode == PpgSensorSample.NORMAL_STATUS) {
            "only an explicit normal Samsung status may be accepted"
        }
    }
}

/** Immutable copy of one Samsung callback. Durable I/O consumes it off callback thread. */
class PpgCallbackBatch private constructor(val samples: List<PpgRawSample>, val receivedAtElapsedRealtimeMillis: Long) {
    companion object {
        fun copyOf(samples: Collection<PpgRawSample>): PpgCallbackBatch = PpgCallbackBatch(
            samples = samples.map {
                PpgRawSample(
                    type = it.type,
                    timestamp = it.timestamp,
                    value = it.value,
                    statusCode = it.statusCode,
                    statusMeaning = it.statusMeaning,
                    accepted = it.accepted
                )
            },
            receivedAtElapsedRealtimeMillis = System.nanoTime() / 1_000_000L
        )
    }
}

/** Buffers raw Watch values; only canonical phone preprocessing may resample them. */
class PpgSensorRepository(context: Context, private val onFatalError: (String) -> Unit = {}) {
    private val sender = PpgBatchSender(context)
    private val accelerometer = WatchAccelerometerCollector(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ingress = Channel<IngressEvent>(capacity = INGRESS_CAPACITY)

    @Volatile private var accepting = false

    @Volatile private var sessionIdentity: SessionIdentity? = null

    @Volatile private var startedElapsedRealtimeMillis: Long = 0L
    private var startedAtEpochMillis: Long = 0L
    private var elapsedTimeline: PpgElapsedTimeline? = null
    private val samples = PpgSampleBuffers()
    private val timestampNormalizers = PpgType.acquisitionChannels.associateWith {
        SensorTimestampNormalizer(SensorTimestampContract.SAMSUNG_HEALTH_EPOCH_MILLIS)
    }
    private val sourceGaps = PpgType.acquisitionChannels.associateWith { SourceGapSummary(it.sourceRateHz) }

    init {
        scope.launch {
            for (event in ingress) {
                when (event) {
                    is IngressEvent.Samples -> appendOnConsumer(event)
                    is IngressEvent.Drain -> drainOnConsumer(event)
                }
            }
        }
    }

    @Suppress("LongParameterList") // Session identity, two clocks, peer and mode are one acquisition boundary.
    @Synchronized
    fun startSession(
        identity: SessionIdentity,
        startedElapsedRealtimeMillis: Long,
        startedAtEpochMillis: Long,
        phoneNodeId: String?,
        generation: Long,
        collectMotion: Boolean = false
    ) {
        require(identity.isCanonical) { "Wear PPG requires a canonical SessionIdentity" }
        require(startedAtEpochMillis > 0L) { "startedAtEpochMillis must be a valid epoch timestamp" }
        sender.beginSession(identity, phoneNodeId, generation)
        this.sessionIdentity = identity
        this.startedElapsedRealtimeMillis = startedElapsedRealtimeMillis
        this.startedAtEpochMillis = startedAtEpochMillis
        this.elapsedTimeline = PpgElapsedTimeline(startedAtEpochMillis)
        samples.clear()
        timestampNormalizers.values.forEach(SensorTimestampNormalizer::reset)
        sourceGaps.values.forEach(SourceGapSummary::clear)
        if (collectMotion) {
            accelerometer.start(TimeUnit.MILLISECONDS.toNanos(startedElapsedRealtimeMillis))
        } else {
            accelerometer.reset()
        }
        accepting = true
    }

    /** Starts the monotonic session clock only after Health Tracker is ready. */
    @Synchronized
    fun markSessionReady(startedElapsedRealtimeMillis: Long) {
        require(startedElapsedRealtimeMillis >= 0L) { "startedElapsedRealtimeMillis must be non-negative" }
        this.startedElapsedRealtimeMillis = startedElapsedRealtimeMillis
    }

    fun onGreen(timestamp: Long, value: Int): Boolean = onSamples(
        listOf(PpgRawSample(PpgType.GREEN, timestamp, value, statusCode = PpgSensorSample.NORMAL_STATUS, accepted = true))
    )

    fun onIr(timestamp: Long, value: Int): Boolean = onSamples(
        listOf(PpgRawSample(PpgType.IR, timestamp, value, statusCode = PpgSensorSample.NORMAL_STATUS, accepted = true))
    )

    fun onRed(timestamp: Long, value: Int): Boolean = onSamples(
        listOf(PpgRawSample(PpgType.RED, timestamp, value, statusCode = PpgSensorSample.NORMAL_STATUS, accepted = true))
    )

    fun onGreenBatch(samples: List<PpgSensorSample>): Boolean = onCallbackBatch(
        PpgCallbackBatch.copyOf(
            samples.map {
                PpgRawSample(
                    type = PpgType.GREEN,
                    timestamp = it.timestamp,
                    value = it.value,
                    statusCode = it.statusCode,
                    statusMeaning = it.statusMeaning,
                    accepted = it.accepted
                )
            }
        )
    )

    fun onIrBatch(samples: List<PpgSensorSample>): Boolean = onCallbackBatch(
        PpgCallbackBatch.copyOf(
            samples.map {
                PpgRawSample(
                    type = PpgType.IR,
                    timestamp = it.timestamp,
                    value = it.value,
                    statusCode = it.statusCode,
                    statusMeaning = it.statusMeaning,
                    accepted = it.accepted
                )
            }
        )
    )

    fun onRedBatch(samples: List<PpgSensorSample>): Boolean = onCallbackBatch(
        PpgCallbackBatch.copyOf(
            samples.map {
                PpgRawSample(
                    type = PpgType.RED,
                    timestamp = it.timestamp,
                    value = it.value,
                    statusCode = it.statusCode,
                    statusMeaning = it.statusMeaning,
                    accepted = it.accepted
                )
            }
        )
    )

    /** One immutable ingress event per Samsung callback prevents per-sample queue overflow. */
    fun onSamples(callbackSamples: List<PpgRawSample>): Boolean = onCallbackBatch(PpgCallbackBatch.copyOf(callbackSamples))

    fun onCallbackBatch(callbackBatch: PpgCallbackBatch): Boolean {
        if (callbackBatch.samples.isEmpty()) return true
        val activeIdentity = sessionIdentity ?: return false
        if (!accepting) return false
        val type = callbackBatch.samples.first().type
        if (callbackBatch.samples.any { it.type != type }) {
            WearTrackingState.recordRejected(type, "mixed_channel_callback")
            return false
        }
        WearTrackingState.recordCallback(
            type = type,
            sampleCount = callbackBatch.samples.size,
            receivedAtElapsedRealtimeMillis = callbackBatch.receivedAtElapsedRealtimeMillis
        )
        val accepted = ingress.trySend(
            IngressEvent.Samples(activeIdentity, callbackBatch)
        ).isSuccess
        if (!accepted) {
            WearTrackingState.recordTransportGap(type, "ingress_queue_full")
            WearTrackingState.message("PPG callback batch dropped: ingress queue is full")
        }
        // A callback containing only invalid Samsung statuses was delivered to
        // the consumer for diagnostics, but it is not evidence that this
        // channel became ready.
        return accepted && callbackBatch.samples.any(PpgRawSample::accepted)
    }

    fun flushAll(onComplete: ((Boolean) -> Unit)? = null) {
        accepting = false
        accelerometer.stop()
        scope.launch {
            ingress.send(IngressEvent.Drain(onComplete ?: {}))
        }
    }

    fun expectedSequences(sessionId: String): PpgExpectedSequences = sender.expectedSequences(sessionId)

    fun stopMotionCollection() = accelerometer.stop()

    fun awaitTerminalAcknowledgement(receipt: PpgTerminalReceipt, nodeId: String?, onComplete: (Boolean) -> Unit) =
        sender.awaitTerminalAcknowledgement(receipt, nodeId, onComplete = onComplete)

    private fun appendOnConsumer(event: IngressEvent.Samples) {
        if (event.identity != sessionIdentity) {
            event.batch.samples.forEach { sample ->
                WearTrackingState.recordRejected(sample.type, "stale_session_callback")
            }
            return
        }
        val byType = event.batch.samples.groupBy(PpgRawSample::type)
        byType.forEach { (type, channelSamples) ->
            val buffer = samples.forType(type)
            if (buffer.firstCallbackElapsedRealtimeMillis == null) {
                buffer.firstCallbackElapsedRealtimeMillis = event.batch.receivedAtElapsedRealtimeMillis
            }
            channelSamples.forEach(::appendSampleOnConsumer)
            if (shouldFlush(type, buffer, event.batch.receivedAtElapsedRealtimeMillis)) {
                flush(type)
            }
        }
    }

    @Suppress("ReturnCount")
    private fun appendSampleOnConsumer(sample: PpgRawSample) {
        if (!sample.accepted) {
            WearTrackingState.recordRejected(
                sample.type,
                "sensor_status_${sample.statusCode}:${sample.statusMeaning}",
                statusCode = sample.statusCode
            )
            return
        }
        val normalizedTimestamp = runCatching {
            requireNotNull(timestampNormalizers[sample.type]).normalize(sample.timestamp)
        }.getOrElse { error ->
            WearTrackingState.recordRejected(sample.type, error.message ?: "invalid_timestamp")
            WearTrackingState.message("Rejected ${sample.type.assetSuffix} timestamp: ${error.message}")
            return
        }
        val buffer = samples.forType(sample.type)
        val previousTimestamp = buffer.lastTimestamp
        if (previousTimestamp != null && normalizedTimestamp <= previousTimestamp) {
            WearTrackingState.recordRejected(sample.type, "non_monotonic_or_duplicate_timestamp")
            return
        }
        // Samsung can deliver many samples in one callback. Callback arrival
        // time is therefore not a sample timeline: it collapses an entire
        // batch to one instant and makes the phone reject the 240 s core.
        // Use the normalized epoch timestamp from the sensor as provenance
        // and derive monotonic session elapsed from the Watch session start.
        val elapsedMillis = requireNotNull(elapsedTimeline).elapsed(normalizedTimestamp)
        buffer.timestamps += normalizedTimestamp
        buffer.values += sample.value
        buffer.elapsedMillis += elapsedMillis
        buffer.lastTimestamp = normalizedTimestamp
        sourceGaps.getValue(sample.type).observe(normalizedTimestamp)
        WearTrackingState.recordAccepted(sample.type, sample.statusCode)
    }

    private fun shouldFlush(type: PpgType, buffer: MutableSampleBuffer, callbackReceivedAtElapsedRealtimeMillis: Long): Boolean {
        val firstCallback = buffer.firstCallbackElapsedRealtimeMillis ?: return false
        val callbackAge = (callbackReceivedAtElapsedRealtimeMillis - firstCallback).coerceAtLeast(0L)
        val sampleSpan = if (buffer.elapsedMillis.size > 1) {
            (buffer.elapsedMillis.last() - buffer.elapsedMillis.first()).coerceAtLeast(0L)
        } else {
            0L
        }
        return PpgBatchPolicy.shouldFlush(type, buffer.values.size, callbackAge, sampleSpan)
    }

    private fun flush(type: PpgType) {
        val activeSession = sessionIdentity?.sessionId ?: return
        val buffer = samples.forType(type)
        if (buffer.values.isEmpty()) return
        val timestamps = buffer.timestamps.toLongArray()
        val values = buffer.values.toIntArray()
        val startElapsed = buffer.elapsedMillis.first().coerceAtLeast(0L)
        val endElapsed = buffer.elapsedMillis.last().coerceAtLeast(startElapsed)
        val callbackReceivedAt = buffer.firstCallbackElapsedRealtimeMillis
            ?: monotonicNowMillis()
        runCatching {
            sender.send(
                type = type,
                timestamps = timestamps,
                values = values,
                sessionId = activeSession,
                elapsedMillis = endElapsed,
                elapsedStartMillis = startElapsed,
                elapsedEndMillis = endElapsed,
                callbackReceivedAtElapsedRealtimeMillis = callbackReceivedAt,
                accelerometerSamples = accelerometer.samplesBetween(startElapsed, endElapsed)
            )
            buffer.clear()
        }.onFailure { error ->
            WearTrackingState.recordTransportGap(type, error.message ?: "send_failed")
            buffer.clear()
            WearTrackingState.message("PPG transport gap: ${error.message}")
        }
    }

    private fun drainOnConsumer(event: IngressEvent.Drain) {
        flush(PpgType.GREEN)
        flush(PpgType.IR)
        flush(PpgType.RED)
        val telemetry = WearTrackingState.state.value.channelTelemetry
        val summaries = PpgType.acquisitionChannels.map { type ->
            val gaps = sourceGaps.getValue(type)
            val channel = telemetry[type]
            "channel=${type.assetSuffix} accepted=${channel?.acceptedSampleCount ?: 0} " +
                "invalidStatus=${channel?.invalidStatusCount ?: 0} " +
                "firstSourceDeltaMs=${gaps.firstTimestampMillis?.minus(startedAtEpochMillis)} " +
                "lastSourceDeltaMs=${gaps.lastTimestampMillis?.minus(startedAtEpochMillis)} " +
                "sourceMaxGapMs=${gaps.maxGapMillis} sourceGapsOverFour=${gaps.gapCount} " +
                "sendFailures=${channel?.transportGapCount ?: 0}"
        }
        sender.awaitFlush { settled ->
            summaries.forEach { summary -> Log.i(LOG_TAG, "$summary dispatchSettled=$settled") }
            event.onComplete(settled)
        }
    }

    private class SourceGapSummary(private val sourceRateHz: Int) {
        var firstTimestampMillis: Long? = null
            private set
        var lastTimestampMillis: Long? = null
            private set
        var maxGapMillis: Long = 0L
            private set
        var gapCount: Int = 0
            private set

        fun observe(timestampMillis: Long) {
            if (firstTimestampMillis == null) firstTimestampMillis = timestampMillis
            lastTimestampMillis?.let { previous ->
                val gap = timestampMillis - previous
                maxGapMillis = maxOf(maxGapMillis, gap)
                if (gap > MAX_CONSECUTIVE_INTERVALS * MILLIS_PER_SECOND / sourceRateHz) gapCount++
            }
            lastTimestampMillis = timestampMillis
        }

        fun clear() {
            firstTimestampMillis = null
            lastTimestampMillis = null
            maxGapMillis = 0L
            gapCount = 0
        }
    }

    private class PpgSampleBuffers {
        private val byType = PpgType.acquisitionChannels.associateWith { MutableSampleBuffer() }.toMutableMap()

        fun forType(type: PpgType): MutableSampleBuffer = requireNotNull(byType[type]) {
            "Only acquired PPG channels may be buffered"
        }

        fun clear() = byType.values.forEach(MutableSampleBuffer::clear)
    }

    private class MutableSampleBuffer {
        var firstCallbackElapsedRealtimeMillis: Long? = null
        var lastTimestamp: Long? = null
        val timestamps = mutableListOf<Long>()
        val values = mutableListOf<Int>()
        val elapsedMillis = mutableListOf<Long>()

        fun clear() {
            firstCallbackElapsedRealtimeMillis = null
            lastTimestamp = null
            timestamps.clear()
            values.clear()
            elapsedMillis.clear()
        }
    }

    private sealed interface IngressEvent {
        data class Samples(val identity: SessionIdentity, val batch: PpgCallbackBatch) : IngressEvent

        data class Drain(val onComplete: (Boolean) -> Unit) : IngressEvent
    }

    companion object {
        const val INGRESS_CAPACITY = 500
        private const val LOG_TAG = "WearPpg"
        private const val MAX_CONSECUTIVE_INTERVALS = 4L
        private const val MILLIS_PER_SECOND = 1_000L
    }

    private fun monotonicNowMillis(): Long = System.nanoTime() / 1_000_000L
}

/** Maps normalized Samsung epoch timestamps to the immutable session timeline. */
internal class PpgElapsedTimeline(private val sessionStartEpochMillis: Long) {
    init {
        require(sessionStartEpochMillis > 0L) { "sessionStartEpochMillis must be positive" }
    }

    fun elapsed(normalizedSourceTimestampMillis: Long): Long = (normalizedSourceTimestampMillis - sessionStartEpochMillis).coerceAtLeast(0L)
}
