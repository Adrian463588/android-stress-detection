@file:Suppress("MagicNumber", "ComplexCondition")

package com.stressdetector.mobile.domain.engine

import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.shared.PpgType
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Generates consented, de-identified time-series research artifacts (`research_export.csv`
 * and `session_metadata.json`) without raw speech transcripts, client names, or audio files.
 */
object VerbatimResearchExporter {

    private const val RESEARCH_EXPORT_SCHEMA = "verbatim-research-export-v1"

    fun buildCsv(
        summary: VerbatimSessionSummary,
        events: List<DivergenceEventEntity>,
        prvByChannel: Map<PpgType, PrvMetrics> = emptyMap()
    ): String {
        val header = listOf(
            "elapsed_ms",
            "detected_at_elapsed_ms",
            "marker_type",
            "channel",
            "bpm",
            "baseline_bpm",
            "bpm_delta",
            "prv_rmssd_ms",
            "prv_sdnn_ms",
            "prv_pnn50_pct",
            "signal_quality",
            "speaker_id",
            "baseline_value",
            "observed_value",
            "delta_value",
            "unit",
            "watch_wearer_role"
        ).joinToString(",")

        val sorted = events.distinctBy { it.eventId }.sortedBy { it.markerElapsedMillis }
        val rows = sorted.map { ev ->
            val ppgChannel = PpgType.entries.firstOrNull { it.name.equals(ev.channel, ignoreCase = true) }
            val prv = ppgChannel?.let { prvByChannel[it] }?.takeIf { it.isQualityPassed }
            listOf(
                ev.markerElapsedMillis.toString(),
                ev.detectedAtElapsedMillis.toString(),
                csvCell(ev.markerType),
                csvCell(ev.channel),
                fmtFloat(ev.bpm),
                fmtFloat(ev.baselineBpm),
                fmtFloat(ev.bpmDelta),
                fmtFloat(prv?.rmssdMs),
                fmtFloat(prv?.sdnnMs),
                fmtFloat(prv?.pnn50Pct),
                fmtFloat(ev.signalQuality),
                csvCell(ev.speakerId.orEmpty()),
                fmtFloat(ev.baselineValue),
                fmtFloat(ev.observedValue),
                fmtFloat(ev.deltaValue),
                csvCell(ev.unit.orEmpty()),
                csvCell(summary.watchWearerRole)
            ).joinToString(",")
        }
        return buildString {
            appendLine(header)
            rows.forEach { appendLine(it) }
        }
    }

    @Suppress("LongParameterList")
    fun buildMetadataJson(
        summary: VerbatimSessionSummary,
        events: List<DivergenceEventEntity>,
        prvByChannel: Map<PpgType, PrvMetrics> = emptyMap(),
        clockBindingSource: String = "UNBOUND",
        skinTemperatureSamples: List<SkinTemperatureObservation> = emptyList(),
        thermalAlgorithmVersion: String = ThermalPolicyConfig.DEFAULT_ALGORITHM_VERSION
    ): String {
        val prvList = prvByChannel.values.map { metrics ->
            linkedMapOf<String, Any>(
                "channel" to metrics.channel.name,
                "window_seconds" to metrics.windowSeconds,
                "valid_ibi_count" to metrics.validIbiCount,
                "quality_passed" to metrics.isQualityPassed
            ).apply {
                metrics.rmssdMs?.let { put("rmssd_ms", it.toDouble()) }
                metrics.sdnnMs?.let { put("sdnn_ms", it.toDouble()) }
                metrics.pnn50Pct?.let { put("pnn50_pct", it.toDouble()) }
            }
        }
        val metadata = linkedMapOf<String, Any>(
            "schema_version" to RESEARCH_EXPORT_SCHEMA,
            "session_id_hash" to summary.sessionId.hashCode().toUInt().toString(16),
            "language_code" to summary.languageCode,
            "watch_wearer_role" to summary.watchWearerRole,
            "duration_seconds" to summary.durationSeconds,
            "audio_duration_ms" to summary.audioDurationMs,
            "finalization_status" to summary.finalizationStatus,
            "clock_binding_source" to clockBindingSource,
            "de_identified" to true,
            "contains_transcript_text" to false,
            "contains_raw_audio" to false,
            "event_count" to events.distinctBy { it.eventId }.size,
            "audio_gap_count" to summary.audioGaps.size,
            "prv_summary" to prvList,
            "thermal_summary" to thermalSummaryMap(skinTemperatureSamples, thermalAlgorithmVersion),
            "clinical_disclaimer" to "Research telemetry only; never a diagnostic finding or deception indicator."
        )
        return com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(metadata)
    }

    fun buildSkinTemperatureCsv(observations: List<SkinTemperatureObservation>): String {
        val header = listOf(
            "elapsed_ms",
            "sensor_timestamp_ms",
            "received_timestamp_ms",
            "sequence",
            "skin_c",
            "ambient_c",
            "status",
            "valid",
            "wearer_role",
            "source",
            "clock_uncertainty_ms",
            "is_paused"
        ).joinToString(",")
        val sorted = observations.sortedWith(
            compareBy(SkinTemperatureObservation::elapsedMillis, SkinTemperatureObservation::sequenceNumber)
        )
        return buildString {
            appendLine(header)
            sorted.forEach { obs ->
                appendLine(
                    listOf(
                        obs.elapsedMillis.toString(),
                        obs.sensorTimestampMs.toString(),
                        obs.receivedTimestampMs.toString(),
                        obs.sequenceNumber.toString(),
                        obs.skinTemperatureC?.takeIf { it.isFinite() }?.toString() ?: "",
                        obs.ambientTemperatureC?.takeIf { it.isFinite() }?.toString() ?: "",
                        obs.measurementStatus.toString(),
                        obs.isValid.toString(),
                        csvCell(obs.wearerRole),
                        csvCell(obs.source),
                        obs.clockUncertaintyMs?.toString() ?: "",
                        obs.isPaused.toString()
                    ).joinToString(",")
                )
            }
        }
    }

    fun writeSkinTemperatureCsv(output: OutputStream, observations: List<SkinTemperatureObservation>) {
        output.write(buildSkinTemperatureCsv(observations).toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    fun buildThermalMetadataJson(
        sessionId: String,
        observations: List<SkinTemperatureObservation>,
        algorithmVersion: String = ThermalPolicyConfig.DEFAULT_ALGORITHM_VERSION
    ): String {
        val payload = thermalSummaryMap(observations, algorithmVersion).toMutableMap().apply {
            put("session_id", sessionId)
            put("units", "Celsius")
        }
        return com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(payload)
    }

    private fun thermalSummaryMap(observations: List<SkinTemperatureObservation>, algorithmVersion: String): Map<String, Any> {
        val stats = computeSkinTemperatureStatistics(observations)
        return linkedMapOf<String, Any>(
            "total_samples" to stats.totalCount,
            "valid_samples" to stats.validCount,
            "coverage_ratio" to stats.coverageRatio.toDouble(),
            "algorithm_version" to algorithmVersion
        ).apply {
            stats.medianC?.let { put("median_skin_c", it.toDouble()) }
            stats.meanC?.let { put("mean_skin_c", it.toDouble()) }
            stats.minC?.let { put("min_skin_c", it.toDouble()) }
            stats.maxC?.let { put("max_skin_c", it.toDouble()) }
            stats.latestAmbientC?.let { put("latest_ambient_c", it.toDouble()) }
        }
    }

    fun writeCsv(
        output: OutputStream,
        summary: VerbatimSessionSummary,
        events: List<DivergenceEventEntity>,
        prvByChannel: Map<PpgType, PrvMetrics> = emptyMap()
    ) {
        output.write(buildCsv(summary, events, prvByChannel).toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun fmtFloat(value: Float?): String = value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.4f", it) } ?: ""

    private fun csvCell(raw: String): String {
        val sanitized = if (raw.startsWith("=") || raw.startsWith("+") || raw.startsWith("-") || raw.startsWith("@")) {
            "'$raw"
        } else {
            raw
        }
        return "\"${sanitized.replace("\"", "\"\"")}\""
    }
}
