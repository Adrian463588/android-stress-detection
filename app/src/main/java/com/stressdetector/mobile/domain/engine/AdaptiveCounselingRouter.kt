package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.domain.assessment.AssessmentInstrument
import com.stressdetector.mobile.domain.assessment.InstrumentImplementationStatus
import com.stressdetector.mobile.domain.assessment.PsychometricInstrumentRegistry
import java.util.Locale
import java.util.UUID

data class AdaptiveRoutingDecision(
    val route: AdaptiveCounselingRoute,
    val messageType: CounselorMessageType,
    val journalPrompt: JournalBubblePayload? = null,
    val goalSuggestion: GoalSuggestionPayload? = null,
    val assessmentRecommendation: AssessmentRecommendationBubblePayload? = null,
    val limitations: List<String> = emptyList()
)

/**
 * Pure domain router separating Daily Wellbeing (journaling/goals) from
 * Context-Aware Assessment Support while respecting safety overrides and
 * clinical screening boundaries (e.g., never defaulting to MMPI for initial
 * depression screening).
 */
object AdaptiveCounselingRouter {
    fun evaluate(
        userMessage: String,
        recommendedInstruments: List<AssessmentInstrument>,
        isCrisis: Boolean = false,
        hasRepeatedDistressHistory: Boolean = false,
        recentPpgElevated: Boolean = false
    ): AdaptiveRoutingDecision {
        if (isCrisis) {
            return AdaptiveRoutingDecision(
                route = AdaptiveCounselingRoute.SAFETY_OVERRIDE,
                messageType = CounselorMessageType.TEXT,
                limitations = listOf("Safety override memprioritaskan dukungan manusia di atas asesmen rutin.")
            )
        }

        val normalized = userMessage.lowercase(Locale.ROOT)
        if (CLINICAL_ASSESSMENT_DISCUSSION.containsMatchIn(normalized)) {
            return AdaptiveRoutingDecision(
                route = AdaptiveCounselingRoute.ASSESSMENT_SUPPORT,
                messageType = CounselorMessageType.TEXT
            )
        }

        if (recommendedInstruments.isNotEmpty() || needsSymptomExploration(normalized, hasRepeatedDistressHistory)) {
            val chosen = selectPrimaryRecommendation(normalized, recommendedInstruments)
            if (chosen != null) {
                return buildAssessmentRecommendationDecision(chosen, recentPpgElevated)
            }
        }

        if (HABIT_AND_GOAL_INTENT.containsMatchIn(normalized)) {
            val goal = suggestWellbeingGoal(normalized)
            return AdaptiveRoutingDecision(
                route = AdaptiveCounselingRoute.DAILY_WELLBEING,
                messageType = CounselorMessageType.GOAL_SUGGESTION,
                goalSuggestion = goal
            )
        }

        if (DAILY_REFLECTION_INTENT.containsMatchIn(normalized)) {
            val journal = suggestJournalPrompt(normalized)
            return AdaptiveRoutingDecision(
                route = AdaptiveCounselingRoute.DAILY_WELLBEING,
                messageType = CounselorMessageType.JOURNAL_PROMPT,
                journalPrompt = journal
            )
        }

        return AdaptiveRoutingDecision(
            route = AdaptiveCounselingRoute.DAILY_WELLBEING,
            messageType = CounselorMessageType.TEXT
        )
    }

    private fun selectPrimaryRecommendation(
        normalizedQuery: String,
        candidates: List<AssessmentInstrument>
    ): AssessmentInstrument? {
        // Never use MMPI as a default first-line depression screen when BDI / SRQ-29 exists.
        val filtered = if (DEPRESSIVE_SCREEN_CUES.containsMatchIn(normalizedQuery)) {
            val nonMmpi = candidates.filterNot { it.id.startsWith("mmpi") }
            nonMmpi.ifEmpty { candidates }
        } else {
            candidates
        }
        return filtered.firstOrNull { instrument ->
            PsychometricInstrumentRegistry.specFor(instrument.id).implementationStatus !=
                InstrumentImplementationStatus.UNRESOLVED_IDENTITY
        }
    }

    private fun buildAssessmentRecommendationDecision(
        instrument: AssessmentInstrument,
        recentPpgElevated: Boolean
    ): AdaptiveRoutingDecision {
        val spec = PsychometricInstrumentRegistry.specFor(instrument.id)
        val requiresDedicated = spec.dedicatedScreenRequired || instrument.id !in INLINE_SIMPLE_SCREENERS
        val eligibility = when {
            spec.requiresExaminerReview ->
                "Memerlukan peninjauan & coding psikolog (${spec.implementationStatus.name})"
            spec.implementationStatus == InstrumentImplementationStatus.RAW_SCORE_ONLY ->
                "Skoring terverifikasi (skor mentah); interpretasi tetap memerlukan konteks konselor"
            else ->
                "Instrumen terstruktur · keputusan akhir tetap di tangan konselor/psikolog"
        }
        val rationale = buildString {
            append("Keluhan atau kebutuhan eksplorasi pada percakapan selaras dengan cakupan ${instrument.title}.")
            if (recentPpgElevated) {
                append(" Catatan: sinyal PPG menunjukkan arousal non-spesifik dan bukan bukti diagnosis.")
            }
        }
        val duration = spec.estimatedMinutes?.takeIf { it > 0 }?.let { "$it menit" }
            ?: if (requiresDedicated) "5–15 menit" else "2–5 menit"
        val purpose = instrument.description.ifBlank { instrument.category }

        return AdaptiveRoutingDecision(
            route = AdaptiveCounselingRoute.ASSESSMENT_SUPPORT,
            messageType = CounselorMessageType.ASSESSMENT_RECOMMENDATION,
            assessmentRecommendation = AssessmentRecommendationBubblePayload(
                recommendationId = UUID.randomUUID().toString(),
                instrumentId = instrument.id,
                instrumentTitle = instrument.title,
                purpose = purpose,
                rationale = rationale,
                eligibilityNote = eligibility,
                estimatedDuration = duration,
                requiresDedicatedScreen = requiresDedicated,
                requiresExaminerReview = spec.requiresExaminerReview
            ),
            limitations = listOf(
                "Asesmen bersifat sukarela dan membutuhkan persetujuan pengguna.",
                "Hasil skor terstruktur dihitung deterministik oleh mesin skoring, bukan oleh LLM."
            )
        )
    }

    private fun suggestJournalPrompt(normalized: String): JournalBubblePayload {
        val tags = when {
            SLEEP_INTENT.containsMatchIn(normalized) -> listOf("tidur", "rutinitas", "energi")
            PROCRASTINATION_INTENT.containsMatchIn(normalized) -> listOf("disiplin", "fokus", "hambatan")
            else -> listOf("refleksi", "emosi", "harian")
        }
        val description = when {
            SLEEP_INTENT.containsMatchIn(normalized) ->
                "Catat jam tidur, hal yang mengganggu sebelum tidur, dan tingkat kesegaran pagi hari."
            PROCRASTINATION_INTENT.containsMatchIn(normalized) ->
                "Tuliskan satu pemicu utama menunda tugas hari ini dan langkah kecil 10 menit."
            else ->
                "Luangkan waktu sejenak untuk mencatat suasana hati dan peristiwa bermakna hari ini."
        }
        return JournalBubblePayload(
            promptId = UUID.randomUUID().toString(),
            title = "Refleksi & Check-in Harian",
            description = description,
            suggestedTags = tags
        )
    }

    private fun suggestWellbeingGoal(normalized: String): GoalSuggestionPayload {
        val (title, category, rationale) = when {
            SLEEP_INTENT.containsMatchIn(normalized) -> Triple(
                "Rutinitas wind-down 20 menit sebelum tidur",
                "Tidur & Pemulihan",
                "Menjaga konsistensi jam istirahat harian."
            )
            PROCRASTINATION_INTENT.containsMatchIn(normalized) -> Triple(
                "Blok fokus bertahap 15 menit tanpa distraksi",
                "Manajemen Waktu",
                "Mengurangi beban memulai tugas berat lewat behavioral activation."
            )
            else -> Triple(
                "Check-in jurnal & napas lambat 5 menit",
                "Regulasi Emosi",
                "Mendukung kesadaran stres harian secara non-klinis."
            )
        }
        return GoalSuggestionPayload(
            goalId = UUID.randomUUID().toString(),
            title = title,
            category = category,
            cadence = "Harian",
            targetCount = 7,
            rationale = rationale
        )
    }

    private fun needsSymptomExploration(normalized: String, hasRepeatedDistress: Boolean): Boolean =
        hasRepeatedDistress && DISTRESS_EXPLORATION_INTENT.containsMatchIn(normalized)

    private val INLINE_SIMPLE_SCREENERS = setOf("srq-29", "bdi", "cbi")
    private val CLINICAL_OR_PROJECTIVE_IDS = setOf("mmpi", "wartegg", "rorschach", "htp", "dap")

    private val CLINICAL_ASSESSMENT_DISCUSSION = Regex(
        "(?i)\\b(bahas hasil|interpretasi hasil|skor|hasil tiu|hasil disc|hasil mmpi|hasil bdi|hasil wartegg)\\b"
    )
    private val DEPRESSIVE_SCREEN_CUES = Regex(
        "(?i)(kehilangan semangat|sulit menikmati|murung|sedih berkepanjangan|tidak bergairah)"
    )
    private val DISTRESS_EXPLORATION_INTENT = Regex(
        "(?i)(lelah terus|cemas berulang|tidak bisa tidur berminggu|sulit konsentrasi terus)"
    )
    private val HABIT_AND_GOAL_INTENT = Regex(
        "(?i)(?:\\b(?:kebiasaan|habit|manajemen waktu|time management|target|disiplin|prokrastinasi|bangun kesiangan)\\b|\\bmenunda(?:\\s+(?:pekerjaan|tugas))?\\b)"
    )
    private val DAILY_REFLECTION_INTENT = Regex(
        "(?i)(?:\\b(?:jurnal|journal|refleksi|hari ini|mood|capek hari ini|terlambat bangun|curhat)\\b|\\b(?:sulit|susah|gangguan)\\s+tidur\\b)"
    )
    private val SLEEP_INTENT = Regex("(?i)\\b(tidur|insomnia|begadang|kesiangan|sleep)\\b")
    private val PROCRASTINATION_INTENT = Regex("(?i)\\b(tunda|menunda|prokrastinasi|malas|fokus|disiplin)\\b")
}
