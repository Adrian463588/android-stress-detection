@file:Suppress(
    "MagicNumber",
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "TooManyFunctions",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "LoopWithTooManyJumpStatements"
)

package com.stressdetector.mobile.domain.engine

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.stressdetector.mobile.data.db.AnnotationEntity
import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.data.db.TranscriptSegmentEntity
import com.stressdetector.mobile.data.db.VerbatimSessionEntity
import com.stressdetector.mobile.data.repository.sessionStatusForFinalization
import com.stressdetector.mobile.security.EncryptedStorageService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

sealed interface SessionPackageImportResult {
    data class Imported(
        val sessionId: String,
        val sessionName: String,
        val temperatureSampleCount: Int,
        val markerCount: Int,
        val legacyFormat: Boolean
    ) : SessionPackageImportResult

    data class DuplicateSession(val existingSessionId: String, val sessionName: String) : SessionPackageImportResult

    data class Rejected(val reason: String) : SessionPackageImportResult
}

internal data class PackageEntryDigest(val path: String, val sizeBytes: Long, val sha256: String)

internal data class SessionPackageManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val sessionId: String = "",
    val sessionName: String = "",
    val startedAtMillis: Long = 0L,
    val endedAtMillis: Long = 0L,
    val durationSeconds: Long = 0L,
    val audioDurationMs: Long = 0L,
    val watchWearerRole: String = WatchWearerRole.CLIENT.name,
    val languageCode: String = "id",
    val finalizationStatus: String = "COMPLETED",
    val summaryStatus: String = "NOT_REQUESTED",
    val audioGaps: List<AudioGap> = emptyList(),
    val hasSkinTemperature: Boolean = false,
    val algorithmVersion: String = ThermalPolicyConfig.DEFAULT_ALGORITHM_VERSION,
    val entries: List<PackageEntryDigest> = emptyList()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

/**
 * Portable, checksummed, atomic & idempotent ZIP session archive service.
 *
 * Exports complete multimodal counseling evidence (audio WAV, transcripts, markers,
 * annotations, AI summary, and raw/metadata skin temperature CSV/JSON) and restores
 * saved sessions cleanly via SAF without fabricating missing temperature or
 * re-running threshold detection automatically.
 */
class VerbatimSessionPackageService(
    context: Context,
    private val encryptedStorage: EncryptedStorageService,
    private val repository: VerbatimSessionRepository
) {
    private val appContext = context.applicationContext
    private val gson: Gson = GsonBuilder().serializeNulls().create()
    private val stagingRoot = File(appContext.cacheDir, "verbatim_package_import_staging")

    fun exportSessionPackage(
        userId: String,
        detail: VerbatimSessionDetail,
        annotations: List<DomainAnnotation>,
        aiSummaryText: String?,
        destination: Uri
    ) {
        val output = appContext.contentResolver.openOutputStream(destination)
            ?: error("Tujuan ekspor paket sesi tidak dapat dibuka.")
        output.use { out ->
            exportSessionPackageToStream(userId, detail, annotations, aiSummaryText, out)
        }
    }

    internal fun exportSessionPackageToStream(
        userId: String,
        detail: VerbatimSessionDetail,
        annotations: List<DomainAnnotation>,
        aiSummaryText: String?,
        output: java.io.OutputStream
    ) {
        val summary = detail.summary
        val digests = mutableListOf<PackageEntryDigest>()
        ZipOutputStream(output).use { zip ->
            if (summary.audioFilePath.isNotBlank()) {
                val audioBytes = runCatching {
                    val buf = ByteArrayOutputStream()
                    encryptedStorage.copyEncryptedFile(userId, normalizeAudioPath(summary.audioFilePath), buf)
                    buf.toByteArray()
                }.getOrNull()
                if (audioBytes != null && audioBytes.isNotEmpty()) {
                    digests += writeZipEntry(zip, ENTRY_AUDIO_WAV, audioBytes)
                }
            }

            val transcriptJsonl = detail.transcript.joinToString("\n") { seg ->
                gson.toJson(
                    mapOf(
                        "segmentId" to seg.id,
                        "speakerId" to seg.speakerId,
                        "startOffsetMs" to seg.startOffsetMs,
                        "endOffsetMs" to seg.endOffsetMs,
                        "rawText" to seg.rawText,
                        "editedText" to seg.editedText,
                        "confidence" to seg.confidence,
                        "language" to seg.language
                    )
                )
            }
            digests += writeZipEntry(zip, ENTRY_TRANSCRIPT_JSONL, transcriptJsonl.toByteArray(StandardCharsets.UTF_8))

            val markersJson = gson.toJson(detail.divergenceEvents.distinctBy { it.eventId })
            digests += writeZipEntry(zip, ENTRY_MARKERS_JSON, markersJson.toByteArray(StandardCharsets.UTF_8))

            val annotationsJson = gson.toJson(annotations.distinctBy { it.id })
            digests += writeZipEntry(zip, ENTRY_ANNOTATIONS_JSON, annotationsJson.toByteArray(StandardCharsets.UTF_8))

            if (!aiSummaryText.isNullOrBlank()) {
                digests += writeZipEntry(zip, ENTRY_AI_SUMMARY, aiSummaryText.toByteArray(StandardCharsets.UTF_8))
            }

            val hasTemp = detail.skinTemperatureSamples.isNotEmpty()
            if (hasTemp) {
                val tempCsv = VerbatimResearchExporter.buildSkinTemperatureCsv(detail.skinTemperatureSamples)
                val tempMeta = VerbatimResearchExporter.buildThermalMetadataJson(
                    sessionId = summary.sessionId,
                    observations = detail.skinTemperatureSamples
                )
                digests += writeZipEntry(zip, ENTRY_SKIN_TEMP_CSV, tempCsv.toByteArray(StandardCharsets.UTF_8))
                digests += writeZipEntry(zip, ENTRY_THERMAL_METADATA, tempMeta.toByteArray(StandardCharsets.UTF_8))
            }

            val manifest = SessionPackageManifest(
                schemaVersion = SessionPackageManifest.CURRENT_SCHEMA_VERSION,
                sessionId = summary.sessionId,
                sessionName = summary.sessionName,
                startedAtMillis = summary.startedAtMillis,
                endedAtMillis = summary.endedAtMillis,
                durationSeconds = summary.durationSeconds,
                audioDurationMs = summary.audioDurationMs,
                watchWearerRole = summary.watchWearerRole,
                languageCode = summary.languageCode,
                finalizationStatus = summary.finalizationStatus,
                summaryStatus = summary.summaryStatus,
                audioGaps = summary.audioGaps,
                hasSkinTemperature = hasTemp,
                entries = digests
            )
            val manifestBytes = gson.toJson(manifest).toByteArray(StandardCharsets.UTF_8)
            zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
            zip.write(manifestBytes)
            zip.closeEntry()
        }
    }

    suspend fun importSessionPackage(userId: String, source: Uri): SessionPackageImportResult {
        val stream = appContext.contentResolver.openInputStream(source)
            ?: return SessionPackageImportResult.Rejected("Berkas arsip sesi tidak dapat dibuka.")
        return stream.use { importSessionPackageFromStream(userId, it) }
    }

    internal suspend fun importSessionPackageFromStream(userId: String, input: InputStream): SessionPackageImportResult {
        val tempDir = File(stagingRoot, "pkg_${UUID.randomUUID()}")
        val stagedEncryptedPaths = mutableListOf<String>()
        try {
            tempDir.mkdirs()
            val extractedFiles = extractValidatedZip(input, tempDir)
            val manifestFile = extractedFiles[ENTRY_MANIFEST]
                ?: return if (extractedFiles.keys.any { it.endsWith("skin_temperature.csv") }) {
                    SessionPackageImportResult.Rejected(
                        "File suhu mandiri tanpa manifest sesi tidak dapat diimpor otomatis."
                    )
                } else {
                    SessionPackageImportResult.Rejected("Arsip sesi tidak memiliki manifest.json yang valid.")
                }

            val manifest = runCatching {
                gson.fromJson(manifestFile.readText(StandardCharsets.UTF_8), SessionPackageManifest::class.java)
            }.getOrNull() ?: return SessionPackageImportResult.Rejected("Format manifest.json tidak valid.")

            if (manifest.schemaVersion !in 1..SessionPackageManifest.CURRENT_SCHEMA_VERSION) {
                return SessionPackageImportResult.Rejected(
                    "Versi skema paket sesi tidak didukung (${manifest.schemaVersion})."
                )
            }
            val safeSessionId = manifest.sessionId.trim()
            if (!safeSessionId.matches(SAFE_ID_REGEX)) {
                return SessionPackageImportResult.Rejected("Identitas sesi pada arsip tidak valid.")
            }
            if (repository.hasSession(safeSessionId)) {
                return SessionPackageImportResult.DuplicateSession(safeSessionId, manifest.sessionName.ifBlank { safeSessionId })
            }

            // Verify checksums and exact sizes for every listed manifest entry.
            manifest.entries.forEach { expected ->
                val targetFile = extractedFiles[expected.path]
                    ?: return SessionPackageImportResult.Rejected("Artefak yang terdaftar hilang: ${expected.path}")
                val actualBytes = targetFile.length()
                val actualDigest = sha256File(targetFile)
                if (actualBytes != expected.sizeBytes || !actualDigest.equals(expected.sha256, ignoreCase = true)) {
                    return SessionPackageImportResult.Rejected(
                        "Pemeriksaan checksum gagal untuk ${expected.path}."
                    )
                }
            }

            val folder = "verbatim_sessions/${safeFolderSegment(manifest.sessionName, manifest.startedAtMillis, safeSessionId)}"
            var persistedAudioPath = ""
            extractedFiles[ENTRY_AUDIO_WAV]?.let { wavFile ->
                WavFileValidator.validate(wavFile)
                val targetAudio = "$folder/audio.wav.enc"
                FileInputStream(wavFile).use { wavIn ->
                    encryptedStorage.writeEncryptedFile(userId, targetAudio, wavIn)
                }
                stagedEncryptedPaths += targetAudio
                persistedAudioPath = targetAudio
            }

            val transcriptFile = extractedFiles[ENTRY_TRANSCRIPT_JSONL]
            val parsedTranscripts = transcriptFile?.let { parseTranscripts(safeSessionId, it) }.orEmpty()
            val markersFile = extractedFiles[ENTRY_MARKERS_JSON]
            val parsedMarkers = markersFile?.let { parseMarkers(safeSessionId, it) }.orEmpty()
            val annotationsFile = extractedFiles[ENTRY_ANNOTATIONS_JSON]
            val parsedAnnotations = annotationsFile?.let { parseAnnotations(safeSessionId, it) }.orEmpty()
            val aiSummaryText = extractedFiles[ENTRY_AI_SUMMARY]?.readText(StandardCharsets.UTF_8)?.trim()
                ?.takeIf { it.isNotBlank() }

            val tempFile = extractedFiles[ENTRY_SKIN_TEMP_CSV]
            val tempObservations = if (tempFile != null) {
                parseTemperatureCsv(safeSessionId, manifest.watchWearerRole, tempFile)
            } else {
                emptyList()
            }

            extractedFiles[ENTRY_TRANSCRIPT_JSONL]?.let { file ->
                val enc = "$folder/live_transcript.jsonl.enc"
                FileInputStream(file).use { encryptedStorage.writeEncryptedFile(userId, enc, it) }
                stagedEncryptedPaths += enc
            }
            extractedFiles[ENTRY_MARKERS_JSON]?.let { file ->
                val enc = "$folder/divergence_events.jsonl.enc"
                FileInputStream(file).use { encryptedStorage.writeEncryptedFile(userId, enc, it) }
                stagedEncryptedPaths += enc
            }
            aiSummaryText?.let { text ->
                val enc = "$folder/ai_summary.txt.enc"
                encryptedStorage.writeEncryptedFile(userId, enc, text.byteInputStream(StandardCharsets.UTF_8))
                stagedEncryptedPaths += enc
            }
            if (tempObservations.isNotEmpty()) {
                extractedFiles[ENTRY_SKIN_TEMP_CSV]?.let { file ->
                    val enc = "$folder/skin_temperature.csv.enc"
                    FileInputStream(file).use { encryptedStorage.writeEncryptedFile(userId, enc, it) }
                    stagedEncryptedPaths += enc
                }
                extractedFiles[ENTRY_THERMAL_METADATA]?.let { file ->
                    val enc = "$folder/thermal_metadata.json.enc"
                    FileInputStream(file).use { encryptedStorage.writeEncryptedFile(userId, enc, it) }
                    stagedEncryptedPaths += enc
                }
            }

            val sessionEntity = VerbatimSessionEntity(
                sessionId = safeSessionId,
                startedAtMillis = manifest.startedAtMillis,
                endedAtMillis = manifest.endedAtMillis,
                audioFilePath = persistedAudioPath,
                consentGranted = true,
                durationSeconds = manifest.durationSeconds.coerceAtLeast(0L),
                status = sessionStatusForFinalization(manifest.finalizationStatus),
                sessionName = manifest.sessionName.ifBlank { "Sesi Impor" },
                artifactFolderPath = folder,
                finalizationStatus = manifest.finalizationStatus.ifBlank { "COMPLETED" },
                summaryStatus = manifest.summaryStatus.ifBlank { "NOT_REQUESTED" },
                audioGapsJson = AudioGapCodec.encode(manifest.audioGaps),
                watchWearerRole = manifest.watchWearerRole.ifBlank { WatchWearerRole.CLIENT.name },
                audioDurationMs = manifest.audioDurationMs.coerceAtLeast(0L),
                languageCode = manifest.languageCode.ifBlank { "id" }
            )

            val dbResult = repository.importSessionAtomically(
                VerbatimImportedSessionRecord(
                    session = sessionEntity,
                    segments = parsedTranscripts,
                    events = parsedMarkers,
                    annotations = parsedAnnotations,
                    temperatureSamples = tempObservations,
                    aiSummaryText = aiSummaryText
                )
            )
            if (dbResult.isFailure) {
                cleanupEncrypted(userId, stagedEncryptedPaths)
                return SessionPackageImportResult.Rejected(
                    dbResult.exceptionOrNull()?.message ?: "Penyimpanan impor ke database gagal."
                )
            }
            if (dbResult.getOrNull() != true) {
                cleanupEncrypted(userId, stagedEncryptedPaths)
                return SessionPackageImportResult.DuplicateSession(safeSessionId, sessionEntity.sessionName)
            }

            return SessionPackageImportResult.Imported(
                sessionId = safeSessionId,
                sessionName = sessionEntity.sessionName,
                temperatureSampleCount = tempObservations.size,
                markerCount = parsedMarkers.size,
                legacyFormat = tempFile == null
            )
        } catch (error: Throwable) {
            cleanupEncrypted(userId, stagedEncryptedPaths)
            return SessionPackageImportResult.Rejected(error.message ?: "Arsip ZIP tidak valid atau rusak.")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun extractValidatedZip(input: InputStream, targetRoot: File): Map<String, File> {
        val result = linkedMapOf<String, File>()
        var entryCount = 0
        var totalBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val canonicalRoot = targetRoot.canonicalFile
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                entryCount += 1
                require(entryCount <= MAX_ZIP_ENTRIES) { "Jumlah file dalam arsip melebihi batas." }
                val cleanName = validateZipEntryPath(entry.name)
                val dest = File(canonicalRoot, cleanName).canonicalFile
                require(dest.path.startsWith(canonicalRoot.path + File.separator)) {
                    "Path traversal terdeteksi pada arsip: $cleanName"
                }
                dest.parentFile?.mkdirs()
                var entryBytes = 0L
                FileOutputStream(dest).use { out ->
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        entryBytes += read
                        totalBytes += read
                        require(entryBytes <= MAX_ENTRY_BYTES) { "Ukuran file terlalu besar dalam arsip: $cleanName" }
                        require(totalBytes <= MAX_TOTAL_BYTES) { "Total ukuran arsip ZIP melebihi batas aman." }
                        out.write(buffer, 0, read)
                    }
                }
                result[cleanName] = dest
                zip.closeEntry()
            }
        }
        return result
    }

    private fun parseTranscripts(sessionId: String, file: File): List<TranscriptSegmentEntity> {
        val lines = file.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
        return lines.mapNotNull { line ->
            val obj = runCatching { gson.fromJson(line, Map::class.java) }.getOrNull() ?: return@mapNotNull null
            val segId = obj["segmentId"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rawText = obj["rawText"]?.toString() ?: return@mapNotNull null
            val editedText = obj["editedText"]?.toString()
            TranscriptSegmentEntity(
                segmentId = segId,
                sessionId = sessionId,
                speakerId = obj["speakerId"]?.toString().orEmpty().ifBlank { "Speaker_0" },
                startOffsetMs = (obj["startOffsetMs"] as? Number)?.toLong() ?: 0L,
                endOffsetMs = (obj["endOffsetMs"] as? Number)?.toLong() ?: 0L,
                rawText = rawText,
                cleanedText = editedText ?: rawText,
                confidence = (obj["confidence"] as? Number)?.toFloat() ?: 0.8f,
                language = obj["language"]?.toString().orEmpty().ifBlank { "id" }
            )
        }
    }

    private fun parseMarkers(sessionId: String, file: File): List<DivergenceEventEntity> {
        val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, DivergenceEventEntity::class.java).type
        val raw: List<DivergenceEventEntity> = runCatching {
            gson.fromJson<List<DivergenceEventEntity>>(file.readText(StandardCharsets.UTF_8), type)
        }.getOrNull().orEmpty()
        return raw.map { it.copy(sessionId = sessionId) }
    }

    private fun parseAnnotations(sessionId: String, file: File): List<AnnotationEntity> {
        val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, DomainAnnotation::class.java).type
        val raw: List<DomainAnnotation> = runCatching {
            gson.fromJson<List<DomainAnnotation>>(file.readText(StandardCharsets.UTF_8), type)
        }.getOrNull().orEmpty()
        return raw.map {
            AnnotationEntity(
                annotationId = it.id,
                sessionId = sessionId,
                timestampMs = it.timestampMs,
                text = it.text,
                author = it.author,
                citationId = it.citationId
            )
        }
    }

    private fun parseTemperatureCsv(sessionId: String, defaultWearerRole: String, file: File): List<SkinTemperatureObservation> {
        val lines = file.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
        if (lines.size <= 1) return emptyList()
        val header = lines.first().split(",").map { it.trim().removeSurrounding("\"") }
        val idxElapsed = header.indexOf("elapsed_ms")
        val idxSensorTs = header.indexOf("sensor_timestamp_ms")
        val idxRecvTs = header.indexOf("received_timestamp_ms")
        val idxSeq = header.indexOf("sequence")
        val idxSkin = header.indexOf("skin_c")
        val idxAmbient = header.indexOf("ambient_c")
        val idxStatus = header.indexOf("status")
        val idxValid = header.indexOf("valid")
        val idxRole = header.indexOf("wearer_role")
        val idxSource = header.indexOf("source")
        val idxUnc = header.indexOf("clock_uncertainty_ms")
        val idxPaused = header.indexOf("is_paused")
        require(idxElapsed >= 0 && idxSkin >= 0) { "Header skin_temperature.csv tidak valid." }

        return lines.drop(1).mapIndexedNotNull { rowIndex, row ->
            val cols = parseCsvRow(row)
            val elapsed = cols.getOrNull(idxElapsed)?.toLongOrNull() ?: return@mapIndexedNotNull null
            val seq = cols.getOrNull(idxSeq)?.toLongOrNull() ?: (rowIndex + 1L)
            val skin = cols.getOrNull(idxSkin)?.toFloatOrNull()?.takeIf { it.isFinite() }
            val ambient = cols.getOrNull(idxAmbient)?.toFloatOrNull()?.takeIf { it.isFinite() }
            val status = cols.getOrNull(idxStatus)?.toIntOrNull() ?: 0
            val isValid = cols.getOrNull(idxValid)?.toBooleanStrictOrNull() ?: (skin != null && status == 0)
            val role = cols.getOrNull(idxRole)?.takeIf { it.isNotBlank() } ?: defaultWearerRole
            val source = cols.getOrNull(idxSource)?.takeIf { it.isNotBlank() } ?: "WATCH_SDK_SKIN_TEMP"
            SkinTemperatureObservation(
                sampleId = "$sessionId:temp:import:$seq:$rowIndex",
                sessionId = sessionId,
                generation = 1L,
                sequenceNumber = seq,
                sensorTimestampMs = cols.getOrNull(idxSensorTs)?.toLongOrNull() ?: elapsed,
                receivedTimestampMs = cols.getOrNull(idxRecvTs)?.toLongOrNull() ?: elapsed,
                elapsedMillis = elapsed,
                clockUncertaintyMs = cols.getOrNull(idxUnc)?.toLongOrNull(),
                skinTemperatureC = skin,
                ambientTemperatureC = ambient,
                measurementStatus = status,
                isValid = isValid,
                wearerRole = role,
                source = source,
                isPaused = cols.getOrNull(idxPaused)?.toBooleanStrictOrNull() ?: false
            )
        }
    }

    private fun writeZipEntry(zip: ZipOutputStream, entryName: String, bytes: ByteArray): PackageEntryDigest {
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(bytes)
        zip.closeEntry()
        return PackageEntryDigest(
            path = entryName,
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256Bytes(bytes)
        )
    }

    private fun validateZipEntryPath(name: String): String {
        require(name.isNotBlank() && !name.startsWith("/") && !name.contains('\\')) {
            "Path entri ZIP tidak aman: $name"
        }
        val segments = name.split('/')
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Path traversal terdeteksi pada entri ZIP: $name"
        }
        return name
    }

    private fun normalizeAudioPath(path: String): String {
        val canonical = path.substringAfterLast("/verbatim/", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.let { "verbatim/$it" }
            ?: path
        require(canonical.startsWith("verbatim/") || canonical.startsWith("verbatim_sessions/")) {
            "Invalid encrypted artifact path."
        }
        return canonical
    }

    private fun safeFolderSegment(name: String, timestamp: Long, sessionId: String): String {
        val cleaned = name.trim().replace(Regex("[^A-Za-z0-9._ -]+"), "_").replace("..", "_").take(40).ifBlank { "Imported" }
        return "${cleaned}_${timestamp}_${sessionId.take(8)}"
    }

    private fun cleanupEncrypted(userId: String, paths: List<String>) {
        paths.forEach { runCatching { encryptedStorage.deleteEncryptedFile(userId, it) } }
    }

    private fun parseCsvRow(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    out += cur.toString().trim()
                    cur.clear()
                }
                else -> cur.append(ch)
            }
            i++
        }
        out += cur.toString().trim()
        return out
    }

    private fun sha256Bytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val ENTRY_MANIFEST = "manifest.json"
        const val ENTRY_AUDIO_WAV = "audio.wav"
        const val ENTRY_TRANSCRIPT_JSONL = "transcript.jsonl"
        const val ENTRY_MARKERS_JSON = "markers.json"
        const val ENTRY_ANNOTATIONS_JSON = "annotations.json"
        const val ENTRY_AI_SUMMARY = "ai_summary.txt"
        const val ENTRY_SKIN_TEMP_CSV = "signals/skin_temperature.csv"
        const val ENTRY_THERMAL_METADATA = "signals/thermal_metadata.json"
        private const val MAX_ZIP_ENTRIES = 64
        private const val MAX_ENTRY_BYTES = 128L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 256L * 1024L * 1024L
        private val SAFE_ID_REGEX = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
