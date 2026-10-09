package com.stressdetector.mobile.domain.engine

/** Exact binding between model-visible labels and immutable evidence provenance. */
data class CitationBinding(
    val label: String,
    val evidenceId: String,
    val chunkId: String?,
    val documentId: String?,
    val sourcePage: Int?,
    val section: String,
    val citation: KnowledgeCitation
) {
    val marker: String get() = "[$label|$evidenceId]"
}

data class CitationRegistry(val bindings: List<CitationBinding>) {
    private val byLabel = bindings.associateBy(CitationBinding::label)

    fun exactBinding(label: String, evidenceId: String): CitationBinding? = byLabel[label]?.takeIf { it.evidenceId == evidenceId }

    fun localBindings(): List<CitationBinding> = bindings.filter { it.citation.sourceType == CitationSource.LOCAL_DOCUMENT }
    fun webBindings(): List<CitationBinding> = bindings.filter { it.citation.sourceType == CitationSource.WEB }

    companion object {
        val Empty = CitationRegistry(emptyList())

        fun fromIncludedEvidence(local: List<KnowledgeCitation>, web: List<KnowledgeCitation>): CitationRegistry {
            val localBindings = local
                .filter { it.sourceType == CitationSource.LOCAL_DOCUMENT }
                .distinctBy { it.chunkId ?: it.citationId }
                .mapIndexed { index, citation -> citation.toBinding("S${index + 1}") }
            val webBindings = web
                .filter { it.sourceType == CitationSource.WEB }
                .distinctBy { it.citationId }
                .mapIndexed { index, citation -> citation.toBinding("W${index + 1}") }
            return CitationRegistry(localBindings + webBindings)
        }

        private fun KnowledgeCitation.toBinding(label: String): CitationBinding {
            val exactId = chunkId?.takeIf(String::isNotBlank) ?: citationId
            return CitationBinding(
                label = label,
                evidenceId = exactId,
                chunkId = chunkId ?: citationId.takeIf { sourceType == CitationSource.LOCAL_DOCUMENT },
                documentId = documentId,
                sourcePage = sourcePage,
                section = sourceLocator ?: section,
                citation = this
            )
        }
    }
}

/** Heuristic only for fail-closed routing; it never grants more model capability. */
object DocumentGroundingIntentDetector {
    private val explicitDocumentPattern = Regex(
        "(?i)\\b(?:dokumen|pdf|docx|file|lampiran|document|attachment|sitasi|citation)\\b|" +
            "\\b(?:kutip|berdasarkan|menurut|according to|quote)\\b.{0,48}" +
            "\\b(?:artikel|paper|buku|panduan|penelitian|studi|sumber|article|study|guideline|source)\\b|" +
            "\\b(?:halaman|bab|page|chapter)\\s+[0-9]+\\b|" +
            "\\b(?:sertakan|berikan|cantumkan|include|cite)\\b.{0,24}\\b(?:sumber|rujukan|referensi|source)\\b"
    )
    private val factualQuestionPattern = Regex(
        "(?i)\\b(?:apa(?:kah)?|siapa|kapan|di mana|dimana|mengapa|mengartikan|" +
            "bagaimana|berapa|jelaskan|definisi|penyebab|gejala|efek|manfaat|risiko|" +
            "what|who|when|where|why|how|definition|cause|symptom|effect|benefit|risk)\\b"
    )
    private val supportiveActionPattern = Regex(
        "(?i)(?:\\b(?:saya|aku|anda|kamu)\\b.{0,48}\\b(?:cemas|stres|stress|tenang|" +
            "coping|lakukan|bantu|rasakan|merasa|ceritakan|sedih|kesepian|takut|marah|lelah|capek|bingung|sulit tidur)\\b)|" +
            "(?:\\b(?:perasaan|cerita)\\b.{0,32}\\b(?:saya|aku)\\b)|" +
            "(?:\\b(?:cara|langkah)\\b.{0,32}\\b(?:menenangkan|" +
            "bernapas|coping|mengelola|relaks)\\b)|" +
            "(?:\\b(?:i|my)\\b.{0,48}\\b(?:feel|feeling|anxious|sad|lonely|tired|upset|worried|cope|coping)\\b)"
    )
    private val clinicalFactPattern = Regex(
        "(?i)\\b(?:definisi|penyebab|gejala|efek|manfaat|risiko|dosis|diagnosis|" +
            "definition|causes?|symptoms?|effects?|benefits?|risks?|dosage|diagnosis)\\b"
    )

    /** Factual questions need grounded evidence; personal coping requests remain GENERAL_SAFE. */
    fun requiresLocalEvidence(userText: String): Boolean {
        val normalized = userText.trim()
        return explicitDocumentPattern.containsMatchIn(normalized) ||
            (factualQuestionPattern.containsMatchIn(normalized) && clinicalFactPattern.containsMatchIn(normalized)) ||
            (
                factualQuestionPattern.containsMatchIn(normalized) &&
                    !supportiveActionPattern.containsMatchIn(normalized)
                )
    }
}
