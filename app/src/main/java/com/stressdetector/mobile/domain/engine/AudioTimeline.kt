package com.stressdetector.mobile.domain.engine

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Maps the session clock (which includes pause/dropout gaps) to WAV time. */
class AudioTimeline(gaps: List<AudioGap>) {
    private val gaps: List<AudioGap> = gaps
        .filter { !it.recordedPcmPresent && it.startOffsetMs >= 0L && it.durationMs > 0L }
        .sortedBy(AudioGap::startOffsetMs)
        .fold(mutableListOf<AudioGap>()) { normalized, gap ->
            val previous = normalized.lastOrNull()
            if (previous != null && gap.startOffsetMs <= previous.startOffsetMs + previous.durationMs) {
                val mergedEnd = maxOf(
                    previous.startOffsetMs + previous.durationMs,
                    gap.startOffsetMs + gap.durationMs
                )
                normalized[normalized.lastIndex] = previous.copy(
                    durationMs = mergedEnd - previous.startOffsetMs,
                    reason = listOf(previous.reason, gap.reason)
                        .filter(String::isNotBlank)
                        .distinct()
                        .joinToString("+")
                )
            } else {
                normalized += gap
            }
            normalized
        }

    fun sessionOffsetToAudioOffset(sessionOffsetMs: Long): Long {
        require(sessionOffsetMs >= 0L) { "sessionOffsetMs must not be negative" }
        var removedGapMillis = 0L
        for (gap in gaps) {
            if (sessionOffsetMs <= gap.startOffsetMs) break
            val gapEnd = gap.startOffsetMs + gap.durationMs
            if (sessionOffsetMs < gapEnd) {
                return (gap.startOffsetMs - removedGapMillis).coerceAtLeast(0L)
            }
            removedGapMillis += gap.durationMs
        }
        return (sessionOffsetMs - removedGapMillis).coerceAtLeast(0L)
    }

    /** Inverse mapping used to keep playback position on the session clock. */
    fun audioOffsetToSessionOffset(audioOffsetMs: Long): Long {
        require(audioOffsetMs >= 0L) { "audioOffsetMs must not be negative" }
        var removedGapMillis = 0L
        for (gap in gaps) {
            val audioGapStart = (gap.startOffsetMs - removedGapMillis).coerceAtLeast(0L)
            if (audioOffsetMs < audioGapStart) break
            removedGapMillis += gap.durationMs
        }
        return audioOffsetMs + removedGapMillis
    }

    fun sessionDurationForAudio(audioDurationMs: Long): Long {
        require(audioDurationMs >= 0L) { "audioDurationMs must not be negative" }
        var removedGapMillis = 0L
        for (gap in gaps) {
            val audioGapStart = (gap.startOffsetMs - removedGapMillis).coerceAtLeast(0L)
            if (audioDurationMs < audioGapStart) break
            removedGapMillis += gap.durationMs
        }
        return audioDurationMs + removedGapMillis
    }
}

internal object AudioGapCodec {
    private val listType = object : TypeToken<List<AudioGap>>() {}.type

    fun encode(gaps: List<AudioGap>): String = Gson().toJson(gaps, listType)

    fun decode(json: String): List<AudioGap> = runCatching {
        Gson().fromJson<List<AudioGap>>(json, listType).orEmpty()
    }.getOrDefault(emptyList())
}
