package com.stressdetector.mobile.audio

/** Partitions the complete utterance; uncovered or conflicting speech stays Unknown. */
internal object DiarizedUtterancePlanner {
    fun plan(segments: List<StableDiarizationSegment>, start: Long, end: Long): List<StableDiarizationSegment> {
        if (end <= start) return emptyList()
        val turns = segments.mapNotNull { segment ->
            val first = maxOf(segment.startOffsetMs, start)
            val last = minOf(segment.endOffsetMs, end)
            if (last > first) segment.copy(startOffsetMs = first, endOffsetMs = last) else null
        }
        // Native turn counts are bounded by the utterance. Scan their actual
        // boundaries rather than bridging gaps or manufacturing speaker turns.
        val spans = intervalSpans(turns.map { it.startOffsetMs to it.endOffsetMs } + (start to end))
        return spans.fold(mutableListOf<StableDiarizationSegment>()) { output, (first, last) ->
            val covering = turns.filter { it.startOffsetMs < last && it.endOffsetMs > first }
            val identities = covering.map { it.stableSpeakerIndex }.distinct()
            val identity = identities.singleOrNull()
            val rawCluster = if (identities.size == 1) covering.map { it.rawCluster }.distinct().singleOrNull() ?: -1 else -1
            val turn = StableDiarizationSegment(first, last, rawCluster, identity)
            val previous = output.lastOrNull()
            if (previous != null &&
                previous.endOffsetMs == first &&
                previous.stableSpeakerIndex == turn.stableSpeakerIndex
            ) {
                output[output.lastIndex] = previous.copy(
                    endOffsetMs = last,
                    rawCluster = previous.rawCluster.takeIf { it == rawCluster } ?: -1
                )
            } else {
                output.add(turn)
            }
            output
        }
    }

    /** Actual raw-cluster PCM ranges; only cross-cluster overlap is removed. */
    fun exclusiveSpeakerSpans(segments: List<DiarizationSegment>): List<DiarizationSegment> {
        val turns = segments.filter { it.endOffsetMs > it.startOffsetMs }
        val spans = intervalSpans(turns.map { it.startOffsetMs to it.endOffsetMs })
        return spans.fold(mutableListOf<DiarizationSegment>()) { output, (first, last) ->
            val clusters = turns.filter { it.startOffsetMs < last && it.endOffsetMs > first }
                .map { it.rawCluster }.distinct()
            val cluster = clusters.singleOrNull()
            if (cluster != null) {
                val previous = output.lastOrNull()
                if (previous != null && previous.rawCluster == cluster && previous.endOffsetMs == first) {
                    output[output.lastIndex] = previous.copy(endOffsetMs = last)
                } else {
                    output.add(DiarizationSegment(first, last, cluster))
                }
            }
            output
        }
    }

    private fun intervalSpans(intervals: List<Pair<Long, Long>>): List<Pair<Long, Long>> =
        intervals.flatMap { (start, end) -> listOf(start, end) }.distinct().sorted().zipWithNext()
}
