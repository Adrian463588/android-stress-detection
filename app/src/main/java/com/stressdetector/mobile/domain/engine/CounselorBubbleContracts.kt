package com.stressdetector.mobile.domain.engine

enum class AdaptiveCounselingRoute(val label: String) {
    DAILY_WELLBEING("Refleksi & Kebiasaan"),
    ASSESSMENT_SUPPORT("Dukungan Asesmen"),
    SAFETY_OVERRIDE("Prioritas Keselamatan")
}

enum class CounselorMessageType {
    TEXT,
    JOURNAL_PROMPT,
    GOAL_SUGGESTION,
    ASSESSMENT_RECOMMENDATION,
    ASSESSMENT_PROGRESS,
    ASSESSMENT_RESULT,
    FOLLOW_UP_REMINDER,
    PROFESSIONAL_REVIEW
}

data class JournalBubblePayload(
    val promptId: String,
    val title: String,
    val description: String,
    val estimatedMinutes: String = "3–5 menit",
    val suggestedTags: List<String> = emptyList(),
    val completedEntryId: String? = null
)

data class GoalSuggestionPayload(
    val goalId: String,
    val title: String,
    val category: String,
    val cadence: String = "Harian",
    val targetCount: Int = 5,
    val rationale: String = "",
    val accepted: Boolean = false
)

data class AssessmentRecommendationBubblePayload(
    val recommendationId: String,
    val instrumentId: String,
    val instrumentTitle: String,
    val purpose: String,
    val rationale: String,
    val eligibilityNote: String,
    val estimatedDuration: String,
    val requiresDedicatedScreen: Boolean = true,
    val requiresExaminerReview: Boolean = false,
    val status: String = "OFFERED"
)

data class AssessmentResultBubblePayload(
    val instrumentId: String,
    val instrumentTitle: String,
    val primaryScoreSummary: String,
    val validityLabel: String,
    val reviewLabel: String,
    val aiContextEligible: Boolean
)

data class StructuredBubbleEnvelope(
    val messageType: CounselorMessageType = CounselorMessageType.TEXT,
    val adaptiveRoute: AdaptiveCounselingRoute = AdaptiveCounselingRoute.DAILY_WELLBEING,
    val journalPrompt: JournalBubblePayload? = null,
    val goalSuggestion: GoalSuggestionPayload? = null,
    val assessmentBubble: AssessmentRecommendationBubblePayload? = null,
    val assessmentResultSummary: AssessmentResultBubblePayload? = null,
    val limitations: List<String> = emptyList()
)
