package com.stressdetector.shared.audio

import kotlinx.coroutines.flow.Flow

/**
 * Defines the contract for distinguishing between different speakers (e.g., patient vs. clinician).
 */
interface SpeakerDiarizer {

    /**
     * Emits diarization events indicating who spoke when.
     */
    val diarizationEvents: Flow<DiarizedSegment>

    /**
     * Initializes the diarization engine.
     */
    suspend fun initialize()

    /**
     * Processes an audio segment and determines the speaker.
     */
    suspend fun processAudio(segment: AudioSegment)

    /**
     * Releases resources.
     */
    suspend fun release()
}

/**
 * Represents a segment of audio assigned to a specific speaker.
 */
data class DiarizedSegment(val speakerId: String, val startTimestampUtc: Long, val endTimestampUtc: Long)
