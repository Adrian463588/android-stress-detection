package com.stressdetector.mobile.domain.assessment

import java.util.Locale

data class AssessmentInstrument(
    val id: String,
    val title: String,
    val category: String,
    val description: String,
    val source: String,
    val aliases: List<String>,
    val form: AssessmentForm? = null,
    val referenceUrl: String? = null,
    val protocol: AssessmentProtocol? = null
)

data class AssessmentRecommendation(val instrumentId: String, val title: String)

data class CounselingKnowledgeModule(
    val id: String,
    val title: String,
    val pillar: String,
    val category: String,
    val summary: String,
    val assetFileName: String,
    val keyTopics: List<String>
)

data class AssessmentForm(
    val version: String,
    val title: String,
    val instructions: String,
    val sourceTitle: String,
    val sourceUrl: String,
    val sourceLocator: String,
    val choices: List<String>,
    val values: List<Int>,
    val scales: List<AssessmentScale>,
    val items: List<AssessmentItem>,
    val average: Boolean,
    val minimumAnswers: Int,
    val interpretationNote: String
)

data class AssessmentItem(
    val text: String,
    val scale: String,
    val reverse: Boolean = false,
    val choices: List<String>? = null,
    val imageAssetPath: String? = null,
    val multiSelect: Boolean = false,
    val maxSelections: Int = 1
)

data class AssessmentScale(val id: String, val label: String, val meaning: String, val minimumAnswers: Int? = null)

data class AssessmentScore(val label: String, val value: Double, val minimum: Double, val maximum: Double, val meaning: String) {
    fun displayValue(): String = format(value)

    fun displayRange(): String = "${format(minimum)}–${format(maximum)}"

    private fun format(number: Double): String = if (number == number.toInt().toDouble()) {
        number.toInt().toString()
    } else {
        String.format(Locale.forLanguageTag("id-ID"), "%.2f", number).trimEnd('0').trimEnd(',')
    }
}

data class AssessmentResult(
    val resultId: String,
    val instrumentId: String,
    val title: String,
    val version: String,
    val sourceTitle: String,
    val sourceUrl: String,
    val sourceLocator: String,
    val createdAtMillis: Long,
    val answered: Int,
    val itemCount: Int,
    val scores: List<AssessmentScore>,
    val interpretation: String,
    val fromMarkedSheet: Boolean = false
)

fun AssessmentResult.toCounselorDiscussionPrompt(): String {
    val scoresList = scores.joinToString("\n") { score ->
        "- ${score.label}: ${score.displayValue()} (${score.displayRange()})"
    }
    return buildString {
        appendLine("Halo AI Counselor, saya baru saja menyelesaikan asesmen $title.")
        if (scoresList.isNotBlank()) {
            appendLine("Rincian Skor & Subskala:")
            appendLine(scoresList)
        }
        if (interpretation.isNotBlank()) {
            appendLine("Interpretasi Klinis:")
            appendLine("\"$interpretation\"")
        }
        appendLine()
        append("Berdasarkan hasil asesmen ini dan materi konseling yang relevan, bagaimana evaluasi kondisi saya serta rekomendasi langkah refleksi atau tindak lanjut konseling yang sesuai?")
    }.trim()
}

fun AssessmentInstrument.toGuideDiscussionPrompt(): String {
    val prot = protocol ?: return "Halo AI Counselor, tolong jelaskan instrumen $title."
    return buildString {
        appendLine("Halo AI Counselor, tolong jelaskan panduan asesmen $title (${prot.edition}).")
        appendLine("Metode skoring: ${prot.method}")
        appendLine("Cara membaca: ${prot.interpretation}")
        appendLine("Batasan klinis: ${prot.limitation}")
        appendLine()
        append("Bagaimana instrumen ini diterapkan dalam konseling dan apa yang perlu diperhatikan?")
    }.trim()
}

internal object AssessmentScoring {
    fun canScore(form: AssessmentForm, answers: Map<Int, Int>): Boolean {
        if (form.items.isEmpty()) return false
        if (form.title.startsWith("DISC", ignoreCase = true) ||
            form.title.startsWith("Kuder", ignoreCase = true)
        ) {
            val answeredGroups = (0 until form.items.size).count { g ->
                val most = answers[g * 2]
                val least = answers[g * 2 + 1]
                most != null && least != null && most != least
            }
            return answeredGroups >= form.minimumAnswers
        }
        if (form.title.contains("Kraepelin", ignoreCase = true) ||
            form.title.contains("Pauli", ignoreCase = true)
        ) {
            val answeredCols = (0 until form.items.size).count { answers.containsKey(it) }
            return answeredCols >= 1
        }
        if (form.title.contains("Wartegg", ignoreCase = true)) {
            val answeredBoxes = (0 until form.items.size).count { answers.containsKey(it) }
            return answeredBoxes >= form.minimumAnswers
        }
        if (form.title.startsWith("Rothwell", ignoreCase = true) ||
            form.title.contains("RMIB", ignoreCase = true)
        ) {
            val answeredGroups = (0 until form.items.size).count { g ->
                val ranks = (0 until 12).mapNotNull { p -> answers[g * 12 + p] }
                ranks.size == 12 && ranks.toSet().size == 12
            }
            return answeredGroups >= form.minimumAnswers
        }
        if (form.title.contains("Rorschach", ignoreCase = true)) {
            val answeredCards = (0 until form.items.size).count { answers.containsKey(it) }
            return answeredCards >= form.minimumAnswers
        }
        if (answers.size < form.minimumAnswers) return false
        val allValid = answers.all { (index, choice) ->
            val item = form.items.getOrNull(index)
            val maxChoices = item?.choices?.size ?: form.choices.size
            if (item?.multiSelect == true) {
                val count = (0 until maxChoices).count { (choice and (1 shl it)) != 0 }
                index in form.items.indices && count in 1..item.maxSelections
            } else {
                index in form.items.indices && choice in 0 until maxChoices
            }
        }
        if (!allValid) return false
        return form.scales.any { scale ->
            answers.keys.count { form.items.getOrNull(it)?.scale == scale.id } >= (scale.minimumAnswers ?: 1)
        }
    }

    fun score(form: AssessmentForm, answers: Map<Int, Int>): List<AssessmentScore> {
        require(canScore(form, answers))
        if (form.title.contains("Kraepelin", ignoreCase = true) ||
            form.title.contains("Pauli", ignoreCase = true) ||
            form.title.contains("Wartegg", ignoreCase = true) ||
            form.title.startsWith("Rothwell", ignoreCase = true) ||
            form.title.contains("RMIB", ignoreCase = true) ||
            form.title.contains("Rorschach", ignoreCase = true)
        ) {
            return emptyList()
        }
        return form.scales.mapNotNull { scale ->
            val values = form.items.withIndex().filter { it.value.scale == scale.id }.mapNotNull { (index, item) ->
                answers[index]?.let { choice ->
                    val idx = if (item.reverse) form.values.lastIndex - choice else choice
                    form.values.getOrElse(idx) { choice }
                }
            }
            if (values.size < (scale.minimumAnswers ?: 1)) return@mapNotNull null
            val multiplier = if (form.average) 1 else values.size
            AssessmentScore(
                label = scale.label,
                value = if (form.average) values.average() else values.sum().toDouble(),
                minimum = form.values.min().toDouble() * multiplier,
                maximum = form.values.max().toDouble() * multiplier,
                meaning = scale.meaning
            )
        }
    }
}

internal class AssessmentCatalog(val instruments: List<AssessmentInstrument>) {
    fun find(id: String): AssessmentInstrument? = instruments.firstOrNull { it.id == id }

    fun recommend(query: String): List<AssessmentInstrument> {
        val normalized = normalize(query)
        if (declinesAssessment.containsMatchIn(normalized)) return emptyList()
        return matchInstruments(normalized).take(MAX_RECOMMENDATIONS)
    }

    private fun matchInstruments(normalized: String): List<AssessmentInstrument> {
        val requestsAssessment = assessmentIntent.containsMatchIn(normalized)
        val explicit = instruments.filter { instrument ->
            instrument.aliases.any { alias ->
                val term = normalize(alias)
                normalized == term || (requestsAssessment && normalized.contains(term))
            }
        }
        if (explicit.isNotEmpty()) {
            val alternative = find("big_five")?.takeIf { explicit.any { match -> match.id == "disc" } }
            return (explicit + listOfNotNull(alternative)).distinctBy { it.id }
        }
        return matchGoal(normalized, requestsAssessment)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun matchGoal(normalized: String, requestsAssessment: Boolean): List<AssessmentInstrument> {
        val ids = when {
            iq.containsMatchIn(normalized) -> listOf("spm", "cfit_3a", "ist")
            personality.containsMatchIn(normalized) && requestsAssessment -> listOf("big_five", "disc")
            interest.containsMatchIn(normalized) && requestsAssessment -> listOf("holland", "rmib")
            exhaustion.containsMatchIn(normalized) -> listOf("cbi")
            lowMood.containsMatchIn(normalized) -> listOf("bdi", "srq-29")
            screening.containsMatchIn(normalized) && requestsAssessment -> listOf("srq-29")
            leadership.containsMatchIn(normalized) && requestsAssessment -> listOf("msdt", "msai")
            workAttitude.containsMatchIn(normalized) && requestsAssessment -> listOf("pauli", "kraepelin", "papikostick")
            stressOrAdversity.containsMatchIn(normalized) && requestsAssessment -> listOf("adversity_quotient")
            learningStyle.containsMatchIn(normalized) && requestsAssessment -> listOf("vak", "mit")
            else -> emptyList()
        }
        return ids.mapNotNull(::find)
    }

    private fun normalize(text: String): String = " " + text.lowercase(Locale.ROOT).replace(nonWord, " ").trim() + " "

    private companion object {
        const val MAX_RECOMMENDATIONS = 3
        val nonWord = Regex("[^\\p{L}\\p{N}]+")
        val assessmentIntent = Regex(
            "\\b(tes|test|psikotes|psikotest|psikometri|asesmen|assessment|ukur|tahu|mengetahui|" +
                "cek|hasil|skor|rekomendasi|rekomendasikan|kuesioner)\\b"
        )
        val declinesAssessment = Regex(
            "\\b(tidak|jangan|gak|nggak|bukan) (mau |ingin |perlu )?(tes|test|asesmen|psikotes|ukur|cek|tahu|mengetahui)\\b"
        )
        val iq = Regex(
            "\\b(tes|test|ukur|cek|tahu|berapa|mengetahui) (skor |nilai |tingkat )?" +
                "(iq|inteligensi|kecerdasan|kemampuan kognitif)\\b|\\biq (saya|aku|ku)\\b"
        )
        val personality = Regex("\\b(kepribadian|personality|karakter|sifat saya)\\b")
        val interest = Regex("\\b(minat|bakat|karier|karir|jurusan)\\b")
        val exhaustion = Regex(
            "\\bburnout\\b|\\b(lelah|capek|kelelahan) " +
                "(terus|berkepanjangan|terus menerus|(karena|akibat) (kerja|pekerjaan))\\b"
        )
        val lowMood = Regex("\\b(sedih|murung) (terus|berkepanjangan|terus menerus)\\b")
        val screening = Regex("\\b(skrining|gangguan mental emosional|cemas|kecemasan)\\b")
        val leadership = Regex("\\b(kepemimpinan|leadership|gaya memimpin|manajerial|kompetensi manajer)\\b")
        val workAttitude = Regex("\\b(sikap kerja|daya tahan kerja|ketelitian|kecepatan kerja|kepribadian kerja|pauli)\\b")
        val stressOrAdversity = Regex("\\b(daya juang|daya tahan|mengatasi kesulitan|adversity)\\b")
        val learningStyle = Regex("\\b(gaya belajar|cara belajar|modalitas belajar|multiple intelligences)\\b")
    }
}
