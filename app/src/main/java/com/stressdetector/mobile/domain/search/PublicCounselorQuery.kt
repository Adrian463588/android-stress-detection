package com.stressdetector.mobile.domain.search

/** Automatic networking never receives the raw personal message or its history. */
object PublicCounselorQuery {
    private val topics = linkedMapOf(
        "jurnal|journaling|journal" to "journaling harian untuk mengelola stres",
        "tidur|insomnia|sleep" to "kebiasaan tidur sehat mengelola stres",
        "cemas|anxiety|anxious|khawatir" to "cara mengelola kecemasan sehari-hari",
        "burnout|kelelahan|lelah|overwhelmed" to "mengelola kelelahan dan stres sehari-hari",
        "duka|kehilangan|grief" to "dukungan menghadapi duka dan kehilangan",
        "coping|stres|stress|tertekan" to "cara mengelola stres sehari-hari"
    ).map { (terms, query) -> Regex("(?i)(?<![\\p{L}])(?:$terms)(?![\\p{L}])") to query }

    private val excludedClause = Regex("(?i)\\b(?:jangan|bukan|not about|don't discuss|do not discuss)\\b")

    fun fromMessage(message: String): String? {
        val clauses = message.split(Regex("[.!?;\\n]")).filterNot(excludedClause::containsMatchIn)
        val matches = topics.filter { (pattern, _) -> clauses.any(pattern::containsMatchIn) }
        val specific = matches.filterNot { it == topics.last() }
        // Ambiguous topics need an edited public query, never an arbitrary dictionary-first choice.
        return when {
            specific.size == 1 -> specific.single().second
            specific.isNotEmpty() -> null
            else -> matches.singleOrNull()?.second
        }
    }
}
