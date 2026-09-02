package com.stressdetector.mobile.domain.engine

import java.io.File

/**
 * Interface-First clean abstraction for audio recording and session audio management.
 */
interface AudioCaptureEngine {
    fun isRecording(): Boolean
    fun startRecording(outputFile: File, userConsentGranted: Boolean, onAudioLevel: (Float) -> Unit = {}): Boolean
    fun pauseRecording(): Boolean
    fun resumeRecording(): Boolean
    fun stopRecording(): File?
    fun getDurationMillis(): Long
}

/**
 * Segment model for Speech-to-Text transcription outputs.
 */
data class SpeechSegment(
    val id: String,
    val speakerId: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val startTimestampUtc: Long,
    val endTimestampUtc: Long,
    val rawText: String,
    val confidence: Float,
    val language: String = "id",
    val isDisfluent: Boolean = false,
    /** Session identity prevents late native events leaking into a new session. */
    val sessionId: String = "",
    /** False is a replaceable live partial; true is persisted final transcript. */
    val isFinal: Boolean = true,
    /** Optional user-edited text; rawText remains the immutable STT output. */
    val editedText: String? = null
) {
    /** Never present an inferred speaker identity before endpoint diarization. */
    val displaySpeakerLabel: String
        get() = if (isFinal) speakerId else "Diarisasi sedang dianalisis"

    val displayText: String
        get() = editedText ?: rawText
}

/**
 * Interface-First offline Speech-to-Text engine.
 */
interface SpeechToTextEngine {
    suspend fun transcribeAudio(audioFile: File, languagePreference: String = "id"): List<SpeechSegment>
}

/**
 * Interface-First Speaker Diarization engine.
 */
interface SpeakerDiarizer {
    suspend fun diarize(audioFile: File, expectedSpeakers: Int = 2): Map<String, String> // segmentId to speakerId
}

/**
 * Interface-First Timestamp Aligner for appending UTC time to elapsed offsets.
 */
interface TimestampAligner {
    fun alignOffsetsToUtc(sessionStartUtc: Long, segments: List<SpeechSegment>): List<SpeechSegment>
}

/**
 * Interface-First Text-to-Speech playback engine.
 */
interface TextToSpeechEngine {
    fun speak(text: String, language: String = "id", onComplete: () -> Unit = {})
    fun stop()
}
