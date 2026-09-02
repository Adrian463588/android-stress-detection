package com.stressdetector.shared.audio

import kotlinx.coroutines.flow.Flow

/**
 * Defines the contract for converting speech to text.
 * Must support both Indonesian and English.
 */
interface SpeechToTextEngine {

    /**
     * Emits transcripts as they are recognized.
     */
    val transcripts: Flow<Transcript>

    /**
     * Initializes the engine, preparing it for the specified languages (e.g., id-ID, en-US).
     */
    suspend fun initialize(supportedLanguages: List<String> = listOf("id-ID", "en-US"))

    /**
     * Processes an audio segment.
     */
    suspend fun processAudio(segment: AudioSegment)

    /**
     * Releases resources.
     */
    suspend fun release()
}

/**
 * Represents a recognized text transcript from audio.
 */
data class Transcript(val text: String, val startTimestampUtc: Long, val endTimestampUtc: Long, val languageCode: String)
