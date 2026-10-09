package com.stressdetector.mobile.lifecycle

import com.google.gson.JsonObject
import com.stressdetector.mobile.security.AtomicFilePublisher
import com.stressdetector.mobile.security.EncryptedStorageService
import com.stressdetector.shared.PpgType
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Re-encrypts portable media with the current installation's key without buffering audio in RAM. */
internal class PortableArchiveMedia(private val storage: EncryptedStorageService, private val userId: String, private val charts: File) {
    fun prepareCharts(sources: Map<String, String>, stagingId: String, prepared: MutableList<File>) {
        sources.forEach { (id, source) ->
            prepared += chartTemporary(id, stagingId)
            prepareChart(source, id, stagingId)
        }
    }

    fun prepareChart(source: String, sessionId: String, stagingId: String): File {
        PortableArchiveFormat.validateChartSessionId(sessionId)
        check(charts.isDirectory || charts.mkdirs()) { "History chart directory unavailable" }
        val temporary = chartTemporary(sessionId, stagingId)
        check(temporary.parentFile!!.isDirectory || temporary.parentFile!!.mkdirs()) { "Restore chart staging unavailable" }
        FileOutputStream(temporary).use { output ->
            storage.copyEncryptedFile(userId, source, output)
            output.fd.sync()
        }
        return temporary
    }

    fun chartTemporary(sessionId: String, stagingId: String): File =
        chartTemporary(charts, sessionId, "portable_archive_staging/$stagingId")

    /** Called by the synchronized archive coordinator only after successful journal recovery. */
    fun cleanupChartStaging() {
        val root = scopedFile(charts.canonicalFile, ".portable-restore")
        if (!root.exists()) return
        check(root.isDirectory) { "Invalid chart staging directory" }
        val directories = checkNotNull(root.listFiles()).toList()
        val files = directories.flatMap { directory ->
            check(
                directory.isDirectory &&
                    directory.canonicalFile == directory.absoluteFile &&
                    UUID.fromString(directory.name).toString() == directory.name
            ) { "Invalid chart staging scope" }
            checkNotNull(directory.listFiles()).toList().onEach { file ->
                check(file.canonicalFile == file.absoluteFile && file.name.endsWith(".json") && readOwnership(file) != null) {
                    "Invalid chart staging file"
                }
                PortableArchiveFormat.validateChartSessionId(file.name.removeSuffix(".json"))
            }
        }
        files.forEach { check(it.delete()) { "Restore chart staging cleanup failed" } }
        directories.forEach { check(it.delete()) { "Restore chart directory cleanup failed" } }
        check(root.delete()) { "Restore chart staging root cleanup failed" }
    }

    fun publishChart(sessionId: String, stagingId: String) {
        AtomicFilePublisher.moveNew(chartTemporary(sessionId, stagingId), File(charts, "$sessionId.json"))
    }

    fun publishEncrypted(source: String, target: String) {
        storage.moveNewEncryptedFile(userId, source, target)
    }
}

internal fun validatePortableHistoryChart(chart: JsonObject, sessionId: String) {
    require(chart.get("version")?.asInt == 2 && chart.get("series")?.isJsonArray == true) { "Unsupported history chart artifact" }
    val series = chart.getAsJsonArray("series")
    require(series.size() > 0 || chart.get("unavailable_reason")?.asString?.isNotBlank() == true) {
        "Empty history chart without a reason"
    }
    val channels = mutableSetOf<String>()
    series.forEach { item ->
        val value = item.asJsonObject
        require(value.get("session_id")?.asString == sessionId) { "History chart belongs to another session" }
        val channel = value.get("channel")?.asString
        require(PpgType.entries.any { it.assetSuffix == channel } && channels.add(checkNotNull(channel))) { "Invalid chart channel" }
        require(value.get("processing_fingerprint")?.asString?.isNotBlank() == true) { "Missing chart processing fingerprint" }
        validatePortableChartPoints(value)
    }
}

private fun validatePortableChartPoints(value: JsonObject) {
    val elapsed = value.getAsJsonArray("elapsed_millis").map { it.asString.toLong() }
    val display = value.getAsJsonArray("display_values")
    val mask = value.getAsJsonArray("interpolated_mask")
    require(elapsed.isNotEmpty() && elapsed.size == display.size() && elapsed.size == mask.size()) { "Chart sample lengths mismatch" }
    require(elapsed.all { it in CORE_START_MILLIS until CORE_END_MILLIS } && elapsed.zipWithNext().all { (a, b) -> a < b }) {
        "Invalid cleaned-core chart timeline"
    }
    require(display.all { it.asDouble.isFinite() } && mask.all { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }) {
        "Invalid chart observation"
    }
    value.getAsJsonArray("gaps").forEach { gap ->
        val start = gap.asJsonObject.get("start").asString.toLong()
        val end = gap.asJsonObject.get("end").asString.toLong()
        require(start >= CORE_START_MILLIS && end < CORE_END_MILLIS && start < end) { "Invalid chart gap" }
    }
}

private const val CORE_START_MILLIS = 30_000L
private const val CORE_END_MILLIS = 270_000L
