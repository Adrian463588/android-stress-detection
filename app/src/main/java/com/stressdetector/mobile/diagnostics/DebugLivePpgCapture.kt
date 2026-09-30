package com.stressdetector.mobile.diagnostics

import android.content.Context
import com.google.gson.Gson
import com.stressdetector.mobile.BuildConfig
import com.stressdetector.shared.PpgBatch
import com.stressdetector.shared.PpgBatchCodec
import com.stressdetector.shared.StressInferenceTrace
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val BATCH_ID_FILE_HASH_LENGTH = 12
private const val SESSION_KEY_HASH_LENGTH = 32

/**
 * Explicit opt-in for debug-only capture of acknowledged Watch PPG batches.
 *
 * The setting is disabled by default and cannot be enabled in a release build.
 * Captures remain app-private, are never sent to logs, and are not evidence of
 * Android/Python parity until the offline verifier writes a separate receipt.
 */
class DebugLivePpgCaptureSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    val isEnabled: Boolean
        get() = BuildConfig.DEBUG && preferences.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean): Boolean {
        val accepted = BuildConfig.DEBUG && enabled
        preferences.edit().putBoolean(KEY_ENABLED, accepted).apply()
        return accepted
    }

    private companion object {
        const val PREFERENCES_NAME = "debug_live_ppg_capture"
        const val KEY_ENABLED = "enabled"
    }
}

/**
 * Writes only batches already accepted by the phone repository. It deliberately
 * has no sampler, resampler, interpolation, or reconstruction path.
 */
class DebugLivePpgCapture(
    private val rootDirectory: File,
    private val enabled: () -> Boolean,
    private val executor: Executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_PENDING_WRITES),
        { task -> Thread(task, "DebugLivePpgCapture").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    ),
    private val gson: Gson = Gson()
) {
    private val lock = Any()
    private val sessions = linkedMapOf<String, CaptureSession>()

    fun appendAcknowledged(batches: List<PpgBatch>) {
        if (!enabled() || batches.isEmpty()) return
        batches.forEach { batch ->
            val copied = batch.copy(
                timestampsMillis = batch.timestampsMillis.copyOf(),
                rawValues = batch.rawValues.copyOf()
            )
            // Session allocation is in-memory only. Filesystem work stays on the
            // debug executor so it cannot delay Watch transport acknowledgement.
            val session = sessionFor(copied)
            schedule(session) { persistBatch(copied, session) }
        }
    }

    /** Stores model-bound trace metadata after the result is persisted. */
    fun recordInferenceTraces(sessionId: String, traces: List<StressInferenceTrace>) {
        val session = sessionForId(sessionId) ?: return
        if (traces.isEmpty()) return
        schedule(session) {
            val destination = File(session.directory, TRACE_FILE_NAME)
            atomicCaptureWrite(destination, gson.toJson(traces))
            synchronized(lock) {
                session.traceFileName = TRACE_FILE_NAME
                writeManifest(session)
            }
        }
    }

    /** Records the exact Android-prepared window used for a debug inference. */
    fun recordWindowEvidence(sessionId: String, evidence: List<DebugLivePpgWindowEvidence>) {
        val session = sessionForId(sessionId) ?: return
        if (evidence.isEmpty()) return
        schedule(session) {
            val destination = File(session.directory, WINDOW_EVIDENCE_FILE_NAME)
            val enriched = evidence.map { item ->
                val lineage = item.timestampsMillis.map { timestamp ->
                    session.timestampBatchIds["${item.channel}:$timestamp"]
                }
                val checksums = item.timestampsMillis.map { timestamp ->
                    session.timestampBatchChecksums["${item.channel}:$timestamp"]
                }
                item.copy(
                    timestampBatchIds = lineage.filterNotNull(),
                    timestampBatchChecksums = checksums.filterNotNull(),
                    sourceBatchIds = lineage.filterNotNull().distinct(),
                    sourceBatchChecksums = checksums.filterNotNull().distinct()
                )
            }
            atomicCaptureWrite(destination, gson.toJson(enriched))
            synchronized(lock) {
                session.windowEvidenceFileName = WINDOW_EVIDENCE_FILE_NAME
                writeManifest(session)
            }
        }
    }

    /**
     * Closes a capture after a terminal evaluation outcome.
     *
     * The capture can outlive the Activity process because acknowledged Watch
     * batches are persisted asynchronously. Recover the manifest from disk if
     * the in-memory session registry was recreated; never create a manifest or
     * change a parity result when no matching capture exists.
     */
    fun markSessionFinalized(sessionId: String, sessionNonce: String? = null): Boolean {
        val session = sessionForId(sessionId, sessionNonce)
        return if (session != null) {
            synchronized(lock) {
                // Set the flag before later batch writes run. Their manifest
                // updates must not reopen a terminal capture.
                session.finalized = true
                writeManifest(session)
            }
            true
        } else {
            val directory = findPersistedSessionDirectory(sessionId, sessionNonce)
            if (directory == null) {
                false
            } else {
                val manifestFile = File(directory, MANIFEST_FILE_NAME)
                runCatching {
                    val manifest = gson.fromJson(
                        manifestFile.readText(StandardCharsets.UTF_8),
                        CapturedWatchPpgManifest::class.java
                    )
                    if (manifest.sessionId == sessionId &&
                        (sessionNonce.isNullOrBlank() || manifest.sessionNonce == sessionNonce)
                    ) {
                        atomicCaptureWrite(
                            manifestFile,
                            gson.toJson(
                                manifest.copy(
                                    verificationStatus = "PENDING_PYTHON_ORACLE",
                                    actualSensorAcquisition = false,
                                    sensorPathParityStatus = "PENDING_PYTHON_ORACLE",
                                    finalized = true
                                )
                            )
                        )
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)
            }
        }
    }

    private fun persistBatch(batch: PpgBatch, session: CaptureSession) {
        val batchHash = debugCaptureSha256(batch.effectiveBatchId)
            .take(BATCH_ID_FILE_HASH_LENGTH)
        val file = File(
            session.directory,
            "${batch.type.assetSuffix}_${batch.sequenceNumber}_$batchHash.json"
        )
        val payload = CapturedWatchPpgBatch(
            schemaVersion = CAPTURE_SCHEMA_VERSION,
            source = SOURCE_ACKNOWLEDGED_WATCH_BATCH,
            channel = batch.type.assetSuffix,
            sessionId = batch.sessionId,
            sessionNonce = batch.sessionNonce,
            protocolVersion = batch.protocolVersion,
            peerNodeId = batch.peerNodeId,
            generation = batch.generation,
            batchId = batch.effectiveBatchId,
            sequenceNumber = batch.sequenceNumber,
            samplingRateHz = batch.samplingRateHz,
            elapsedStartMillis = batch.elapsedStartMillis,
            elapsedEndMillis = batch.elapsedEndMillis,
            timestampsMillis = batch.timestampsMillis.toList(),
            rawValues = batch.rawValues.toList(),
            payloadSha256 = debugCaptureSha256(debugCaptureCanonicalPayload(batch)),
            encodedBatchSha256 = debugCaptureSha256(PpgBatchCodec.encode(batch))
        )
        synchronized(lock) {
            if (!file.isFile) {
                atomicCaptureWrite(file, gson.toJson(payload))
                session.batchFilesByChannel[batch.type.assetSuffix] =
                    (session.batchFilesByChannel[batch.type.assetSuffix] ?: 0) + 1
                batch.timestampsMillis.forEach { timestamp ->
                    val key = "${batch.type.assetSuffix}:$timestamp"
                    session.timestampBatchIds[key] = batch.effectiveBatchId
                    session.timestampBatchChecksums[key] = payload.payloadSha256
                }
            }
            writeManifest(session)
        }
    }

    private fun schedule(session: CaptureSession, task: () -> Unit) {
        runCatching { executor.execute(task) }
            .onFailure {
                synchronized(lock) {
                    session.droppedWriteCount += 1
                }
            }
    }

    private fun sessionFor(batch: PpgBatch): CaptureSession = synchronized(lock) {
        val key = debugCaptureSessionKey(batch.sessionId, batch.sessionNonce)
        sessions.getOrPut(key) {
            CaptureSession(
                sessionId = batch.sessionId,
                sessionNonce = batch.sessionNonce,
                directory = File(rootDirectory, key)
            )
        }
    }

    private fun sessionForId(sessionId: String, sessionNonce: String? = null): CaptureSession? = synchronized(lock) {
        sessions.values.singleOrNull {
            it.sessionId == sessionId &&
                (sessionNonce.isNullOrBlank() || it.sessionNonce == sessionNonce)
        }
    }

    private fun findPersistedSessionDirectory(sessionId: String, sessionNonce: String?): File? {
        return rootDirectory.listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull { directory ->
                val manifestFile = File(directory, MANIFEST_FILE_NAME)
                if (!manifestFile.isFile) return@mapNotNull null
                val manifest = runCatching {
                    gson.fromJson(
                        manifestFile.readText(StandardCharsets.UTF_8),
                        CapturedWatchPpgManifest::class.java
                    )
                }.getOrNull() ?: return@mapNotNull null
                directory.takeIf {
                    manifest.sessionId == sessionId &&
                        (sessionNonce.isNullOrBlank() || manifest.sessionNonce == sessionNonce)
                }
            }
            .singleOrNull()
    }

    private fun writeManifest(session: CaptureSession) {
        val manifest = CapturedWatchPpgManifest(
            schemaVersion = CAPTURE_SCHEMA_VERSION,
            sessionId = session.sessionId,
            sessionNonce = session.sessionNonce,
            source = SOURCE_ACKNOWLEDGED_WATCH_BATCH,
            verificationStatus = if (session.finalized) "PENDING_PYTHON_ORACLE" else "CAPTURING",
            actualSensorAcquisition = false,
            sensorPathParityStatus = "PENDING_PYTHON_ORACLE",
            batchFilesByChannel = session.batchFilesByChannel.toSortedMap(),
            droppedWriteCount = session.droppedWriteCount,
            inferenceTraceFile = session.traceFileName,
            windowEvidenceFile = session.windowEvidenceFileName,
            finalized = session.finalized
        )
        atomicCaptureWrite(File(session.directory, MANIFEST_FILE_NAME), gson.toJson(manifest))
    }

    private data class CaptureSession(
        val sessionId: String,
        val sessionNonce: String,
        val directory: File,
        val batchFilesByChannel: MutableMap<String, Int> = linkedMapOf(),
        val timestampBatchIds: MutableMap<String, String> = linkedMapOf(),
        val timestampBatchChecksums: MutableMap<String, String> = linkedMapOf(),
        var droppedWriteCount: Int = 0,
        var traceFileName: String? = null,
        var windowEvidenceFileName: String? = null,
        var finalized: Boolean = false
    )

    private data class CapturedWatchPpgBatch(
        val schemaVersion: Int,
        val source: String,
        val channel: String,
        val sessionId: String,
        val sessionNonce: String,
        val protocolVersion: Int,
        val peerNodeId: String,
        val generation: Long,
        val batchId: String,
        val sequenceNumber: Long,
        val samplingRateHz: Int,
        val elapsedStartMillis: Long,
        val elapsedEndMillis: Long,
        val timestampsMillis: List<Long>,
        val rawValues: List<Int>,
        val payloadSha256: String,
        val encodedBatchSha256: String
    )

    private data class CapturedWatchPpgManifest(
        val schemaVersion: Int,
        val sessionId: String,
        val sessionNonce: String,
        val source: String,
        val verificationStatus: String,
        val actualSensorAcquisition: Boolean,
        val sensorPathParityStatus: String,
        val batchFilesByChannel: Map<String, Int>,
        val droppedWriteCount: Int,
        val inferenceTraceFile: String?,
        val windowEvidenceFile: String?,
        val finalized: Boolean
    )

    companion object {
        const val DIRECTORY_NAME = "debug_live_ppg_captures"
        private const val MAX_PENDING_WRITES = 64
        private const val CAPTURE_SCHEMA_VERSION = 2
        private const val MANIFEST_FILE_NAME = "capture_manifest.json"
        private const val TRACE_FILE_NAME = "inference_traces.json"
        private const val WINDOW_EVIDENCE_FILE_NAME = "window_evidence.json"
        private const val SOURCE_ACKNOWLEDGED_WATCH_BATCH = "acknowledged_watch_transport"
    }
}

private fun atomicCaptureWrite(destination: File, text: String) {
    destination.parentFile?.mkdirs()
    val temporary = File(destination.parentFile, ".${destination.name}.tmp")
    temporary.writeText(text, StandardCharsets.UTF_8)
    FileOutputStream(temporary, true).use { output -> output.fd.sync() }
    try {
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (_: AtomicMoveNotSupportedException) {
        destination.delete()
        check(temporary.renameTo(destination)) {
            "debug_capture_atomic_publish_failed"
        }
    }
}

private fun debugCaptureCanonicalPayload(batch: PpgBatch): String = buildString {
    append(batch.type.assetSuffix).append('\n')
    append(batch.sessionId).append('\n')
    append(batch.sessionNonce).append('\n')
    append(batch.protocolVersion).append('\n')
    append(batch.peerNodeId).append('\n')
    append(batch.generation).append('\n')
    append(batch.effectiveBatchId).append('\n')
    append(batch.sequenceNumber).append('\n')
    append(batch.samplingRateHz).append('\n')
    append(batch.elapsedStartMillis).append('\n')
    append(batch.elapsedEndMillis).append('\n')
    batch.timestampsMillis.forEach { append(it).append(',') }
    append('\n')
    batch.rawValues.forEach { append(it).append(',') }
}

private fun debugCaptureSessionKey(sessionId: String, sessionNonce: String): String =
    debugCaptureSha256("$sessionId\u0000$sessionNonce").take(SESSION_KEY_HASH_LENGTH)

private fun debugCaptureSha256(value: String): String = debugCaptureSha256(value.toByteArray(StandardCharsets.UTF_8))

internal fun debugCaptureRawValuesSha256(values: IntArray): String = debugCaptureSha256(values.joinToString(","))

private fun debugCaptureSha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .joinToString("") { "%02x".format(it) }

/** Private debug evidence. The values are Android-filtered, not synthetic samples. */
data class DebugLivePpgWindowEvidence(
    val channel: String,
    val windowId: String,
    val timestampsMillis: List<Long>,
    val rawValues: List<Int>,
    val rawValuesSha256: String,
    val filteredValues: List<Double>,
    val filteredValuesSha256: String = "",
    val observedMask: List<Boolean>,
    val repairedShortGapMask: List<Boolean>,
    val gridResampledMask: List<Boolean>,
    val trueGapCount: Int,
    val unresolvedSampleCount: Int,
    val inferenceTrace: StressInferenceTrace?,
    val timestampBatchIds: List<String> = emptyList(),
    val timestampBatchChecksums: List<String> = emptyList(),
    val sourceBatchIds: List<String> = emptyList(),
    val sourceBatchChecksums: List<String> = emptyList()
)
