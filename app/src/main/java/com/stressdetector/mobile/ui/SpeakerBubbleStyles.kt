package com.stressdetector.mobile.ui

/** Stable visual identity for a diarized speaker cluster. */
enum class SpeakerBubbleTone {
    FIRST,
    SECOND
}

/**
 * Maps canonical speaker labels to stable UI tones.
 * Unknown labels use a deterministic hash so color does not change when list order changes.
 */
interface SpeakerBubbleToneResolver {
    fun resolve(speakerId: String): SpeakerBubbleTone
}

object DefaultSpeakerBubbleToneResolver : SpeakerBubbleToneResolver {
    override fun resolve(speakerId: String): SpeakerBubbleTone = when (speakerId.trim().lowercase()) {
        "speaker_0", "psychologist" -> SpeakerBubbleTone.FIRST
        "speaker_1", "patient" -> SpeakerBubbleTone.SECOND
        else -> if (stableIndex(speakerId) == 0) SpeakerBubbleTone.FIRST else SpeakerBubbleTone.SECOND
    }

    private fun stableIndex(value: String): Int = (value.trim().lowercase().hashCode() and Int.MAX_VALUE) % 2
}
