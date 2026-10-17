package com.stressdetector.mobile.domain.engine

import java.util.Locale

enum class VerbatimCueType(val label: String, val explanation: String) {
    HEDGING("Hedging", "Ungkapan kemungkinan atau pembatas pernyataan; periksa konteks."),
    UNCERTAINTY("Ketidakpastian", "Ungkapan ketidakpastian yang disebutkan pembicara."),
    FILLED_PAUSE("Jeda terisi", "Pengisi jeda yang tertulis pada transkrip."),
    REPETITION("Pengulangan", "Kata diulang berurutan; dengarkan kembali konteksnya."),
    SELF_REPAIR("Koreksi diri", "Pembicara menyatakan koreksi atau perumusan ulang.")
}

enum class VerbatimCueSource { RAW_ASR, USER_EDITED }

data class VerbatimCue(
    val id: String,
    val segmentId: String,
    val type: VerbatimCueType,
    val startChar: Int,
    val endCharExclusive: Int,
    val matchedText: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val source: VerbatimCueSource,
    val ruleId: String,
    val explanation: String,
    val detectorVersion: String
)

data class VerbatimCueAnalysis(val text: String, val cues: List<VerbatimCue>)

/** Normalizes presentation whitespace without removing verbatim evidence. */
object VerbatimTranscriptText {
    private val whitespace = Regex("[\\s\\p{Z}]+")

    fun clean(text: String): String = text.replace(whitespace, " ").trim()
}

/**
 * Literal, versioned review cues. No psychological inference or confidence score.
 * Character spans are exact; timing is the real utterance range, not word timing.
 */
class VerbatimCueAnalyzer {
    fun analyze(segment: SpeechSegment): VerbatimCueAnalysis {
        val text = VerbatimTranscriptText.clean(segment.displayText)
        if (!segment.isFinal || segment.language !in supportedLanguages || segment.recognitionReviewReason != null) {
            return VerbatimCueAnalysis(text, emptyList())
        }
        val tokens = tokenPattern.findAll(text).map { match ->
            Token(match.value.lowercase(Locale.ROOT).replace('’', '\''), match.range.first, match.range.last + 1)
        }.toList()
        val quotes = quotedText.findAll(text).map { it.range }.toList()
        val cues = mutableListOf<VerbatimCue>()
        var index = 0
        while (index < tokens.size) {
            val rule = rules.firstOrNull {
                it.language == segment.language &&
                    matches(it, tokens, index, text) &&
                    !suppressed(it, tokens, index, text, quotes)
            }
            val repetitionEnd = if (rule == null) repetitionEnd(tokens, index, text) else null
            when {
                rule != null -> {
                    val endIndex = index + rule.words.lastIndex
                    cues += cue(segment, text, tokens[index].start until tokens[endIndex].end, rule.type, rule.id)
                    index = endIndex + 1
                }
                repetitionEnd != null && quotes.none { tokens[index].start in it } -> {
                    cues += cue(
                        segment,
                        text,
                        tokens[index].start until tokens[repetitionEnd].end,
                        VerbatimCueType.REPETITION,
                        "${segment.language}.repetition.consecutive"
                    )
                    index = repetitionEnd + 1
                }
                else -> index++
            }
        }
        return VerbatimCueAnalysis(text, cues)
    }

    private fun matches(rule: Rule, tokens: List<Token>, index: Int, text: String): Boolean {
        if (index + rule.words.size > tokens.size) return false
        return rule.words.indices.all { offset ->
            tokens[index + offset].word == rule.words[offset] &&
                (offset == 0 || phraseGap.matches(text.substring(tokens[index + offset - 1].end, tokens[index + offset].start)))
        }
    }

    private fun suppressed(rule: Rule, tokens: List<Token>, index: Int, text: String, quotes: List<IntRange>): Boolean {
        val first = tokens[index]
        val last = tokens[index + rule.words.lastIndex]
        val quoted = quotes.any { first.start <= it.last && last.end > it.first }
        val previous = tokens.getOrNull(index - 1)?.takeIf {
            phraseGap.matches(text.substring(it.end, first.start))
        }?.word
        val negatedHedge = rule.type == VerbatimCueType.HEDGING && previous in negators
        val negatedUncertainty = rule.type == VerbatimCueType.UNCERTAINTY && previous in setOf("bukan", "not")
        val negated = negatedHedge || negatedUncertainty
        val polite = rule.type == VerbatimCueType.HEDGING && politeRequest.containsMatchIn(text.substring(first.start))
        return quoted || negated || polite
    }

    private fun repetitionEnd(tokens: List<Token>, index: Int, text: String): Int? {
        val first = tokens[index]
        if (first.word in ordinaryReduplications || first.word.all(Char::isDigit)) return null
        var end = index
        while (end + 1 < tokens.size &&
            tokens[end + 1].word == first.word &&
            repetitionGap.matches(text.substring(tokens[end].end, tokens[end + 1].start))
        ) {
            end++
        }
        return end.takeIf { it > index }
    }

    private fun cue(segment: SpeechSegment, text: String, span: IntRange, type: VerbatimCueType, ruleId: String) = VerbatimCue(
        id = "${segment.id}:${type.name}:${span.first}:${span.last + 1}",
        segmentId = segment.id,
        type = type,
        startChar = span.first,
        endCharExclusive = span.last + 1,
        matchedText = text.substring(span),
        startOffsetMs = segment.startOffsetMs,
        endOffsetMs = segment.endOffsetMs,
        source = if (segment.editedText == null) VerbatimCueSource.RAW_ASR else VerbatimCueSource.USER_EDITED,
        ruleId = ruleId,
        explanation = type.explanation,
        detectorVersion = VERSION
    )

    private data class Token(val word: String, val start: Int, val end: Int)
    private data class Rule(val language: String, val type: VerbatimCueType, val words: List<String>, val id: String)

    companion object {
        const val VERSION = "verbatim-lexicon-id-en-v2"
        private val supportedLanguages = setOf("id", "en")
        private val tokenPattern = Regex("[\\p{L}\\p{M}\\p{N}]+(?:[-’'][\\p{L}\\p{M}\\p{N}]+)*")
        private val phraseGap = Regex("[\\s\\p{Z}]+")
        private val repetitionGap = Regex("[,\\s\\p{Z}]+")
        private val quotedText = Regex("\"[^\"]*\"|“[^”]*”|‘[^’]*’|(?<![\\p{L}\\p{N}])'[^']*'(?![\\p{L}\\p{N}])")
        private val negators = setOf("tidak", "tak", "nggak", "gak", "bukan", "not", "never")
        private val ordinaryReduplications = setOf("anak", "hati", "baik", "sama", "jalan", "hari", "apa", "siapa", "kira", "laki")
        private val politeRequest = Regex(
            "^(?:mungkin|barangkali|perhaps|maybe)\\s+" +
                "(?:(?:anda|kamu|bapak|ibu)\\s+(?:bisa|boleh)|(?:bisa|boleh)\\s+(?:anda|kamu|bapak|ibu)|you\\s+(?:can|could))\\s+" +
                "(?:menjelaskan|jelaskan|menceritakan|ceritakan|explain|tell)\\b",
            RegexOption.IGNORE_CASE
        )
        private val rules = buildList {
            addRules(
                "id", VerbatimCueType.HEDGING,
                "mungkin", "kayaknya", "sepertinya", "barangkali", "agaknya", "tampaknya", "seingat saya", "setahu saya",
                "kalau tidak salah", "kira-kira", "kira kira", "kurang lebih", "bisa jadi"
            )
            addRules(
                "id", VerbatimCueType.UNCERTAINTY,
                "tidak yakin", "nggak yakin", "gak yakin", "tak yakin", "kurang yakin", "tidak pasti", "kurang pasti",
                "saya ragu", "saya tidak tahu", "saya kurang tahu", "saya tidak ingat", "saya lupa", "entahlah", "apa ya"
            )
            addRules("id", VerbatimCueType.FILLED_PAUSE, "hmm", "hm", "emm", "eee", "ee", "anu")
            addRules("id", VerbatimCueType.SELF_REPAIR, "eh maksud saya", "maksud saya", "lebih tepatnya", "ralat")
            addRules(
                "en", VerbatimCueType.HEDGING,
                "maybe", "perhaps", "probably", "possibly", "i think", "i suppose", "as far as i know",
                "approximately", "roughly", "sort of", "kind of"
            )
            addRules(
                "en", VerbatimCueType.UNCERTAINTY,
                "i don't know", "i do not know", "i am not sure", "i'm not sure", "i cannot remember",
                "i don't remember", "i am uncertain"
            )
            addRules("en", VerbatimCueType.FILLED_PAUSE, "um", "uh", "hmm", "erm")
            addRules("en", VerbatimCueType.SELF_REPAIR, "i mean", "let me correct", "more precisely", "rather i")
        }.sortedByDescending { it.words.size }

        private fun MutableList<Rule>.addRules(language: String, type: VerbatimCueType, vararg phrases: String) {
            phrases.forEach { phrase ->
                add(Rule(language, type, phrase.split(' '), "$language.${type.name.lowercase(Locale.ROOT)}.${phrase.replace(' ', '_')}"))
            }
        }
    }
}
