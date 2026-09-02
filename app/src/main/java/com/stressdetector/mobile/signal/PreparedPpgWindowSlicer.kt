package com.stressdetector.mobile.signal

import com.stressdetector.shared.PredictionWindowConfig

/**
 * Slices one canonical, already-filtered session into model windows.
 * No filtering, interpolation, resampling, or value fabrication happens here.
 */
class PreparedPpgWindowSlicer(
    private val config: PredictionWindowConfig = PredictionWindowConfig(),
    private val coverageToleranceMillis: Long = 1_000L
) {
    fun modelWindows(session: PreparedPpgWindow): List<PreparedPpgWindow> {
        val elapsed = elapsedTimes(session)
        return (0 until config.expectedModelWindows).mapNotNull { index ->
            val start = config.coreStartMillis + index * config.strideMillis
            val end = start + config.modelWindowMillis
            val indices = elapsed.indices.filter { sampleIndex ->
                elapsed[sampleIndex] >= start && elapsed[sampleIndex] < end
            }
            if (!hasCoverage(indices, elapsed, start, end, config.modelWindowMillis)) {
                null
            } else {
                copyWindow(session, indices, elapsed)
            }
        }
    }

    /** Non-overlapping core observations prevent peak/BPM double counting across model windows. */
    fun bpmWindows(session: PreparedPpgWindow, windowSeconds: Int = 30): List<PreparedPpgWindow> {
        require(windowSeconds > 0 && config.analysisSeconds % windowSeconds == 0) {
            "BPM observation window must divide the analysis core"
        }
        val elapsed = elapsedTimes(session)
        val windowMillis = windowSeconds * 1_000L
        return (0 until config.analysisSeconds / windowSeconds).mapNotNull { index ->
            val start = config.coreStartMillis + index * windowMillis
            val end = start + windowMillis
            val indices = elapsed.indices.filter { sampleIndex ->
                elapsed[sampleIndex] >= start && elapsed[sampleIndex] < end
            }
            if (!hasCoverage(indices, elapsed, start, end, windowMillis)) {
                null
            } else {
                copyWindow(session, indices, elapsed)
            }
        }
    }

    private fun copyWindow(source: PreparedPpgWindow, indices: List<Int>, elapsed: LongArray): PreparedPpgWindow = source.copy(
        timestampsMillis = LongArray(indices.size) { source.timestampsMillis[indices[it]] },
        filteredValues = DoubleArray(indices.size) { source.filteredValues[indices[it]] },
        rawSampleCount = indices.size,
        interpolatedSampleCount = indices.count { source.interpolatedMask[it] },
        sessionElapsedStartMillis = elapsed[indices.first()],
        sessionElapsedEndMillis = elapsed[indices.last()],
        elapsedTimestampsMillis = LongArray(indices.size) { elapsed[indices[it]] },
        interpolatedMask = BooleanArray(indices.size) { source.interpolatedMask[indices[it]] },
        sourceGapInterpolatedSampleCount = indices.count { source.sourceGapInterpolatedMask[it] },
        sourceGapInterpolatedMask = BooleanArray(indices.size) {
            source.sourceGapInterpolatedMask[indices[it]]
        }
    )

    private fun hasCoverage(indices: List<Int>, elapsed: LongArray, start: Long, end: Long, expectedDurationMillis: Long): Boolean {
        val first = indices.firstOrNull()?.let(elapsed::get) ?: return false
        val last = indices.lastOrNull()?.let(elapsed::get) ?: return false
        val observedDuration = (last - first).coerceAtLeast(0L)
        return first <= start + coverageToleranceMillis &&
            last >= end - 2L * coverageToleranceMillis &&
            observedDuration >= expectedDurationMillis - 2L * coverageToleranceMillis
    }

    private fun elapsedTimes(source: PreparedPpgWindow): LongArray {
        if (source.elapsedTimestampsMillis.size == source.size) {
            return source.elapsedTimestampsMillis.copyOf()
        }
        if (source.size == 0) return LongArray(0)
        val firstTimestamp = source.timestampsMillis.first()
        return LongArray(source.size) { index ->
            (
                source.sessionElapsedStartMillis +
                    (source.timestampsMillis[index] - firstTimestamp)
                ).coerceAtLeast(0L)
        }
    }
}
