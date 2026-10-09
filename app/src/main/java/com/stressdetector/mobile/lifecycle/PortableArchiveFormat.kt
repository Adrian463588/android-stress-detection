package com.stressdetector.mobile.lifecycle

import android.database.Cursor
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

internal data class ArchiveColumn(val name: String, val type: String, val notNull: Boolean, val defaultValue: String?, val primaryKey: Int)

internal data class ArchiveEntry(val name: String, val size: Long, val sha256: String, val rows: Long? = null)

internal data class ArchiveManifest(
    val formatVersion: Int,
    val databaseVersion: Int,
    val tables: Map<String, List<ArchiveColumn>>,
    val entries: List<ArchiveEntry>
)

/** The archive payload is plaintext only while passing through the caller's authenticated cipher. */
internal object PortableArchiveFormat {
    const val MANIFEST = "manifest.json"
    const val FORMAT_VERSION = 1
    private const val MAX_PATH_LENGTH = 1024
    private const val MAX_SEGMENT_LENGTH = 255
    private const val FIRST_PRINTABLE_CODE_POINT = 32
    val TABLES = listOf(
        "stress_sessions",
        "stress_session_reports",
        "stress_window_evaluations",
        "chat_sessions",
        "chat_messages",
        "verbatim_sessions",
        "transcript_segments",
        "psychology_codes",
        "ai_analyses",
        "live_sessions",
        "realtime_inference_records",
        "divergence_events",
        "annotations"
    )
    private val gson = Gson()
    private val sha256 = Regex("[0-9a-f]{64}")

    fun tableEntry(table: String): String {
        require(table in TABLES) { "Table is outside portable archive scope" }
        return "rows/$table.ndjson"
    }

    fun validateEntryName(name: String) {
        require(name.length <= MAX_PATH_LENGTH && name.isNotEmpty() && name.none { it == '\\' || it.code < FIRST_PRINTABLE_CODE_POINT }) {
            "Invalid archive path"
        }
        require(name.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.length <= MAX_SEGMENT_LENGTH }) {
            "Invalid archive path segment"
        }
        require(isScopedEntry(name)) { "Entry is outside portable archive scope" }
    }

    private fun isScopedEntry(name: String): Boolean = name == MANIFEST ||
        TABLES.any { tableEntry(it) == name } ||
        (name.startsWith("charts/") && name.endsWith(".json") && name.count { it == '/' } == 1) ||
        isScopedArtifact(name)

    private fun isScopedArtifact(name: String): Boolean =
        (name.startsWith("files/verbatim/") || name.startsWith("files/verbatim_sessions/")) &&
            name.endsWith(".enc") &&
            !name.contains(".__staging_")

    fun validateArtifactPath(path: String) {
        validateEntryName("files/$path")
    }

    fun validateChartSessionId(sessionId: String) {
        validateEntryName("charts/$sessionId.json")
        require(!sessionId.contains('/')) { "Invalid chart session ID" }
    }

    fun encodeRow(cursor: Cursor): String {
        val row = JsonArray()
        for (index in 0 until cursor.columnCount) {
            val cell = JsonArray()
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> cell.add("n")
                Cursor.FIELD_TYPE_INTEGER -> {
                    cell.add("i")
                    cell.add(cursor.getLong(index).toString())
                }
                Cursor.FIELD_TYPE_FLOAT -> {
                    val value = cursor.getDouble(index)
                    require(value.isFinite()) { "Non-finite database value" }
                    cell.add("r")
                    cell.add(value.toString())
                }
                Cursor.FIELD_TYPE_STRING -> {
                    cell.add("s")
                    cell.add(cursor.getString(index))
                }
                Cursor.FIELD_TYPE_BLOB -> {
                    cell.add("b")
                    cell.add(Base64.getEncoder().encodeToString(cursor.getBlob(index)))
                }
                else -> error("Unsupported SQLite value type")
            }
            row.add(cell)
        }
        return gson.toJson(row)
    }

    fun decodeRow(line: String, columns: List<ArchiveColumn>): List<Any?> {
        val row = JsonParser.parseString(line).asJsonArray
        require(row.size() == columns.size) { "Archive row column count mismatch" }
        return row.map { item ->
            val cell = item.asJsonArray
            require(cell.size() in 1..2) { "Invalid archive cell" }
            when (cell[0].asString) {
                "n" -> {
                    require(cell.size() == 1) { "Invalid null cell" }
                    null
                }
                "i" -> {
                    require(cell.size() == 2) { "Invalid integer cell" }
                    val raw = cell[1].asString
                    require(raw.matches(Regex("-?(0|[1-9][0-9]*)"))) { "Invalid integer value" }
                    raw.toLong()
                }
                "r" -> {
                    require(cell.size() == 2) { "Invalid real cell" }
                    cell[1].asString.toDouble().also { require(it.isFinite()) { "Non-finite real value" } }
                }
                "s" -> {
                    require(cell.size() == 2 && cell[1].isJsonPrimitive && cell[1].asJsonPrimitive.isString) {
                        "Invalid text cell"
                    }
                    cell[1].asString
                }
                "b" -> {
                    require(cell.size() == 2 && cell[1].isJsonPrimitive && cell[1].asJsonPrimitive.isString) {
                        "Invalid blob cell"
                    }
                    Base64.getDecoder().decode(cell[1].asString)
                }
                else -> error("Unknown archive cell type")
            }
        }
    }

    fun manifestJson(manifest: ArchiveManifest): String = gson.toJson(manifest)

    fun parseManifest(json: String): ArchiveManifest {
        val root = JsonParser.parseString(json).asJsonObject
        require(root.keySet() == setOf("formatVersion", "databaseVersion", "tables", "entries")) {
            "Invalid archive manifest fields"
        }
        val manifest = gson.fromJson(root, ArchiveManifest::class.java)
        require(manifest.formatVersion == FORMAT_VERSION && manifest.databaseVersion > 0) {
            "Unsupported archive version"
        }
        require(manifest.tables.keys == TABLES.toSet() && manifest.tables.values.all { it.isNotEmpty() }) {
            "Archive schema scope mismatch"
        }
        require(manifest.entries.distinctBy(ArchiveEntry::name).size == manifest.entries.size) {
            "Duplicate archive manifest entry"
        }
        manifest.entries.forEach { entry ->
            validateEntryName(entry.name)
            require(entry.name != MANIFEST && entry.size >= 0 && sha256.matches(entry.sha256)) {
                "Invalid archive entry metadata"
            }
            if (entry.name.startsWith("rows/")) {
                require(entry.rows != null && entry.rows >= 0) {
                    "Missing table row count"
                }
            } else {
                require(entry.rows == null) { "Unexpected row count" }
            }
        }
        require(TABLES.all { table -> manifest.entries.any { it.name == tableEntry(table) } }) {
            "Missing archive table"
        }
        return manifest
    }
}

internal fun decodeArchiveUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()
