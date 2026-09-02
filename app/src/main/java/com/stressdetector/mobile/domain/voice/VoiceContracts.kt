package com.stressdetector.mobile.domain.voice

import kotlinx.coroutines.flow.StateFlow

/** Languages supported by the voice counselor pipeline. */
enum class VoiceLanguage {
    INDONESIAN,
    ENGLISH
}

/** Controls whether a recognized turn is reviewed or sent immediately. */
enum class VoiceSendMode {
    REVIEW,
    AUTO_SEND
}

enum class VoiceBackend {
    SUPERTONIC3,
    PIPER_LEGACY,
    ANDROID_TTS
}

sealed interface VoiceInputState {
    data object Idle : VoiceInputState
    data object RequestingPermission : VoiceInputState
    data class Initializing(val stage: String) : VoiceInputState
    data object Listening : VoiceInputState
    data object Transcribing : VoiceInputState
    data class TranscriptReady(val text: String) : VoiceInputState
    data class Error(val message: String, val retryable: Boolean) : VoiceInputState
}

sealed interface VoiceOutputState {
    data object Idle : VoiceOutputState
    data class Preparing(val messageId: String? = null, val backend: VoiceBackend? = null) : VoiceOutputState
    data class Speaking(val messageId: String? = null, val backend: VoiceBackend? = null) : VoiceOutputState
    data object Stopping : VoiceOutputState
    data object Stopped : VoiceOutputState
    data class Error(val message: String, val backend: VoiceBackend? = null) : VoiceOutputState
}

/** Message-scoped playback state exposed to AI+ presentation. */
data class VoicePlaybackUiState(
    val activeMessageId: String? = null,
    val state: VoiceOutputState = VoiceOutputState.Idle,
    val error: String? = null,
    val backend: VoiceBackend? = null
)

/**
 * Converts display-oriented counselor text into bounded, locally speakable text.
 * It deliberately keeps sentence punctuation while removing markup and citations
 * that sound unnatural when read aloud.
 */
object VoiceTextNormalizer {
    private const val MAX_SPOKEN_CHARS = 1_200
    private val citationPattern = Regex("\\[S\\d+\\|[^\\]]+\\]")
    private val markdownLinkPattern = Regex("""\[([^\]]+)\]\(https?://[^)]+\)""")
    private val urlPattern = Regex("https?://\\S+")
    private val disclaimerPattern = Regex(
        "(?is)\\n+\\s*(?:Catatan|Note):\\s*respons ini adalah dukungan umum dan bukan diagnosis medis\\.?\\s*$"
    )

    fun normalize(text: String): String {
        var normalized = text.trim()
            .replace(disclaimerPattern, "")
            .replace(markdownLinkPattern, "$1")
            .replace(citationPattern, "")
            .replace(urlPattern, "")
            .replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
            .replace(Regex("[*_~`]+"), "")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        if (normalized.length > MAX_SPOKEN_CHARS) {
            normalized = normalized.take(MAX_SPOKEN_CHARS).trimEnd()
            val lastBoundary = normalized.lastIndexOfAny(charArrayOf('.', '!', '?', ',', ';', ':'))
            if (lastBoundary >= MAX_SPOKEN_CHARS / 2) normalized = normalized.take(lastBoundary + 1)
        }
        return normalized
    }
}

interface VoiceInputEngine {
    val state: StateFlow<VoiceInputState>

    suspend fun start(language: VoiceLanguage)

    fun stop()

    fun cancel()

    fun close()
}

interface VoiceOutputEngine {
    val state: StateFlow<VoiceOutputState>

    suspend fun speak(messageId: String, text: String, language: VoiceLanguage): Result<VoiceBackend>

    suspend fun stopAndWait(): Result<Unit>

    /** Stops output, waits for native rendering, and releases owned resources. */
    suspend fun closeAndWait() {
        close()
    }

    /** Compatibility wrapper for lifecycle callers that do not need a result. */
    suspend fun close()
}
