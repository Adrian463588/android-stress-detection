package com.stressdetector.wear.data.samsung

import android.os.Handler
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import com.stressdetector.shared.PpgType
import com.stressdetector.wear.data.PpgCallbackBatch
import com.stressdetector.wear.data.PpgRawSample
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class SamsungPpgFailureKind {
    PERMISSION,
    SDK_POLICY,
    TRANSIENT
}

data class SamsungPpgFailure(val kind: SamsungPpgFailureKind, val message: String)

/**
 * Serial boundary around one Samsung PPG_CONTINUOUS Green+IR tracker.
 *
 * Samsung Health SDK status contract (PPG_CONTINUOUS, Galaxy Watch 5):
 * only status {@code 0} is a normal PPG callback. A non-zero status is not a
 * usable raw sample for this recording. It is kept only as a bounded
 * diagnostic, so invalid callbacks can never become chart, SQA, or ONNX input.
 */
class SamsungContinuousPpgSource(
    private val tracker: HealthTracker,
    private val sensorHandler: Handler,
    private val controlHandler: Handler,
    private val onSamples: (PpgCallbackBatch) -> Unit,
    private val onFailure: (SamsungPpgFailure) -> Unit,
    private val diagnostics: SamsungPpgCallbackDiagnostics = SamsungPpgCallbackDiagnostics(),
    private val onDiagnostic: (SamsungPpgSampleDiagnostic) -> Unit = {},
    private val onRegistered: () -> Unit = {},
    private val onUnregistered: () -> Unit = {}
) {
    private val started = AtomicBoolean(false)
    private val stopCompleted = AtomicBoolean(false)

    /** Invalidates callbacks from a cancelled/previous tracker registration. */
    private val listenerEpoch = AtomicLong(0L)
    private var stopCallback: ((Boolean) -> Unit)? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        stopCompleted.set(false)
        stopCallback = null
        val epoch = listenerEpoch.incrementAndGet()
        val listener = listenerFor(epoch)
        sensorHandler.post {
            if (!isActive(epoch)) return@post
            runCatching { tracker.setEventListener(listener) }
                .onSuccess {
                    if (isActive(epoch)) onRegistered()
                }
                .onFailure { error ->
                    if (!isActive(epoch)) return@onFailure
                    started.set(false)
                    listenerEpoch.incrementAndGet()
                    onUnregistered()
                    onFailure(
                        SamsungPpgFailure(
                            SamsungPpgFailureKind.TRANSIENT,
                            "Samsung PPG listener setup failed: ${error.message ?: error::class.java.simpleName}"
                        )
                    )
                }
        }
    }

    fun stopAndFlush(timeoutMillis: Long, onComplete: (Boolean) -> Unit) {
        if (!started.get()) {
            onComplete(true)
            return
        }
        val epoch = listenerEpoch.get()
        stopCompleted.set(false)
        stopCallback = onComplete
        controlHandler.postDelayed(
            { if (isActive(epoch)) completeStop(flushed = false, epoch = epoch) },
            timeoutMillis.coerceAtLeast(0L)
        )
        sensorHandler.post {
            val accepted = runCatching { tracker.flush() }.getOrDefault(false)
            if (!accepted) completeStop(flushed = false, epoch = epoch)
        }
    }

    fun cancel() {
        if (!started.compareAndSet(true, false)) return
        listenerEpoch.incrementAndGet()
        stopCallback = null
        onUnregistered()
        sensorHandler.post { runCatching { tracker.unsetEventListener() } }
    }

    private fun listenerFor(epoch: Long): HealthTracker.TrackerEventListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            if (!isActive(epoch)) return
            val accepted = ArrayList<PpgRawSample>(dataPoints.size * PpgType.acquisitionChannels.size)
            dataPoints.forEach { point ->
                if (isActive(epoch)) acceptPoint(point, accepted)
            }
            if (accepted.isNotEmpty() && isActive(epoch)) onSamples(PpgCallbackBatch.copyOf(accepted))
        }

        override fun onFlushCompleted() {
            completeStop(flushed = true, epoch = epoch)
        }

        override fun onError(error: HealthTracker.TrackerError) {
            if (!isActive(epoch)) return
            val kind = when (error) {
                HealthTracker.TrackerError.PERMISSION_ERROR -> SamsungPpgFailureKind.PERMISSION
                HealthTracker.TrackerError.SDK_POLICY_ERROR -> SamsungPpgFailureKind.SDK_POLICY
            }
            onFailure(SamsungPpgFailure(kind, "Samsung PPG tracker error: $error"))
        }
    }

    private fun isActive(epoch: Long): Boolean = started.get() && listenerEpoch.get() == epoch

    private fun acceptPoint(point: DataPoint, accepted: MutableList<PpgRawSample>) {
        if (!started.get()) return
        val greenStatus = runCatching { point.getValue(ValueKey.PpgSet.GREEN_STATUS) }.getOrNull()
        val irStatus = runCatching { point.getValue(ValueKey.PpgSet.IR_STATUS) }.getOrNull()
        val redStatus = runCatching { point.getValue(ValueKey.PpgSet.RED_STATUS) }.getOrNull()
        acceptChannel(
            type = PpgType.GREEN,
            status = greenStatus,
            timestamp = point.timestamp,
            accepted = accepted,
            valueProvider = { point.getValue(ValueKey.PpgSet.PPG_GREEN) }
        )
        acceptChannel(
            type = PpgType.IR,
            status = irStatus,
            timestamp = point.timestamp,
            accepted = accepted,
            valueProvider = { point.getValue(ValueKey.PpgSet.PPG_IR) }
        )
        acceptChannel(
            type = PpgType.RED,
            status = redStatus,
            timestamp = point.timestamp,
            accepted = accepted,
            valueProvider = { point.getValue(ValueKey.PpgSet.PPG_RED) }
        )
    }

    private fun acceptChannel(
        type: PpgType,
        status: Int?,
        timestamp: Long,
        accepted: MutableList<PpgRawSample>,
        valueProvider: () -> Int?
    ) {
        SamsungPpgContinuousStatusPolicy.rejectionReason(status)?.let { reason ->
            reject(type, reason, status)
            return
        }
        runCatching(valueProvider).getOrNull()
            ?.let { value ->
                accepted += PpgRawSample(
                    type = type,
                    timestamp = timestamp,
                    value = value,
                    statusCode = SamsungPpgContinuousStatusPolicy.NORMAL_STATUS,
                    accepted = true
                )
            }
            ?: reject(type, SamsungPpgSampleRejectionReason.VALUE_UNAVAILABLE)
    }

    private fun reject(type: PpgType, reason: SamsungPpgSampleRejectionReason, status: Int? = null) {
        diagnostics.record(type, reason, status)
            .takeIf { it.shouldReport }
            ?.let(onDiagnostic)
    }

    private fun completeStop(flushed: Boolean, epoch: Long = listenerEpoch.get()) {
        if (!isActive(epoch)) return
        if (!stopCompleted.compareAndSet(false, true)) return
        started.set(false)
        listenerEpoch.incrementAndGet()
        onUnregistered()
        val callback = stopCallback
        stopCallback = null
        sensorHandler.post {
            runCatching { tracker.unsetEventListener() }
            controlHandler.post { callback?.invoke(flushed) }
        }
    }
}

/** Samsung's PPG_CONTINUOUS contract has one eligible callback status: NORMAL (0). */
internal object SamsungPpgContinuousStatusPolicy {
    const val NORMAL_STATUS = 0

    fun rejectionReason(status: Int?): SamsungPpgSampleRejectionReason? = when (status) {
        null -> SamsungPpgSampleRejectionReason.STATUS_UNAVAILABLE
        NORMAL_STATUS -> null
        else -> SamsungPpgSampleRejectionReason.NON_NORMAL_STATUS
    }
}
