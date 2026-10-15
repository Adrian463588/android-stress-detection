package com.stressdetector.mobile.domain.engine

import com.google.gson.Gson
import com.stressdetector.mobile.security.EncryptedStorageService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException

data class AudioSignalChunk(val file: String, val startOffsetMs: Long, val endOffsetMs: Long, val count: Int)

data class AudioSignalTraceManifest(
    val sessionId: String,
    val chunks: List<AudioSignalChunk> = emptyList(),
    val observationCount: Long = 0,
    val complete: Boolean = false,
    val reason: String? = null,
    val schemaVersion: Int = 1,
    /** Measured monotonic SessionTimeline origin, not a wall-clock estimate. */
    val audioStartSessionElapsedMs: Long? = null,
    val captureDurationMs: Long? = null
)

data class AudioSignalTracePage(
    val sessionId: String,
    val startOffsetMs: Long = 0,
    val endOffsetMs: Long = 60_000,
    val observations: List<LivePitchObservation> = emptyList(),
    val manifest: AudioSignalTraceManifest? = null,
    val error: String? = null
) {
    val recordingEndOffsetMs: Long
        get() = maxOf(manifest?.captureDurationMs ?: 0L, manifest?.chunks?.lastOrNull()?.endOffsetMs ?: 0L)
}

data class AudioTimelineReviewMarker(
    val id: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val label: String,
    val explanation: String,
    val source: String,
    /** Audio-capture clock, including pauses; the playback coordinator owns WAV conversion. */
    val playbackOffsetMs: Long? = null
)

fun isAudioSignalTraceFile(relativePath: String): Boolean = relativePath == "manifest.json.enc" ||
    relativePath.matches(Regex("chunk_[0-9]{6}\\.json\\.enc"))

/** Chunk encryption and manifest commits run on the existing pitch worker, never AudioRecord/UI. */
class VerbatimAudioSignalTrace(private val storage: EncryptedStorageService, private val gson: Gson = Gson()) {
    fun writer(userId: String, sessionId: String): Writer {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]+")))
        return Writer(userId, sessionId, "verbatim/$sessionId/audio_signal")
    }

    @Suppress("TooGenericExceptionCaught") // Untrusted chunk/provider boundary returns explicit unavailable; cancellation propagates.
    fun readPage(userId: String, root: String, sessionId: String, startOffsetMs: Long): AudioSignalTracePage {
        require(startOffsetMs in 0..Long.MAX_VALUE - PAGE_MILLIS)
        val manifest = readManifest(userId, root, sessionId)
        val end = startOffsetMs + PAGE_MILLIS
        val selected = manifest.chunks.filter { it.endOffsetMs > startOffsetMs && it.startOffsetMs < end }
        require(selected.sumOf { it.count.toLong() } <= MAX_PAGE_OBSERVATIONS) { "Jendela jejak audio terlalu padat." }
        return try {
            val observations = selected.flatMap { chunk ->
                val records = readChunk(userId, root, chunk)
                require(records.all { it.sessionId == sessionId }) { "Jejak audio berasal dari sesi lain." }
                records.filter { it.endOffsetMs > startOffsetMs && it.startOffsetMs < end }
            }
            AudioSignalTracePage(
                sessionId,
                startOffsetMs,
                end,
                observations,
                manifest,
                if (manifest.complete) null else manifest.reason ?: "Jejak audio belum selesai disimpan."
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AudioSignalTracePage(
                sessionId,
                startOffsetMs,
                end,
                manifest = manifest,
                error = "Bagian waveform/pitch unavailable (${error.javaClass.simpleName}); " +
                    "halaman lain dan audio asli tetap dapat ditinjau."
            )
        }
    }

    fun files(userId: String, root: String, sessionId: String): List<String> {
        val manifest = readManifest(userId, root, sessionId)
        return listOf("$root/$MANIFEST") + manifest.chunks.map { "$root/${it.file}" }
    }

    fun bindSessionOrigin(userId: String, root: String, sessionId: String, originMs: Long) {
        require(originMs >= 0)
        val manifest = readManifest(userId, root, sessionId)
        require(manifest.audioStartSessionElapsedMs == null || manifest.audioStartSessionElapsedMs == originMs)
        val bytes = gson.toJson(manifest.copy(audioStartSessionElapsedMs = originMs)).toByteArray(Charsets.UTF_8)
        storage.writeEncryptedFile(userId, "$root/$MANIFEST", ByteArrayInputStream(bytes))
    }

    internal fun readManifest(userId: String, root: String, sessionId: String): AudioSignalTraceManifest {
        requireSafeRoot(root)
        val manifest = readJson(userId, "$root/$MANIFEST", AudioSignalTraceManifest::class.java)
        require(manifest.schemaVersion == 1 && manifest.sessionId == sessionId) { "Versi/identitas jejak audio tidak cocok." }
        require(manifest.chunks.size <= MAX_CHUNKS && manifest.observationCount >= 0)
        require(manifest.audioStartSessionElapsedMs == null || manifest.audioStartSessionElapsedMs >= 0)
        require(manifest.captureDurationMs == null || manifest.captureDurationMs >= 0)
        var previousEnd = 0L
        manifest.chunks.forEachIndexed { index, chunk ->
            require(chunk.file == chunkName(index) && chunk.count in 1..CHUNK_OBSERVATIONS)
            require(index == manifest.chunks.lastIndex || chunk.count == CHUNK_OBSERVATIONS)
            require(chunk.startOffsetMs >= previousEnd && chunk.endOffsetMs >= chunk.startOffsetMs)
            previousEnd = chunk.endOffsetMs
        }
        require(manifest.chunks.sumOf { it.count.toLong() } == manifest.observationCount)
        return manifest
    }

    private fun readChunk(userId: String, root: String, chunk: AudioSignalChunk): List<LivePitchObservation> {
        val rows = readJson(userId, "$root/${chunk.file}", Array<LivePitchObservation>::class.java).toList()
        require(rows.size == chunk.count)
        var previousEnd = chunk.startOffsetMs
        rows.forEach { row ->
            // Gson bypasses constructors: copy revalidates every public observation invariant.
            row.copy(waveform = requireNotNull(row.waveform), status = requireNotNull(row.status))
            require(row.endOffsetMs > row.startOffsetMs)
            require(row.startOffsetMs >= previousEnd && row.endOffsetMs <= chunk.endOffsetMs)
            previousEnd = row.endOffsetMs
        }
        require(rows.first().startOffsetMs == chunk.startOffsetMs && rows.last().endOffsetMs == chunk.endOffsetMs)
        return rows
    }

    private fun <T> readJson(userId: String, path: String, type: Class<T>): T = storage.readEncryptedFile(userId, path).use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_JSON_BYTES) { "Jejak audio melebihi batas pembacaan." }
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        requireNotNull(gson.fromJson(bytes.toString(Charsets.UTF_8), type))
    }

    inner class Writer internal constructor(private val userId: String, private val sessionId: String, val root: String) {
        private val pending = ArrayList<LivePitchObservation>(CHUNK_OBSERVATIONS)

        // ponytail: one small manifest entry per 30 observations; use a hierarchical index for multi-day capture.
        private val chunks = mutableListOf<AudioSignalChunk>()
        private var lastEnd = 0L
        private var failure: String? = null
        private var finished = false

        init {
            check(storage.listEncryptedFiles(userId).none { it.relativePath.startsWith("$root/") }) {
                "Jejak sesi sudah ada; tidak ditimpa."
            }
            publishManifest(false)
        }

        fun append(observation: LivePitchObservation) {
            check(!finished)
            require(observation.sessionId == sessionId && observation.startOffsetMs >= lastEnd)
            require(observation.endOffsetMs > observation.startOffsetMs)
            val retained = observation.copy(waveform = observation.waveform.toList())
            lastEnd = observation.endOffsetMs
            pending += retained
            if (pending.size == CHUNK_OBSERVATIONS) flush()
        }

        fun finish() {
            if (finished) return
            flush()
            publishManifest(failure == null)
            finished = true
        }

        fun markFailure(reason: String) {
            if (failure == null) failure = reason.take(MAX_REASON_CHARS)
            pending.clear()
        }

        private fun flush() {
            if (pending.isEmpty()) return
            check(chunks.size < MAX_CHUNKS) { "Batas indeks jejak audio tercapai." }
            val chunk = AudioSignalChunk(chunkName(chunks.size), pending.first().startOffsetMs, pending.last().endOffsetMs, pending.size)
            writeJson("$root/${chunk.file}", pending)
            chunks += chunk
            pending.clear()
            publishManifest(false)
        }

        private fun publishManifest(complete: Boolean) = writeJson(
            "$root/$MANIFEST",
            AudioSignalTraceManifest(sessionId, chunks.toList(), chunks.sumOf { it.count.toLong() }, complete, failure)
        )

        private fun writeJson(path: String, value: Any) {
            val bytes = gson.toJson(value).toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_JSON_BYTES)
            storage.writeEncryptedFile(userId, path, ByteArrayInputStream(bytes))
        }
    }

    companion object {
        const val PAGE_MILLIS = 60_000L
        private const val MANIFEST = "manifest.json.enc"
        private const val CHUNK_OBSERVATIONS = 30
        private const val MAX_PAGE_OBSERVATIONS = 512
        private const val MAX_CHUNKS = 20_000
        private const val MAX_REASON_CHARS = 300
        private const val CHUNK_NUMBER_DIGITS = 6
        private const val MAX_JSON_BYTES = 4_194_304

        private fun chunkName(index: Int): String = "chunk_${index.toString().padStart(CHUNK_NUMBER_DIGITS, '0')}.json.enc"

        private fun requireSafeRoot(root: String) {
            require(root.startsWith("verbatim/") || root.startsWith("verbatim_sessions/"))
            require(root.endsWith("/audio_signal") && root.split('/').all { it.isNotBlank() && it != "." && it != ".." })
            require('\\' !in root)
        }
    }
}
