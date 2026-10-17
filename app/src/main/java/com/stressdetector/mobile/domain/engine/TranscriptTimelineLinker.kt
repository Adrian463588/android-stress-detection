package com.stressdetector.mobile.domain.engine

/** Pure, bounded temporal linkage between a marker and a final transcript. */
object TranscriptTimelineLinker {
    private data class MarkerTiming(
        val sessionId: String,
        val markerElapsedMillis: Long,
        val contextWindowStartElapsedMillis: Long,
        val contextWindowEndElapsedMillis: Long
    )

    /** Linked utterance time, not a guess that the PPG and audio clocks share an origin. */
    fun playbackOffset(event: com.stressdetector.mobile.data.db.DivergenceEventEntity, transcript: List<SpeechSegment>): Long? =
        transcript.firstOrNull { it.isFinal && it.id == event.transcriptSegmentId }
            ?.startOffsetMs?.takeIf { it >= 0L }

    fun link(
        event: com.stressdetector.mobile.data.db.DivergenceEventEntity,
        transcript: List<SpeechSegment>,
        toleranceMillis: Long,
        timeline: SessionTimeline? = null
    ): String? = link(
        timing = MarkerTiming(
            sessionId = event.sessionId,
            markerElapsedMillis = event.markerElapsedMillis,
            contextWindowStartElapsedMillis = event.contextWindowStartElapsedMillis,
            contextWindowEndElapsedMillis = event.contextWindowEndElapsedMillis
        ),
        transcript = transcript,
        toleranceMillis = toleranceMillis,
        timeline = timeline
    )

    fun link(event: DivergenceEvent, transcript: List<SpeechSegment>, toleranceMillis: Long, timeline: SessionTimeline? = null): String? =
        link(
            timing = MarkerTiming(
                sessionId = event.sessionId,
                markerElapsedMillis = event.markerElapsedMillis,
                contextWindowStartElapsedMillis = event.contextWindowStartElapsedMillis,
                contextWindowEndElapsedMillis = event.contextWindowEndElapsedMillis
            ),
            transcript = transcript,
            toleranceMillis = toleranceMillis,
            timeline = timeline
        )

    private fun link(timing: MarkerTiming, transcript: List<SpeechSegment>, toleranceMillis: Long, timeline: SessionTimeline?): String? =
        transcript
            .asSequence()
            .filter(SpeechSegment::isFinal)
            .filter { segment -> segment.sessionId == timing.sessionId }
            .filter { segment ->
                val segmentStart = timeline?.audioOffsetToSessionElapsed(segment.startOffsetMs) ?: segment.startOffsetMs
                val segmentEnd = timeline?.audioOffsetToSessionElapsed(segment.endOffsetMs) ?: segment.endOffsetMs
                segmentEnd >= timing.contextWindowStartElapsedMillis - toleranceMillis &&
                    segmentStart <= timing.contextWindowEndElapsedMillis + toleranceMillis
            }
            .minByOrNull { segment ->
                val segmentStart = timeline?.audioOffsetToSessionElapsed(segment.startOffsetMs) ?: segment.startOffsetMs
                val segmentEnd = timeline?.audioOffsetToSessionElapsed(segment.endOffsetMs) ?: segment.endOffsetMs
                distanceToRange(
                    timing.markerElapsedMillis,
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
