package com.stressdetector.mobile.domain.assessment

enum class InstrumentImplementationStatus(val displayLabel: String) {
    VERIFIED("Terverifikasi"),
    RAW_SCORE_ONLY("Skor Mentah Saja"),
    EXPLORATORY("Eksploratif (Purwarupa)"),
    DEMONSTRATION("Demonstrasi Sintetis"),
    EXAMINER_REVIEW_REQUIRED("Perlu Review Pemeriksa"),
    SOURCE_INCOMPLETE("Sumber/Manual Belum Lengkap"),
    LICENSE_RESTRICTED("Akses Terbatas Lisensi"),
    UNRESOLVED_IDENTITY("Identitas Instrumen Belum Terkonfirmasi")
}

enum class AssessmentValidityStatus(val displayLabel: String) {
    VALID("Valid"),
    QUESTIONABLE("Perlu Pemeriksaan"),
    INVALID("Tidak Valid"),
    PENDING("Menunggu Validasi")
}

enum class ExaminerReviewStatus(val displayLabel: String) {
    NOT_REQUIRED("Tidak Diperlukan"),
    PENDING("Menunggu Review Psikolog"),
    APPROVED("Disetujui Pemeriksa"),
    NEEDS_REVISION("Perlu Revisi")
}

enum class AssessmentLifecycleState {
    DRAFT,
    COMPLETED,
    SCORED,
    VISUALIZED,
    VALIDATED,
    REVIEWED,
    AI_CONTEXT_ELIGIBLE,
    INTERPRETED
}

enum class AssessmentVisualizationType {
    PROFILE_LINE_CHART,
    CLINICAL_PROFILE_CHART,
    HORIZONTAL_BAR,
    BIPOLAR_PREFERENCE,
    TIME_SERIES_CURVE,
    RANKED_BAR,
    SCORE_INDICATOR,
    WARTEGG_EIGHT_BOX,
    EXAMINER_CODED_PROFILE,
    OBSERVATION_MATRIX,
    STATUS_ONLY
}

data class AssessmentDiscussionRequest(
    val resultId: String,
    val instrumentId: String,
    val participantId: String,
    val prompt: String
)

data class AssessmentObservation(
    val key: String,
    val label: String,
    val value: String,
    val durationSeconds: Int? = null,
    val orderIndex: Int? = null,
    val pressure: Float? = null,
    val tags: List<String> = emptyList()
)

data class WarteggNormalizedPoint(
    val x: Float,
    val y: Float,
    val pressure: Float? = null,
    val timeMillis: Long? = null,
    val isStylus: Boolean = false,
    val tilt: Float? = null,
    val orientation: Float? = null,
    val toolType: Int? = null
)

data class ProjectivePanelDraft(
    val panelIndex: Int,
    val panelLabel: String,
    val strokes: List<List<WarteggNormalizedPoint>> = emptyList(),
    val title: String = "",
    val notes: String = "",
    val drawOrder: Int? = null,
    val durationSeconds: Int = 0,
    val revisionCount: Int = 0
) {
    fun hasContent(): Boolean = strokes.isNotEmpty() || title.isNotBlank() || notes.isNotBlank()
}

data class WarteggBoxDraft(
    val boxNumber: Int,
    val strokes: List<List<WarteggNormalizedPoint>> = emptyList(),
    val title: String = "",
    val drawOrder: Int? = null,
    val durationSeconds: Int = 0,
    val valence: String? = null,
    val difficulty: String? = null,
    val revisionCount: Int = 0
) {
    fun hasContent(): Boolean = strokes.isNotEmpty() || title.isNotBlank() || valence != null || difficulty != null
}

data class RorschachResponseDraft(
    val cardNumber: Int,
    val responseIndex: Int,
    val freeAssociation: String = "",
    val inquiry: String = "",
    val codingSystem: String = "EXNER_CS",
    val location: String? = null,
    val determinant: String? = null,
    val formQuality: String? = null,
    val popular: Boolean = false,
    val content: String? = null
)

data class InstrumentAuditSpec(
    val instrumentId: String,
    val canonicalTitle: String,
    val formVersion: String,
    val implementationStatus: InstrumentImplementationStatus,
    val visualizationType: AssessmentVisualizationType,
    val requiresExaminerReview: Boolean,
    val normStatus: String,
    val licenseNote: String,
    val sourceReferences: List<String>,
    val eligibleForAiScreening: Boolean,
    val dedicatedScreenRequired: Boolean,
    val estimatedMinutes: Int? = null
)

data class EnrichedScoringOutcome(
    val scores: List<AssessmentScore>,
    val interpretation: String,
    val scoringStatus: InstrumentImplementationStatus,
    val validityStatus: AssessmentValidityStatus = AssessmentValidityStatus.VALID,
    val validityWarnings: List<String> = emptyList(),
    val reviewStatus: ExaminerReviewStatus = ExaminerReviewStatus.NOT_REQUIRED,
    val normReference: String? = null,
    val observations: List<AssessmentObservation> = emptyList(),
    val warteggDrafts: List<WarteggBoxDraft> = emptyList(),
    val rorschachResponses: List<RorschachResponseDraft> = emptyList(),
    val projectivePanels: List<ProjectivePanelDraft> = emptyList(),
    val multimodalSummary: String? = null
)
