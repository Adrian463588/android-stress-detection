package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.db.DivergenceEventEntity

/** Immutable event boundary captured when a live Verbatim session begins draining. */
internal data class SessionStopCutoff(val sessionId: String, val generation: Long, val elapsedMillis: Long)

internal fun SessionStopCutoff.accepts(segment: SpeechSegment, timeline: SessionTimeline): Boolean = timeline.sessionId == sessionId &&
    timeline.generation == generation &&
    segment.sessionId == sessionId &&
    timeline.audioOffsetToSessionElapsed(segment.endOffsetMs) <= elapsedMillis

internal fun SessionStopCutoff.filter(events: List<DivergenceEventEntity>): List<DivergenceEventEntity> = events.mapNotNull { event ->
    if (event.sessionId != sessionId) return@mapNotNull null
    val episodeStart = event.episodeStartElapsedMillis.takeIf { it > 0L } ?: event.markerElapsedMillis
    if (episodeStart > elapsedMillis) return@mapNotNull null

    val episodeEnd = event.episodeEndElapsedMillis.takeIf { it > 0L } ?: event.markerElapsedMillis
    if (event.markerElapsedMillis <= elapsedMillis &&
        episodeEnd <= elapsedMillis &&
        event.contextWindowEndElapsedMillis <= elapsedMillis
    ) {
        event
    } else {
        event.copy(
            timestampMs = minOf(event.timestampMs, elapsedMillis),
            markerElapsedMillis = minOf(event.markerElapsedMillis, elapsedMillis),
            episodeEndElapsedMillis = minOf(episodeEnd, elapsedMillis),
            contextWindowEndElapsedMillis = minOf(event.contextWindowEndElapsedMillis, elapsedMillis)
        )
    }
}
