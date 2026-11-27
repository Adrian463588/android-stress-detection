@file:Suppress("LongMethod", "ReturnCount")

package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.db.DivergenceEventEntity
import kotlin.math.abs

enum class WatchWearerRole(val displayLabel: String) {
    CLIENT("Klien memakai Galaxy Watch"),
    PSYCHOLOGIST("Psikolog memakai Galaxy Watch"),
    UNSPECIFIED("Galaxy Watch tidak dipakai / tidak ditentukan");

    companion object {
        fun fromStored(raw: String?): WatchWearerRole = entries.firstOrNull {
            it.name.equals(raw?.trim(), ignoreCase = true)
        } ?: CLIENT
    }
}

enum class MultimodalComparisonCategory(val label: String) {
    PHYSIOLOGICAL_ONLY("Perubahan fisiologis teramati"),
    ACOUSTIC_ONLY("Perubahan akustik teramati"),
    CONCORDANT_BOTH("Perubahan kedua modalitas"),
    DIRECTIONAL_DIVERGENCE("Perbedaan arah perubahan"),
    SINGLE_MODALITY_AVAILABLE("Bukti modalitas tunggal")
}

data class MultimodalComparisonFinding(
    val id: String,
    val sessionId: String,
    val category: MultimodalComparisonCategory,
    val title: String,
    val summary: String,
    val timestampElapsedMillis: Long,
    val episodeStartElapsedMillis: Long,
    val episodeEndElapsedMillis: Long,
    val evidenceIds: List<String>,
    val linkedSegmentId: String? = null
)

/**
 * Aligns independent acoustic and PPG markers on a shared session timeline without
 * fusing them into a composite score or inferring hidden diagnoses/deception.
 */
object MultimodalCueAnalyzer {
    const val DEFAULT_ALIGN_WINDOW_MS = 15_000L
    const val MARKER_TYPE_MULTIMODAL_CUE = "MULTIMODAL_CUE"

    private val physiologicalMarkerTypes = setOf(
        PhysiologicalMarkerType.BPM_TRAJECTORY.name,
        PhysiologicalMarkerType.TEMPORAL_BASELINE_AROUSAL.name,
        PhysiologicalMarkerType.BPM_RISE.name,
        PhysiologicalMarkerType.BPM_RECOVERY.name,
        PhysiologicalMarkerType.MODEL_AROUSAL.name,
        PhysiologicalMarkerType.CHANNEL_AGREEMENT.name,
        SessionMarkerType.STRESS_ELEVATED.name,
        ThermalPolicyConfig.MARKER_TYPE_THERMAL_DIVERGENCE
    )

    private val acousticMarkerTypes = setOf(
        SessionMarkerType.ACOUSTIC_DIVERGENCE.name,
        "PROSODY_DIVERGENCE"
    )

    fun analyze(
        sessionId: String,
        watchWearerRole: WatchWearerRole,
        events: List<DivergenceEventEntity>,
        segments: List<SpeechSegment>,
        alignWindowMillis: Long = DEFAULT_ALIGN_WINDOW_MS
    ): List<MultimodalComparisonFinding> {
        val physio = events.filter { it.markerType in physiologicalMarkerTypes }
            .sortedBy(DivergenceEventEntity::markerElapsedMillis)
        val acoustic = events.filter { it.markerType in acousticMarkerTypes }
            .sortedBy(DivergenceEventEntity::markerElapsedMillis)

        if (physio.isEmpty() && acoustic.isEmpty()) return emptyList()

        return if (watchWearerRole != WatchWearerRole.CLIENT) {
            buildWearerSeparatedFindings(sessionId, watchWearerRole, physio, acoustic)
        } else {
            buildClientAlignedFindings(sessionId, physio, acoustic, segments, alignWindowMillis)
        }
    }

    fun toMultimodalEntities(findings: List<MultimodalComparisonFinding>): List<DivergenceEventEntity> = findings.map { f ->
        DivergenceEventEntity(
            eventId = f.id,
            sessionId = f.sessionId,
            timestampMs = f.timestampElapsedMillis,
            delta = null,
            currentProbability = null,
            baselineProbability = null,
            copyText = "${f.title} — ${f.summary}",
            channel = "MULTIMODAL",
            markerElapsedMillis = f.timestampElapsedMillis,
            episodeStartElapsedMillis = f.episodeStartElapsedMillis,
            episodeEndElapsedMillis = f.episodeEndElapsedMillis,
            contextWindowStartElapsedMillis = f.episodeStartElapsedMillis,
            contextWindowEndElapsedMillis = f.episodeEndElapsedMillis,
            transcriptSegmentId = f.linkedSegmentId,
            markerType = MARKER_TYPE_MULTIMODAL_CUE,
            sourceChannels = "ACOUSTIC,PPG",
            evidenceIdsJson = encodeEvidenceIds(f.evidenceIds),
            detectedAtElapsedMillis = f.timestampElapsedMillis
        )
    }

    fun encodeEvidenceIds(ids: List<String>): String = ids.joinToString(prefix = "[", postfix = "]", separator = ",") { id ->
        val safe = id.replace("\\", "\\\\").replace("\"", "\\\"")
        "\"$safe\""
    }

    fun decodeEvidenceIds(json: String?): List<String> {
        if (json.isNullOrBlank() || json.trim() == "[]") return emptyList()
        return Regex("\"((?:\\\\.|[^\"])*)\"")
            .findAll(json)
            .map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
            .toList()
    }

    private fun buildWearerSeparatedFindings(
        sessionId: String,
        role: WatchWearerRole,
        physio: List<DivergenceEventEntity>,
        acoustic: List<DivergenceEventEntity>
    ): List<MultimodalComparisonFinding> {
        val findings = mutableListOf<MultimodalComparisonFinding>()
        val roleNote = if (role == WatchWearerRole.PSYCHOLOGIST) {
            "Perangkat Watch terpasang pada psikolog, bukan klien; tidak dipasangkan dengan ujaran klien."
        } else {
            "Pemakai Watch tidak ditentukan; modalitas dibandingkan terpisah."
        }
        acoustic.forEach { a ->
            findings += MultimodalComparisonFinding(
                id = "$sessionId:mm:ac:${a.eventId}",
                sessionId = sessionId,
                category = MultimodalComparisonCategory.SINGLE_MODALITY_AVAILABLE,
                title = "Hanya informasi akustik",
                summary = roleNote,
                timestampElapsedMillis = a.markerElapsedMillis,
                episodeStartElapsedMillis = a.episodeStartElapsedMillis,
                episodeEndElapsedMillis = a.episodeEndElapsedMillis,
                evidenceIds = listOf(a.eventId),
                linkedSegmentId = a.transcriptSegmentId
            )
        }
        if (acoustic.isEmpty() && physio.isNotEmpty()) {
            val firstP = physio.first()
            findings += MultimodalComparisonFinding(
                id = "$sessionId:mm:phonly:${firstP.eventId}",
                sessionId = sessionId,
                category = MultimodalComparisonCategory.SINGLE_MODALITY_AVAILABLE,
                title = "Hanya informasi fisiologis",
                summary = roleNote,
                timestampElapsedMillis = firstP.markerElapsedMillis,
                episodeStartElapsedMillis = firstP.episodeStartElapsedMillis,
                episodeEndElapsedMillis = firstP.episodeEndElapsedMillis,
                evidenceIds = physio.map(DivergenceEventEntity::eventId)
            )
        }
        return findings
    }

    private fun buildClientAlignedFindings(
        sessionId: String,
        physio: List<DivergenceEventEntity>,
        acoustic: List<DivergenceEventEntity>,
        segments: List<SpeechSegment>,
        alignWindowMillis: Long
    ): List<MultimodalComparisonFinding> {
        val matchedAcousticIds = mutableSetOf<String>()
        val findings = mutableListOf<MultimodalComparisonFinding>()
        val hasAudioSpeech = segments.any { it.isFinal }

        if (physio.isNotEmpty() && !hasAudioSpeech) {
            physio.forEach { p ->
                findings += MultimodalComparisonFinding(
                    id = "$sessionId:mm:nospeech:${p.eventId}",
                    sessionId = sessionId,
                    category = MultimodalComparisonCategory.SINGLE_MODALITY_AVAILABLE,
                    title = "Bukti fisiologis tanpa data suara",
                    summary = "Perubahan fisiologis Watch tercatat saat tidak ada segmen bicara final terdeteksi.",
                    timestampElapsedMillis = p.markerElapsedMillis,
                    episodeStartElapsedMillis = p.episodeStartElapsedMillis,
                    episodeEndElapsedMillis = p.episodeEndElapsedMillis,
                    evidenceIds = listOf(p.eventId)
                )
            }
            return findings
        }

        if (physio.isEmpty()) {
            acoustic.forEach { a ->
                findings += MultimodalComparisonFinding(
                    id = "$sessionId:mm:nowatch:${a.eventId}",
                    sessionId = sessionId,
                    category = MultimodalComparisonCategory.SINGLE_MODALITY_AVAILABLE,
                    title = "Hanya informasi akustik",
                    summary = "Watch tidak menghasilkan marker fisiologis pada jendela waktu yang sama.",
                    timestampElapsedMillis = a.markerElapsedMillis,
                    episodeStartElapsedMillis = a.episodeStartElapsedMillis,
                    episodeEndElapsedMillis = a.episodeEndElapsedMillis,
                    evidenceIds = listOf(a.eventId),
                    linkedSegmentId = a.transcriptSegmentId
                )
            }
            return findings
        }

        physio.forEach { p ->
            val nearest = acoustic
                .filter { abs(it.markerElapsedMillis - p.markerElapsedMillis) <= alignWindowMillis }
                .minByOrNull { abs(it.markerElapsedMillis - p.markerElapsedMillis) }
            if (nearest != null) {
                matchedAcousticIds += nearest.eventId
                val finding = classifyPair(sessionId, p, nearest)
                findings += finding
            } else {
                val silentDuringWindow = segments.none { seg ->
                    p.markerElapsedMillis in (seg.startOffsetMs - alignWindowMillis)..(seg.endOffsetMs + alignWindowMillis)
                }
                findings += MultimodalComparisonFinding(
                    id = "$sessionId:mm:p:${p.eventId}",
                    sessionId = sessionId,
                    category = if (silentDuringWindow) {
                        MultimodalComparisonCategory.SINGLE_MODALITY_AVAILABLE
                    } else {
                        MultimodalComparisonCategory.PHYSIOLOGICAL_ONLY
                    },
                    title = if (silentDuringWindow) {
                        "Bukti fisiologis tanpa data suara"
                    } else {
                        "Perubahan fisiologis teramati"
                    },
                    summary = if (silentDuringWindow) {
                        "Respons nadi meningkat saat tidak ada ucapan pada jendela waktu ini."
                    } else {
                        "Karakteristik suara tetap relatif stabil ketika respons nadi berubah."
                    },
                    timestampElapsedMillis = p.markerElapsedMillis,
                    episodeStartElapsedMillis = p.episodeStartElapsedMillis,
                    episodeEndElapsedMillis = p.episodeEndElapsedMillis,
                    evidenceIds = listOf(p.eventId),
                    linkedSegmentId = p.transcriptSegmentId
                )
            }
        }

        acoustic.filter { it.eventId !in matchedAcousticIds }.forEach { a ->
            findings += MultimodalComparisonFinding(
                id = "$sessionId:mm:a:${a.eventId}",
                sessionId = sessionId,
                category = MultimodalComparisonCategory.ACOUSTIC_ONLY,
                title = "Perubahan akustik teramati",
                summary = "Perubahan vokal teramati ketika denyut nadi relatif stabil.",
                timestampElapsedMillis = a.markerElapsedMillis,
                episodeStartElapsedMillis = a.episodeStartElapsedMillis,
                episodeEndElapsedMillis = a.episodeEndElapsedMillis,
                evidenceIds = listOf(a.eventId),
                linkedSegmentId = a.transcriptSegmentId
            )
        }

        return findings.sortedBy(MultimodalComparisonFinding::timestampElapsedMillis)
    }

    private fun classifyPair(sessionId: String, p: DivergenceEventEntity, a: DivergenceEventEntity): MultimodalComparisonFinding {
        val physioRising = isPhysioRising(p)
        val acousticFalling = (a.deltaValue != null && a.deltaValue < 0f) ||
            a.copyText.contains("menurun", ignoreCase = true) ||
            a.copyText.contains("melambat", ignoreCase = true)
        val isOpposite = (physioRising && acousticFalling) || (!physioRising && !acousticFalling)
        val category = if (isOpposite) {
            MultimodalComparisonCategory.DIRECTIONAL_DIVERGENCE
        } else {
            MultimodalComparisonCategory.CONCORDANT_BOTH
        }
        val title = category.label
        val summary = if (isOpposite) {
            "Modalitas akustik dan fisiologis berubah ke arah berlawanan pada jendela waktu ini."
        } else {
            "Perubahan akustik dan fisiologis teramati pada rentang waktu berdekatan."
        }
        return MultimodalComparisonFinding(
            id = "$sessionId:mm:pair:${p.eventId}:${a.eventId}",
            sessionId = sessionId,
            category = category,
            title = title,
            summary = summary,
            timestampElapsedMillis = minOf(p.markerElapsedMillis, a.markerElapsedMillis),
            episodeStartElapsedMillis = minOf(p.episodeStartElapsedMillis, a.episodeStartElapsedMillis),
            episodeEndElapsedMillis = maxOf(p.episodeEndElapsedMillis, a.episodeEndElapsedMillis),
            evidenceIds = listOf(p.eventId, a.eventId),
            linkedSegmentId = a.transcriptSegmentId ?: p.transcriptSegmentId
        )
    }

    private fun isPhysioRising(p: DivergenceEventEntity): Boolean = when {
        p.markerType == ThermalPolicyConfig.MARKER_TYPE_THERMAL_DIVERGENCE ->
            p.thermalDirection.equals(ThermalDirection.INCREASE.name, ignoreCase = true) ||
                (!p.thermalDirection.equals(ThermalDirection.DECREASE.name, ignoreCase = true) && (p.deltaValue ?: 0f) >= 0f)
        else -> !p.bpmTrend.equals("RECOVERING", ignoreCase = true) && (p.bpmDelta == null || p.bpmDelta >= 0f)
    }
}
