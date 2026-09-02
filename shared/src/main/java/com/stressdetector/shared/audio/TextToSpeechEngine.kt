package com.stressdetector.shared.audio

/**
 * Defines the contract for synthesizing speech from text.
 */
interface TextToSpeechEngine {

    /**
     * Initializes the TTS engine.
     */
    suspend fun initialize(supportedLanguages: List<String> = listOf("id-ID", "en-US"))

    /**
     * Synthesizes and plays text back to the user.
     * @param text The text to synthesize.
     * @param languageCode The language to use (e.g., "id-ID" or "en-US").
     */
    suspend fun speak(text: String, languageCode: String = "id-ID")

    /**
     * Stops current playback.
     */
    suspend fun stop()

    /**
     * Releases resources.
     */
    suspend fun release()
}
