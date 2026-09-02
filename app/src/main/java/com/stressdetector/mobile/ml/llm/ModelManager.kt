package com.stressdetector.mobile.ml.llm

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.stressdetector.mobile.ml.artifacts.LocalArtifactCatalog
import com.stressdetector.mobile.ml.artifacts.LocalArtifactId
import com.stressdetector.mobile.ml.assets.AiAssetEntry
import com.stressdetector.mobile.ml.assets.AiAssetKind
import com.stressdetector.mobile.ml.download.AndroidStorageSpaceProvider
import com.stressdetector.mobile.ml.download.DownloadLane
import com.stressdetector.mobile.ml.download.DownloadStorageBudget
import com.stressdetector.mobile.ml.download.StorageReservationResult
import com.stressdetector.mobile.ml.download.StorageSpaceProvider
import com.stressdetector.mobile.ml.download.StorageSpaceResult
import com.stressdetector.mobile.ml.storage.ModelStorageManager
import com.stressdetector.mobile.ml.storage.ModelStorageSlot
import com.stressdetector.mobile.ml.storage.SafGenerationPayload
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class OfficialModelContract(
    val fileName: String,
    val sourceUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val version: String = "gemma-4-E2B-it-litertlm",
    val revision: String = "6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94"
) {
    init {
        require(revision.isNotBlank()) { "Gemma revision must be explicit." }
        require(sourceUrl.contains("/resolve/$revision/")) {
            "Gemma source URL must be pinned to revision $revision."
        }
    }
}

sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class WaitingForNetwork(val networkState: GemmaNetworkState, val message: String) : ModelDownloadState
    data class TransferRetry(
        val message: String,
        val attempt: Int = 0,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = ModelManager.MODEL_SIZE_BYTES
    ) : ModelDownloadState
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long, val stage: String = "Downloading") : ModelDownloadState
    data class Verifying(
        val bytesProcessed: Long = 0L,
        val totalBytes: Long = ModelManager.MODEL_SIZE_BYTES,
        val stage: String = "Verifying"
    ) : ModelDownloadState
    data object Ready : ModelDownloadState
    data object Cancelled : ModelDownloadState
    data class Failed(val message: String, val retryable: Boolean) : ModelDownloadState
}

internal fun ModelDownloadState.Failed.isRuntimePreflightFailure(): Boolean = message.contains("runtime preflight", ignoreCase = true)

internal fun verifyOfficialModelFile(file: File, contract: OfficialModelContract, requireCanonicalName: Boolean): Result<Unit> =
    ModelIntegrityVerifier(contract).verifyBlocking(file, requireCanonicalName)

sealed interface ModelReadiness {
    data class Ready(val metadata: AiAssetEntry) : ModelReadiness
    data object Missing : ModelReadiness
    data class Corrupted(val reason: String, val candidateShouldBeDeleted: Boolean = true) : ModelReadiness
    data class Untrusted(val reason: String) : ModelReadiness
    data class UnsupportedDevice(val reason: String) : ModelReadiness
}

internal fun mapGemmaDownloadSnapshot(snapshot: DownloadSnapshot): ModelDownloadState = when (snapshot.status) {
    DownloadTransportStatus.Pending -> ModelDownloadState.WaitingForNetwork(
        networkState = GemmaNetworkState.UNKNOWN,
        message = "Download menunggu evaluasi jaringan."
    )
    DownloadTransportStatus.Paused -> if (snapshot.reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK) {
        ModelDownloadState.WaitingForNetwork(
            networkState = GemmaNetworkState.UNKNOWN,
            message = "Download menunggu jaringan yang memenuhi policy Wi-Fi."
        )
    } else {
        ModelDownloadState.Failed("Download paused by Android: ${snapshot.reason ?: "unknown reason"}", retryable = true)
    }
    DownloadTransportStatus.Running -> ModelDownloadState.Downloading(
        bytesDownloaded = snapshot.bytesDownloaded,
        totalBytes = snapshot.totalBytes.takeIf { it > 0L } ?: ModelManager.MODEL_SIZE_BYTES
    )
    DownloadTransportStatus.Successful -> ModelDownloadState.Verifying(
        bytesProcessed = snapshot.bytesDownloaded,
        totalBytes = snapshot.totalBytes.takeIf { it > 0L } ?: ModelManager.MODEL_SIZE_BYTES
    )
    DownloadTransportStatus.Failed -> ModelDownloadState.Failed(
        message = "Android DownloadManager failed the Gemma 4 E2B transfer (reason ${snapshot.reason ?: "unknown"})",
        retryable = snapshot.reason.isRetryableDownloadManagerFailure()
    )
}

private fun Int?.isRetryableDownloadManagerFailure(): Boolean = when (this) {
    DownloadManager.ERROR_UNKNOWN,
    DownloadManager.ERROR_HTTP_DATA_ERROR,
    DownloadManager.ERROR_CANNOT_RESUME -> true
    DownloadManager.ERROR_FILE_ERROR,
    DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
    DownloadManager.ERROR_TOO_MANY_REDIRECTS,
    DownloadManager.ERROR_INSUFFICIENT_SPACE,
    DownloadManager.ERROR_DEVICE_NOT_FOUND,
    null -> false
    else -> false
}

internal fun mapFinalizerTerminalState(
    state: WorkInfo.State,
    persisted: PersistedModelDownload?,
    outputMessage: String?,
    retryable: Boolean = false
): ModelDownloadState = when (state) {
    WorkInfo.State.CANCELLED -> ModelDownloadState.Cancelled
    else -> ModelDownloadState.Failed(
        persisted?.errorMessage
            ?: outputMessage
            ?: "Gemma 4 E2B verification failed.",
        retryable = retryable
    )
}

/** Coordinates the canonical Gemma model, DownloadManager, WorkManager, and local storage. */
open class ModelManager(
    context: Context,
    private val downloadTransport: ModelDownloadTransport = AndroidDownloadManagerTransport(context),
    private val downloadStateStore: ModelDownloadStateStore = ModelDownloadStateStore(context),
    storageOverride: ModelStorage? = null,
    private val storageSpaceProvider: StorageSpaceProvider = AndroidStorageSpaceProvider(context),
    private val durableStorage: ModelStorageManager? = null,
    private val artifactCatalog: LocalArtifactCatalog? = null,
    private val candidatePreflight: GemmaCandidatePreflight = LiteRtGemmaCandidatePreflight(context),
    private val networkEvaluator: GemmaNetworkEvaluator = AndroidGemmaNetworkEvaluator(context)
) {
    companion object {
        const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        const val MODEL_FILE = MODEL_FILE_NAME
        const val MODEL_SOURCE = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
        const val MODEL_BASE_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94/gemma-4-E2B-it.litertlm"
        const val MODEL_DOWNLOAD_URL = MODEL_BASE_URL
        const val MODEL_SIZE_BYTES = 2_588_147_712L
        const val MODEL_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        const val DOWNLOAD_CANDIDATE_FILE_NAME = ".${MODEL_FILE_NAME}.download.litertlm"
        private const val LEGACY_DOWNLOAD_CANDIDATE_FILE_NAME = ".${MODEL_FILE_NAME}.download.part"
        internal const val COMPLETE_CANDIDATE_MESSAGE =
            "Gemma sudah diunduh dan checksum belum diverifikasi ulang oleh runtime."
        const val DOWNLOAD_WORK_NAME = "artifact-gemma_4_e2b-gemma-4-E2B-it-litertlm"
        const val RESUME_WORK_NAME = "artifact-gemma_4_e2b-legacy-resume"
        const val DOWNLOAD_ID_INPUT = "download_id"
        const val PROGRESS_STAGE = "stage"
        const val PROGRESS_BYTES = "bytes_downloaded"
        const val PROGRESS_TOTAL = "total_bytes"
        const val PROGRESS_ERROR = "error"
        const val PROGRESS_RETRYABLE = "retryable"
        const val PROGRESS_NETWORK_STATE = "network_state"
        private const val MIN_RAM_GB = 3L
        private const val STORAGE_SAFETY_MARGIN_BYTES = 128L * 1024L * 1024L
        private const val SNAPSHOT_ATTEMPTS = 3
        private const val SNAPSHOT_RETRY_DELAY_MS = 250L
        private const val DOWNLOAD_CANCEL_TIMEOUT_MILLIS = 30_000L
    }

    private val applicationContext = context.applicationContext
    private val workManager = WorkManager.getInstance(applicationContext)
    private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = kotlinx.coroutines.sync.Mutex()
    val officialModelContract = artifactCatalog?.require(LocalArtifactId.GEMMA_4_E2B)?.let { artifact ->
        val payload = artifact.files.single()
        OfficialModelContract(
            fileName = payload.path,
            sourceUrl = requireNotNull(payload.url),
            sizeBytes = payload.sizeBytes,
            sha256 = payload.sha256,
            version = artifact.version,
            revision = artifact.revision
        )
    } ?: OfficialModelContract(
        MODEL_FILE_NAME,
        MODEL_BASE_URL,
        MODEL_SIZE_BYTES,
        MODEL_SHA256
    )
    private val integrityVerifier = ModelIntegrityVerifier(officialModelContract)
    private val verifiedModelStateStore = VerifiedModelStateStore(applicationContext)
    private val storageBudget = DownloadStorageBudget(applicationContext)
    private val storage: ModelStorage = storageOverride
        ?: GemmaModelStorage(applicationContext, officialModelContract)
    private val _readiness = kotlinx.coroutines.flow.MutableStateFlow<ModelReadiness>(ModelReadiness.Missing)
    val readiness: kotlinx.coroutines.flow.StateFlow<ModelReadiness> = _readiness
    private val _isReady = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isReady: kotlinx.coroutines.flow.StateFlow<Boolean> = _isReady
    private var validatedCompletionWorkId: UUID? = null
    private var validatedCompletionState: ModelDownloadState? = null

    fun getModelDir(): File = (storage.activeModelFile().parentFile ?: error("Model directory is unavailable"))
        .also { it.mkdirs() }
    fun getModelFile(): File = storage.activeModelFile()
    fun getDownloadCandidateFile(): File = storage.candidateModelFile()
    fun getLegacyDownloadCandidateFile(): File? = storage.legacyDownloadCandidateFile()
    fun getResumeModelFile(): File = storage.resumeModelFile()
    fun getPartialModelFile(): File = storage.legacyPartialModelFile()
    fun getDiscardMarkerFile(): File = File(getModelDir(), ".${MODEL_FILE}.discard")

    internal suspend fun prepareCandidateForVerification(snapshot: DownloadSnapshot): File = withContext(Dispatchers.IO) {
        val candidate = getDownloadCandidateFile()
        val uri = snapshot.localUri ?: return@withContext candidate
        when (uri.scheme?.lowercase()) {
            "file" -> {
                val downloadedFile = File(requireNotNull(uri.path)).canonicalFile
                check(downloadedFile == candidate.canonicalFile) {
                    "DownloadManager returned an unexpected model destination"
                }
                candidate
            }
            "content" -> storage.stageDownloadedUri(uri, applicationContext.contentResolver)
            else -> error("DownloadManager returned an unsupported model URI")
        }
    }

    fun currentReadiness(): ModelReadiness = inspect().also(::publish)

    suspend fun refreshReadiness(): ModelReadiness = withContext(Dispatchers.IO) {
        durableStorage?.reconcile()
        durableStorage?.restoreArtifactIfMissing(
            slot = ModelStorageSlot.GEMMA,
            fileName = officialModelContract.fileName,
            target = storage.activeModelFile(),
            expectedSizeBytes = officialModelContract.sizeBytes,
            expectedSha256 = officialModelContract.sha256,
            generation = "${officialModelContract.version}-${officialModelContract.sha256.take(12)}"
        )
        inspect().also(::publish)
    }

    suspend fun syncActiveModelToDurableStorage() = withContext(Dispatchers.IO) {
        if (refreshReadiness() is ModelReadiness.Ready) {
            syncDurableArtifact()
        } else {
            Result.success(Unit)
        }
    }

    suspend fun reconcileDownloadState(): ModelReadiness = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val active = refreshReadiness()
            var stored = downloadStateStore.read()
            val migratingLegacyState = stored != null && (stored.workId == null || !isCompatible(stored))
            if (migratingLegacyState) {
                migrateLegacyDownload(stored)
                workManager.cancelUniqueWork(DOWNLOAD_WORK_NAME)
                downloadStateStore.clear()
                stored = null
            }

            val work = findCanonicalWorkInfo(stored)
            if (active is ModelReadiness.Ready && stored?.replacesActiveModel != true && work == null) {
                downloadStateStore.clear()
                storage.deleteCandidates()
                storageBudget.release(DownloadLane.GEMMA)
                return@withLock active
            }

            when {
                work != null && !work.state.isFinished && migratingLegacyState -> {
                    enqueueCanonicalWorkLocked(
                        replacesActiveModel = active is ModelReadiness.Ready,
                        forceReplaceExisting = true
                    )
                }
                work != null && !work.state.isFinished -> {
                    ensureWorkState(work.id.toString(), stored?.replacesActiveModel ?: false)
                    adoptGemmaReservation(work.id.toString())
                }
                work?.state == WorkInfo.State.SUCCEEDED -> {
                    val refreshed = refreshReadiness()
                    if (refreshed is ModelReadiness.Ready) {
                        downloadStateStore.clear()
                        storageBudget.release(DownloadLane.GEMMA, work.id.toString())
                    } else {
                        downloadStateStore.update(
                            stage = ModelDownloadStage.FAILED,
                            errorMessage = "Gemma selesai tetapi artifact aktif belum lolos validasi."
                        )
                        storageBudget.release(DownloadLane.GEMMA, work.id.toString())
                    }
                }
                work?.state == WorkInfo.State.CANCELLED -> {
                    downloadStateStore.update(
                        stage = if (hasCompleteCandidate()) ModelDownloadStage.FAILED else ModelDownloadStage.CANCELLED,
                        errorMessage = if (hasCompleteCandidate()) {
                            COMPLETE_CANDIDATE_MESSAGE
                        } else {
                            "Download Gemma dibatalkan; partial file dipertahankan."
                        }
                    )
                    storageBudget.release(DownloadLane.GEMMA, work.id.toString())
                }
                work?.state == WorkInfo.State.FAILED -> {
                    val retryable = work.retryable()
                    downloadStateStore.update(
                        stage = ModelDownloadStage.FAILED,
                        errorMessage = if (hasCompleteCandidate()) {
                            COMPLETE_CANDIDATE_MESSAGE
                        } else {
                            work.outputData.getString(PROGRESS_ERROR)
                                ?: stored?.errorMessage
                                ?: "Download atau verifikasi Gemma gagal."
                        }
                    )
                    downloadStateStore.setRetryable(if (hasCompleteCandidate()) false else retryable)
                    storageBudget.release(DownloadLane.GEMMA, work.id.toString())
                }
                stored != null &&
                    stored.stage != ModelDownloadStage.FAILED &&
                    stored.stage != ModelDownloadStage.CANCELLED -> {
                    enqueueCanonicalWorkLocked(stored.replacesActiveModel)
                }
                hasCandidateFiles() -> {
                    if (hasCompleteCandidate()) {
                        downloadStateStore.update(
                            stage = ModelDownloadStage.FAILED,
                            errorMessage = COMPLETE_CANDIDATE_MESSAGE
                        )
                        downloadStateStore.setRetryable(false)
                    } else {
                        enqueueCanonicalWorkLocked(
                            replacesActiveModel = active is ModelReadiness.Ready,
                            forceReplaceExisting = true,
                            requiresNetwork = true
                        )
                    }
                }
            }
            active
        }
    }

    fun isModelReady(): Boolean = _isReady.value

    /** True when a complete candidate exists but has not been activated yet. */
    fun hasCompleteDownloadCandidate(): Boolean = hasCompleteCandidate()

    fun getModelMetadata(): AiAssetEntry? = (_readiness.value as? ModelReadiness.Ready)?.metadata

    fun getModelSizeMb(): Long = getModelFile().takeIf(File::isFile)?.length()?.div(1024 * 1024) ?: 0L

    fun getAvailableStorageMb(): Long = getAvailableStorageBytes() / (1024 * 1024)

    fun getAvailableStorageBytes(): Long = (storageSpaceProvider.measure(getModelDir(), 0L) as? StorageSpaceResult.Available)
        ?.snapshot
        ?.availableBytes
        ?: 0L

    fun hasEnoughStorageForDownload(): Boolean = getAvailableStorageBytes() >= requiredDownloadBytes()

    fun hasEnoughStorageForAdditionalBytes(additionalBytes: Long): Boolean =
        getAvailableStorageBytes() >= additionalBytes.coerceAtLeast(0L) + STORAGE_SAFETY_MARGIN_BYTES

    fun observeDownload(): Flow<ModelDownloadState> = flow {
        while (currentCoroutineContext().isActive) {
            emit(queryDownloadState())
            delay(1_000L)
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    /**
     * Enqueues one idempotent download. A null result means a verified active model already exists.
     */
    suspend fun enqueueModelDownload(forceReplaceActive: Boolean = false): Long? = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            getDiscardMarkerFile().delete()
            val active = inspect().also(::publish)
            val stored = downloadStateStore.read()
            val migratingLegacyState = stored != null && (stored.workId == null || !isCompatible(stored))
            if (migratingLegacyState) {
                migrateLegacyDownload(stored)
                workManager.cancelUniqueWork(DOWNLOAD_WORK_NAME)
                downloadStateStore.clear()
            }

            if (active is ModelReadiness.Ready && !forceReplaceActive && !hasCandidateFiles()) {
                return@withLock null
            }

            val existing = findCanonicalWorkInfo(downloadStateStore.read())
            if (!migratingLegacyState && existing != null && !existing.state.isFinished) {
                ensureWorkState(existing.id.toString(), forceReplaceActive)
                adoptGemmaReservation(existing.id.toString())
                return@withLock null
            }

            downloadStateStore.clear()
            enqueueCanonicalWorkLocked(forceReplaceActive, forceReplaceExisting = migratingLegacyState)
            null
        }
    }

    fun handleDownloadCompleted(downloadId: Long) {
        // Kept for backward-compatible manifest receivers. The canonical lane is
        // WorkManager-based and never treats DownloadManager broadcasts as truth.
        operationScope.launch { reconcileDownloadState() }
    }

    fun cancelModelDownload(keepPartial: Boolean = true) {
        if (keepPartial) getDiscardMarkerFile().delete() else getDiscardMarkerFile().writeText("discarded")
        val tracked = downloadStateStore.read()
        if (tracked != null && tracked.downloadId > 0L) {
            downloadTransport.remove(tracked.downloadId)
        }
        if (!keepPartial) storage.deleteCandidates()
        if (keepPartial) {
            downloadStateStore.update(
                stage = ModelDownloadStage.CANCELLED,
                errorMessage = "Download Gemma dibatalkan; partial file dipertahankan."
            )
        } else {
            downloadStateStore.clear()
        }
        storageBudget.release(DownloadLane.GEMMA)
        workManager.cancelUniqueWork(DOWNLOAD_WORK_NAME)
        workManager.cancelUniqueWork(RESUME_WORK_NAME)
    }

    internal fun clearTrackedDownload(downloadId: Long) {
        if (downloadStateStore.read()?.downloadId == downloadId) downloadStateStore.clear()
    }

    internal fun markFinalizationFailed(downloadId: Long, message: String, deleteCandidate: Boolean, preserveTransferState: Boolean) {
        if (downloadId <= 0L) return
        val current = downloadStateStore.read()
        if (current?.downloadId != downloadId) return
        downloadStateStore.update(
            stage = if (preserveTransferState) ModelDownloadStage.TRANSFERRING else ModelDownloadStage.FAILED,
            errorMessage = message
        )
        if (deleteCandidate) {
            storage.deleteCandidates()
            downloadTransport.remove(downloadId)
        }
        if (!preserveTransferState) storageBudget.release(DownloadLane.GEMMA)
    }

    fun discardPartialDownload() {
        cancelModelDownload(keepPartial = false)
    }

    internal fun discardInvalidCandidate() {
        storage.deleteCandidates()
        getDiscardMarkerFile().delete()
    }

    internal fun markTransferRetry(message: String) {
        downloadStateStore.update(
            stage = ModelDownloadStage.TRANSFERRING,
            errorMessage = message
        )
    }

    suspend fun retryDownload(forceReplaceActive: Boolean = false): UUID? = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            getDiscardMarkerFile().delete()
            val active = inspect().also(::publish)
            downloadStateStore.clear()
            enqueueCanonicalWorkLocked(
                replacesActiveModel = forceReplaceActive || active is ModelReadiness.Ready,
                forceReplaceExisting = true
            )
        }
    }

    suspend fun verifyAndPublishDownloadedModel(
        candidate: File = getDownloadCandidateFile(),
        onProgress: suspend (ModelVerificationProgress) -> Unit = {},
        onStage: suspend (ModelDownloadStage) -> Unit = {}
    ): ModelReadiness = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            if (getDiscardMarkerFile().isFile) {
                storage.deleteCandidates()
                storageBudget.release(DownloadLane.GEMMA)
                return@withLock ModelReadiness.Corrupted(
                    "Gemma 4 E2B activation was cancelled by the user.",
                    candidateShouldBeDeleted = true
                )
            }
            if (!hasMinimumRam()) {
                return@withLock ModelReadiness.UnsupportedDevice("At least ${MIN_RAM_GB}GB RAM is required")
            }
            downloadStateStore.update(ModelDownloadStage.VERIFYING)
            val validation = storage.verifyCandidate(candidate, onProgress)
            if (validation.isFailure) {
                val error = validation.exceptionOrNull()
                return@withLock ModelReadiness.Corrupted(
                    error?.message ?: "Downloaded model is invalid",
                    candidateShouldBeDeleted = error is ModelChecksumMismatchException
                )
            }
            downloadStateStore.update(
                stage = ModelDownloadStage.PREFLIGHTING,
                bytesProcessed = officialModelContract.sizeBytes,
                totalBytes = officialModelContract.sizeBytes
            )
            onStage(ModelDownloadStage.PREFLIGHTING)
            val preflight = candidatePreflight.verify(candidate)
            if (preflight.isFailure) {
                return@withLock ModelReadiness.UnsupportedDevice(
                    "Gemma lulus checksum tetapi gagal runtime preflight: " +
                        (preflight.exceptionOrNull()?.message ?: "error tidak diketahui")
                ).also(::publish)
            }
            downloadStateStore.update(
                stage = ModelDownloadStage.PUBLISHING,
                bytesProcessed = officialModelContract.sizeBytes,
                totalBytes = officialModelContract.sizeBytes
            )
            onStage(ModelDownloadStage.PUBLISHING)
            val published = storage.publishAtomically(candidate)
            storage.deleteCandidates()
            verifiedModelStateStore.markVerified(storage.activeModelFile(), officialModelContract)
            val durableSync = syncDurableArtifact()
            if (durableSync.isFailure) {
                durableStorage?.reportError(
                    "Gemma lokal siap, tetapi mirror SAF gagal: " +
                        (durableSync.exceptionOrNull()?.message ?: "error tidak diketahui")
                )
            }
            storageBudget.release(DownloadLane.GEMMA)
            published.also(::publish)
        }
    }

    suspend fun retryVerification(): UUID? = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            getDiscardMarkerFile().delete()
            if (!hasCandidateFiles()) return@withLock null
            val stored = downloadStateStore.read()
            downloadStateStore.update(
                stage = ModelDownloadStage.TRANSFERRING,
                errorMessage = null
            )
            enqueueCanonicalWorkLocked(
                replacesActiveModel = stored?.replacesActiveModel
                    ?: (inspect() is ModelReadiness.Ready),
                forceReplaceExisting = true,
                requiresNetwork = false
            )
        }
    }

    private suspend fun syncDurableArtifact(): Result<Unit> {
        val durable = durableStorage
        if (durable == null || !durable.state.value.connected) return Result.success(Unit)
        return runCatching {
            val generation = "${officialModelContract.version}-${officialModelContract.sha256.take(12)}"
            durable.publishGeneration(
                slot = ModelStorageSlot.GEMMA,
                generation = generation,
                version = officialModelContract.version,
                revision = officialModelContract.sourceUrl.substringAfter("/resolve/").substringBefore('/'),
                payloads = listOf(
                    SafGenerationPayload(
                        source = storage.activeModelFile(),
                        fileName = officialModelContract.fileName,
                        expectedSizeBytes = officialModelContract.sizeBytes,
                        expectedSha256 = officialModelContract.sha256
                    )
                )
            ).getOrThrow()
        }
    }

    suspend fun deleteModel(): Boolean = withContext(Dispatchers.IO) {
        getDiscardMarkerFile().writeText("discarded")
        val tracked = downloadStateStore.read()
        if (tracked != null && tracked.downloadId > 0L) {
            downloadTransport.remove(tracked.downloadId)
        }
        workManager.cancelUniqueWork(DOWNLOAD_WORK_NAME)
        workManager.cancelUniqueWork(RESUME_WORK_NAME)
        if (!awaitDownloadCancellation()) {
            getDiscardMarkerFile().delete()
            return@withContext false
        }

        operationMutex.withLock {
            val durableDelete = durableStorage?.deleteSlot(
                slot = ModelStorageSlot.GEMMA
            ) ?: Result.success(Unit)
            if (durableDelete.isFailure) {
                getDiscardMarkerFile().delete()
                return@withLock false
            }
            val modelDeleted = storage.deleteActiveAndLegacyMetadata()
            verifiedModelStateStore.clear()
            storage.deleteCandidates()
            downloadStateStore.clear()
            storageBudget.release(DownloadLane.GEMMA)
            getDiscardMarkerFile().delete()
            publish(ModelReadiness.Missing)
            modelDeleted
        }
    }

    private suspend fun awaitDownloadCancellation(): Boolean = withTimeoutOrNull(
        DOWNLOAD_CANCEL_TIMEOUT_MILLIS
    ) {
        workManager.getWorkInfosForUniqueWorkFlow(DOWNLOAD_WORK_NAME).first { infos ->
            infos.none { !it.state.isFinished }
        }
        workManager.getWorkInfosForUniqueWorkFlow(RESUME_WORK_NAME).first { infos ->
            infos.none { !it.state.isFinished }
        }
        true
    } ?: false

    private suspend fun queryDownloadState(): ModelDownloadState {
        val tracked = downloadStateStore.read()
        val work = findCanonicalWorkInfo(tracked)
        if (work != null) {
            if (work.state == WorkInfo.State.SUCCEEDED) return verifiedCompletionState(work.id)
            if (!work.state.isFinished) return work.toModelDownloadState()
            if (hasCompleteCandidate()) {
                return ModelDownloadState.Failed(COMPLETE_CANDIDATE_MESSAGE, retryable = false)
            }
            if (work.state == WorkInfo.State.CANCELLED) return ModelDownloadState.Cancelled
            return ModelDownloadState.Failed(
                work.outputData.getString(PROGRESS_ERROR)
                    ?: tracked?.errorMessage
                    ?: "Gemma 4 E2B download failed.",
                retryable = work.retryable()
            )
        }

        if (tracked?.stage == ModelDownloadStage.FAILED) {
            val candidateReady = hasCompleteCandidate()
            return ModelDownloadState.Failed(
                if (candidateReady) {
                    COMPLETE_CANDIDATE_MESSAGE
                } else {
                    tracked.errorMessage ?: "Gemma 4 E2B download failed."
                },
                retryable = tracked.retryable ?: false
            )
        }

        return if (_readiness.value is ModelReadiness.Ready) {
            ModelDownloadState.Ready
        } else if (hasCompleteCandidate()) {
            ModelDownloadState.Failed(COMPLETE_CANDIDATE_MESSAGE, retryable = false)
        } else {
            ModelDownloadState.Idle
        }
    }

    private suspend fun verifiedCompletionState(workId: UUID): ModelDownloadState {
        validatedCompletionState?.takeIf { validatedCompletionWorkId == workId }?.let { return it }
        // A finalizer runs with a separate ModelManager instance. Always inspect
        // the canonical file here so the UI instance observes the worker's disk
        // activation instead of relying on its own in-memory StateFlow.
        refreshReadiness()
        val result = if (_readiness.value is ModelReadiness.Ready) {
            ModelDownloadState.Ready
        } else {
            ModelDownloadState.Failed(
                "Download completed but the active Gemma 4 E2B model did not pass validation",
                retryable = true
            )
        }
        validatedCompletionWorkId = workId
        validatedCompletionState = result
        return result
    }

    private fun DownloadSnapshot.toModelDownloadState(): ModelDownloadState = mapGemmaDownloadSnapshot(this)

    private fun WorkInfo.toModelDownloadState(): ModelDownloadState {
        val stage = progress.getString(PROGRESS_STAGE)
        if (stage == "WaitingForNetwork") {
            val evaluation = networkEvaluator.evaluate()
            return ModelDownloadState.WaitingForNetwork(evaluation.state, evaluation.userMessage)
        }
        if (stage == "Retrying") {
            return ModelDownloadState.TransferRetry(
                message = progress.getString(PROGRESS_ERROR) ?: "Transfer Gemma akan dicoba lagi.",
                attempt = runAttemptCount,
                bytesDownloaded = progress.getLong(PROGRESS_BYTES, 0L),
                totalBytes = progress.getLong(PROGRESS_TOTAL, MODEL_SIZE_BYTES)
            )
        }
        if (state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED) {
            val evaluation = networkEvaluator.evaluate()
            return ModelDownloadState.WaitingForNetwork(evaluation.state, evaluation.userMessage)
        }
        if (state == WorkInfo.State.RUNNING) {
            if (stage == "Verifying" || stage == "Preflighting" || stage == "Publishing") {
                return ModelDownloadState.Verifying(
                    bytesProcessed = progress.getLong(PROGRESS_BYTES, 0L),
                    totalBytes = progress.getLong(PROGRESS_TOTAL, MODEL_SIZE_BYTES),
                    stage = stage
                )
            }
            return ModelDownloadState.Downloading(
                bytesDownloaded = progress.getLong(PROGRESS_BYTES, 0L),
                totalBytes = progress.getLong(PROGRESS_TOTAL, MODEL_SIZE_BYTES),
                stage = stage ?: "Downloading"
            )
        }
        if (state == WorkInfo.State.SUCCEEDED) return ModelDownloadState.Ready
        if (state == WorkInfo.State.CANCELLED) return ModelDownloadState.Cancelled
        return ModelDownloadState.Failed(
            message = outputData.getString(PROGRESS_ERROR) ?: "Gemma 4 E2B download failed",
            retryable = retryable()
        )
    }

    private fun WorkInfo.retryable(): Boolean = progress.getBoolean(
        PROGRESS_RETRYABLE,
        outputData.getBoolean(PROGRESS_RETRYABLE, false)
    )

    private suspend fun findCanonicalWorkInfo(persisted: PersistedModelDownload? = downloadStateStore.read()): WorkInfo? {
        val infos = workManager.getWorkInfosForUniqueWorkFlow(DOWNLOAD_WORK_NAME).first()
        val trackedId = persisted?.workId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return trackedId?.let { id -> infos.firstOrNull { it.id == id } }
            ?: infos.firstOrNull { !it.state.isFinished }
            ?: infos.firstOrNull { it.id.toString() == persisted?.finalizerWorkId }
    }

    private suspend fun enqueueCanonicalWorkLocked(
        replacesActiveModel: Boolean,
        forceReplaceExisting: Boolean = false,
        requiresNetwork: Boolean = !hasCompleteCandidate()
    ): UUID {
        val existing = findCanonicalWorkInfo(downloadStateStore.read())
        if (existing != null && !existing.state.isFinished && !forceReplaceExisting) {
            ensureWorkState(existing.id.toString(), replacesActiveModel)
            adoptGemmaReservation(existing.id.toString())
            return existing.id
        }

        val request = OneTimeWorkRequestBuilder<GemmaRangeResumeWorker>()
            .setConstraints(if (requiresNetwork) downloadConstraints() else localVerificationConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(DOWNLOAD_WORK_NAME)
            .build()
        reserveGemmaStorage(request.id.toString())
        downloadStateStore.saveWork(request.id.toString(), replacesActiveModel)
        try {
            workManager.enqueueUniqueWork(DOWNLOAD_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        } catch (error: Throwable) {
            downloadStateStore.clear()
            storageBudget.release(DownloadLane.GEMMA, request.id.toString())
            throw error
        }
        validatedCompletionWorkId = null
        validatedCompletionState = null
        return request.id
    }

    private fun ensureWorkState(workId: String, replacesActiveModel: Boolean) {
        if (downloadStateStore.read()?.workId != workId) {
            downloadStateStore.saveWork(workId, replacesActiveModel)
        }
    }

    private fun downloadConstraints(): Constraints = Constraints.Builder()
        // WorkManager is only a wake-up mechanism. The worker performs the
        // stricter validated-Wi-Fi check immediately before opening HTTP.
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true)
        .build()

    private fun localVerificationConstraints(): Constraints = Constraints.Builder()
        .setRequiresStorageNotLow(true)
        .build()

    private fun cleanupOrphanedDownload(tracked: PersistedModelDownload?) {
        if (tracked != null && tracked.downloadId > 0L) downloadTransport.remove(tracked.downloadId)
        downloadStateStore.clear()
        workManager.cancelUniqueWork(DOWNLOAD_WORK_NAME)
        workManager.cancelUniqueWork(RESUME_WORK_NAME)
        storage.deleteCandidates()
        storageBudget.release(DownloadLane.GEMMA)
    }

    private suspend fun preserveOrphanedCandidate() {
        val candidate = getDownloadCandidateFile()
        if (candidate.isFile) {
            storage.snapshotCandidate(candidate, getResumeModelFile(), candidate.length())
            candidate.delete()
        }
    }

    private fun hasCandidateFiles(): Boolean {
        val candidate = getDownloadCandidateFile()
        val candidatePartial = File(candidate.parentFile, "${candidate.name}.part")
        return candidate.isFile ||
            candidatePartial.isFile ||
            getLegacyDownloadCandidateFile()?.isFile == true ||
            getResumeModelFile().isFile ||
            getPartialModelFile().isFile
    }

    private fun hasCompleteCandidate(): Boolean {
        val expectedBytes = officialModelContract.sizeBytes
        return listOfNotNull(
            getDownloadCandidateFile(),
            getLegacyDownloadCandidateFile()
        ).any { it.isFile && it.length() == expectedBytes }
    }

    private fun migrateLegacyDownload(download: PersistedModelDownload) {
        // A completed legacy DownloadManager entry may already have a complete
        // candidate at the canonical destination. Leave that file in place for
        // the WorkManager worker to verify; only abandon the old state contract.
        if (download.downloadId > 0L) {
            downloadTransport.query(download.downloadId)
        }
    }

    private fun requiredDownloadBytes(): Long {
        val existing = getModelFile().takeIf(File::isFile)?.length() ?: 0L
        return MODEL_SIZE_BYTES + existing + STORAGE_SAFETY_MARGIN_BYTES
    }

    private fun reserveGemmaStorage(workId: String) {
        val requiredBytes = requiredDownloadBytes()
        val snapshot = when (val storage = storageSpaceProvider.measure(getModelDir(), requiredBytes)) {
            is StorageSpaceResult.Available -> storage.snapshot
            is StorageSpaceResult.Unavailable -> error(
                "Gemma storage path unavailable: ${storage.reason}"
            )
        }
        when (
            val result = storageBudget.tryReserve(
                lane = DownloadLane.GEMMA,
                requiredBytes = snapshot.requiredBytes,
                availableBytes = snapshot.availableBytes,
                volumeId = snapshot.volumeId,
                workId = workId
            )
        ) {
            is StorageReservationResult.Reserved -> Unit
            is StorageReservationResult.Insufficient -> error(
                "Insufficient shared storage for Gemma 4 E2B. " +
                    "Free ${result.availableBytes / (1024 * 1024)} MB; " +
                    "Gemma needs at least ${result.requiredBytes / (1024 * 1024)} MB " +
                    "plus ${result.competingBytes / (1024 * 1024)} MB reserved by another download."
            )
        }
    }

    private fun adoptGemmaReservation(workId: String) {
        val storage = storageSpaceProvider.measure(getModelDir(), requiredDownloadBytes())
        if (storage is StorageSpaceResult.Available) {
            storageBudget.adopt(
                lane = DownloadLane.GEMMA,
                volumeId = storage.snapshot.volumeId,
                workId = workId,
                reservedBytes = storage.snapshot.requiredBytes
            )
        }
    }

    private fun isCompatible(download: PersistedModelDownload): Boolean =
        (download.contractSha256.isEmpty() || download.contractSha256.equals(MODEL_SHA256, ignoreCase = true)) &&
            (
                download.candidateFileName.isEmpty() ||
                    download.candidateFileName == DOWNLOAD_CANDIDATE_FILE_NAME ||
                    download.candidateFileName == LEGACY_DOWNLOAD_CANDIDATE_FILE_NAME
                )

    private fun inspect(): ModelReadiness {
        if (!hasMinimumRam()) return ModelReadiness.UnsupportedDevice("At least ${MIN_RAM_GB}GB RAM is required")
        val model = getModelFile()
        if (model.isFile && model.name == MODEL_FILE) {
            if (verifiedModelStateStore.isCurrent(model, officialModelContract)) {
                return ModelReadiness.Ready(officialMetadata())
            }
            val validation = integrityVerifier.verifyBlocking(model, requireCanonicalName = true)
            if (validation.isSuccess) verifiedModelStateStore.markVerified(model, officialModelContract)
            return validation.fold(
                onSuccess = { ModelReadiness.Ready(officialMetadata()) },
                onFailure = { ModelReadiness.Corrupted(it.message ?: "Gemma 4 E2B validation failed") }
            )
        }
        return ModelReadiness.Missing
    }

    private fun officialMetadata(): AiAssetEntry = AiAssetEntry(
        kind = AiAssetKind.LLM,
        fileName = MODEL_FILE,
        source = MODEL_SOURCE,
        license = "Apache-2.0",
        version = "gemma-4-E2B-it-litertlm",
        sizeBytes = MODEL_SIZE_BYTES,
        sha256 = MODEL_SHA256
    )

    private fun hasMinimumRam(): Boolean {
        val manager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024 * 1024) >= MIN_RAM_GB
    }

    private fun publish(value: ModelReadiness) {
        _readiness.value = value
        _isReady.value = value is ModelReadiness.Ready
    }
}
