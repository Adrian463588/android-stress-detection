package com.stressdetector.mobile.domain.engine

import android.content.Context
import android.net.Uri
import com.google.gson.JsonParseException
import com.stressdetector.mobile.security.EncryptedStorageService
import com.stressdetector.mobile.security.SecureStorageException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** User-action-only review/export/delete boundary for finalized encrypted session artifacts. */
class VerbatimArtifactService(
    context: Context,
    private val encryptedStorage: EncryptedStorageService,
    private val locationResolver: VerbatimArtifactLocationResolver = CanonicalVerbatimArtifactLocationResolver()
) {
    private val cacheRoot = File(context.applicationContext.cacheDir, "verbatim_playback")

    fun prepareAudioForPlayback(userId: String, session: VerbatimSessionSummary): File {
        val audioPath = locationResolver.resolveAudio(session)
        val destination = File(cacheRoot, "${cacheKey(session.sessionId)}.wav")
        val temporary = File(cacheRoot, ".${destination.name}.tmp")
        destination.parentFile?.mkdirs()
        try {
            FileOutputStream(temporary).use { output ->
                encryptedStorage.copyEncryptedFile(userId, normalizeAudioPath(audioPath), output)
                output.fd.sync()
            }
            WavFileValidator.validate(temporary)
            if (destination.exists() && !destination.delete()) error("Playback cache could not be replaced.")
            check(temporary.renameTo(destination)) { "Playback cache could not be published." }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
        return destination
    }

    fun clearPlaybackCache(sessionId: String? = null) {
        if (sessionId == null) {
            cacheRoot.listFiles()?.filter(File::isFile)?.forEach { it.delete() }
            return
        }
        val prefix = cacheKey(sessionId)
        cacheRoot.listFiles()?.filter { it.isFile && it.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    fun exportSessionBundle(userId: String, folderPath: String, destination: Uri, context: Context) {
        requireSafeFolder(folderPath)
        val files = encryptedStorage.listEncryptedFiles(userId)
            .filter { it.relativePath.startsWith("$folderPath/") }
        require(files.isNotEmpty()) { "No finalized artifacts were found for this session." }
        context.contentResolver.openOutputStream(destination)?.use { output ->
            ZipOutputStream(output).use { zip ->
                files.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativePath.removePrefix("$folderPath/")))
                    encryptedStorage.readEncryptedFile(userId, file.relativePath).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: error("The selected export destination could not be opened.")
    }

    /** SAF export is explicit and intentionally plaintext at the user-selected destination. */
    fun exportTranscript(session: VerbatimSessionDetail, annotations: List<DomainAnnotation>, destination: Uri, context: Context) {
        val report = VerbatimTextReportRenderer().render(session, annotations)
        context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
            output.writer(StandardCharsets.UTF_8).use { it.write(report) }
        } ?: error("Tujuan ekspor TXT tidak dapat dibuka.")
    }

    /** One existing encrypted snapshot; no database migration or reconstructed acoustic evidence. */
    fun restoreProsody(userId: String, session: VerbatimSessionDetail): VerbatimSessionDetail {
        val folder = session.summary.artifactFolderPath
        if (folder.isBlank()) return session
        val result = try {
            requireSafeFolder(folder)
            encryptedStorage.readEncryptedFile(userId, "$folder/live_transcript.jsonl.enc").bufferedReader().use { input ->
                Result.success(VerbatimProsodyArchive.parse(session.summary.sessionId, input.lineSequence()))
            }
        } catch (error: SecureStorageException) {
            Result.failure(error)
        } catch (error: JsonParseException) {
            Result.failure(error)
        } catch (error: IllegalArgumentException) {
            Result.failure(error)
        } catch (error: IllegalStateException) {
            Result.failure(error)
        }
        val evidence = result.getOrElse { error ->
            session.transcript.associate { segment ->
                segment.id to VerbatimProsodyEvidence(unavailableReason = "Arsip openSMILE unavailable: ${error.message}")
            }
        }
        return session.copy(transcript = session.transcript.map { it.copy(prosody = evidence[it.id]) })
    }

    fun deleteSession(userId: String, folderPath: String): Boolean {
        requireSafeFolder(folderPath)
        val files = encryptedStorage.listEncryptedFiles(userId)
            .filter { it.relativePath.startsWith("$folderPath/") }
        val deleted = files.map { file -> encryptedStorage.deleteEncryptedFile(userId, file.relativePath) }
        return deleted.all { it }
    }

    fun deleteArtifact(userId: String, relativePath: String): Boolean =
        encryptedStorage.deleteEncryptedFile(userId, normalizeAudioPath(relativePath))

    /** Accepts the pre-history-migration path shape but never forwards it to storage. */
    private fun normalizeAudioPath(path: String): String {
        val canonical = path.substringAfterLast("/verbatim/", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.let { "verbatim/$it" }
            ?: path
        require(
            (canonical.startsWith("verbatim/") || canonical.startsWith("verbatim_sessions/")) &&
                !canonical.contains("..")
        ) {
            "Invalid encrypted artifact path."
        }
        return canonical
    }

    private fun requireSafeFolder(folderPath: String) {
        require(
            folderPath.startsWith("verbatim_sessions/") &&
                !folderPath.contains("..") &&
                !folderPath.endsWith("/") &&
                folderPath.substringAfter("verbatim_sessions/").isNotBlank()
        ) { "Invalid session artifact folder." }
    }

    private fun cacheKey(path: String): String = MessageDigest.getInstance("SHA-256")
        .digest(path.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

/** Validates the decrypted playback artifact before exposing it to MediaPlayer. */
internal object WavFileValidator {
    private const val PCM_FORMAT = 1
    private const val MIN_SAMPLE_RATE = 8_000
    private const val MAX_SAMPLE_RATE = 48_000

    fun validate(file: File) {
        require(file.isFile && file.length() > 44) { "Audio WAV kosong atau tidak tersedia." }
        FileInputStream(file).use { input ->
            val header = ByteArray(12)
            readFully(input, header)
            require(header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray())) { "Audio bukan file RIFF." }
            require(header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())) { "Audio bukan file WAV." }

            var formatFound = false
            var dataBytes = 0L
            while (true) {
                val chunkHeader = ByteArray(8)
                val first = input.read(chunkHeader, 0, 1)
                if (first < 0) break
                readFully(input, chunkHeader, 1, 7)
                val chunkSize = littleEndianUInt32(chunkHeader, 4)
                require(chunkSize <= input.available().toLong()) { "Audio WAV terpotong." }
                when (String(chunkHeader, 0, 4, Charsets.US_ASCII)) {
                    "fmt " -> {
                        require(chunkSize >= 16) { "Header format WAV tidak valid." }
                        val format = ByteArray(16)
                        readFully(input, format)
                        val audioFormat = littleEndianUInt16(format, 0)
                        val channels = littleEndianUInt16(format, 2)
                        val sampleRate = littleEndianUInt32(format, 4)
                        require(audioFormat == PCM_FORMAT) { "Format audio tidak didukung." }
                        require(channels in 1..2) { "Jumlah channel audio tidak didukung." }
                        require(sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) { "Sample rate audio tidak didukung." }
                        skipFully(input, chunkSize - 16)
                        formatFound = true
                    }
                    "data" -> {
                        dataBytes += chunkSize
                        skipFully(input, chunkSize)
                    }
                    else -> skipFully(input, chunkSize)
                }
                if (chunkSize % 2L != 0L) skipFully(input, 1)
            }
            require(formatFound) { "Header format WAV tidak ditemukan." }
            require(dataBytes > 0) { "Audio WAV tidak memiliki data." }
        }
    }

    private fun readFully(input: FileInputStream, target: ByteArray, offset: Int = 0, length: Int = target.size - offset) {
        var read = 0
        while (read < length) {
            val count = input.read(target, offset + read, length - read)
            require(count >= 0) { "Audio WAV terpotong." }
            read += count
        }
    }

    private fun skipFully(input: FileInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                require(input.read() >= 0) { "Audio WAV terpotong." }
                remaining--
            }
        }
    }

    private fun littleEndianUInt16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianUInt32(bytes: ByteArray, offset: Int): Long = (bytes[offset].toLong() and 0xff) or
        ((bytes[offset + 1].toLong() and 0xff) shl 8) or
        ((bytes[offset + 2].toLong() and 0xff) shl 16) or
        ((bytes[offset + 3].toLong() and 0xff) shl 24)
}
