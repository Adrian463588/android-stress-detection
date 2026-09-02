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
            if (HAS_CITATION_LIKE_MARKER.containsMatchIn(normalized) && markers.isEmpty()) {
                return GuardedCounselorOutput.Rejected(CITATION_FAILURE_RESPONSE)
            }
            if (mode == RagMode.GENERAL_SAFE && markers.isNotEmpty()) {
                return GuardedCounselorOutput.Rejected(SAFE_SCOPE_RESPONSE)
            }

            val referenced = mutableListOf<CitationBinding>()
            for (match in markers) {
                val label = match.groupValues[1]
                val exactId = match.groupValues[2]
                val binding = registry.exactBinding(label, exactId)
                    ?: return GuardedCounselorOutput.Rejected(CITATION_FAILURE_RESPONSE)
                if (referenced.any { it.label == label && it.evidenceId != exactId }) {
                    return GuardedCounselorOutput.Rejected(CITATION_FAILURE_RESPONSE)
                }
                referenced += binding
            }

            if (mode == RagMode.GROUNDED &&
                referenced.none {
                    it.citation.sourceType == CitationSource.LOCAL_DOCUMENT &&
                        it.citation.sourceTier == EvidenceSourceTier.APP_RAG
                }
            ) {
                return GuardedCounselorOutput.Rejected(DOCUMENT_ABSTENTION_RESPONSE)
            }
            if (mode == RagMode.BUNDLED_GROUNDED &&
                referenced.none {
                    it.citation.sourceType == CitationSource.LOCAL_DOCUMENT &&
                        it.citation.sourceTier == EvidenceSourceTier.BUNDLED_LOCAL
                }
            ) {
                return GuardedCounselorOutput.Rejected(DOCUMENT_ABSTENTION_RESPONSE)
            }
            if (mode == RagMode.WEB_GROUNDED &&
                referenced.none {
                    it.citation.sourceType == CitationSource.WEB &&
                        it.citation.sourceTier == EvidenceSourceTier.WEB
                }
            ) {
                return GuardedCounselorOutput.Rejected(CITATION_FAILURE_RESPONSE)
            }

            val citedEvidence = referenced
                .distinctBy { it.label to it.evidenceId }
                .map(CitationBinding::citation)
            return GuardedCounselorOutput.Accepted(normalized, citedEvidence)
        }
    }
}

sealed interface GuardedCounselorOutput {
    data class Accepted(val text: String, val citations: List<KnowledgeCitation>) : GuardedCounselorOutput
    data class Rejected(val text: String) : GuardedCounselorOutput
}

private val CITATION_MARKER = Regex("\\[((?:S|W)\\d+)\\|([^]\\s|]+)]")
private val HAS_CITATION_LIKE_MARKER = Regex("\\[(?:S|W)\\d+(?:\\|[^]]*)?]")
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
