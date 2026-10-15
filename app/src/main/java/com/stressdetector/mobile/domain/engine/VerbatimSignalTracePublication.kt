package com.stressdetector.mobile.domain.engine

import com.google.gson.Gson
import com.stressdetector.mobile.security.EncryptedStorageService
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException

internal data class AudioSignalPublication(val sourceFiles: List<String> = emptyList(), val warnings: List<String> = emptyList())

/** Optional analysis never participates in rollback of the committed WAV/transcript/decoder evidence. */
@Suppress("TooGenericExceptionCaught") // Optional post-commit I/O reports concrete warnings, never rolls back core evidence.
internal class VerbatimSignalTracePublication(private val storage: EncryptedStorageService, private val gson: Gson = Gson()) {
    fun publish(userId: String, audio: RecordedAudio, folder: String, originMs: Long?): AudioSignalPublication {
        if (audio.signalTraceRoot == null && originMs == null) return AudioSignalPublication()
        val target = "$folder/audio_signal"
        val written = mutableListOf<String>()
        val duration = AudioTimeline(audio.audioGaps).sessionDurationForAudio(audio.durationMs)
        return try {
            val source = requireNotNull(audio.signalTraceRoot) { "Capture tidak menghasilkan jejak waveform/pitch." }
            require(source == "verbatim/${audio.sessionId}/audio_signal") { "Lokasi jejak tidak sesuai sesi." }
            val trace = VerbatimAudioSignalTrace(storage)
            val manifest = trace.readManifest(userId, source, audio.sessionId)
            require(originMs == null || manifest.audioStartSessionElapsedMs == null || manifest.audioStartSessionElapsedMs == originMs)
            val files = trace.files(userId, source, audio.sessionId)
            files.filterNot { it.endsWith("/manifest.json.enc") }.forEach { path ->
                val destination = "$target/${path.removePrefix("$source/")}"
                storage.readEncryptedFile(userId, path).use { storage.writeEncryptedFile(userId, destination, it) }
                written += destination
            }
            writeJson(
                userId,
                "$target/manifest.json.enc",
                manifest.copy(
                    audioStartSessionElapsedMs = originMs ?: manifest.audioStartSessionElapsedMs,
                    captureDurationMs = maxOf(duration, manifest.chunks.lastOrNull()?.endOffsetMs ?: 0L)
                )
            )
            AudioSignalPublication(files, traceWarnings(manifest))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val rollbackWarnings = pendingArtifactCleanup(storage, userId, written)
            val warning = "Jejak audio unavailable; WAV dan bukti decoder disimpan. ${error.message.orEmpty()}"
            val placeholder = AudioSignalTraceManifest(
                audio.sessionId,
                reason = warning,
                audioStartSessionElapsedMs = originMs,
                captureDurationMs = duration
            )
            val recorded = runCatching { writeJson(userId, "$target/manifest.json.enc", placeholder) }
            AudioSignalPublication(
                warnings =
                listOf(warning) + rollbackWarnings +
                    if (recorded.isFailure) listOf("Manifest unavailable gagal disimpan: $target") else emptyList()
            )
        }
    }

    /** Durable local warning evidence; failure cannot undo a successful core publication. */
    fun recordWarnings(userId: String, folder: String, warnings: List<String>): List<String> {
        if (warnings.isEmpty()) return warnings
        val path = "$folder/artifact_warnings.json.enc"
        return try {
            writeJson(userId, path, mapOf("warnings" to warnings))
            warnings
        } catch (error: Exception) {
            warnings + "Catatan cleanup gagal disimpan: $path (${error.message})"
        }
    }

    private fun writeJson(userId: String, path: String, value: Any) {
        storage.writeEncryptedFile(userId, path, ByteArrayInputStream(gson.toJson(value).toByteArray(Charsets.UTF_8)))
    }

    private fun traceWarnings(manifest: AudioSignalTraceManifest): List<String> =
        if (manifest.complete) emptyList() else listOf(manifest.reason ?: "Jejak audio tersimpan sebagian.")
}

internal fun pendingArtifactCleanup(storage: EncryptedStorageService, userId: String, paths: List<String>): List<String> =
    paths.mapNotNull { path ->
        if (runCatching { storage.deleteEncryptedFile(userId, path) }.getOrDefault(false)) null else "Cleanup belum dikonfirmasi: $path"
    }
