package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.repository.AssessmentRepository
import com.stressdetector.mobile.data.repository.JournalAndGoalRepository
import com.stressdetector.mobile.domain.assessment.AssessmentResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull

data class ClinicalCounselingContext(
    val primaryAssessmentResult: AssessmentResult? = null,
    val assessmentSummary: String = "",
    val verbatimSummary: String = "",
    val journalAndGoalSummary: String = "",
    val enrichedRetrievalQuery: String = "",
    val activeSources: List<String> = emptyList()
)

class ClinicalCounselingContextAssembler(
    private val assessmentRepository: AssessmentRepository? = null,
    private val verbatimRepository: VerbatimSessionRepository? = null,
    private val journalAndGoalRepository: JournalAndGoalRepository? = null,
    private val participantId: String = JournalAndGoalRepository.DEFAULT_PARTICIPANT_ID
) {
    suspend fun assemble(
        userQuery: String,
        interactionMode: CounselingInteractionMode,
        includeBehavioralContext: Boolean,
        pinnedResultId: String? = null
    ): ClinicalCounselingContext {
        val activeSources = mutableListOf<String>()
        val allResults = runCatching {
            assessmentRepository?.getAllResults().orEmpty().sortedByDescending(AssessmentResult::createdAtMillis)
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())

        val sameParticipantResults = allResults.filter { belongsToActiveParticipant(it) }
        val eligibleResults = sameParticipantResults.filter {
            it.aiContextAllowed && it.resolvedValidityStatus() != com.stressdetector.mobile.domain.assessment.AssessmentValidityStatus.INVALID
        }

        val pinnedLookup = if (!pinnedResultId.isNullOrBlank()) {
            runCatching { assessmentRepository?.getResultById(pinnedResultId) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        } else {
            null
        }

        val namedResult = if (pinnedResultId.isNullOrBlank()) {
            runCatching { assessmentRepository?.resultForDiscussion(userQuery) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
                ?.takeIf {
                    belongsToActiveParticipant(it) &&
                        it.aiContextAllowed &&
                        it.resolvedValidityStatus() != com.stressdetector.mobile.domain.assessment.AssessmentValidityStatus.INVALID
                }
        } else {
            null
        }

        val pinnedRejectedReason = when {
            pinnedResultId.isNullOrBlank() -> null
            pinnedLookup == null -> "Hasil asesmen yang diminta ($pinnedResultId) tidak ditemukan."
            !belongsToActiveParticipant(pinnedLookup) ->
                "Akses ditolak: hasil asesmen milik partisipan berbeda tidak dapat digabungkan."
            !pinnedLookup.aiContextAllowed ->
                pinnedLookup.aiIneligibilityReason() ?: "Izin penggunaan hasil untuk AI+ dinonaktifkan."
            else -> null
        }

        val shouldAttachGeneralAssessment = interactionMode != CounselingInteractionMode.GENERAL_COUNSELING ||
            CLINICAL_CONTEXT_INTENT.containsMatchIn(userQuery)

        val primaryResult = when {
            !pinnedResultId.isNullOrBlank() -> pinnedLookup?.takeIf {
                belongsToActiveParticipant(it) && it.aiContextAllowed
            }
            namedResult != null -> namedResult
            shouldAttachGeneralAssessment -> eligibleResults.firstOrNull()
            else -> null
        }

        val assessmentSummary = if (pinnedRejectedReason != null) {
            "Catatan sistem: $pinnedRejectedReason"
        } else {
            buildAssessmentSummary(
                primary = primaryResult,
                eligibleResults = eligibleResults,
                excludedCount = sameParticipantResults.size - eligibleResults.size,
                includeAllScalesForPrimary = !pinnedResultId.isNullOrBlank()
            )
        }
        if (primaryResult != null || eligibleResults.isNotEmpty() || assessmentSummary.isNotBlank()) {
            activeSources += if (primaryResult != null) "Asesmen (${primaryResult.instrumentId})" else "Asesmen (${eligibleResults.size})"
        }

        val verbatimSummary = if (includeBehavioralContext) {
            buildVerbatimSummary().also { if (it.isNotBlank()) activeSources += "Verbatim+" }
        } else {
            ""
        }

        val journalSummary = buildJournalAndGoalSummary().also {
            if (it.isNotBlank()) activeSources += "Jurnal & Tujuan"
        }

        val enrichedQuery = buildEnrichedRetrievalQuery(userQuery, primaryResult, verbatimSummary, interactionMode)
        return ClinicalCounselingContext(
            primaryAssessmentResult = primaryResult,
            assessmentSummary = assessmentSummary,
            verbatimSummary = verbatimSummary,
            journalAndGoalSummary = journalSummary,
            enrichedRetrievalQuery = enrichedQuery,
            activeSources = activeSources
        )
    }

    private fun belongsToActiveParticipant(result: AssessmentResult): Boolean =
        result.participantId == participantId ||
            (participantId == JournalAndGoalRepository.DEFAULT_PARTICIPANT_ID && result.participantId == "local-device-profile")

    private fun buildAssessmentSummary(
        primary: AssessmentResult?,
        eligibleResults: List<AssessmentResult>,
        excludedCount: Int,
        includeAllScalesForPrimary: Boolean = false
    ): String {
        val targetResults = buildList {
            if (primary != null) add(primary)
            eligibleResults.filter { it.resultId != primary?.resultId }.take(MAX_EXTRA_ASSESSMENTS).forEach(::add)
        }
        val lines = targetResults.map { result ->
            val dateText = formatDate(result.createdAtMillis)
            val origin = if (result.fromMarkedSheet) "rekap lembar" else "aplikasi"
            val scaleSource = if (includeAllScalesForPrimary && result.resultId == primary?.resultId) {
                result.scores
            } else {
                result.scores.take(MAX_SCORES_PER_ASSESSMENT)
            }
            val scoreParts = scaleSource.joinToString("; ") { score ->
                val detail = if (score.label.startsWith("Kategori", ignoreCase = true) && score.meaning.isNotBlank()) {
                    score.meaning
                } else {
                    "${score.displayValue()} (${score.displayRange()})"
                }
                "${score.label}=$detail"
            }
            val reviewNote = result.reviewNotes?.takeIf(String::isNotBlank)?.let { " | Review: ${it.take(90)}" }.orEmpty()
            val obsPart = result.observations.take(6).takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.label}: ${it.value}" }
                ?.let { " | Observasi: $it" }.orEmpty()
            val multimodalPart = result.multimodalSummary?.takeIf(String::isNotBlank)?.let {
                " | Visual/Multimodal: ${it.take(260)}"
            }.orEmpty()
            val summaryInterp = result.interpretation.take(MAX_INTERPRETATION_CHARS)
            "- [${result.instrumentId}] ${result.title} (${result.version}, $dateText, $origin, " +
                "status=${result.resolvedScoringStatus().displayLabel}, validitas=${result.resolvedValidityStatus().name}, review=${result.resolvedReviewStatus().name}): " +
                "$scoreParts$obsPart$multimodalPart.$reviewNote Catatan: $summaryInterp"
        }
        return if (lines.isEmpty() && excludedCount > 0) {
            "Catatan sistem: terdapat $excludedCount hasil asesmen yang belum memenuhi syarat validitas/review/izin untuk konteks AI."
        } else if (excludedCount > 0) {
            lines.joinToString("\n") +
                "\n- ($excludedCount hasil lain tidak disertakan karena belum tervalidasi/berizin.)"
        } else {
            lines.joinToString("\n")
        }
    }

    private suspend fun buildJournalAndGoalSummary(): String {
        val repo = journalAndGoalRepository ?: return ""
        val journals = runCatching { repo.getAiConsentedJournals(participantId, MAX_JOURNALS) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
        val goals = runCatching { repo.getActiveGoals(participantId) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
        if (journals.isEmpty() && goals.isEmpty()) return ""
        return buildString {
            if (journals.isNotEmpty()) {
                appendLine("Jurnal terbaru (self-report):")
                journals.forEach { entry ->
                    appendLine(
                        "- [${formatDate(entry.createdAtMillis)}] Mood ${entry.moodScore}/5 (${entry.moodLabel}): " +
                            entry.reflectionText.take(100)
                    )
                }
            }
            if (goals.isNotEmpty()) {
                appendLine("Target kebiasaan aktif:")
                goals.take(MAX_GOALS).forEach { goal ->
                    appendLine("- ${goal.title} (${goal.completedCount}/${goal.targetCount} · ${goal.cadence})")
                }
            }
        }.trim()
    }

    private suspend fun buildVerbatimSummary(): String {
        val repo = verbatimRepository ?: return ""
        val sessions = runCatching { repo.observeSessions().firstOrNull().orEmpty() }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
        val latest = sessions.firstOrNull() ?: return ""
        val detail = runCatching { repo.observeSession(latest.sessionId).firstOrNull() }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return ""
        val notes = runCatching { repo.getLatestSessionNotes(latest.sessionId) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()

        val participantQuotes = detail.transcript
            .filter {
                val content = it.editedText?.takeIf(String::isNotBlank) ?: it.rawText
                content.isNotBlank() && !it.speakerId.equals("Konselor", ignoreCase = true)
            }
            .takeLast(MAX_VERBATIM_SEGMENTS)
            .joinToString(" | ") { seg ->
                val content = seg.editedText?.takeIf(String::isNotBlank) ?: seg.rawText
                "${seg.speakerId}: \"${content.take(MAX_QUOTE_CHARS)}\""
            }

        val prosodyList = detail.transcript.mapNotNull { it.prosody?.takeIf(VerbatimProsodyEvidence::isAvailable) }
        val prosodySummary = if (prosodyList.isNotEmpty()) {
            val avgPitch = prosodyList.mapNotNull(VerbatimProsodyEvidence::pitchHz).averageOrNull()
            val avgLoudness = prosodyList.mapNotNull(VerbatimProsodyEvidence::loudness).averageOrNull()
            val avgJitter = prosodyList.mapNotNull(VerbatimProsodyEvidence::jitter).averageOrNull()
            buildString {
                append("Observasi akustik (${prosodyList.size} segmen, bukan diagnosis): ")
                avgPitch?.let { append("F0=%.1f Hz; ".format(Locale.US, it)) }
                avgLoudness?.let { append("loudness=%.2f; ".format(Locale.US, it)) }
                avgJitter?.let { append("jitter=%.4f".format(Locale.US, it)) }
            }.trimEnd(';', ' ')
        } else {
            ""
        }

        val markers = detail.divergenceEvents.takeLast(MAX_DIVERGENCE_MARKERS).joinToString("; ") { event ->
            "${event.markerType}: ${event.copyText.take(80)}"
        }

        return buildString {
            val started = formatDate(detail.summary.startedAtMillis)
            appendLine("Sesi Verbatim+ terbaru (${detail.summary.sessionName}, $started, ${detail.summary.durationSeconds}d):")
            if (participantQuotes.isNotBlank()) appendLine("Ucapan peserta: $participantQuotes")
            if (prosodySummary.isNotBlank()) appendLine(prosodySummary)
            if (markers.isNotBlank()) appendLine("Penanda sesi: $markers")
            if (!notes.isNullOrBlank()) appendLine("Catatan sesi tersimpan: ${notes.take(180)}")
        }.trim()
    }

    private fun buildEnrichedRetrievalQuery(
        userQuery: String,
        primaryResult: AssessmentResult?,
        verbatimSummary: String,
        mode: CounselingInteractionMode
    ): String {
        val extras = mutableListOf(userQuery.trim())
        if (primaryResult != null) {
            extras += primaryResult.title
            extras += "asesmen tindak lanjut intervensi"
        }
        if (mode == CounselingInteractionMode.COUNSELOR_PLANNING || CLINICAL_CONTEXT_INTENT.containsMatchIn(userQuery)) {
            extras += "teknik konseling CBT REBT behavioral client centered"
        }
        if (verbatimSummary.isNotBlank() && extras.size == 1) {
            extras += "keterampilan mikro konseling regulasi emosi"
        }
        return extras.distinct().joinToString(" ")
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private fun formatDate(millis: Long): String = synchronized(DATE_FORMAT) { DATE_FORMAT.format(Date(millis)) }

    private companion object {
        const val MAX_EXTRA_ASSESSMENTS = 2
        const val MAX_SCORES_PER_ASSESSMENT = 4
        const val MAX_INTERPRETATION_CHARS = 180
        const val MAX_VERBATIM_SEGMENTS = 3
        const val MAX_QUOTE_CHARS = 110
        const val MAX_DIVERGENCE_MARKERS = 2
        const val MAX_JOURNALS = 4
        const val MAX_GOALS = 4
        val DATE_FORMAT = SimpleDateFormat("dd MMM yyyy", Locale("id", "ID"))
        val CLINICAL_CONTEXT_INTENT = Regex(
            "(?i)\\b(?:asesmen|psikotes|hasil|skor|terapi|konseling|intervensi|rebt|cbt|behavioral|" +
                "verbatim|stres|stress|rencana|formulasi|tindak lanjut|rekomendasi|latihan)\\b"
        )
    }
}
