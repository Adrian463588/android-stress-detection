package com.stressdetector.mobile.domain.history

/** A point copied from a persisted observed sample; it is never interpolated by the projector. */
data class HistoryChartPoint(val elapsedMillis: Long, val value: Float, val interpolated: Boolean)

data class RenderableHistoryChartSegment(val points: List<HistoryChartPoint>)

data class RenderableHistoryChartSeries(
    val segments: List<RenderableHistoryChartSegment>,
    val sourceSampleCount: Int,
    val renderedPointCount: Int
)

/**
 * Pure display projection for persisted cleaned-core PPG. The returned points
 * are all original samples, so rendering can be bounded without smoothing,
 * resampling, or bridging a recorded gap.
 */
interface HistoryChartProjector {
    fun project(
        series: HistoryChartSeries,
        pixelWidth: Int,
        maxRenderedPoints: Int = pixelWidth.coerceAtLeast(1) * 2
    ): RenderableHistoryChartSeries
}

class GapAwareHistoryChartProjector : HistoryChartProjector {
    override fun project(series: HistoryChartSeries, pixelWidth: Int, maxRenderedPoints: Int): RenderableHistoryChartSeries {
        require(pixelWidth > 0) { "pixelWidth must be positive" }
        require(maxRenderedPoints > 0) { "maxRenderedPoints must be positive" }
        require(series.elapsedMillis.size == series.displayValues.size) { "series shape mismatch" }

        val observed = observedSegments(series)
        val budgets = allocateBudgets(observed, maxRenderedPoints)
        val rendered = observed.mapIndexedNotNull { index, segment ->
            val points = projectSegment(segment, budgets[index])
            points.takeIf { it.isNotEmpty() }?.let(::RenderableHistoryChartSegment)
        }
        return RenderableHistoryChartSeries(
            segments = rendered,
            sourceSampleCount = series.elapsedMillis.size,
            renderedPointCount = rendered.sumOf { it.points.size }
        )
    }

    private fun observedSegments(series: HistoryChartSeries): List<List<HistoryChartPoint>> {
        if (series.elapsedMillis.isEmpty()) return emptyList()
        val gaps = series.gapSegments.sortedBy(HistoryGapSegment::startElapsedMillis)
        val segments = mutableListOf<MutableList<HistoryChartPoint>>()
        var current = mutableListOf<HistoryChartPoint>()
        var gapIndex = 0

        for (index in series.elapsedMillis.indices) {
            val elapsed = series.elapsedMillis[index]
            val value = series.displayValues[index]
            val previous = current.lastOrNull()
            while (gapIndex < gaps.size && previous != null && gaps[gapIndex].endElapsedMillis <= previous.elapsedMillis) {
                gapIndex++
            }
            val sampleFallsInsideRecordedGap = gapIndex < gaps.size &&
                elapsed > gaps[gapIndex].startElapsedMillis &&
                elapsed < gaps[gapIndex].endElapsedMillis
            val crossesRecordedGap = previous != null &&
                gapIndex < gaps.size &&
                previous.elapsedMillis <= gaps[gapIndex].startElapsedMillis &&
                elapsed >= gaps[gapIndex].endElapsedMillis
            val discontinuity = previous != null && (elapsed <= previous.elapsedMillis || crossesRecordedGap)
            if (discontinuity || sampleFallsInsideRecordedGap || !value.isFinite()) {
                if (current.isNotEmpty()) segments += current
                current = mutableListOf()
            }
            if (value.isFinite() && !sampleFallsInsideRecordedGap) {
                current += HistoryChartPoint(
                    elapsedMillis = elapsed,
                    value = value,
                    interpolated = series.interpolatedMask.getOrElse(index) { false }
                )
            }
        }
        if (current.isNotEmpty()) segments += current
        return segments
    }

    private fun allocateBudgets(segments: List<List<HistoryChartPoint>>, maxRenderedPoints: Int): IntArray {
        val budgets = IntArray(segments.size)
        var remaining = maxRenderedPoints
        segments.indices.forEach { index ->
            if (remaining > 0 && segments[index].isNotEmpty()) {
                budgets[index] = 1
                remaining--
            }
        }
        while (remaining > 0) {
            val next = segments.indices
                .filter { budgets[it] < segments[it].size }
                .maxByOrNull { segments[it].size - budgets[it] }
                ?: break
            budgets[next]++
            remaining--
        }
        return budgets
    }

    private fun projectSegment(segment: List<HistoryChartPoint>, budget: Int): List<HistoryChartPoint> {
        if (budget <= 0 || segment.isEmpty()) return emptyList()
        if (segment.size <= budget) return segment

        val selected = linkedSetOf<Int>()
        val minIndex = segment.indices.minBy { segment[it].value }
        val maxIndex = segment.indices.maxBy { segment[it].value }
        when (budget) {
            1 -> selected += minIndex
            else -> {
                selected += minIndex
                selected += maxIndex
                if (selected.size < budget) selected += 0
                if (selected.size < budget) selected += segment.lastIndex
            }
        }

        val interiorBudget = budget - selected.size
        if (interiorBudget > 0 && segment.size > 2) {
            val bucketCount = maxOf(1, interiorBudget / 2)
            repeat(bucketCount) { bucket ->
                val start = 1 + ((segment.size - 2) * bucket / bucketCount)
                val endExclusive = 1 + ((segment.size - 2) * (bucket + 1) / bucketCount)
                if (start < endExclusive) {
                    val range = start until endExclusive
                    selected += range.minBy { segment[it].value }
                    selected += range.maxBy { segment[it].value }
                }
            }
        }
        if (selected.size < budget) {
            segment.indices.asSequence()
                .filterNot(selected::contains)
                .take(budget - selected.size)
                .forEach(selected::add)
        }
        return selected.sorted().take(budget).map(segment::get)
    }
}
