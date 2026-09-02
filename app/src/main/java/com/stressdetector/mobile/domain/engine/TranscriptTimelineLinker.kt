package com.stressdetector.mobile.domain.engine

/** Pure, bounded temporal linkage between a marker and a final transcript. */
object TranscriptTimelineLinker {
    fun link(event: DivergenceEvent, transcript: List<SpeechSegment>, toleranceMillis: Long, timeline: SessionTimeline? = null): String? =
        transcript
            .asSequence()
            .filter(SpeechSegment::isFinal)
            .filter { segment -> segment.sessionId == event.sessionId }
            .filter { segment ->
                val segmentStart = timeline?.audioOffsetToSessionElapsed(segment.startOffsetMs) ?: segment.startOffsetMs
                val segmentEnd = timeline?.audioOffsetToSessionElapsed(segment.endOffsetMs) ?: segment.endOffsetMs
                segmentEnd >= event.contextWindowStartElapsedMillis - toleranceMillis &&
                    segmentStart <= event.contextWindowEndElapsedMillis + toleranceMillis
            }
            .minByOrNull { segment ->
                val segmentStart = timeline?.audioOffsetToSessionElapsed(segment.startOffsetMs) ?: segment.startOffsetMs
                val segmentEnd = timeline?.audioOffsetToSessionElapsed(segment.endOffsetMs) ?: segment.endOffsetMs
                distanceToRange(
                    event.markerElapsedMillis,
                    segmentStart,
                    segmentEnd
                )
            }
            ?.id

    private fun distanceToRange(point: Long, start: Long, end: Long): Long = when {
        point < start -> start - point
        point > end -> point - end
        else -> 0L
    }
}
