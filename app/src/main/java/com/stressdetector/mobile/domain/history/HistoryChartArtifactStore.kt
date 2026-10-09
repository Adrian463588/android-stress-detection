package com.stressdetector.mobile.domain.history

import android.content.Context
import com.stressdetector.mobile.domain.PpgChartData
import com.stressdetector.shared.PpgType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class HistoryGapSegment(val startElapsedMillis: Long, val endElapsedMillis: Long)

data class HistoryChartSeries(
    val sessionId: String,
    val channel: PpgType,
    val elapsedMillis: LongArray,
    val displayValues: FloatArray,
    val interpolatedMask: BooleanArray,
    val gapSegments: List<HistoryGapSegment>,
    val processingFingerprint: String
)

data class HistoryChartArtifact(val series: List<HistoryChartSeries>, val unavailableReason: String? = null)

enum class HistoryResultStatus {
    STRESS,
    NORMAL,
    INVALID
}

object HistoryStatusMapper {
    fun map(finalLabel: String?, errorReason: String?, reportStatus: String?, transportIncomplete: Boolean = false): HistoryResultStatus {
        val outcome = SessionOutcomeResolver.resolve(
            finalLabel = finalLabel,
            errorReason = errorReason,
            reportStatus = reportStatus,
            transportIncomplete = transportIncomplete
        )
        return when (outcome.predictionStatus) {
            PredictionStatus.INVALID -> HistoryResultStatus.INVALID
            PredictionStatus.VALID -> when (outcome.label) {
                com.stressdetector.shared.StressLabel.STRESS -> HistoryResultStatus.STRESS
                com.stressdetector.shared.StressLabel.RELAX -> HistoryResultStatus.NORMAL
                null,
                com.stressdetector.shared.StressLabel.INVALID -> HistoryResultStatus.INVALID
            }
        }
    }
}

/** JSON codec kept separate so round-trip tests do not require an Android Context. */
internal object HistoryChartArtifactCodec {
    fun encode(artifact: HistoryChartArtifact): String = JSONObject().apply {
        put("version", 2)
        artifact.unavailableReason?.let { put("unavailable_reason", it) }
        put(
            "series",
            JSONArray().apply {
                artifact.series.forEach { value ->
                    put(
                        JSONObject().apply {
                            put("session_id", value.sessionId)
                            put("channel", value.channel.assetSuffix)
                            put("processing_fingerprint", value.processingFingerprint)
                            put("elapsed_millis", JSONArray(value.elapsedMillis.toList()))
                            put("display_values", JSONArray(value.displayValues.map(Float::toDouble)))
                            put("interpolated_mask", JSONArray(value.interpolatedMask.toList()))
                            put(
                                "gaps",
                                JSONArray().apply {
                                    value.gapSegments.forEach { gap ->
                                        put(
                                            JSONObject().apply {
                                                put("start", gap.startElapsedMillis)
                                                put("end", gap.endElapsedMillis)
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    )
                }
            }
        )
    }.toString()

    fun decode(json: String): HistoryChartArtifact {
        val root = JSONObject(json)
        val values = root.optJSONArray("series") ?: JSONArray()
        val series = buildList {
            for (index in 0 until values.length()) {
                val item = values.getJSONObject(index)
                val channel = PpgType.entries.firstOrNull {
                    it.assetSuffix == item.optString("channel")
                } ?: continue
                val elapsed = (item.optJSONArray("elapsed_millis") ?: JSONArray()).toLongArray()
                val display = (item.optJSONArray("display_values") ?: JSONArray()).toFloatArray()
                val mask = (item.optJSONArray("interpolated_mask") ?: JSONArray()).toBooleanArray()
                if (elapsed.isEmpty() || elapsed.size != display.size) continue
                add(
                    HistoryChartSeries(
                        sessionId = item.optString("session_id"),
                        channel = channel,
                        elapsedMillis = elapsed,
                        displayValues = display,
                        interpolatedMask = mask.copyOf(elapsed.size),
                        gapSegments = item.optJSONArray("gaps").toGapSegments(),
                        processingFingerprint = item.optString("processing_fingerprint")
                    )
                )
            }
        }
        return HistoryChartArtifact(
            series = series,
            unavailableReason = root.optString("unavailable_reason").takeIf { it.isNotBlank() }
        )
    }

    private fun JSONArray.toLongArray(): LongArray = LongArray(length()) { getLong(it) }

    private fun JSONArray.toFloatArray(): FloatArray = FloatArray(length()) { getDouble(it).toFloat() }

    private fun JSONArray.toBooleanArray(): BooleanArray = BooleanArray(length()) { getBoolean(it) }

    private fun JSONArray?.toGapSegments(): List<HistoryGapSegment> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val gap = getJSONObject(index)
                add(HistoryGapSegment(gap.getLong("start"), gap.getLong("end")))
            }
        }
    }
}

/**
 * App-private, atomic history artifact storage. It stores only the cleaned chart
 * projection produced by final inference; no raw/model/SAF data is copied here.
 */
class HistoryChartArtifactStore(context: Context, private val onChanged: () -> Unit = {}) {
    private val directory = File(context.applicationContext.filesDir, "history_chart_artifacts")

    suspend fun save(sessionId: String, series: List<HistoryChartSeries>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(sessionId.isNotBlank()) { "sessionId is blank" }
            require(series.all { it.sessionId == sessionId }) { "chart session mismatch" }
            writeAtomically(sessionId, HistoryChartArtifact(series))
        }
    }

    suspend fun saveUnavailable(sessionId: String, reason: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { writeAtomically(sessionId, HistoryChartArtifact(emptyList(), reason)) }
    }

    suspend fun load(sessionId: String): HistoryChartArtifact? = withContext(Dispatchers.IO) {
        val file = File(directory, "$sessionId.json")
        file.takeIf(File::isFile)?.let { runCatching { HistoryChartArtifactCodec.decode(it.readText()) }.getOrNull() }
    }

    private fun writeAtomically(sessionId: String, artifact: HistoryChartArtifact) {
        directory.mkdirs()
        val target = File(directory, "$sessionId.json")
        val temporary = File(directory, "$sessionId.json.tmp")
        temporary.outputStream().bufferedWriter().use { it.write(HistoryChartArtifactCodec.encode(artifact)) }
        check(temporary.renameTo(target)) { "history chart atomic rename failed" }
        onChanged()
    }
}

private fun PpgChartData.toHistorySeries(sessionId: String, channel: PpgType): HistoryChartSeries {
    val source = timestampsMillis.indices
        .filter { index -> values.getOrNull(index)?.isFinite() == true }
        .map { index -> HistorySourcePoint(timestampsMillis[index], values[index], interpolatedMask.getOrElse(index) { false }) }
    val segments = source.fold(mutableListOf(mutableListOf<HistorySourcePoint>())) { groups, point ->
        val current = groups.last()
        if (current.isNotEmpty() && point.elapsedMillis - current.last().elapsedMillis > gapThresholdMillis) {
            groups.add(mutableListOf())
        }
        groups.last().add(point)
        groups
    }.filter { it.isNotEmpty() }
    val points = segments.flatMap(::envelopePoints)
    val gaps = segments.zipWithNext { first, second ->
        HistoryGapSegment(first.last().elapsedMillis, second.first().elapsedMillis)
    }
    return HistoryChartSeries(
        sessionId = sessionId,
        channel = channel,
        elapsedMillis = points.map(HistorySourcePoint::elapsedMillis).toLongArray(),
        displayValues = points.map(HistorySourcePoint::value).toFloatArray(),
        interpolatedMask = points.map(HistorySourcePoint::interpolated).toBooleanArray(),
        gapSegments = gaps,
        processingFingerprint = sourceFingerprint.orEmpty()
    )
}

private data class HistorySourcePoint(val elapsedMillis: Long, val value: Float, val interpolated: Boolean)

/** Bounded, extrema-preserving projection; it never invents display samples. */
private fun envelopePoints(segment: List<HistorySourcePoint>): List<HistorySourcePoint> {
    val bucketSize = (segment.size + HISTORY_CHART_MAX_POINTS_PER_CHANNEL - 1) /
        HISTORY_CHART_MAX_POINTS_PER_CHANNEL
    if (bucketSize <= 1) return segment
    return segment.chunked(bucketSize).flatMap { bucket ->
        listOf(bucket.minBy(HistorySourcePoint::value), bucket.maxBy(HistorySourcePoint::value))
            .distinctBy(HistorySourcePoint::elapsedMillis)
            .sortedBy(HistorySourcePoint::elapsedMillis)
    }
}

suspend fun HistoryChartArtifactStore.saveCharts(sessionId: String, charts: Map<PpgType, PpgChartData>): Result<Unit> {
    val series = charts.mapNotNull { (channel, chart) ->
        if (chart.mode != com.stressdetector.mobile.domain.PpgChartMode.CLEANED_CORE || chart.isEmpty) {
            null
        } else {
            chart.toHistorySeries(sessionId, channel)
        }
    }
    return if (series.isEmpty()) {
        saveUnavailable(sessionId, "cleaned_core_chart_unavailable")
    } else {
        save(sessionId, series)
    }
}

private const val HISTORY_CHART_MAX_POINTS_PER_CHANNEL = 1_440
