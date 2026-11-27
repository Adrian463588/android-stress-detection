package com.stressdetector.mobile.lifecycle

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.stressdetector.mobile.data.db.LongitudinalDao
import com.stressdetector.mobile.data.db.ResearchExportManifestEntity
import com.stressdetector.mobile.data.db.StressSessionDao
import com.stressdetector.mobile.data.repository.AssessmentRepository
import com.stressdetector.mobile.data.repository.JournalAndGoalRepository
import com.stressdetector.mobile.domain.assessment.AssessmentResult
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class ResearchExportOutcome(
    val exportId: String,
    val exportFile: File,
    val artifactSha256: String,
    val includedAssessmentCount: Int,
    val includedJournalCount: Int,
    val includedStressSessionCount: Int
)

/**
 * Generates pseudonymized longitudinal research bundles strictly from records
 * whose per-record consent flag (`researchExportAllowed`) is explicitly enabled.
 */
class ResearchExportService(
    private val longitudinalDao: LongitudinalDao,
    private val assessmentRepository: AssessmentRepository,
    private val stressSessionDao: StressSessionDao,
    private val exportDirectory: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
) {
    suspend fun exportParticipantBundle(
        participantId: String = JournalAndGoalRepository.DEFAULT_PARTICIPANT_ID,
        kbVersion: String = "bundled-counseling-v2",
        ethicsApprovalRef: String? = null
    ): ResearchExportOutcome = withContext(Dispatchers.IO) {
        val now = clock()
        val participant = longitudinalDao.getParticipant(participantId)
        val pseudonym = participant?.pseudonymCode ?: JournalAndGoalRepository.DEFAULT_PSEUDONYM
        val consentedAssessments = assessmentRepository.getAllResults()
            .filter { it.researchExportAllowed }
        val consentedJournals = longitudinalDao.getResearchConsentedJournals(participantId)
        val stressSessions = stressSessionDao.latest(200).first()
            .map { session ->
                mapOf(
                    "sessionId" to session.sessionId,
                    "startedAtMillis" to session.startedAtMillis,
                    "endedAtMillis" to session.endedAtMillis,
                    "primarySensor" to session.primarySensor,
                    "finalLabel" to session.finalLabel,
                    "qualityTier" to session.qualityTier,
                    "stressProbability" to session.stressProbability,
                    "transportIncomplete" to session.transportIncomplete
                )
            }
        val goals = longitudinalDao.getWellbeingGoals(participantId)
        val interpretations = longitudinalDao.getAiInterpretations(participantId)
        val sessions = longitudinalDao.getAssessmentSessions(participantId)
            .filter { it.researchExportAllowed }

        val exportId = "res-${UUID.randomUUID().toString().take(12)}"
        val bundle = mapOf(
            "schemaVersion" to SCHEMA_VERSION,
            "exportId" to exportId,
            "exportedAtMillis" to now,
            "participantPseudonym" to pseudonym,
            "kbVersion" to kbVersion,
            "ethicsApprovalRef" to ethicsApprovalRef,
            "assessments" to consentedAssessments.map(::sanitizeAssessmentForResearch),
            "assessmentSessions" to sessions,
            "journals" to consentedJournals.map { entry ->
                mapOf(
                    "entryId" to entry.entryId,
                    "moodScore" to entry.moodScore,
                    "moodLabel" to entry.moodLabel,
                    "sleepQuality" to entry.sleepQuality,
                    "stressLevelSelfReport" to entry.stressLevelSelfReport,
                    "createdAtMillis" to entry.createdAtMillis
                )
            },
            "wellbeingGoals" to goals,
            "ppgStressSummary" to stressSessions,
            "aiInterpretations" to interpretations.map { interp ->
                mapOf(
                    "interpretationId" to interp.interpretationId,
                    "assessmentResultId" to interp.assessmentResultId,
                    "instrumentId" to interp.instrumentId,
                    "modelVersion" to interp.modelVersion,
                    "kbVersion" to interp.kbVersion,
                    "createdAtMillis" to interp.createdAtMillis
                )
            }
        )

        exportDirectory.mkdirs()
        val file = File(exportDirectory, "research_export_${pseudonym}_$now.json")
        val jsonBytes = gson.toJson(bundle).toByteArray(Charsets.UTF_8)
        file.writeBytes(jsonBytes)
        val sha = sha256(jsonBytes)

        longitudinalDao.insertResearchExportManifest(
            ResearchExportManifestEntity(
                exportId = exportId,
                participantId = participantId,
                exportedAtMillis = now,
                schemaVersion = SCHEMA_VERSION,
                artifactSha256 = sha,
                includedModulesJson = gson.toJson(
                    listOf("assessments", "journals", "wellbeing_goals", "ppg_summary", "ai_interpretations")
                ),
                consentSnapshotJson = gson.toJson(
                    mapOf(
                        "consentedAssessmentCount" to consentedAssessments.size,
                        "consentedJournalCount" to consentedJournals.size
                    )
                ),
                ethicsApprovalRef = ethicsApprovalRef
            )
        )

        ResearchExportOutcome(
            exportId = exportId,
            exportFile = file,
            artifactSha256 = sha,
            includedAssessmentCount = consentedAssessments.size,
            includedJournalCount = consentedJournals.size,
            includedStressSessionCount = stressSessions.size
        )
    }

    private fun sanitizeAssessmentForResearch(result: AssessmentResult): Map<String, Any?> = mapOf(
        "resultId" to result.resultId,
        "instrumentId" to result.instrumentId,
        "version" to result.version,
        "scoringSystemId" to result.examinerCodes["scoring_system"],
        "administrationMode" to result.administrationMode,
        "createdAtMillis" to result.createdAtMillis,
        "answered" to result.answered,
        "itemCount" to result.itemCount,
        "scoringStatus" to result.resolvedScoringStatus().name,
        "validityStatus" to result.resolvedValidityStatus().name,
        "validityWarnings" to result.validityWarnings,
        "reviewStatus" to result.resolvedReviewStatus().name,
        "scorerVersion" to result.scorerVersion,
        "normReference" to result.normReference,
        "scores" to result.scores,
        "examinerCodes" to result.examinerCodes,
        "warteggDraftsCount" to result.warteggDrafts.size,
        "rorschachResponsesCount" to result.rorschachResponses.size
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val SCHEMA_VERSION = "research_export_v1"
    }
}
