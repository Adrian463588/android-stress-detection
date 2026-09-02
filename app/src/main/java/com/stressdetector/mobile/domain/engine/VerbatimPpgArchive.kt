package com.stressdetector.mobile.domain.engine

import com.google.gson.Gson
import com.stressdetector.mobile.security.EncryptedStorageService
import com.stressdetector.shared.PpgBatch
import com.stressdetector.shared.PpgType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.EnumMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class PpgCaptureStatus {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE
}

data class VerbatimPpgArchiveResult(
    val status: PpgCaptureStatus,
    val manifestPath: String? = null,
    val chunkPaths: List<String> = emptyList(),
    val chunkRelativePaths: List<String> = emptyList(),
    val manifestJson: String = "",
    val sampleCounts: Map<String, Long> = emptyMap(),
    val firstElapsedMillis: Long? = null,
    val lastElapsedMillis: Long? = null,
    val sequenceGapCount: Long = 0L,
    val reason: String? = null
) {
    companion object {
        fun unavailable(reason: String): VerbatimPpgArchiveResult = VerbatimPpgArchiveResult(
            status = PpgCaptureStatus.UNAVAILABLE,
            reason = reason
        )
    }
}

/**
 * Writes acknowledged Watch batches into bounded, encrypted staging chunks.
 * No interpolation, retry fabrication, or unbounded full-session raw buffer exists here.
 */
class VerbatimPpgArchive(private val encryptedStorage: EncryptedStorageService, private val gson: Gson = Gson()) {
    private val lock = Any()
    private var active: ActiveArchive? = null

    fun begin(sessionId: String, sessionNonce: String, userId: String): Result<Unit> = synchronized(lock) {
        if (sessionId.isBlank() || sessionNonce.isBlank() || userId.isBlank()) {
            return@synchronized Result.failure(IllegalArgumentException("PPG archive identity is incomplete"))
        }
        if (active != null) {
            return@synchronized Result.failure(IllegalStateException("Another PPG archive is active"))
        }
        active = ActiveArchive(sessionId, sessionNonce, userId, encryptedStorage, gson)
        Result.success(Unit)
    }

    /** Accepts only batches already acknowledged by [com.stressdetector.mobile.data.PpgRepositoryImpl]. */
    fun append(acknowledgedBatches: List<PpgBatch>) {
        if (acknowledgedBatches.isEmpty()) return
        val archive = synchronized(lock) { active } ?: return
        val copied = acknowledgedBatches.map { batch ->
            batch.copy(
                timestampsMillis = batch.timestampsMillis.copyOf(),
                rawValues = batch.rawValues.copyOf()
            )
        }
        try {
            archive.executor.execute {
                runCatching { archive.append(copied) }
                    .onFailure { archive.markPartial(it.message ?: "PPG chunk write failed") }
            }
        } catch (_: RejectedExecutionException) {
            archive.markPartial("PPG archive queue full; acknowledged batches were not archived")
        }
    }

    fun drainAndFinalize(): VerbatimPpgArchiveResult {
        val archive = synchronized(lock) { active }
        return if (archive == null) {
            VerbatimPpgArchiveResult.unavailable("archive_not_started")
        } else {
            drainArchive(archive)
        }
    }

    private fun drainArchive(archive: ActiveArchive): VerbatimPpgArchiveResult {
        val future: Future<VerbatimPpgArchiveResult> = try {
            archive.executor.submit<VerbatimPpgArchiveResult> { archive.finish() }
        } catch (error: RejectedExecutionException) {
            archive.markPartial(error.message ?: "PPG archive drain rejected")
            synchronized(lock) { if (active === archive) active = null }
            archive.executor.shutdownNow()
            return VerbatimPpgArchiveResult.unavailable("archive_drain_rejected")
        }
        return try {
            future.get()
        } finally {
            archive.executor.shutdown()
            synchronized(lock) { if (active === archive) active = null }
        }
    }

    fun abort(reason: String): VerbatimPpgArchiveResult {
        synchronized(lock) { active }?.markPartial(reason)
        return drainAndFinalize()
    }

    private class ActiveArchive(
        private val sessionId: String,
        private val sessionNonce: String,
        private val userId: String,
        private val encryptedStorage: EncryptedStorageService,
        private val gson: Gson
    ) {
        val executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(QUEUE_CAPACITY),
            { task -> Thread(task, "VerbatimPpgArchive").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()
        )
        private val writers = EnumMap<PpgType, ChannelWriter>(PpgType::class.java)
        private val chunkPaths = mutableListOf<String>()
        private val chunkRelativePaths = mutableListOf<String>()
        private val sampleCounts = EnumMap<PpgType, Long>(PpgType::class.java)
        private var firstElapsedMillis: Long? = null
        private var lastElapsedMillis: Long? = null
        private var sequenceGapCount = 0L
        private var failureReason: String? = null
        private var acceptedBatchCount = 0L
        private var finished = false

        init {
            PpgType.entries.forEach { type ->
                writers[type] = ChannelWriter(type)
                sampleCounts[type] = 0L
            }
        }

        @Synchronized
        fun append(batches: List<PpgBatch>) {
            if (finished) return
            batches.forEach { batch ->
                if (batch.sessionId != sessionId || batch.sessionNonce != sessionNonce) {
                    markPartial("PPG archive identity mismatch")
                    return@forEach
                }
                if (writers.getValue(batch.type).append(batch)) {
                    acceptedBatchCount += 1L
                    sampleCounts[batch.type] = sampleCounts.getValue(batch.type) + batch.size
                    firstElapsedMillis = minOfNullable(firstElapsedMillis, batch.elapsedStartMillis)
                    lastElapsedMillis = maxOfNullable(lastElapsedMillis, batch.elapsedEndMillis)
                }
            }
        }

        @Synchronized
        fun markPartial(reason: String) {
            if (failureReason == null) failureReason = reason
        }

        @Synchronized
        fun finish(): VerbatimPpgArchiveResult {
            if (finished) return result()
            finished = true
            writers.values.forEach { writer ->
                runCatching { writer.flush() }
                    .onFailure { markPartial(it.message ?: "PPG chunk flush failed") }
            }
            val status = when {
                acceptedBatchCount == 0L -> PpgCaptureStatus.UNAVAILABLE
                failureReason != null || sequenceGapCount > 0L -> PpgCaptureStatus.PARTIAL
                else -> PpgCaptureStatus.COMPLETE
            }
            var manifest = manifestJson(status)
            val manifestPath = runCatching {
                encryptedStorage.writeEncryptedFile(
                    userId,
                    manifestPath(),
                    ByteArrayInputStream(manifest.toByteArray(Charsets.UTF_8))
                )
                manifestPath()
            }.onFailure { markPartial(it.message ?: "PPG manifest write failed") }.getOrNull()
            val finalStatus = if (manifestPath == null && status == PpgCaptureStatus.COMPLETE) {
                PpgCaptureStatus.PARTIAL
            } else {
                status
            }
            if (finalStatus != status) manifest = manifestJson(finalStatus)
            return result(
                status = finalStatus,
                manifestPath = manifestPath,
                manifestJson = manifest,
                reason = failureReason
            )
        }

        private fun result(
            status: PpgCaptureStatus = when {
                acceptedBatchCount == 0L -> PpgCaptureStatus.UNAVAILABLE
                failureReason != null || sequenceGapCount > 0L -> PpgCaptureStatus.PARTIAL
                else -> PpgCaptureStatus.COMPLETE
            },
            manifestPath: String? = null,
            manifestJson: String = "",
            reason: String? = failureReason
        ) = VerbatimPpgArchiveResult(
            status = status,
            manifestPath = manifestPath,
            chunkPaths = chunkPaths.toList(),
            chunkRelativePaths = chunkRelativePaths.toList(),
            manifestJson = manifestJson,
            sampleCounts = sampleCounts.mapKeys { it.key.assetSuffix },
            firstElapsedMillis = firstElapsedMillis,
            lastElapsedMillis = lastElapsedMillis,
            sequenceGapCount = sequenceGapCount,
            reason = reason
        )

        private fun manifestJson(status: PpgCaptureStatus): String = gson.toJson(
            mapOf(
                "schemaVersion" to 1,
                "sessionId" to sessionId,
                "sessionNonce" to sessionNonce,
                "status" to status.name,
                "sampleCounts" to sampleCounts.mapKeys { it.key.assetSuffix },
                "firstElapsedMillis" to firstElapsedMillis,
                "lastElapsedMillis" to lastElapsedMillis,
                "sequenceGapCount" to sequenceGapCount,
                "channels" to writers.values.associate { writer -> writer.type.assetSuffix to writer.manifest() },
                "chunkFiles" to chunkRelativePaths.toList(),
                "reason" to failureReason
            )
        )

        private fun manifestPath(): String = "$root/ppg_manifest.json.enc"

        private val root: String
            get() = "verbatim_capture/${safePathPart(sessionId)}_${safePathPart(sessionNonce)}"

        private inner class ChannelWriter(val type: PpgType) {
            private var chunk = ByteArrayOutputStream()
            private var firstElapsed: Long? = null
            private var firstObservedSequence: Long? = null
            private var lastObservedSequence: Long? = null
            private var chunkIndex = 0
            private var channelSampleCount = 0L
            private var channelSequenceGapCount = 0L
            private var chunkCount = 0

            fun append(batch: PpgBatch): Boolean {
                val lineBytes = encodeBatch(batch)
                val span = firstElapsed?.let { batch.elapsedEndMillis - it } ?: 0L
                return if (lineBytes.size > MAX_CHUNK_BYTES) {
                    markPartial("PPG batch exceeds encrypted chunk limit")
                    false
                } else {
                    if (chunk.size() > 0 &&
                        (chunk.size() + lineBytes.size > MAX_CHUNK_BYTES || span > MAX_CHUNK_SPAN_MILLIS)
                    ) {
                        flush()
                    }
                    if (!acceptSequence(batch.sequenceNumber)) {
                        false
                    } else {
                        if (chunk.size() == 0) firstElapsed = batch.elapsedStartMillis
                        chunk.write(lineBytes)
                        firstObservedSequence = firstObservedSequence ?: batch.sequenceNumber
                        lastObservedSequence = batch.sequenceNumber
                        channelSampleCount += batch.size.toLong()
                        true
                    }
                }
            }

            private fun encodeBatch(batch: PpgBatch): ByteArray = gson.toJson(
                mapOf(
                    "sessionId" to batch.sessionId,
                    "sessionNonce" to batch.sessionNonce,
                    "channel" to batch.type.assetSuffix,
                    "sequence" to batch.sequenceNumber,
                    "batchId" to batch.effectiveBatchId,
                    "samplingRateHz" to batch.samplingRateHz,
                    "sensorTimestampsMillis" to batch.timestampsMillis,
                    "elapsedMillis" to batch.elapsedMillis,
                    "elapsedStartMillis" to batch.elapsedStartMillis,
                    "elapsedEndMillis" to batch.elapsedEndMillis,
                    "rawValues" to batch.rawValues,
                    "protocolVersion" to batch.protocolVersion,
                    "peerNodeId" to batch.peerNodeId,
                    "generation" to batch.generation
                )
            ).plus("\n").toByteArray(Charsets.UTF_8)

            private fun acceptSequence(sequenceNumber: Long): Boolean {
                val previousSequence = lastObservedSequence
                return when {
                    previousSequence == null -> {
                        if (sequenceNumber > FIRST_SEQUENCE) {
                            channelSequenceGapCount += sequenceNumber - FIRST_SEQUENCE
                            sequenceGapCount += sequenceNumber - FIRST_SEQUENCE
                        }
                        true
                    }

                    sequenceNumber <= previousSequence -> {
                        markPartial("PPG archive sequence regression or duplicate")
                        false
                    }

                    else -> {
                        if (sequenceNumber > previousSequence + 1L) {
                            channelSequenceGapCount += sequenceNumber - previousSequence - 1L
                            sequenceGapCount += sequenceNumber - previousSequence - 1L
                        }
                        true
                    }
                }
            }

            fun flush() {
                if (chunk.size() == 0) return
                val relative = "ppg_raw/${type.assetSuffix}_${"%06d".format(chunkIndex++)}.jsonl.enc"
                val path = "$root/$relative"
                encryptedStorage.writeEncryptedFile(
                    userId,
                    path,
                    ByteArrayInputStream(chunk.toByteArray())
                )
                chunkPaths += path
                chunkRelativePaths += relative
                chunkCount += 1
                chunk = ByteArrayOutputStream()
                firstElapsed = null
            }

            fun manifest(): Map<String, Any?> = mapOf(
                "samplingRateHz" to type.sourceRateHz,
                "sampleCount" to channelSampleCount,
                "firstSequence" to firstObservedSequence,
                "lastSequence" to lastObservedSequence,
                "sequenceGapCount" to channelSequenceGapCount,
                "chunkCount" to chunkCount
            )
        }
    }

    private companion object {
        const val MAX_CHUNK_BYTES = 512 * 1024
        const val MAX_CHUNK_SPAN_MILLIS = 30_000L
        const val QUEUE_CAPACITY = 64
        const val FIRST_SEQUENCE = 1L
        const val MAX_PATH_PART_LENGTH = 96

        fun safePathPart(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(MAX_PATH_PART_LENGTH)
            .ifBlank { "session" }

        fun minOfNullable(current: Long?, candidate: Long): Long = current?.let { minOf(it, candidate) } ?: candidate

        fun maxOfNullable(current: Long?, candidate: Long): Long = current?.let { maxOf(it, candidate) } ?: candidate
    }
}
