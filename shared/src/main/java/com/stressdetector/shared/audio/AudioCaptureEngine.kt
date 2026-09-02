package com.stressdetector.shared.audio

import kotlinx.coroutines.flow.Flow

/**
 * Defines the contract for an engine that captures audio.
 *
 * EXPLICIT CONSENT REQUIRED:
 * Calling [startCapture] MUST be preceded by explicit user consent,
 * typically documented and enforced at the UI/ViewModel layer.
 * No audio capture should initiate without the user explicitly starting a session.
 */
interface AudioCaptureEngine {

    /**
     * Emits segmented audio.
     * Segments speech deterministically via VAD.
     * Maintains UTC-aligned timestamps per segment.
     */
    val audioSegments: Flow<AudioSegment>

    /**
     * Starts audio capture. Requires explicit user consent before calling.
     */
    suspend fun startCapture()

    /**
     * Stops audio capture.
     */
    suspend fun stopCapture()
}

/**
 * Represents a segmented chunk of audio.
 */
data class AudioSegment(val audioData: ByteArray, val startTimestampUtc: Long, val endTimestampUtc: Long)
