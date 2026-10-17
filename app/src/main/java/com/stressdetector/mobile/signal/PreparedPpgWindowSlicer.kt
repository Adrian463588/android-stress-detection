package com.stressdetector.mobile.signal

import com.stressdetector.shared.PpgSamplingPolicy
import com.stressdetector.shared.PredictionWindowConfig
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Slices one canonical, already-filtered session into model windows.
 * No filtering, interpolation, resampling, or value fabrication happens here.
 */
class PreparedPpgWindowSlicer(
    private val config: PredictionWindowConfig = PredictionWindowConfig(),
    private val coverageToleranceMillis: Long = 1_000L,
    private val motionAnnotator: MotionArtifactAnnotator = MotionArtifactAnnotator()
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

    private fun copyWindow(source: PreparedPpgWindow, indices: List<Int>, elapsed: LongArray): PreparedPpgWindow {
        val raw = source.rawValues.takeIf { it.size == source.size }
            ?.let { values -> IntArray(indices.size) { values[indices[it]] } }
            ?: IntArray(0)
        val filtered = DoubleArray(indices.size) { source.filteredValues[indices[it]] }
        val batchIds = source.timestampBatchIds.takeIf { it.size == source.size }
            ?.let { ids -> Array(indices.size) { ids[indices[it]] } }
            ?: emptyArray()
        val startMillis = elapsed[indices.first()]
        val endMillis = elapsed[indices.last()]
        val motionSamples = source.accelerometerSamples.filter { it.elapsedMillis in startMillis..endMillis }
        val timestamps = LongArray(indices.size) { source.timestampsMillis[indices[it]] }
        val observedRate = PpgSamplingPolicy.observedRateHz(timestamps)
        return source.copy(
            timestampsMillis = timestamps,
            windowId = "${source.windowId}:$startMillis-$endMillis",
            modelRateHz = if (source.samplingRatePolicy == PpgSamplingPolicy.RECORDING_OBSERVED_V1) {
                observedRate
            } else {
                source.modelRateHz
            },
            observedCadenceHz = observedRate,
            filteredValues = filtered,
            rawValues = raw,
            rawValuesSha256 = raw.takeIf { it.isNotEmpty() }?.let(::sha256Csv) ?: "",
            filteredValuesSha256 = sha256Csv(filtered),
            timestampBatchIds = batchIds,
            sourceBatchIds = batchIds.asList().distinct(),
            rawSampleCount = indices.size,
            interpolatedSampleCount = indices.count { source.interpolatedMask[it] },
            sessionElapsedStartMillis = startMillis,
            sessionElapsedEndMillis = endMillis,
            elapsedTimestampsMillis = LongArray(indices.size) { elapsed[indices[it]] },
            accelerometerSamples = motionSamples,
            motionAnnotation = motionAnnotator.annotate(motionSamples, startMillis, endMillis),
            interpolatedMask = BooleanArray(indices.size) { source.interpolatedMask[indices[it]] },
            sourceGapInterpolatedSampleCount = indices.count { source.sourceGapInterpolatedMask[it] },
            sourceGapInterpolatedMask = BooleanArray(indices.size) {
                source.sourceGapInterpolatedMask[indices[it]]
            },
            observedSampleCount = indices.count { source.observedMask[it] },
            observedMask = BooleanArray(indices.size) { source.observedMask[indices[it]] },
            repairedShortGapSampleCount = indices.count { source.repairedShortGapMask[it] },
            repairedShortGapMask = BooleanArray(indices.size) { source.repairedShortGapMask[indices[it]] },
            trueGapCount = source.trueGapCount,
            unresolvedSampleCount = source.unresolvedSampleCount,
            gridResampledSampleCount = indices.count { source.gridResampledMask[it] },
            gridResampledMask = BooleanArray(indices.size) { source.gridResampledMask[indices[it]] }
        )
    }

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

    private fun sha256Csv(values: IntArray): String = sha256Text(values.joinToString(","))

    private fun sha256Csv(values: DoubleArray): String = sha256Text(values.joinToString(","))

    private fun sha256Text(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
