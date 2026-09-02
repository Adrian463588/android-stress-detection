package com.stressdetector.mobile.domain.engine

/** Applies partial/final transcript events without crossing session boundaries. */
internal class LiveTranscriptReducer {
    private val lock = Any()
    private val speakerOverrides = mutableMapOf<String, String>()
    private val textOverrides = mutableMapOf<String, String>()

    /** User edits outlive any partial/final replacement for the same segment. */
    fun setSpeakerOverride(segmentId: String, speakerId: String) {
        if (segmentId.isBlank() || speakerId.isBlank()) return
        synchronized(lock) { speakerOverrides[segmentId] = speakerId }
    }

    /** User text edits outlive any partial/final replacement for the same segment. */
    fun setTextOverride(segmentId: String, text: String) {
        if (segmentId.isBlank()) return
        synchronized(lock) { textOverrides[segmentId] = text }
    }

    fun applyUserOverrides(segment: SpeechSegment): SpeechSegment = synchronized(lock) {
        segment.copy(
            speakerId = speakerOverrides[segment.id] ?: segment.speakerId,
            editedText = textOverrides[segment.id] ?: segment.editedText
        )
    }

    fun reset() = synchronized(lock) {
        speakerOverrides.clear()
        textOverrides.clear()
    }

    fun accept(state: LiveTranscriptState, segment: SpeechSegment, expectedSessionId: String): LiveTranscriptState {
        if (state.sessionId != expectedSessionId) return state
        val normalized = if (segment.sessionId.isBlank()) {
            segment.copy(sessionId = expectedSessionId)
        } else {
            segment
        }
        if (normalized.sessionId != expectedSessionId) return state

        val effective = applyUserOverrides(normalized)
        val index = state.segments.indexOfFirst { it.id == effective.id }
        val segments = if (index >= 0) {
            state.segments.toMutableList().also { it[index] = effective }
        } else {
            state.segments + effective
        }
        return state.copy(segments = segments)
    }
}
