package com.stressdetector.mobile.domain.engine

import java.util.Locale

/** Index in the caller's oldest-first history, with an optional persisted message identity. */
data class CounselorMemorySource(val turnIndex: Int, val sourceId: String? = null)

/** Literal user excerpt, never a generated account of an earlier conversation. */
data class CounselorMemoryEpisode(val text: String, val source: CounselorMemorySource)

enum class CounselorMemoryFactKind { GOAL, PREFERENCE, SELF_REPORT }

/** An attributed statement, including its original negation; SELF_REPORT does not establish a diagnosis. */
data class CounselorMemoryFact(val kind: CounselorMemoryFactKind, val text: String, val source: CounselorMemorySource)

data class CounselorMemorySnapshot(
    val recentTurns: List<ConversationTurn>,
    val episodes: List<CounselorMemoryEpisode>,
    val facts: List<CounselorMemoryFact>
)

/**
 * Pure projection of persisted turns from ONE chat, supplied oldest first. No storage, cache, or generation.
 * The query selects older excerpts only and never becomes a persisted user fact. All text remains untrusted data.
 * Recognized explicit statements use literal topic slots; newer statements in the same slot replace older ones.
 * Extraction inspects at most the first [MAX_SOURCE_TURN_CHARS] characters per turn. Unrecognized language abstains.
 */
class CounselorConversationMemory {
    fun build(history: List<ConversationTurn>, query: String): CounselorMemorySnapshot {
        val recent = history.indices.reversed().asSequence()
            .mapNotNull { index -> admittedTurn(history[index])?.let { index to it } }
            .take(MAX_RECENT_TURNS)
            .toList()
            .asReversed()
        val queryWords = cleanSentences(query.take(MAX_QUERY_CHARS))
            .flatMap(::words)
            .take(MAX_QUERY_WORDS)
            .toSet()
        return CounselorMemorySnapshot(
            recentTurns = recent.map { it.second },
            episodes = episodes(history, recent.firstOrNull()?.first ?: history.size, queryWords, recent.map { it.second }),
            facts = facts(history)
        )
    }

    private fun admittedTurn(turn: ConversationTurn): ConversationTurn? {
        val role = role(turn)
        if (role != "user" && role != "assistant") return null
        val content = boundedText(sanitizeUntrustedText(turn.content.take(MAX_SOURCE_TURN_CHARS)), MAX_RECENT_TURN_CHARS)
        return content.takeIf(String::isNotBlank)?.let { turn.copy(role = role, content = it) }
    }

    private fun facts(history: List<ConversationTurn>): List<CounselorMemoryFact> {
        val usedSlots = mutableSetOf<String>()
        return history.indices.reversed().asSequence()
            .filter { role(history[it]) == "user" }
            .flatMap { index ->
                val source = CounselorMemorySource(index, history[index].sourceId)
                cleanSentences(history[index].content).toList().asReversed().asSequence()
                    .mapNotNull { statement -> explicitFact(statement, source) }
            }
            .filter { usedSlots.add(it.slot) }
            .take(MAX_FACTS)
            .map { it.fact }
            .toList()
    }

    private fun explicitFact(statement: String, source: CounselorMemorySource): FactStatement? {
        if ('?' in statement) return null
        return FACT_RULES.firstNotNullOfOrNull { rule ->
            rule.pattern.matchEntire(statement)?.let { match ->
                val target = literalTarget(match.groupValues[1])
                val topic = rule.topic ?: if (rule.kind == CounselorMemoryFactKind.GOAL) {
                    target.takeIf { words(it).any() }
                } else {
                    words(match.groupValues[1]).firstOrNull()
                }
                topic?.let {
                    FactStatement(
                        slot = "${rule.kind}:$it",
                        literalSlot = "${rule.kind}:$target",
                        fact = CounselorMemoryFact(rule.kind, boundedText(statement, MAX_FACT_CHARS), source)
                    )
                }
            }
        }
    }

    private fun literalTarget(text: String): String = normalized(text)
        .trimEnd('.', '!', ';')
        .trimEnd()
        .replace(LEADING_TARGET_NEGATION, "")
        .replace(TRAILING_CORRECTION_WORDS, "")

    private fun episodes(
        history: List<ConversationTurn>,
        olderEnd: Int,
        queryWords: Set<String>,
        recent: List<ConversationTurn>
    ): List<CounselorMemoryEpisode> {
        if (queryWords.isEmpty()) return emptyList()
        val selected = mutableListOf<ScoredEpisode>()
        val seenLiteralSlots = mutableSetOf<String>()
        val userIndices = history.indices.reversed().asSequence().filter { role(history[it]) == "user" }
        for (index in userIndices) {
            val turn = history[index]
            val source = CounselorMemorySource(index, turn.sourceId)
            // Track newer statements even outside the fact budget. Only literal targets suppress older recall.
            val statements = cleanSentences(turn.content).toList().asReversed().filter { statement ->
                val fact = explicitFact(statement, source)
                fact == null || seenLiteralSlots.add(fact.literalSlot)
            }
            if (index >= olderEnd) continue
            val best = statements.asSequence()
                .map { boundedText(it, MAX_EPISODE_CHARS) }
                .map { text -> text to words(text).toSet().count { it in queryWords } }
                .filter { (text, score) -> score > 0 && recent.none { it.role == "user" && it.content.contains(text, ignoreCase = true) } }
                .maxByOrNull { it.second }
            if (best != null && selected.none { normalized(it.episode.text) == normalized(best.first) }) {
                val episode = CounselorMemoryEpisode(best.first, source)
                selected += ScoredEpisode(episode, best.second)
                selected.sortWith(compareByDescending<ScoredEpisode> { it.score }.thenByDescending { it.episode.source.turnIndex })
                if (selected.size > MAX_EPISODES) selected.removeAt(selected.lastIndex)
            }
        }
        return selected.map { it.episode }
    }

    private fun cleanSentences(text: String): Sequence<String> = text.take(MAX_SOURCE_TURN_CHARS)
        .lineSequence()
        .map(String::trim)
        // Reuse the prompt boundary's sanitizer. Removed instruction lines cannot become facts or recall evidence.
        .filter { it.isNotBlank() && sanitizeUntrustedText(it) == it }
        // Exclude whole quoted lines before splitting; inner sentences must not lose their attribution.
        .filterNot { QUOTE_MARKER.containsMatchIn(it) }
        .flatMap { it.splitToSequence(SENTENCE_BOUNDARY) }
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun words(text: String): Sequence<String> = WORD.findAll(text.lowercase(Locale.ROOT))
        .map { it.value }
        .filter { it.length >= MIN_WORD_CHARS && it !in STOP_WORDS }

    private fun normalized(text: String): String = text.lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()

    private fun role(turn: ConversationTurn): String = turn.role.trim().lowercase(Locale.ROOT)

    private data class FactRule(val kind: CounselorMemoryFactKind, val pattern: Regex, val topic: String? = null)
    private data class FactStatement(val slot: String, val literalSlot: String, val fact: CounselorMemoryFact)
    private data class ScoredEpisode(val episode: CounselorMemoryEpisode, val score: Int)

    companion object {
        const val MAX_RECENT_TURNS = 6
        const val MAX_RECENT_TURN_CHARS = 480
        const val MAX_EPISODES = 3
        const val MAX_EPISODE_CHARS = 240
        const val MAX_FACTS = 6
        const val MAX_FACT_CHARS = 180
        const val MAX_SOURCE_TURN_CHARS = 4_096
        private const val MAX_QUERY_CHARS = 512
        private const val MAX_QUERY_WORDS = 32
        private const val MIN_WORD_CHARS = 3
        private const val SELF_ID = "(?:saya|aku)"
        private const val NEGATION_ID = "(?:(?:sudah\\s+)?(?:tidak|tak|nggak|gak)(?:\\s+lagi)?\\s+)?"
        private const val NEGATION_EN = "(?:(?:do\\s+not|don't|don’t|no\\s+longer)\\s+)?"
        private const val SELF_EN = "i(?:\\s+am|['’]m)"
        private const val STATE_NEGATION_EN = "(?:(?:not|no\\s+longer)\\s+)?"
        private val WORD = Regex("[\\p{L}\\p{N}]+")
        private val WHITESPACE = Regex("\\s+")
        private val SENTENCE_BOUNDARY = Regex("(?<=[.!?;])\\s+")
        private val QUOTE_MARKER = Regex("[\"“”„«»]|(?<![\\p{L}\\p{N}])['‘’]")
        private val LEADING_TARGET_NEGATION = Regex("^(?:(?:sudah\\s+)?(?:tidak|tak|nggak|gak)(?:\\s+lagi)?|not|no\\s+longer)\\s+")
        private val TRAILING_CORRECTION_WORDS = Regex("\\s+(?:lagi|anymore|any\\s+more)$")
        private val STOP_WORDS = setOf(
            "saya", "aku", "anda", "kamu", "ini", "itu", "yang", "dan", "atau", "untuk", "dengan", "dari", "pada", "dalam",
            "tentang", "apa", "bagaimana", "apakah", "bisa", "tolong", "ingat", "tadi", "lagi", "lebih", "tidak", "tak", "nggak",
            "gak", "bukan", "masih", "sudah", "sedang", "ingin", "mau", "merasa", "adalah", "sangat", "sekarang", "pernah", "saat",
            "mungkin", "akan", "the", "and", "for", "with", "from", "about", "what", "how", "can", "could", "please", "remember",
            "want", "like", "prefer", "feel", "feeling", "not", "don", "longer", "very", "more", "now", "today", "again", "are",
            "was", "been", "that", "this", "have", "has", "would", "should", "much", "just", "really"
        )
        private val FACT_RULES = listOf(
            factRule(
                CounselorMemoryFactKind.GOAL,
                "(?:$SELF_ID\\s+$NEGATION_ID(?:ingin|mau|berharap|bertujuan)|" +
                    "i\\s+$NEGATION_EN(?:want|hope|aim|would\\s+like)|(?:tujuan|target)\\s+saya(?:\\s+adalah)?|my\\s+goal(?:\\s+is)?)"
            ),
            factRule(
                CounselorMemoryFactKind.PREFERENCE,
                "(?:$SELF_ID\\s+$NEGATION_ID(?:lebih\\s+)?(?:suka|memilih|nyaman\\s+dengan)|" +
                    "i\\s+$NEGATION_EN(?:prefer|like|dislike))"
            ),
            factRule(
                CounselorMemoryFactKind.SELF_REPORT,
                "(?:$SELF_ID\\s+$NEGATION_ID(?:sedang\\s+)?merasa|" +
                    "i\\s+${NEGATION_EN}feel|$SELF_EN\\s+${STATE_NEGATION_EN}feeling|" +
                    "i\\s+(?:have\\s+(?:not\\s+)?|haven['’]t\\s+)been\\s+${STATE_NEGATION_EN}feeling)",
                topic = "feeling"
            ),
            factRule(
                CounselorMemoryFactKind.SELF_REPORT,
                "(?:$SELF_ID(?=\\s+$NEGATION_ID(?:cemas|lelah|capek|sedih|takut|khawatir|tenang|marah|stres|bingung)\\b)|" +
                    "$SELF_EN(?=\\s+$STATE_NEGATION_EN(?:sad|tired|anxious|afraid|worried|calm|angry|stressed)\\b))",
                topic = "feeling"
            ),
            factRule(
                CounselorMemoryFactKind.SELF_REPORT,
                "(?:$SELF_ID\\s+(?:sedang|mengalami|kesulitan|sulit|susah)|" +
                    "i\\s+(?:am\\s+experiencing|struggle\\s+with|have\\s+trouble\\s+with))"
            )
        )

        private fun factRule(kind: CounselorMemoryFactKind, prefix: String, topic: String? = null): FactRule =
            FactRule(kind, Regex("^$prefix\\s+(.+)$", RegexOption.IGNORE_CASE), topic)
    }
}

private fun boundedText(text: String, limit: Int): String {
    val trimmed = text.trim()
    var end = minOf(trimmed.length, limit)
    if (end < trimmed.length && end > 0 && trimmed[end - 1].isHighSurrogate()) end--
    return trimmed.substring(0, end).trimEnd()
}
