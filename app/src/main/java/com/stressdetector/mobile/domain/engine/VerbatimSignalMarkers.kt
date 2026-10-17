package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.db.DivergenceEventEntity

/** Capture-clock projection. Legacy unaligned events abstain or disclose the linked utterance range. */
fun List<SpeechSegment>.verbatimSignalMarkers(
    sessionId: String,
    events: List<DivergenceEventEntity>,
    audioStartSessionElapsedMs: Long?,
    range: LongRange,
    annotations: List<DomainAnnotation> = emptyList()
): List<AudioTimelineReviewMarker> {
    val cues = filter { it.sessionId == sessionId && it.isFinal && it.intersects(range) }.flatMap { it.timelineCues() }
    val measured = events.filter { it.sessionId == sessionId }.mapNotNull { it.audioMarker(this, audioStartSessionElapsedMs) }
    // Trace v1 notes use capture time. Legacy note clocks remain ambiguous: do not guess chart positions.
    val notes = annotations.takeIf { audioStartSessionElapsedMs != null }.orEmpty().filter {
        it.sessionId == sessionId && it.timestampMs >= 0
    }.map { AudioTimelineReviewMarker(it.id, it.timestampMs, it.timestampMs, "Catatan manual", it.text, it.author, it.timestampMs) }
    return (cues + measured + notes).filter { it.endOffsetMs >= range.first && it.startOffsetMs <= range.last }
        .distinctBy { it.id }.sortedWith(compareBy(AudioTimelineReviewMarker::startOffsetMs, AudioTimelineReviewMarker::id))
}

private fun SpeechSegment.intersects(range: LongRange): Boolean = endOffsetMs >= range.first && startOffsetMs <= range.last

private fun SpeechSegment.timelineCues(): List<AudioTimelineReviewMarker> = VerbatimCueAnalyzer().analyze(this).cues.map { cue ->
    AudioTimelineReviewMarker(
        cue.id,
        cue.startOffsetMs,
        cue.endOffsetMs,
        cue.type.label,
        "${cue.explanation} Bukti: «${cue.matchedText}». Waktu adalah rentang ujaran, bukan per kata.",
        "Teks · ${cue.source}",
        cue.startOffsetMs
    )
}

private fun DivergenceEventEntity.audioMarker(transcript: List<SpeechSegment>, originMs: Long?): AudioTimelineReviewMarker? {
    val linked = transcript.firstOrNull { it.sessionId == sessionId && it.isFinal && it.id == transcriptSegmentId }
    val start = originMs?.let { episodeStartElapsedMillis - it } ?: linked?.startOffsetMs
    val end = originMs?.let { episodeEndElapsedMillis - it } ?: linked?.endOffsetMs
    if (start == null || end == null) return null
    val location = if (originMs == null) " Rentang ujaran tertaut, bukan onset sensor." else ""
    return if (start < 0 || end < start) {
        null
    } else {
        AudioTimelineReviewMarker(eventId, start, end, signalMarkerLabel(markerType), copyText + location, channel, start)
    }
}

private fun signalMarkerLabel(type: String): String = when (type) {
    "LOW_AUDIO_QUALITY" -> "Kualitas audio"
    "ACOUSTIC_DIVERGENCE", "ACOUSTIC_ENERGY_CHANGE" -> "Perubahan akustik"
    "PROSODY_DIVERGENCE" -> "Perubahan prosodi"
    "BPM_TRAJECTORY", "PHYSIOLOGICAL_BPM_TRAJECTORY", "BPM_RISE", "BPM_RECOVERY" -> "Perubahan BPM"
    "TEMPORAL_BASELINE_AROUSAL", "MODEL_AROUSAL", "STRESS_ELEVATED" -> "Arousal PPG"
    "CHANNEL_AGREEMENT" -> "Kesepakatan kanal"
    "MANUAL" -> "Catatan manual"
    "INCONSISTENCY_CUE" -> "Cue untuk ditinjau"
    "AUXILIARY_EVENT" -> "Observasi tambahan"
    else -> "$type · tinjau bukti"
}
