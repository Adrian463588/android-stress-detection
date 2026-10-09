package com.stressdetector.mobile.domain.search

/** Keeps automatic web discovery limited to the user's bounded question. */
object WebQuerySanitizer {
    private val privateContextLabel = Regex(
        "(?i)(?<![\\p{L}\\p{N}_])(?:session(?:[\\s_-]?id)?|source[\\s_-]?session[\\s_-]?ids|" +
            "stress[\\s_-]?probability|confidence|quality|raw[\\s_-]*ppg|waveform|transcript|" +
            "persona(?:[\\s_-]?markdown)?|stress[\\s_-]?history[\\s_-]?context)[\"'`]?\\s*[:=]"
    )
    private val whitespace = Regex("\\s+")

    fun sanitize(raw: String): Result<String> = runCatching {
        require(!privateContextLabel.containsMatchIn(raw)) {
            "Query web memuat konteks privat berlabel. Masukkan pertanyaan publik terpisah."
        }
        val sanitized = raw
            .replace(whitespace, " ")
            .trim()
            .take(WebSearchLimits.MAX_QUERY_CHARS)
        require(sanitized.isNotBlank()) { "Pertanyaan web tidak memiliki teks yang dapat dicari." }
        sanitized
    }
}
