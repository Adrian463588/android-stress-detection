package com.stressdetector.mobile.signal

import com.stressdetector.mobile.domain.engine.BpmSummary
import kotlin.math.abs

/** Evidence extracted from one SQA-approved, canonical 30-second PPG tail. */
data class BpmEstimate(val bpm: Float, val peakCount: Int, val windowStartElapsedMillis: Long, val windowEndElapsedMillis: Long)

/** Accumulates only finite BPM estimates that already passed canonical SQA. */
class BpmSummaryAccumulator {
    private val observations = mutableListOf<BpmEstimate>()

    fun add(estimate: BpmEstimate) {
        if (estimate.bpm.isFinite() && estimate.peakCount > 0) observations += estimate
    }

    fun summary(): BpmSummary? {
        if (observations.isEmpty()) return null
        val bpmValues = observations.map(BpmEstimate::bpm).sorted()
        val middle = bpmValues.size / 2
        val median = if (bpmValues.size % 2 == 0) {
            (bpmValues[middle - 1] + bpmValues[middle]) / 2f
        } else {
            bpmValues[middle]
        }
        return BpmSummary(
            meanBpm = bpmValues.average().toFloat(),
            medianBpm = median,
            minBpm = bpmValues.first(),
            maxBpm = bpmValues.last(),
            observationCount = bpmValues.size,
            validPeakCount = observations.sumOf(BpmEstimate::peakCount)
        )
    }

    fun clear() = observations.clear()
}

/**
 * Peak-to-peak BPM estimator for the marker path.
 *
 * It consumes the already filtered, canonical PPG window; it never filters or
 * resamples the waveform again. Callers must run SQA before requesting an
 * estimate, so invalid data produces no physiological marker.
 */
class PpgBpmEstimator(private val windowSeconds: Int) {
    fun estimate(window: PreparedPpgWindow): BpmEstimate? {
        val sampleCount = window.type.samplingRateHz * windowSeconds
        if (window.size < sampleCount || window.elapsedTimestampsMillis.size != window.size) return null

        val first = window.size - sampleCount
        val values = window.filteredValues.copyOfRange(first, window.size)
        if (values.any { !it.isFinite() }) return null
        val range = (values.maxOrNull() ?: 0.0) - (values.minOrNull() ?: 0.0)
        if (range <= MIN_AMPLITUDE_RANGE) return null

        val minimumDistance = (window.type.samplingRateHz * MIN_BEAT_SECONDS).toInt().coerceAtLeast(1)
        val prominence = maxOf(MIN_PROMINENCE, range * PROMINENCE_RATIO)
        val peaks = findPeaks(values, minimumDistance, prominence)
        val intervalsMillis = buildList<Double> {
            for (index in 0 until peaks.lastIndex) {
                val interval = (peaks[index + 1] - peaks[index]) * 1_000.0 / window.type.samplingRateHz
                if (interval in MIN_IBI_MILLIS..MAX_IBI_MILLIS) add(interval)
            }
        }
        if (intervalsMillis.size < MIN_INTERVAL_COUNT) return null

        val medianIbi = intervalsMillis.sorted().let { sorted ->
            val middle = sorted.size / 2
            if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
        }
        val bpm = (60_000.0 / medianIbi).toFloat()
        if (!bpm.isFinite() || bpm !in MIN_BPM..MAX_BPM) return null

        return BpmEstimate(
            bpm = bpm,
            peakCount = intervalsMillis.size + 1,
            windowStartElapsedMillis = window.elapsedTimestampsMillis[first],
            windowEndElapsedMillis = window.elapsedTimestampsMillis.last()
        )
    }

    private fun findPeaks(values: DoubleArray, minimumDistance: Int, minimumProminence: Double): IntArray {
        val candidates = (1 until values.lastIndex)
            .filter { index -> values[index] > values[index - 1] && values[index] >= values[index + 1] }
            .sortedByDescending { values[it] }
        val accepted = mutableListOf<Int>()
        candidates.forEach { candidate ->
            if (accepted.none { abs(it - candidate) < minimumDistance } &&
                localProminence(values, candidate) >= minimumProminence
            ) {
                accepted += candidate
            }
        }
        return accepted.sorted().toIntArray()
    }

    private fun localProminence(values: DoubleArray, peak: Int): Double {
        val radius = (values.size / windowSeconds).coerceAtLeast(1)
        val start = (peak - radius).coerceAtLeast(0)
        val end = (peak + radius).coerceAtMost(values.lastIndex)
        val leftMinimum = values.sliceArray(start..peak).minOrNull() ?: values[peak]
        val rightMinimum = values.sliceArray(peak..end).minOrNull() ?: values[peak]
        return values[peak] - maxOf(leftMinimum, rightMinimum)
    }

    private companion object {
        const val MIN_BEAT_SECONDS = 0.30
        const val MIN_IBI_MILLIS = 300.0
        const val MAX_IBI_MILLIS = 1_500.0
        const val MIN_INTERVAL_COUNT = 5
        const val MIN_BPM = 40f
        const val MAX_BPM = 200f
        const val MIN_AMPLITUDE_RANGE = 1.0e-9
        const val MIN_PROMINENCE = 1.0e-9
        const val PROMINENCE_RATIO = 0.05
    }
}

enum class BpmMarkerTrend {
    RISING,
    RECOVERING
}

data class BpmMarkerEvidence(
    val trend: BpmMarkerTrend,
    val bpm: Float,
    val baselineBpm: Float,
    val peakBpm: Float,
    val deltaBpm: Float,
    val episodeStartElapsedMillis: Long,
    val windowStartElapsedMillis: Long,
    val windowEndElapsedMillis: Long
)

/**
 * Stateful BPM marker policy. Baseline contains only preceding observations;
 * the active rise keeps that baseline fixed until it recovers or times out.
 */
class BpmPhysiologicalMarkerDetector(private val config: LiveMonitorConfig) {
    private val observations = ArrayDeque<BpmEstimate>()
    private var activeRise: ActiveRise? = null
    private var lastObservationEndElapsedMillis: Long? = null
    private val maximumObservationGapMillis =
        config.inferenceStepSeconds * 1_000L + config.maxTimestampGapDeltaMs.toLong()

    fun reset() {
        observations.clear()
        activeRise = null
        lastObservationEndElapsedMillis = null
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    fun observe(estimate: BpmEstimate): List<BpmMarkerEvidence> {
        if (!estimate.bpm.isFinite() || estimate.peakCount <= 0 || estimate.windowEndElapsedMillis < estimate.windowStartElapsedMillis) {
            reset()
            return emptyList()
        }
        val previousEnd = lastObservationEndElapsedMillis
        if (previousEnd != null && estimate.windowEndElapsedMillis <= previousEnd) return emptyList()
        if (previousEnd != null &&
            estimate.windowEndElapsedMillis - previousEnd > maximumObservationGapMillis
        ) {
            reset()
        }
        lastObservationEndElapsedMillis = estimate.windowEndElapsedMillis
        val baseline = precedingBaseline(estimate.windowEndElapsedMillis) ?: run {
            observations.addLast(estimate)
            trim(estimate.windowEndElapsedMillis)
            return emptyList()
        }
        val evidence = mutableListOf<BpmMarkerEvidence>()
        val active = activeRise
        // Once a rise begins, retain its pre-rise baseline. Otherwise the
        // elevated observations would dilute the baseline and cancel a real,
        // sustained arousal marker on the next ten-second sample.
        val riseBaseline = active?.baselineBpm ?: baseline
        val riseThreshold = maxOf(
            config.bpmRiseAbsoluteThreshold,
            riseBaseline * config.bpmRiseRelativeThreshold
        )
        val isRising = estimate.bpm - riseBaseline >= riseThreshold
        when {
            isRising -> {
                val rise = (
                    active ?: ActiveRise(
                        startedAtElapsedMillis = estimate.windowEndElapsedMillis,
                        baselineBpm = riseBaseline,
                        peakBpm = estimate.bpm
                    )
                    ).copy(peakBpm = maxOf(active?.peakBpm ?: estimate.bpm, estimate.bpm))
                activeRise = rise
                if (!rise.riseEmitted &&
                    estimate.windowEndElapsedMillis - rise.startedAtElapsedMillis >=
                    config.bpmRiseSustainSeconds * 1_000L
                ) {
                    activeRise = rise.copy(riseEmitted = true)
                    evidence += BpmMarkerEvidence(
                        trend = BpmMarkerTrend.RISING,
                        bpm = estimate.bpm,
                        baselineBpm = rise.baselineBpm,
                        peakBpm = rise.peakBpm,
                        deltaBpm = estimate.bpm - rise.baselineBpm,
                        episodeStartElapsedMillis = rise.startedAtElapsedMillis,
                        windowStartElapsedMillis = estimate.windowStartElapsedMillis,
                        windowEndElapsedMillis = estimate.windowEndElapsedMillis
                    )
                }
            }

            active != null -> {
                val recoveryThreshold = maxOf(
                    config.bpmRecoveryAbsoluteThreshold,
                    active.baselineBpm * config.bpmRecoveryRelativeThreshold
                )
                val elapsedSinceRise = estimate.windowEndElapsedMillis - active.startedAtElapsedMillis
                if (elapsedSinceRise <= config.bpmRecoveryWithinSeconds * 1_000L &&
                    abs(estimate.bpm - active.baselineBpm) <= recoveryThreshold
                ) {
                    evidence += BpmMarkerEvidence(
                        trend = BpmMarkerTrend.RECOVERING,
                        bpm = estimate.bpm,
                        baselineBpm = active.baselineBpm,
                        peakBpm = active.peakBpm,
                        deltaBpm = estimate.bpm - active.baselineBpm,
                        episodeStartElapsedMillis = estimate.windowEndElapsedMillis,
                        windowStartElapsedMillis = estimate.windowStartElapsedMillis,
                        windowEndElapsedMillis = estimate.windowEndElapsedMillis
                    )
                    activeRise = null
                } else if (elapsedSinceRise > config.bpmRecoveryWithinSeconds * 1_000L) {
                    activeRise = null
                }
            }
        }

        observations.addLast(estimate)
        trim(estimate.windowEndElapsedMillis)
        return evidence
    }

    private fun precedingBaseline(nowMillis: Long): Float? {
        val cutoff = nowMillis - config.bpmBaselineWindowSeconds * 1_000L
        val observationsInBaseline = observations
            .asSequence()
            .filter { it.windowEndElapsedMillis >= cutoff && it.windowEndElapsedMillis < nowMillis }
            .toList()
        // Do not silently replace the specified preceding 60-second baseline
        // with a shorter warm-up average.
        if (observationsInBaseline.isEmpty() || observationsInBaseline.first().windowEndElapsedMillis > cutoff) {
            return null
        }
        return observationsInBaseline.map(BpmEstimate::bpm).average().toFloat()
    }

    private fun trim(nowMillis: Long) {
        val cutoff = nowMillis - config.bpmBaselineWindowSeconds * 1_000L
        while (observations.firstOrNull()?.windowEndElapsedMillis?.let { it < cutoff } == true) {
            observations.removeFirst()
        }
    }

    private data class ActiveRise(
        val startedAtElapsedMillis: Long,
        val baselineBpm: Float,
        val peakBpm: Float,
        val riseEmitted: Boolean = false
    )
}
