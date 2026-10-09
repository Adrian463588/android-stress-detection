package com.stressdetector.mobile.lifecycle

import android.content.Context
import com.google.gson.JsonParser
import com.stressdetector.mobile.data.db.StressDatabase
import com.stressdetector.mobile.security.EncryptedStorageService
import com.stressdetector.mobile.security.PrivacyIdentity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class RestoreSummary(val rowsByTable: Map<String, Long>, val chartFiles: Int, val encryptedFiles: Int)

/**
 * Scoped ZIP payload for a parent PortableBackupCipher. This class never writes a portable
 * plaintext backup: callers must encrypt writeTo's output and authenticate restoreFrom's input.
 * Both methods block and belong on an IO thread. A failed write leaves an incomplete output that
 * the caller must discard. Restore requires empty scoped tables and never replaces existing files.
 */
class PortableUserDataArchive(
    context: Context,
    private val database: StressDatabase,
    private val encryptedStorage: EncryptedStorageService
) {
    private val appContext = context.applicationContext
    private val chartDirectory get() = File(appContext.filesDir, "history_chart_artifacts")
    private val userId = PrivacyIdentity.LOCAL_PROFILE_ID
    private val rows = PortableArchiveRows(encryptedStorage, userId)
    private val media = PortableArchiveMedia(encryptedStorage, userId, chartDirectory)
    private val journal = PortableRestoreJournal(appContext, encryptedStorage, userId)
    private val commits = PortableRestoreCommits()

    @Synchronized
    fun writeTo(output: OutputStream) {
        recoverInterruptedRestore()
        val sqlite = database.openHelper.readableDatabase
        val schema = rows.readSchema(sqlite)
        val graph = PortableArchiveGraph(schema)
        val entries = mutableListOf<ArchiveEntry>()
        val zip = ZipOutputStream(object : java.io.FilterOutputStream(output) {
            override fun close() = flush()
        })
        zip.use {
            database.runInTransaction {
                PortableArchiveFormat.TABLES.forEach { table ->
                    entries += writeEntry(zip, PortableArchiveFormat.tableEntry(table)) { sink ->
                        rows.writeTable(sqlite, table, schema.getValue(table), graph, sink)
                    }
                }
                graph.validateRelations()
            }

            graph.homeIds.sorted().forEach { sessionId ->
                PortableArchiveFormat.validateChartSessionId(sessionId)
                val chart = File(chartDirectory, "$sessionId.json")
                if (chart.isFile) {
                    val name = "charts/$sessionId.json"
                    entries += writeEntry(zip, name) { sink ->
                        chart.inputStream().use { it.copyTo(sink) }
                        null
                    }
                }
            }

            val available = encryptedStorage.listEncryptedFiles(userId).map { it.relativePath }.toSet()
            val paths = graph.referencedArtifactPaths(available)
            paths.sorted().forEach { path ->
                entries += writeEntry(zip, "files/$path") { sink ->
                    encryptedStorage.copyEncryptedFile(userId, path, sink)
                    null
                }
            }
            graph.validateFiles(entries.map(ArchiveEntry::name).toSet())
            val manifest = ArchiveManifest(
                PortableArchiveFormat.FORMAT_VERSION,
                sqlite.version,
                schema,
                entries
            )
            zip.putNextEntry(ZipEntry(PortableArchiveFormat.MANIFEST))
            val encoded = PortableArchiveFormat.manifestJson(manifest).toByteArray(StandardCharsets.UTF_8)
            require(encoded.size <= MAX_MANIFEST_BYTES) { "Archive manifest is too large" }
            zip.write(encoded)
            zip.closeEntry()
            zip.finish()
            zip.flush()
        }
    }

    @Synchronized
    @Suppress("TooGenericExceptionCaught") // Restore rollback must cover every failure, then rethrow.
    fun restoreFrom(input: InputStream): RestoreSummary {
        recoverInterruptedRestore()
        val stagePrefix = "portable_archive_staging/${UUID.randomUUID()}"
        val staged = linkedMapOf<String, StagedEntry>()
        val stagedPaths = mutableListOf<String>()
        val preparedCharts = mutableListOf<File>()
        var preserveStaging = false
        try {
            val manifest = stageAndReadManifest(input, stagePrefix, staged, stagedPaths)
            val sqlite = database.openHelper.writableDatabase
            val schema = rows.readSchema(sqlite)
            require(manifest.databaseVersion == sqlite.version && manifest.tables == schema) {
                "Archive database schema does not match this app"
            }
            val graph = validateStagedRows(schema, staged, manifest)
            validateStagedFiles(graph, staged)
            val chartSources = staged.filterKeys { it.startsWith("charts/") }
                .mapKeys { it.key.removePrefix("charts/").removeSuffix(".json") }.mapValues { it.value.stagePath }
            media.prepareCharts(chartSources, stagePrefix.substringAfterLast('/'), preparedCharts)
            database.runInTransaction {
                rows.requireEmpty(sqlite)
                requireEmptyTargets(graph, staged)
                journal.begin(
                    files = staged.filterKeys { it.startsWith("files/") }
                        .mapKeys { it.key.removePrefix("files/") }.mapValues { it.value.stagePath },
                    charts = staged.filterKeys { it.startsWith("charts/") }
                        .mapKeys { it.key.removePrefix("charts/").removeSuffix(".json") }.mapValues { it.value.sha256 },
                    stagePrefix = stagePrefix
                )
                preserveStaging = true
                promoteFiles(stagePrefix, staged)
                PortableArchiveFormat.TABLES.forEach { table ->
                    rows.restoreTable(
                        sqlite,
                        table,
                        schema.getValue(table),
                        staged.getValue(PortableArchiveFormat.tableEntry(table)).stagePath
                    )
                }
                commits.markCommitted(sqlite, stagePrefix)
            }
            recoverInterruptedRestore()
            preserveStaging = false
            return restoreSummary(manifest, staged.keys)
        } catch (error: Throwable) {
            if (preserveStaging) {
                runCatching { recoverInterruptedRestore() }
                    .onSuccess { preserveStaging = false }
                    .onFailure(error::addSuppressed)
            }
            throw error
        } finally {
            if (!preserveStaging) {
                preparedCharts.forEach(File::delete)
                stagedPaths.forEach { path -> runCatching { encryptedStorage.deleteEncryptedFile(userId, path) } }
            }
        }
    }

    @Synchronized
    fun recoverInterruptedRestore() {
        database.runInTransaction {
            val sqlite = database.openHelper.writableDatabase
            journal.recover { token -> commits.isCommitted(sqlite, token) }
            commits.clearRecovered(sqlite)
        }
        media.cleanupChartStaging()
        // No journal remains: abandoned encrypted staging files are not user records.
        encryptedStorage.listEncryptedFiles(userId)
            .filter { it.relativePath.startsWith("portable_archive_staging/") }
            .forEach { check(encryptedStorage.deleteEncryptedFile(userId, it.relativePath)) { "Archive staging cleanup failed" } }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, write: (OutputStream) -> Long?): ArchiveEntry {
        PortableArchiveFormat.validateEntryName(name)
        require(name != PortableArchiveFormat.MANIFEST)
        zip.putNextEntry(ZipEntry(name))
        val sink = DigestOutputStream(zip)
        val rows = write(sink)
        sink.flush()
        zip.closeEntry()
        return ArchiveEntry(name, sink.count, sink.sha256(), rows)
    }

    private fun stageAndReadManifest(
        input: InputStream,
        stagePrefix: String,
        staged: MutableMap<String, StagedEntry>,
        stagedPaths: MutableList<String>
    ): ArchiveManifest {
        val zip = ZipInputStream(object : FilterInputStream(input) {
            override fun close() = Unit
        })
        return zip.use { readZipEntries(it, stagePrefix, staged, stagedPaths) }
    }

    private fun readZipEntries(
        zip: ZipInputStream,
        stagePrefix: String,
        staged: MutableMap<String, StagedEntry>,
        stagedPaths: MutableList<String>
    ): ArchiveManifest {
        var manifestText: String? = null
        var totalBytes = 0L
        var entryCount = 0
        while (true) {
            val entry = zip.nextEntry ?: break
            entryCount++
            require(entryCount <= MAX_ENTRIES && !entry.isDirectory) { "Too many or invalid ZIP entries" }
            val name = entry.name
            PortableArchiveFormat.validateEntryName(name)
            require(manifestText == null && !staged.containsKey(name)) { "Duplicate or trailing ZIP entry" }
            if (name == PortableArchiveFormat.MANIFEST) {
                manifestText = decodeArchiveUtf8(readLimited(zip, MAX_MANIFEST_BYTES.toLong()))
            } else {
                val stagePath = "$stagePrefix/$entryCount.enc"
                stagedPaths += stagePath
                val limit = when {
                    name.startsWith("rows/") -> MAX_TABLE_BYTES
                    name.startsWith("charts/") -> MAX_CHART_BYTES
                    else -> MAX_FILE_BYTES
                }
                val digestInput = DigestInputStream(zip, limit) { size ->
                    totalBytes += size
                    require(totalBytes <= MAX_TOTAL_BYTES) { "Archive expanded size exceeds limit" }
                }
                encryptedStorage.writeEncryptedFile(userId, stagePath, digestInput)
                require(digestInput.exhausted) { "Incomplete ZIP entry" }
                staged[name] = StagedEntry(stagePath, digestInput.count, digestInput.sha256())
            }
            zip.closeEntry()
        }
        val manifest = PortableArchiveFormat.parseManifest(requireNotNull(manifestText) { "Missing final archive manifest" })
        require(manifest.entries.size == staged.size && manifest.entries.map(ArchiveEntry::name) == staged.keys.toList()) {
            "Archive entry list mismatch"
        }
        manifest.entries.forEach { expected ->
            val actual = staged.getValue(expected.name)
            require(expected.size == actual.size && expected.sha256 == actual.sha256) {
                "Archive entry checksum mismatch"
            }
        }
        return manifest
    }

    private fun validateStagedRows(
        schema: Map<String, List<ArchiveColumn>>,
        staged: Map<String, StagedEntry>,
        manifest: ArchiveManifest
    ): PortableArchiveGraph {
        val graph = PortableArchiveGraph(schema)
        PortableArchiveFormat.TABLES.forEach { table ->
            val name = PortableArchiveFormat.tableEntry(table)
            val entry = staged[name] ?: error("Missing scoped table")
            var count = 0L
            rows.readLines(entry.stagePath) { line ->
                val row = PortableArchiveFormat.decodeRow(line, schema.getValue(table))
                require(graph.include(table, row)) { "Archive contains unrelated $table row" }
                graph.record(table, row)
                count++
            }
            val expected = manifest.entries.first { it.name == name }.rows
            require(count == expected) { "Archive table row count mismatch" }
        }
        graph.validateRelations()
        return graph
    }

    private fun validateStagedFiles(graph: PortableArchiveGraph, staged: Map<String, StagedEntry>) {
        graph.validateFiles(staged.keys)
        staged.forEach { (name, entry) ->
            if (!name.startsWith("charts/")) return@forEach
            val sessionId = name.removePrefix("charts/").removeSuffix(".json")
            val bytes = ByteArrayOutputStream()
            encryptedStorage.copyEncryptedFile(userId, entry.stagePath, LimitedOutputStream(bytes, MAX_CHART_BYTES))
            val chart = JsonParser.parseString(decodeArchiveUtf8(bytes.toByteArray())).asJsonObject
            validatePortableHistoryChart(chart, sessionId)
        }
    }

    private fun promoteFiles(stagePrefix: String, staged: Map<String, StagedEntry>) {
        staged.forEach { (name, entry) ->
            if (name.startsWith("files/")) {
                val target = name.removePrefix("files/")
                media.publishEncrypted(entry.stagePath, target)
            } else if (name.startsWith("charts/")) {
                val sessionId = name.removePrefix("charts/").removeSuffix(".json")
                media.publishChart(sessionId, stagePrefix.substringAfterLast('/'))
            }
        }
    }

    private fun requireEmptyTargets(graph: PortableArchiveGraph, staged: Map<String, StagedEntry>) {
        val existing = encryptedStorage.listEncryptedFiles(userId).map { it.relativePath }.toSet()
        staged.keys.filter { it.startsWith("files/") }.forEach { name ->
            require(name.removePrefix("files/") !in existing) { "Encrypted artifact target already exists" }
        }
        staged.keys.filter { it.startsWith("charts/") }.forEach { name ->
            val id = name.removePrefix("charts/").removeSuffix(".json")
            require(id in graph.homeIds && !File(chartDirectory, "$id.json").exists()) {
                "History chart target already exists"
            }
        }
    }

    private data class StagedEntry(val stagePath: String, val size: Long, val sha256: String)

    private companion object {
        const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
        const val MAX_CHART_BYTES = 64L * 1024L * 1024L
        const val MAX_TABLE_BYTES = 1024L * 1024L * 1024L
        const val MAX_FILE_BYTES = 16L * 1024L * 1024L * 1024L
        const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L * 1024L
        const val MAX_ENTRIES = 100_000
    }
}

private fun restoreSummary(manifest: ArchiveManifest, names: Set<String>): RestoreSummary = RestoreSummary(
    rowsByTable = manifest.entries.filter { it.name.startsWith("rows/") }.associate {
        it.name.removePrefix("rows/").removeSuffix(".ndjson") to checkNotNull(it.rows)
    },
    chartFiles = names.count { it.startsWith("charts/") },
    encryptedFiles = names.count { it.startsWith("files/") }
)

private class DigestOutputStream(private val target: OutputStream) : OutputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
        private set

    override fun write(value: Int) {
        target.write(value)
        digest.update(value.toByte())
        count++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        target.write(bytes, offset, length)
        digest.update(bytes, offset, length)
        count += length
    }

    fun sha256(): String = digest.digest().joinToString("") { "%02x".format(it) }
}

private class DigestInputStream(source: InputStream, private val limit: Long, private val onRead: (Long) -> Unit) :
    FilterInputStream(source) {
    private val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
        private set
    var exhausted = false
        private set

    // Storage closes each entry's input. Only the archive owns the shared ZIP stream.
    override fun close() = Unit

    override fun read(): Int {
        val value = super.read()
        if (value < 0) exhausted = true else record(byteArrayOf(value.toByte()), 0, 1)
        return value
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val size = `in`.read(bytes, offset, length)
        if (size < 0) exhausted = true else record(bytes, offset, size)
        return size
    }

    private fun record(bytes: ByteArray, offset: Int, length: Int) {
        count += length
        require(count <= limit) { "ZIP entry exceeds size limit" }
        onRead(length.toLong())
        digest.update(bytes, offset, length)
    }

    fun sha256(): String = digest.digest().joinToString("") { "%02x".format(it) }
}

private class LimitedOutputStream(private val target: OutputStream, private val limit: Long) : OutputStream() {
    private var count = 0L
    override fun write(value: Int) {
        count++
        require(count <= limit) { "Entry exceeds size limit" }
        target.write(value)
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        count += length
        require(count <= limit) { "Entry exceeds size limit" }
        target.write(bytes, offset, length)
    }
}

private fun readLimited(input: InputStream, limit: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= limit) { "Archive manifest exceeds size limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
