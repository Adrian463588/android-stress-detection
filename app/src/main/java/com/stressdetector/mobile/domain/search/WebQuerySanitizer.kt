package com.stressdetector.mobile.domain.search

/** Keeps automatic web discovery limited to the user's bounded question. */
object WebQuerySanitizer {
    private val privateContextLine = Regex(
        "(?im)^\\s*(?:session(?:Id|_id)?|sourceSessionIds|stressProbability|confidence|quality|" +
            "raw\\s*ppg|waveform|transcript|persona(?:Markdown)?|stressHistoryContext)\\s*[:=].*(?:\\r?\\n|$)"
    )
    private val whitespace = Regex("\\s+")

    fun sanitize(raw: String): Result<String> = runCatching {
        val sanitized = raw
            .replace(privateContextLine, " ")
            .replace(whitespace, " ")
            .trim()
            .take(WebSearchLimits.MAX_QUERY_CHARS)
        require(sanitized.isNotBlank()) { "Pertanyaan web tidak memiliki teks yang dapat dicari." }
        sanitized
    }
}
