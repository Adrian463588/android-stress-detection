package com.stressdetector.mobile.domain

import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.PredictionWindowConfig

/** Actual core evidence used to explain an abstention without altering its gate. */
data class CoreCoverageDiagnostics(
    val requestedStartMillis: Long,
    val requestedEndMillis: Long,
    val observedStartMillis: Long?,
    val observedEndMillis: Long?,
    val observedCoverageMillis: Long,
    val sampleCount: Int,
    val actualCadenceHz: Float?,
    val medianIntervalMillis: Long?,
    val significantGapCount: Int,
    val largestGapMillis: Long,
    val transportMissingSequenceCount: Long,
    val rawMinimum: Int?,
    val rawMaximum: Int?,
    val rejectionReasons: List<String>
) {
    val isComplete: Boolean get() = rejectionReasons.isEmpty()
}

data class CorePpgSnapshot(
    val channel: PpgType,
    val fullSession: WindowSnapshot,
    val coreWindow: WindowSnapshot?,
    val requestedStartMillis: Long,
    val requestedEndMillis: Long,
    val observedStartMillis: Long? = null,
    val observedEndMillis: Long? = null,
    val observedCoverageMillis: Long = 0L,
    val expectedModelWindows: Int,
    val diagnostics: CoreCoverageDiagnostics? = null,
    val rejectionReason: String? = null
) {
    val isComplete: Boolean get() = coreWindow != null && rejectionReason == null
}

/**
 * Slices a retained 300-second session using elapsed sample time.
 * No padding, resampling, or synthetic sample creation happens here.
 */
class CorePpgWindowSlicer(
    private val config: PredictionWindowConfig = PredictionWindowConfig(),
    private val coverageToleranceMillis: Long = 1_000L
) {
    fun slice(fullSession: WindowSnapshot, transportMissingSequenceCount: Long = 0L): CorePpgSnapshot {
        val elapsed = elapsedTimes(fullSession)
        val start = config.coreStartMillis
        val end = config.coreEndMillis
        val indices = elapsed.indices.filter { index ->
            elapsed[index] >= start && elapsed[index] < end
        }
        val observedStart = indices.firstOrNull()?.let(elapsed::get)
        val observedEnd = indices.lastOrNull()?.let(elapsed::get)
        val coverage = if (observedStart != null && observedEnd != null) {
            (observedEnd - observedStart).coerceAtLeast(0L)
        } else {
            0L
        }
        val hasCoverage = observedStart != null &&
            observedEnd != null &&
            observedStart <= start + coverageToleranceMillis &&
            observedEnd >= end - coverageToleranceMillis &&
            coverage >= requiredCoverageMillis()
        val diagnostics = diagnostics(
            CoreEvidence(
                source = fullSession,
                elapsed = elapsed,
                indices = indices,
                observedStart = observedStart,
                observedEnd = observedEnd,
                coverage = coverage,
                transportMissingSequenceCount = transportMissingSequenceCount
            )
        )
        val core = if (hasCoverage) copyWindow(fullSession, indices, elapsed) else null
        return CorePpgSnapshot(
            channel = fullSession.type,
            fullSession = fullSession,
            coreWindow = core,
            requestedStartMillis = start,
            requestedEndMillis = end,
            observedStartMillis = observedStart,
            observedEndMillis = observedEnd,
            observedCoverageMillis = coverage,
            expectedModelWindows = config.expectedModelWindows,
            diagnostics = diagnostics,
            rejectionReason = if (hasCoverage) null else diagnostics.failureSummary()
        )
    }

    private fun diagnostics(evidence: CoreEvidence): CoreCoverageDiagnostics {
        val samples = sampleTelemetry(evidence)
        return CoreCoverageDiagnostics(
            requestedStartMillis = config.coreStartMillis,
            requestedEndMillis = config.coreEndMillis,
            observedStartMillis = evidence.observedStart,
            observedEndMillis = evidence.observedEnd,
            observedCoverageMillis = evidence.coverage,
            sampleCount = evidence.indices.size,
            actualCadenceHz = samples.medianIntervalMillis
                ?.takeIf { it > 0L }
                ?.let { MILLIS_PER_SECOND / it },
            medianIntervalMillis = samples.medianIntervalMillis,
            significantGapCount = samples.significantGapCount,
            largestGapMillis = samples.largestGapMillis,
            transportMissingSequenceCount = evidence.transportMissingSequenceCount,
            rawMinimum = samples.rawMinimum,
            rawMaximum = samples.rawMaximum,
            rejectionReasons = coverageRejections(evidence)
        )
    }

    private fun sampleTelemetry(evidence: CoreEvidence): CoreSampleTelemetry {
        var intervalCount = 0
        var largestGapMillis = 0L
        var rawMinimum: Int? = null
        var rawMaximum: Int? = null
        val intervals = LongArray(evidence.indices.size.coerceAtLeast(1))
        for (position in evidence.indices.indices) {
            val sourceIndex = evidence.indices[position]
            val rawValue = evidence.source.rawValues[sourceIndex]
            rawMinimum = rawMinimum?.let { minOf(it, rawValue) } ?: rawValue
            rawMaximum = rawMaximum?.let { maxOf(it, rawValue) } ?: rawValue
            if (position == 0) continue
            val previousIndex = evidence.indices[position - 1]
            val interval = (evidence.elapsed[sourceIndex] - evidence.elapsed[previousIndex]).coerceAtLeast(0L)
            if (interval > 0L) {
                intervals[intervalCount] = interval
                intervalCount += 1
                largestGapMillis = maxOf(largestGapMillis, interval)
            }
        }
        val medianIntervalMillis = medianInterval(intervals, intervalCount)
        var significantGapCount = 0
        if (intervalCount > 0) {
            for (index in 0 until intervalCount) {
                if (intervals[index] > SIGNIFICANT_GAP_MILLIS) significantGapCount += 1
            }
        }
        return CoreSampleTelemetry(
            medianIntervalMillis = medianIntervalMillis,
            significantGapCount = significantGapCount,
            largestGapMillis = largestGapMillis,
            rawMinimum = rawMinimum,
            rawMaximum = rawMaximum
        )
    }

    private fun coverageRejections(evidence: CoreEvidence): List<String> = buildList {
        evidence.observedStart?.let { start ->
            if (start > config.coreStartMillis + coverageToleranceMillis) add("core_start_late:${start}ms")
        } ?: add("core_no_samples")
        evidence.observedEnd?.let { end ->
            if (end < config.coreEndMillis - coverageToleranceMillis) add("core_end_early:${end}ms")
        } ?: add("core_no_samples")
        if (evidence.coverage < requiredCoverageMillis()) add("core_coverage_short:${evidence.coverage}ms")
    }.distinct()

    private fun requiredCoverageMillis(): Long = config.analysisDurationMillis - 2L * coverageToleranceMillis

    private fun medianInterval(intervals: LongArray, count: Int): Long? {
        if (count == 0) return null
        intervals.sort(0, count)
        val middle = count / 2
        return if (count % 2 == 0) {
            (intervals[middle - 1] + intervals[middle]) / 2L
        } else {
            intervals[middle]
        }
    }

    private fun copyWindow(source: WindowSnapshot, indices: List<Int>, elapsed: LongArray): WindowSnapshot = WindowSnapshot(
        type = source.type,
        timestampsMillis = LongArray(indices.size) { source.timestampsMillis[indices[it]] },
        rawValues = IntArray(indices.size) { source.rawValues[indices[it]] },
        sessionElapsedStartMillis = elapsed[indices.first()],
        sessionElapsedEndMillis = elapsed[indices.last()],
        elapsedTimestampsMillis = LongArray(indices.size) { elapsed[indices[it]] },
        sessionId = source.sessionId,
        windowId = source.windowId
    )

    private fun elapsedTimes(source: WindowSnapshot): LongArray {
        if (source.elapsedTimestampsMillis.size == source.size) {
            return source.elapsedTimestampsMillis.copyOf()
        }
        if (source.size == 0) return LongArray(0)
        val firstTimestamp = source.timestampsMillis.first()
        val baseElapsed = source.sessionElapsedStartMillis
        return LongArray(source.size) { index ->
            (baseElapsed + (source.timestampsMillis[index] - firstTimestamp)).coerceAtLeast(0L)
        }
    }

    private fun CoreCoverageDiagnostics.failureSummary(): String =
        "core_coverage_insufficient:${rejectionReasons.joinToString(",").ifBlank { "unknown" }}"

    private companion object {
        const val SIGNIFICANT_GAP_MILLIS = 1_000L
        const val MILLIS_PER_SECOND = 1_000f
    }

    private data class CoreEvidence(
        val source: WindowSnapshot,
        val elapsed: LongArray,
        val indices: List<Int>,
        val observedStart: Long?,
        val observedEnd: Long?,
        val coverage: Long,
        val transportMissingSequenceCount: Long
    )

    private data class CoreSampleTelemetry(
        val medianIntervalMillis: Long?,
        val significantGapCount: Int,
        val largestGapMillis: Long,
        val rawMinimum: Int?,
        val rawMaximum: Int?
    )
}
