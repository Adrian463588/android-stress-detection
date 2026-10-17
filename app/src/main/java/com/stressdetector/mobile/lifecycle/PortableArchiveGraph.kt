package com.stressdetector.mobile.lifecycle

import com.google.gson.Gson
import com.stressdetector.mobile.domain.engine.isAudioSignalTraceFile

/** Bounded in-memory identity index; clinical text and waveform bytes stay in streaming entries. */
internal class PortableArchiveGraph(private val schema: Map<String, List<ArchiveColumn>>) {
    val homeIds = linkedSetOf<String>()
    private val chatIds = linkedSetOf<String>()
    private val verbatimIds = linkedSetOf<String>()
    private val segmentIds = linkedSetOf<String>()
    private val references = mutableMapOf<String, MutableSet<String>>()
    private val primaryKeys = mutableMapOf<String, MutableSet<String>>()
    private val artifactFolders = linkedSetOf<String>()
    private val audioPaths = linkedSetOf<String>()
    private val gson = Gson()

    fun include(table: String, row: List<Any?>): Boolean = when (table) {
        "transcript_segments", "ai_analyses", "live_sessions", "realtime_inference_records",
        "divergence_events", "annotations" -> value(table, row, "sessionId") in verbatimIds
        "psychology_codes" -> value(table, row, "segmentId") in segmentIds
        else -> true
    }

    fun record(table: String, row: List<Any?>) {
        val columns = schema.getValue(table)
        require(row.size == columns.size) { "Archive row schema mismatch" }
        val key = columns.withIndex().filter { it.value.primaryKey > 0 }
            .sortedBy { it.value.primaryKey }.map { row[it.index] }
        require(key.isNotEmpty() && key.none { it == null }) { "Missing archive row primary key" }
        require(primaryKeys.getOrPut(table) { linkedSetOf() }.add(gson.toJson(key))) { "Duplicate archive row primary key" }
        when (table) {
            "stress_sessions" -> {
                val id = id(table, row, "sessionId")
                PortableArchiveFormat.validateChartSessionId(id)
                homeIds += id
            }
            "stress_session_reports", "stress_window_evaluations" -> reference("home", id(table, row, "sessionId"))
            "chat_sessions" -> chatIds += id(table, row, "chatSessionId")
            "chat_messages" -> reference("chat", id(table, row, "chatSessionId"))
            "verbatim_sessions" -> recordVerbatim(table, row)
            "transcript_segments" -> {
                segmentIds += id(table, row, "segmentId")
                reference("verbatim", id(table, row, "sessionId"))
            }
            "psychology_codes" -> reference("segment", id(table, row, "segmentId"))
            "ai_analyses", "live_sessions", "realtime_inference_records", "divergence_events", "annotations" ->
                reference("verbatim", id(table, row, "sessionId"))
        }
    }

    private fun recordVerbatim(table: String, row: List<Any?>) {
        verbatimIds += id(table, row, "sessionId")
        require(value(table, row, "status") != "RECORDING" && value(table, row, "finalizationStatus") != "PERSISTING") {
            "Active Verbatim session cannot be archived"
        }
        val folder = value(table, row, "artifactFolderPath") as? String ?: ""
        val audio = value(table, row, "audioFilePath") as? String ?: ""
        if (folder.isNotEmpty()) {
            if (folder.endsWith(".enc")) {
                PortableArchiveFormat.validateArtifactPath(folder)
            } else {
                require(folder.startsWith("verbatim_sessions/")) { "Invalid Verbatim artifact folder" }
                PortableArchiveFormat.validateArtifactPath("$folder/audio.wav.enc")
            }
            artifactFolders += folder
        }
        if (audio.isNotEmpty()) {
            PortableArchiveFormat.validateArtifactPath(audio)
            audioPaths += audio
        }
    }

    fun validateRelations() {
        require(references["home"].orEmpty().all { it in homeIds }) { "Orphan Home evidence" }
        require(references["chat"].orEmpty().all { it in chatIds }) { "Orphan AI+ message" }
        require(references["verbatim"].orEmpty().all { it in verbatimIds }) { "Orphan Verbatim evidence" }
        require(references["segment"].orEmpty().all { it in segmentIds }) { "Orphan psychology code" }
    }

    fun referencedArtifactPaths(available: Set<String>): Set<String> {
        val selected = linkedSetOf<String>()
        artifactFolders.forEach { folder ->
            if (folder.endsWith(".enc")) {
                require(folder in available) { "Legacy Verbatim artifact is missing" }
                selected += folder
            } else {
                val inFolder = available.filter { it.startsWith("$folder/") }
                require(inFolder.isNotEmpty()) { "Verbatim artifact folder is missing" }
                inFolder.forEach { path ->
                    PortableArchiveFormat.validateArtifactPath(path)
                    val relative = path.removePrefix("$folder/")
                    require(
                        relative in CORE_FILES ||
                            relative == "artifact_warnings.json.enc" ||
                            relative == "ppg_manifest.json.enc" ||
                            relative.startsWith("audio_signal/") &&
                            isAudioSignalTraceFile(relative.removePrefix("audio_signal/")) ||
                            relative.matches(Regex("ppg_raw/[A-Za-z0-9_-]+_[0-9]{6}\\.jsonl\\.enc"))
                    ) {
                        "Unexpected Verbatim artifact file"
                    }
                }
                require(CORE_FILES.all { "$folder/$it" in inFolder }) { "Incomplete Verbatim artifact folder" }
                selected += inFolder
            }
        }
        audioPaths.forEach { path ->
            require(path in available) { "Referenced Verbatim audio is missing" }
            selected += path
        }
        // Failed-with-evidence sessions may retain their original capture folder.
        verbatimIds.filter { it.matches(Regex("[A-Za-z0-9_-]+")) }.forEach { sessionId ->
            val prefix = "verbatim/$sessionId/audio_signal/"
            available.filter { it.startsWith(prefix) }.forEach { path ->
                PortableArchiveFormat.validateArtifactPath(path)
                require(isAudioSignalTraceFile(path.removePrefix(prefix))) { "Unexpected captured audio signal file" }
                selected += path
            }
        }
        return selected
    }

    fun validateFiles(names: Collection<String>) {
        val chartNames = names.filter { it.startsWith("charts/") }
        chartNames.forEach { name ->
            val sessionId = name.removePrefix("charts/").removeSuffix(".json")
            require(sessionId in homeIds) { "Orphan history chart" }
        }
        val filePaths = names.filter { it.startsWith("files/") }.map { it.removePrefix("files/") }.toSet()
        require(referencedArtifactPaths(filePaths) == filePaths) { "Unreferenced encrypted file in archive" }
    }

    private fun id(table: String, row: List<Any?>, column: String): String =
        (value(table, row, column) as? String)?.takeIf(String::isNotBlank)
            ?: error("Invalid archive identity: $table.$column")

    private fun value(table: String, row: List<Any?>, column: String): Any? {
        val index = schema.getValue(table).indexOfFirst { it.name == column }
        require(index >= 0) { "Missing archive column: $table.$column" }
        return row[index]
    }

    private fun reference(kind: String, id: String) {
        references.getOrPut(kind) { linkedSetOf() } += id
    }

    private companion object {
        val CORE_FILES = setOf(
            "audio.wav.enc",
            "live_transcript.jsonl.enc",
            "divergence_events.jsonl.enc",
            "ai_summary.txt.enc"
        )
    }
}
