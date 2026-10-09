package com.stressdetector.mobile.domain.engine

/** Pure output boundary. Model text can never create new provenance. */
interface CounselorOutputGuard {
    fun validate(
        text: String,
        mode: RagMode,
        citations: List<KnowledgeCitation>,
        registry: CitationRegistry = CitationRegistry.fromIncludedEvidence(
            local = citations.filter { it.sourceType == CitationSource.LOCAL_DOCUMENT },
            web = citations.filter { it.sourceType == CitationSource.WEB }
        )
    ): GuardedCounselorOutput

    data object Default : CounselorOutputGuard {
        override fun validate(
            text: String,
            mode: RagMode,
            citations: List<KnowledgeCitation>,
            registry: CitationRegistry
        ): GuardedCounselorOutput {
            val normalized = text.trim()
            if (normalized.isBlank()) return GuardedCounselorOutput.Rejected(SAFE_SCOPE_RESPONSE)
            if (mode == RagMode.UNAVAILABLE) return GuardedCounselorOutput.Rejected(DOCUMENT_ABSTENTION_RESPONSE)
            if (DANGEROUS_CLAIM.containsMatchIn(normalized)) {
                return GuardedCounselorOutput.Rejected(SAFE_SCOPE_RESPONSE)
            }

            val markers = CITATION_MARKER.findAll(normalized).toList()
            val withoutExactMarkers = normalized.replace(CITATION_MARKER, " ")
            if (HAS_CITATION_LIKE_MARKER.containsMatchIn(withoutExactMarkers)) {
                return GuardedCounselorOutput.Rejected(CITATION_FAILURE_RESPONSE, "citation_malformed")
            }
            if (mode == RagMode.GENERAL_SAFE && markers.isNotEmpty()) {
                return GuardedCounselorOutput.Rejected(SAFE_SCOPE_RESPONSE)
            }

            val referenced = mutableListOf<CitationBinding>()
            for (match in markers) {
                val label = match.groupValues[1]
                val exactId = match.groupValues[2]
                val binding = registry.exactBinding(label, exactId)
                    ?: return GuardedCounselorOutput.Rejected(CITATION_FAILURE_RESPONSE, "citation_unknown_binding")
                if (referenced.any { it.label == label && it.evidenceId != exactId }) {
                    return GuardedCounselorOutput.Rejected(CITATION_FAILURE_RESPONSE, "citation_conflicting_binding")
                }
                referenced += binding
            }

            missingEvidence(mode, referenced)?.let { return it }

            val citedEvidence = referenced
                .distinctBy { it.label to it.evidenceId }
                .map(CitationBinding::citation)
            if (citedEvidence.isNotEmpty() && !hasAnswerBeyondSourceMetadata(withoutExactMarkers, citedEvidence)) {
                return GuardedCounselorOutput.Rejected(
                    "Sumber tersedia, tetapi AI belum menyusun jawabannya. Coba lagi.",
                    "citation_answer_empty"
                )
            }
            return GuardedCounselorOutput.Accepted(normalized, citedEvidence)
        }
    }
}

private fun missingEvidence(mode: RagMode, bindings: List<CitationBinding>): GuardedCounselorOutput.Rejected? {
    val tier = when (mode) {
        RagMode.GROUNDED -> EvidenceSourceTier.APP_RAG
        RagMode.BUNDLED_GROUNDED -> EvidenceSourceTier.BUNDLED_LOCAL
        RagMode.WEB_GROUNDED -> EvidenceSourceTier.WEB
        else -> null
    }
    val source = if (tier == EvidenceSourceTier.WEB) CitationSource.WEB else CitationSource.LOCAL_DOCUMENT
    return if (tier == null || bindings.any { it.citation.sourceTier == tier && it.citation.sourceType == source }) {
        null
    } else {
        GuardedCounselorOutput.Rejected(
            if (tier == EvidenceSourceTier.WEB) CITATION_FAILURE_RESPONSE else DOCUMENT_ABSTENTION_RESPONSE,
            when (tier) {
                EvidenceSourceTier.WEB -> "citation_missing_web"
                EvidenceSourceTier.BUNDLED_LOCAL -> "citation_missing_bundled"
                else -> "citation_missing_local"
            }
        )
    }
}

private fun hasAnswerBeyondSourceMetadata(text: String, citations: List<KnowledgeCitation>): Boolean {
    val metadata = citations.flatMap { citation ->
        listOfNotNull(
            citation.sourceTitle,
            citation.section,
            citation.sourceUrl,
            citation.sourceDomain,
            "source ${citation.sourceTier.name}"
        )
    }.filter(String::isNotBlank).distinct().sortedByDescending(String::length)
    val answer = metadata.fold(text) { remaining, value -> remaining.replace(value, "", ignoreCase = true) }
    return answer.any(Char::isLetterOrDigit)
}

sealed interface GuardedCounselorOutput {
    data class Accepted(val text: String, val citations: List<KnowledgeCitation>) : GuardedCounselorOutput
    data class Rejected(val text: String, val reasonCode: String = "output_rejected") : GuardedCounselorOutput
}

private val CITATION_MARKER = Regex("\\[((?:S|W)[0-9]+)\\|([^\\[\\]\\s|]+)]")
private val HAS_CITATION_LIKE_MARKER = Regex(
    "[\\[［][\\s\\p{Cf}]*[SWswＳＷｓｗ](?=[\\s\\p{Cf}]*(?:[\\p{N}#|｜\\]］]|$)|[^]\\r\\n]*[|｜])"
)
private val DANGEROUS_CLAIM = Regex(
    "(?i)(?:anda|kamu|pasien|you)\\s+(?:mengidap|menderita|terdiagnosis|are diagnosed)|" +
        "(?:lie detected|deception detected|truth score|patient is lying)|" +
        "(?:minum|gunakan|konsumsi|dosis|resep|take)\\s+(?:obat|antidepresan|benzodiazepin|medication)|" +
        "(?:saya\\s+(?:mendiagnosis|meresepkan)|i\\s+(?:diagnose|prescribe))"
)

const val DOCUMENT_ABSTENTION_RESPONSE =
    "Saya belum menemukan evidence dokumen yang cukup untuk menjawab dengan aman. Silakan pilih dokumen yang relevan atau ajukan pertanyaan umum tanpa mengatasnamakan dokumen."

private const val CITATION_FAILURE_RESPONSE =
    "Saya tidak dapat memverifikasi citation pada respons ini, jadi jawaban grounded tidak ditampilkan."

private const val SAFE_SCOPE_RESPONSE =
    "Saya fokus pada dukungan emosional, stres, dan coping umum. Saya tidak dapat mendiagnosis, memberi resep, atau menentukan kebohongan."
