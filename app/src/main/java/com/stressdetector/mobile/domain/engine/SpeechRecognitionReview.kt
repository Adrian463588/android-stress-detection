package com.stressdetector.mobile.domain.engine

import java.util.Locale

/** Decoder output at its actual PCM bounds; these are not word timestamps. */
data class SpeechDecoderResult(
    val backend: String,
    val modelRevision: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val rawText: String
)

enum class SpeechRecognitionStatus { PARTIAL, DECODED, EMPTY_FINAL, EMPTY_CROP, SPEAKER_SPANS_UNAVAILABLE }

/** Whole and crop results remain separate; a blank final never borrows partial text. */
data class SpeechDecoderEvidence(
    val wholeUtterance: SpeechDecoderResult,
    val crop: SpeechDecoderResult? = null,
    val precedingPartial: SpeechDecoderResult? = null,
    val streamingFinal: SpeechDecoderResult? = null,
    val status: SpeechRecognitionStatus = SpeechRecognitionStatus.DECODED
)

/** Review policy, not an ASR accuracy score or a transcript cleaner. */
object SpeechRecognitionReview {
    const val VERSION = "asr-review-id-en-v1"
    private const val MIN_REPETITIONS = 4
    private const val MAX_PHRASE_WORDS = 6
    private val words = Regex("[\\p{L}\\p{M}\\p{N}]+")

    fun reason(rawText: String, evidence: SpeechDecoderEvidence?): String? = when (evidence?.status) {
        SpeechRecognitionStatus.EMPTY_FINAL -> "Hasil akhir ASR kosong. Tinjau audio dan hipotesis sebelumnya bila tersedia."
        SpeechRecognitionStatus.EMPTY_CROP -> "Hasil ASR pada rentang pembicara ini kosong. Tinjau audio dan bukti ujaran utuh."
        SpeechRecognitionStatus.SPEAKER_SPANS_UNAVAILABLE -> "Rentang ucapan native belum tersedia. Teks ujaran memerlukan tinjauan audio."
        else -> when {
            rawText.isBlank() -> "Teks ASR kosong. Tinjau audio; teks kosong bukan pernyataan yang terverifikasi."
            hasSuspiciousRepetition(rawText) || evidence?.wholeUtterance?.rawText?.let(::hasSuspiciousRepetition) == true ->
                "Pengulangan output ASR memerlukan tinjauan audio; belum menjadi bukti pengulangan pembicara."
            else -> null
        }
    }

    private fun hasSuspiciousRepetition(text: String): Boolean {
        val tokens = words.findAll(text).map { it.value.lowercase(Locale.ROOT) }.toList()
        return tokens.indices.any { start ->
            val longest = minOf(MAX_PHRASE_WORDS, (tokens.size - start) / MIN_REPETITIONS)
            (1..longest).any { length ->
                (length until length * MIN_REPETITIONS).all { offset ->
                    tokens[start + offset] == tokens[start + offset % length]
                }
            }
        }
    }
}
