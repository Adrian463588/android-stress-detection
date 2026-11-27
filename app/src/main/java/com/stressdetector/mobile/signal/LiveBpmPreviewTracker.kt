@file:Suppress("LongParameterList", "TooManyFunctions", "ComplexCondition", "MagicNumber")

package com.stressdetector.mobile.signal

import com.stressdetector.mobile.data.PpgStreamState
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.domain.engine.BpmSignalStatus
import com.stressdetector.mobile.domain.engine.BpmSource
import com.stressdetector.mobile.domain.engine.LiveBpmChannelSnapshot
import com.stressdetector.mobile.domain.engine.PrvMetrics
import com.stressdetector.shared.PpgFilterContract
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.WearHeartRateBatch
import kotlin.math.sqrt

/**
 * Exploratory 1-second live BPM/PRV preview tracker for Verbatim+.
 *
 * Uses a 10–15s sliding valid-peak window for responsive clinician feedback,
 * explicitly clears stale readings when signal quality fails or packets stop,
 * and keeps PPG_GREEN, PPG_IR, PPG_RED_DIAGNOSTIC, and WATCH_SDK_HR strictly separate.
 */
class LiveBpmPreviewTracker(
    private val config: LiveMonitorConfig = defaultLivePreviewConfig(),
    private val prvExtractor: PrvFeatureExtractor = PrvFeatureExtractor(),
    private val staleTimeoutMillis: Long = DEFAULT_STALE_TIMEOUT_MS
) {
    private val baselinesBySource = mutableMapOf<BpmSource, ArrayDeque<Pair<Long, Float>>>()
    private val lastSnapshotBySource = mutableMapOf<BpmSource, LiveBpmChannelSnapshot>()
    private val preprocessorCache = mutableMapOf<Pair<PpgType, Int>, WearablePpgWindowPreprocessor>()
    private val estimatorCache = mutableMapOf<Int, PpgBpmEstimator>()

    @Synchronized
    fun reset() {
        baselinesBySource.clear()
        lastSnapshotBySource.clear()
    }

    @Synchronized
    fun evaluate(
        windows: Map<PpgType, WindowSnapshot>,
        stream: PpgStreamState,
        monotonicNowMillis: Long,
        watchHeartRate: WearHeartRateBatch? = null,
        watchHeartRateReceiptMonotonicMillis: Long = 0L
    ): Map<BpmSource, LiveBpmChannelSnapshot> {
        val out = linkedMapOf<BpmSource, LiveBpmChannelSnapshot>()
        listOf(PpgType.GREEN to BpmSource.PPG_GREEN, PpgType.IR to BpmSource.PPG_IR).forEach { (type, source) ->
            out[source] = evaluatePpgSource(
                type = type,
                source = source,
                snapshot = windows[type],
                stream = stream,
                monotonicNowMillis = monotonicNowMillis
            )
        }
        watchHeartRate?.let { hr ->
            out[BpmSource.WATCH_SDK_HR] = evaluateWatchSdkHr(
                batch = hr,
                receiptMonotonicMillis = watchHeartRateReceiptMonotonicMillis,
                monotonicNowMillis = monotonicNowMillis
            )
        }
        return out
    }

    fun selectActivePreview(bySource: Map<BpmSource, LiveBpmChannelSnapshot>): LiveBpmChannelSnapshot? {
        val validPriority = listOf(BpmSource.PPG_GREEN, BpmSource.PPG_IR, BpmSource.WATCH_SDK_HR)
        validPriority.forEach { source ->
            val snap = bySource[source]
            if (snap != null && !snap.isStale && snap.observedBpm != null && snap.status == BpmSignalStatus.SIGNAL_VALID) {
                return snap
            }
        }
        return bySource[BpmSource.PPG_GREEN]
            ?: bySource[BpmSource.PPG_IR]
            ?: bySource[BpmSource.WATCH_SDK_HR]
    }

    private fun evaluatePpgSource(
        type: PpgType,
        source: BpmSource,
        snapshot: WindowSnapshot?,
        stream: PpgStreamState,
        monotonicNowMillis: Long
    ): LiveBpmChannelSnapshot {
        val lastBatchMono = when (type) {
            PpgType.GREEN -> stream.lastGreenBatchMonotonicMillis
            PpgType.IR -> stream.lastIrBatchMonotonicMillis
            PpgType.RED -> stream.lastRedBatchMonotonicMillis
        }
        val staleByTimeout = lastBatchMono > 0L && (monotonicNowMillis - lastBatchMono) > staleTimeoutMillis
        val result: LiveBpmChannelSnapshot = if (staleByTimeout) {
            LiveBpmChannelSnapshot(
                source = source,
                observedBpm = null,
                baselineBpm = medianBaseline(source, monotonicNowMillis),
                deltaBpm = null,
                qualityScore = 0f,
                status = BpmSignalStatus.DISCONNECTED,
                isStale = true,
                statusReason = "signal_timeout_disconnected"
            )
        } else if (snapshot == null || snapshot.size == 0) {
            LiveBpmChannelSnapshot(
                source = source,
                observedBpm = null,
                baselineBpm = null,
                deltaBpm = null,
                qualityScore = 0f,
                status = BpmSignalStatus.WARMING_UP,
                isStale = false,
                statusReason = "awaiting_samples"
            )
        } else {
            computeFromSnapshot(type, source, snapshot, monotonicNowMillis)
        }
        lastSnapshotBySource[source] = result
        return result
    }

    @Suppress("ReturnCount")
    private fun computeFromSnapshot(
        type: PpgType,
        source: BpmSource,
        snapshot: WindowSnapshot,
        monotonicNowMillis: Long
    ): LiveBpmChannelSnapshot {
        val chosenWindowSeconds = when {
            snapshot.size >= type.samplingRateHz * PREVIEW_WINDOW_SECONDS -> PREVIEW_WINDOW_SECONDS
            snapshot.size >= type.samplingRateHz * MIN_WARMUP_SECONDS -> MIN_WARMUP_SECONDS
            else -> return warmingUpSnapshot(source, snapshot.size, type.samplingRateHz * MIN_WARMUP_SECONDS, monotonicNowMillis)
        }
        val preprocessor = preprocessorCache.getOrPut(type to chosenWindowSeconds) {
            WearablePpgWindowPreprocessor(
                contract = PpgPreprocessingContract(
                    windowSeconds = chosenWindowSeconds,
                    sourceRateHz = type.sourceRateHz.toDouble(),
                    modelRateHz = type.modelRateHz.toDouble(),
                    filterContract = PpgFilterContract.forChannel(type.assetSuffix)
                )
            )
        }
        val prepared = (preprocessor.prepare(snapshot) as? PpgPreprocessResult.Ready)?.window
        val quality = prepared?.let { SignalQualityGate(config).evaluate(it) }
        return when {
            prepared == null || quality == null -> LiveBpmChannelSnapshot(
                source = source,
                observedBpm = null,
                baselineBpm = medianBaseline(source, monotonicNowMillis),
                deltaBpm = null,
                qualityScore = 0f,
                status = BpmSignalStatus.POOR_QUALITY,
                isStale = true,
                statusReason = "preprocess_rejected"
            )
            !quality.isUsable || quality.qualityScore < config.minimumQualityScore -> {
                val isMotion = quality.reason.contains("motion", ignoreCase = true)
                LiveBpmChannelSnapshot(
                    source = source,
                    observedBpm = null,
                    baselineBpm = medianBaseline(source, monotonicNowMillis),
                    deltaBpm = null,
                    qualityScore = quality.qualityScore,
                    status = if (isMotion) BpmSignalStatus.MOTION_ARTIFACT else BpmSignalStatus.POOR_QUALITY,
                    isStale = true,
                    statusReason = quality.reason
                )
            }
            else -> estimateValidPreview(type, source, prepared, chosenWindowSeconds, quality.qualityScore, monotonicNowMillis)
        }
    }

    private fun warmingUpSnapshot(
        source: BpmSource,
        currentSamples: Int,
        requiredSamples: Int,
        monotonicNowMillis: Long
    ): LiveBpmChannelSnapshot = LiveBpmChannelSnapshot(
        source = source,
        observedBpm = null,
        baselineBpm = medianBaseline(source, monotonicNowMillis),
        deltaBpm = null,
        qualityScore = 0f,
        status = BpmSignalStatus.WARMING_UP,
        isStale = false,
        statusReason = "warming_up_${currentSamples}_of_$requiredSamples"
    )

    private fun estimateValidPreview(
        type: PpgType,
        source: BpmSource,
        prepared: PreparedPpgWindow,
        chosenWindowSeconds: Int,
        qualityScore: Float,
        monotonicNowMillis: Long
    ): LiveBpmChannelSnapshot {
        val estimator = estimatorCache.getOrPut(chosenWindowSeconds) { PpgBpmEstimator(chosenWindowSeconds) }
        val peaks = estimator.extractPeaks(prepared)
        val estimate = estimator.estimate(prepared)
        val prv = peaks?.let {
            prvExtractor.extract(
                channel = type,
                values = it.values,
                peaks = it.peaks,
                samplingRateHz = type.samplingRateHz.toDouble(),
                windowSeconds = chosenWindowSeconds,
                isSqaValid = true
            )
        }
        return if (estimate == null) {
            LiveBpmChannelSnapshot(
                source = source,
                observedBpm = null,
                baselineBpm = medianBaseline(source, monotonicNowMillis),
                deltaBpm = null,
                qualityScore = qualityScore,
                status = BpmSignalStatus.POOR_QUALITY,
                isStale = true,
                statusReason = "insufficient_valid_peaks",
                prvMetrics = prv
            )
        } else {
            val baseline = updateAndReadBaseline(source, monotonicNowMillis, estimate.bpm)
            val delta = baseline?.let { estimate.bpm - it }
            LiveBpmChannelSnapshot(
                source = source,
                observedBpm = estimate.bpm,
                baselineBpm = baseline,
                deltaBpm = delta,
                qualityScore = qualityScore,
                status = BpmSignalStatus.SIGNAL_VALID,
                isStale = false,
                isCanonicalWindow = false,
                statusReason = "preview_${chosenWindowSeconds}s",
                lastUpdatedElapsedMillis = estimate.windowEndElapsedMillis,
                prvMetrics = prv
            )
        }
    }

    private fun evaluateWatchSdkHr(
        batch: WearHeartRateBatch,
        receiptMonotonicMillis: Long,
        monotonicNowMillis: Long
    ): LiveBpmChannelSnapshot {
        val isTimedOut = receiptMonotonicMillis > 0L && (monotonicNowMillis - receiptMonotonicMillis) > staleTimeoutMillis
        val plausible = batch.bpm in MIN_WATCH_BPM..MAX_WATCH_BPM && batch.heartRateStatus >= 0
        val ibiPrv = computeIbiPrvFromWatch(batch.validIbiMillis)
        return when {
            isTimedOut -> LiveBpmChannelSnapshot(
                source = BpmSource.WATCH_SDK_HR,
                observedBpm = null,
                baselineBpm = medianBaseline(BpmSource.WATCH_SDK_HR, monotonicNowMillis),
                deltaBpm = null,
                qualityScore = 0f,
                status = BpmSignalStatus.DISCONNECTED,
                isStale = true,
                statusReason = "watch_hr_timeout"
            )
            !plausible -> LiveBpmChannelSnapshot(
                source = BpmSource.WATCH_SDK_HR,
                observedBpm = null,
                baselineBpm = medianBaseline(BpmSource.WATCH_SDK_HR, monotonicNowMillis),
                deltaBpm = null,
                qualityScore = 0.2f,
                status = BpmSignalStatus.POOR_QUALITY,
                isStale = true,
                statusReason = "watch_hr_status_${batch.heartRateStatus}"
            )
            else -> {
                val bpmFloat = batch.bpm.toFloat()
                val baseline = updateAndReadBaseline(BpmSource.WATCH_SDK_HR, monotonicNowMillis, bpmFloat)
                LiveBpmChannelSnapshot(
                    source = BpmSource.WATCH_SDK_HR,
                    observedBpm = bpmFloat,
                    baselineBpm = baseline,
                    deltaBpm = baseline?.let { bpmFloat - it },
                    qualityScore = WATCH_HR_VALID_QUALITY,
                    status = BpmSignalStatus.SIGNAL_VALID,
                    isStale = false,
                    statusReason = "watch_sdk_hr",
                    lastUpdatedElapsedMillis = batch.sampleTimestampElapsedMillis,
                    prvMetrics = ibiPrv
                )
            }
        }
    }

    private fun computeIbiPrvFromWatch(validIbis: List<Int>): PrvMetrics? {
        if (validIbis.size < 4) return null
        val diffs = buildList<Double> {
            for (i in 0 until validIbis.lastIndex) {
                add(kotlin.math.abs(validIbis[i + 1] - validIbis[i]).toDouble())
            }
        }
        val rmssd = sqrt(diffs.sumOf { it * it } / diffs.size).toFloat()
        val mean = validIbis.average()
        val sdnn = sqrt(validIbis.sumOf { (it - mean) * (it - mean) } / validIbis.size).toFloat()
        val pnn50 = diffs.count { it > PrvFeatureExtractor.PNN50_THRESHOLD_MS }.toFloat() * 100f / diffs.size
        val sorted = validIbis.sorted()
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        } else {
            sorted[sorted.size / 2].toFloat()
        }
        return PrvMetrics(
            channel = PpgType.GREEN,
            rmssdMs = rmssd,
            sdnnMs = sdnn,
            pnn50Pct = pnn50,
            meanPpiMs = mean.toFloat(),
            medianPpiMs = median,
            validIbiCount = validIbis.size,
            candidateIbiCount = validIbis.size,
            validIbiRatio = 1f,
            windowSeconds = PrvFeatureExtractor.MIN_PRV_WINDOW_SECONDS,
            isQualityPassed = true
        )
    }

    private fun updateAndReadBaseline(source: BpmSource, nowMillis: Long, bpm: Float): Float? {
        val queue = baselinesBySource.getOrPut(source) { ArrayDeque() }
        val prior = medianBaselineLocked(queue, nowMillis)
        queue.addLast(nowMillis to bpm)
        while (queue.isNotEmpty() && nowMillis - queue.first().first > BASELINE_WINDOW_MS) {
            queue.removeFirst()
        }
        return prior
    }

    private fun medianBaseline(source: BpmSource, nowMillis: Long): Float? {
        val queue = baselinesBySource[source] ?: return null
        return medianBaselineLocked(queue, nowMillis)
    }

    private fun medianBaselineLocked(queue: ArrayDeque<Pair<Long, Float>>, nowMillis: Long): Float? {
        val valid = queue.filter { nowMillis - it.first in MIN_BASELINE_LAG_MS..BASELINE_WINDOW_MS }
            .map { it.second }
            .sorted()
        return if (valid.size < MIN_BASELINE_POINTS) {
            null
        } else if (valid.size % 2 == 0) {
            (valid[valid.size / 2 - 1] + valid[valid.size / 2]) / 2f
        } else {
            valid[valid.size / 2]
        }
    }

    companion object {
        const val DEFAULT_STALE_TIMEOUT_MS = 5_000L
        private const val PREVIEW_WINDOW_SECONDS = 15
        private const val MIN_WARMUP_SECONDS = 10
        private const val BASELINE_WINDOW_MS = 75_000L
        private const val MIN_BASELINE_LAG_MS = 10_000L
        private const val MIN_BASELINE_POINTS = 5
        private const val MIN_WATCH_BPM = 25
        private const val MAX_WATCH_BPM = 240
        private const val WATCH_HR_VALID_QUALITY = 0.8f

        internal fun defaultLivePreviewConfig(): LiveMonitorConfig = LiveMonitorConfig(
            adrReference = "ADR-012",
            inferenceStepSeconds = 10,
            minimumQualityScore = 0.40f,
            maxTimestampGapDeltaMs = 500.0,
            maxTimestampGapCountPerSecond = 5,
            maxFlatlineRatioUsable = 0.25,
            maxFlatlineRatioExcellent = 0.05,
            divergenceDeltaMin = 0.20f,
            divergenceProbabilityMin = 0.65f,
            baselineWindowCount = 10
        )
    }
}
