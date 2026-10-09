package com.stressdetector.mobile.lifecycle

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.stressdetector.mobile.security.EncryptedStorageService
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

internal data class FileOwnership(val device: Long, val inode: Long) {
    init {
        require(device >= 0L && inode > 0L) { "Invalid restore file ownership" }
    }
}

internal data class PlannedFile(val stagePath: String, val ownership: FileOwnership)
internal data class PlannedChart(val sha256: String, val ownership: FileOwnership)
internal data class RestorePlan(
    val files: Map<String, PlannedFile>,
    val charts: Map<String, PlannedChart>,
    val staging: Map<String, FileOwnership>,
    val stagePrefix: String
)

private const val VERSION = 3
private const val MAX_ENTRIES = 100_000
private val STAGE_PREFIX = Regex("portable_archive_staging/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val STAGE_FILE = Regex("(?:[1-9][0-9]*\\.enc|\\.[1-9][0-9]*\\.enc\\.(?:tmp|bak))")
private val SHA256 = Regex("[0-9a-f]{64}")
private val OWNERSHIP_NUMBER = Regex("0|[1-9][0-9]*")

internal fun validateRestoreScope(files: Map<String, String>, charts: Map<String, String>, stagePrefix: String) {
    require(
        STAGE_PREFIX.matches(stagePrefix) &&
            files.size + charts.size <= MAX_ENTRIES &&
            files.values.toSet().size == files.size
    ) { "Invalid restore journal scope" }
    files.forEach { (target, source) ->
        PortableArchiveFormat.validateArtifactPath(target)
        require(isStagePath(source, stagePrefix) && !source.substringAfterLast('/').startsWith('.')) {
            "Invalid restore staging source"
        }
    }
    charts.forEach { (id, digest) ->
        PortableArchiveFormat.validateChartSessionId(id)
        require(SHA256.matches(digest)) { "Invalid restore chart checksum" }
    }
}

internal fun encodeJournalPlan(plan: RestorePlan): ByteArray = JsonObject().apply {
    addProperty("version", VERSION)
    addProperty("stagePrefix", plan.stagePrefix)
    val files = JsonObject()
    plan.files.forEach { (target, file) ->
        val value = ownershipJson(file.ownership).apply {
            addProperty("stagePath", file.stagePath)
        }
        files.add(target, value)
    }
    add("files", files)
    val charts = JsonObject()
    plan.charts.forEach { (id, chart) ->
        val value = ownershipJson(chart.ownership).apply {
            addProperty("sha256", chart.sha256)
        }
        charts.add(id, value)
    }
    add("charts", charts)
    val staging = JsonObject()
    plan.staging.forEach { (path, ownership) -> staging.add(path, ownershipJson(ownership)) }
    add("staging", staging)
}.toString().toByteArray(StandardCharsets.UTF_8)

private fun ownershipJson(ownership: FileOwnership): JsonObject = JsonObject().apply {
    addProperty("dev", ownership.device.toString())
    addProperty("ino", ownership.inode.toString())
}

private fun parseJournalFile(value: JsonElement): PlannedFile {
    require(value.isJsonObject && value.asJsonObject.keySet() == setOf("stagePath", "dev", "ino")) {
        "Invalid restore journal file"
    }
    val root = value.asJsonObject
    require(root.get("stagePath").isJsonPrimitive && root.getAsJsonPrimitive("stagePath").isString) {
        "Invalid restore journal staging source"
    }
    return PlannedFile(root.get("stagePath").asString, parseOwnership(root))
}

private fun parseJournalChart(value: JsonElement): PlannedChart {
    require(value.isJsonObject && value.asJsonObject.keySet() == setOf("sha256", "dev", "ino")) {
        "Invalid restore journal chart"
    }
    val root = value.asJsonObject
    require(root.get("sha256").isJsonPrimitive && root.getAsJsonPrimitive("sha256").isString) {
        "Invalid restore journal chart checksum"
    }
    return PlannedChart(root.get("sha256").asString, parseOwnership(root))
}

private fun parseOwnership(root: JsonObject): FileOwnership {
    val values = listOf("dev", "ino").map { name ->
        val value = root.get(name)
        require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isString && OWNERSHIP_NUMBER.matches(value.asString)) {
            "Invalid restore file ownership"
        }
        value.asString.toLong()
    }
    return FileOwnership(values[0], values[1])
}

internal fun isStagePath(path: String, prefix: String): Boolean =
    path.startsWith("$prefix/") && STAGE_FILE.matches(path.removePrefix("$prefix/"))

internal fun decodeRestorePlan(bytes: ByteArray): RestorePlan {
    val root = JsonParser.parseString(decodeArchiveUtf8(bytes)).asJsonObject
    require(
        root.keySet() == setOf("version", "stagePrefix", "files", "charts", "staging") &&
            root.get("version")?.isJsonPrimitive == true &&
            root.getAsJsonPrimitive("version").isNumber &&
            root.get("version").asString == VERSION.toString() &&
            root.get("stagePrefix")?.isJsonPrimitive == true &&
            root.getAsJsonPrimitive("stagePrefix").isString
    ) { "Invalid restore journal" }
    val files = root.getAsJsonObject("files").entrySet().associate { (target, value) ->
        target to parseJournalFile(value)
    }
    val charts = root.getAsJsonObject("charts").entrySet().associate { (id, value) ->
        id to parseJournalChart(value)
    }
    val staging = root.getAsJsonObject("staging").entrySet().associate { (path, value) ->
        require(value.isJsonObject && value.asJsonObject.keySet() == setOf("dev", "ino")) {
            "Invalid restore journal staging file"
        }
        path to parseOwnership(value.asJsonObject)
    }
    return RestorePlan(files, charts, staging, root.get("stagePrefix").asString).also(::validateRestorePlan)
}

internal fun validateRestorePlan(plan: RestorePlan) {
    validateRestoreScope(plan.files.mapValues { it.value.stagePath }, plan.charts.mapValues { it.value.sha256 }, plan.stagePrefix)
    require(
        plan.staging.size + plan.charts.size <= MAX_ENTRIES &&
            plan.staging.keys.all { isStagePath(it, plan.stagePrefix) } &&
            plan.files.values.all { plan.staging[it.stagePath] == it.ownership }
    ) { "Invalid restore journal scope" }
}

internal fun readJournalBytes(storage: EncryptedStorageService, userId: String): ByteArray {
    val output = ByteArrayOutputStream()
    val bounded = object : OutputStream() {
        override fun write(value: Int) {
            require(output.size() < MAX_RESTORE_JOURNAL_BYTES) { "Restore journal exceeds size limit" }
            output.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(length <= MAX_RESTORE_JOURNAL_BYTES - output.size()) { "Restore journal exceeds size limit" }
            output.write(bytes, offset, length)
        }
    }
    storage.copyEncryptedFile(userId, RESTORE_JOURNAL_PATH, bounded)
    return output.toByteArray()
}
