package com.stressdetector.mobile.ml.speech

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stressdetector.mobile.di.AppLocator
import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import com.stressdetector.mobile.ml.artifacts.VerifiedArtifactDownloadManager
import com.stressdetector.mobile.ml.download.AndroidStorageSpaceProvider
import com.stressdetector.mobile.ml.download.DownloadLane
import com.stressdetector.mobile.ml.download.DownloadStorageBudget
import com.stressdetector.mobile.ml.download.ResumableDownloadFailure
import com.stressdetector.mobile.ml.download.StorageReservationResult
import com.stressdetector.mobile.ml.download.StorageSpaceProvider
import com.stressdetector.mobile.ml.download.StorageSpaceResult
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SpeechVerificationStage {
    COMPONENT_SHA256
}

enum class SpeechAssemblyStage {
    EXTRACTING,
    EXTRACTED_SHA256,
    BUILDING_ZIP
}

sealed interface SpeechBundleDownloadState {
    data object Idle : SpeechBundleDownloadState
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long, val component: String) : SpeechBundleDownloadState
    data class Verifying(
        val component: String,
        val bytesProcessed: Long,
        val totalBytes: Long,
        val stage: SpeechVerificationStage = SpeechVerificationStage.COMPONENT_SHA256
    ) : SpeechBundleDownloadState
    data class Assembling(val component: String, val bytesProcessed: Long, val totalBytes: Long, val stage: SpeechAssemblyStage) :
        SpeechBundleDownloadState
    data class Installing(val step: String) : SpeechBundleDownloadState
    data object Ready : SpeechBundleDownloadState
    data object Cancelling : SpeechBundleDownloadState
    data object Deleting : SpeechBundleDownloadState
    data object Cancelled : SpeechBundleDownloadState
    data class Failed(val message: String, val retryable: Boolean) : SpeechBundleDownloadState
}

// Work ID must be durable before reconciliation can observe the new download state.
@SuppressLint("ApplySharedPref")
private class SpeechBundleDownloadStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SpeechBundleDownloadState> = _state.asStateFlow()

    fun set(value: SpeechBundleDownloadState) {
        _state.value = value
        preferences.edit()
            .putString(KEY_STATUS, statusOf(value))
            .putLong(KEY_BYTES, bytesOf(value))
            .putLong(KEY_TOTAL, totalBytesOf(value))
            .putString(KEY_COMPONENT, componentOf(value))
            .putString(KEY_STAGE, stageOf(value))
            .putString(KEY_STEP, (value as? SpeechBundleDownloadState.Installing)?.step)
            .putString(KEY_MESSAGE, (value as? SpeechBundleDownloadState.Failed)?.message)
            .putBoolean(KEY_RETRYABLE, (value as? SpeechBundleDownloadState.Failed)?.retryable ?: false)
            .apply()
    }

    fun refreshFromDisk() {
        val persisted = read()
        if (_state.value != persisted) _state.value = persisted
    }

    fun readWorkId(): String? = preferences.getString(KEY_WORK_ID, null)

    fun setWorkId(workId: String) {
        preferences.edit().putString(KEY_WORK_ID, workId).commit()
    }

    fun clearWorkId() {
        preferences.edit().remove(KEY_WORK_ID).commit()
    }

    private fun read(): SpeechBundleDownloadState = when (preferences.getString(KEY_STATUS, null)) {
        STATUS_DOWNLOADING -> SpeechBundleDownloadState.Downloading(
            preferences.getLong(KEY_BYTES, 0L),
            preferences.getLong(KEY_TOTAL, 0L),
            preferences.getString(KEY_COMPONENT, "") ?: ""
        )
        STATUS_VERIFYING -> SpeechBundleDownloadState.Verifying(
            component = preferences.getString(KEY_COMPONENT, "Sherpa") ?: "Sherpa",
            bytesProcessed = preferences.getLong(KEY_BYTES, 0L),
            totalBytes = preferences.getLong(KEY_TOTAL, 0L),
            stage = runCatching {
                SpeechVerificationStage.valueOf(
                    preferences.getString(KEY_STAGE, SpeechVerificationStage.COMPONENT_SHA256.name)
                        ?: SpeechVerificationStage.COMPONENT_SHA256.name
                )
            }.getOrDefault(SpeechVerificationStage.COMPONENT_SHA256)
        )
        STATUS_ASSEMBLING -> SpeechBundleDownloadState.Assembling(
            component = preferences.getString(KEY_COMPONENT, "Sherpa") ?: "Sherpa",
            bytesProcessed = preferences.getLong(KEY_BYTES, 0L),
            totalBytes = preferences.getLong(KEY_TOTAL, 0L),
            stage = runCatching {
                SpeechAssemblyStage.valueOf(
                    preferences.getString(KEY_STAGE, SpeechAssemblyStage.EXTRACTING.name)
                        ?: SpeechAssemblyStage.EXTRACTING.name
                )
            }.getOrDefault(SpeechAssemblyStage.EXTRACTING)
        )
        STATUS_INSTALLING -> SpeechBundleDownloadState.Installing(
            preferences.getString(KEY_STEP, "Memvalidasi runtime Sherpa")
                ?: "Memvalidasi runtime Sherpa"
        )
        STATUS_READY -> SpeechBundleDownloadState.Ready
        STATUS_CANCELLING -> SpeechBundleDownloadState.Cancelling
        STATUS_DELETING -> SpeechBundleDownloadState.Deleting
        STATUS_CANCELLED -> SpeechBundleDownloadState.Cancelled
        STATUS_FAILED -> SpeechBundleDownloadState.Failed(
            preferences.getString(KEY_MESSAGE, "Speech bundle download failed.")
                ?: "Speech bundle download failed.",
            preferences.getBoolean(KEY_RETRYABLE, false)
        )
        else -> SpeechBundleDownloadState.Idle
    }

    private fun statusOf(value: SpeechBundleDownloadState): String = when (value) {
        SpeechBundleDownloadState.Idle -> STATUS_IDLE
        is SpeechBundleDownloadState.Downloading -> STATUS_DOWNLOADING
        is SpeechBundleDownloadState.Verifying -> STATUS_VERIFYING
        is SpeechBundleDownloadState.Assembling -> STATUS_ASSEMBLING
        is SpeechBundleDownloadState.Installing -> STATUS_INSTALLING
        SpeechBundleDownloadState.Ready -> STATUS_READY
        SpeechBundleDownloadState.Cancelling -> STATUS_CANCELLING
        SpeechBundleDownloadState.Deleting -> STATUS_DELETING
        SpeechBundleDownloadState.Cancelled -> STATUS_CANCELLED
        is SpeechBundleDownloadState.Failed -> STATUS_FAILED
    }

    private fun bytesOf(value: SpeechBundleDownloadState): Long = when (value) {
        is SpeechBundleDownloadState.Downloading -> value.bytesDownloaded
        is SpeechBundleDownloadState.Verifying -> value.bytesProcessed
        is SpeechBundleDownloadState.Assembling -> value.bytesProcessed
        else -> 0L
    }

    private fun totalBytesOf(value: SpeechBundleDownloadState): Long = when (value) {
        is SpeechBundleDownloadState.Downloading -> value.totalBytes
        is SpeechBundleDownloadState.Verifying -> value.totalBytes
        is SpeechBundleDownloadState.Assembling -> value.totalBytes
        else -> 0L
    }

    private fun componentOf(value: SpeechBundleDownloadState): String? = when (value) {
        is SpeechBundleDownloadState.Downloading -> value.component
        is SpeechBundleDownloadState.Verifying -> value.component
        is SpeechBundleDownloadState.Assembling -> value.component
        else -> null
    }

    private fun stageOf(value: SpeechBundleDownloadState): String? = when (value) {
        is SpeechBundleDownloadState.Verifying -> value.stage.name
        is SpeechBundleDownloadState.Assembling -> value.stage.name
        else -> null
    }

    private companion object {
        const val PREFERENCES = "speech_bundle_download_state"
        const val KEY_STATUS = "status"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_COMPONENT = "component"
        const val KEY_STAGE = "stage"
        const val KEY_STEP = "step"
        const val KEY_MESSAGE = "message"
        const val KEY_RETRYABLE = "retryable"
        const val KEY_WORK_ID = "work_id"
        const val STATUS_IDLE = "idle"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_VERIFYING = "verifying"
        const val STATUS_ASSEMBLING = "assembling"
        const val STATUS_INSTALLING = "installing"
        const val STATUS_READY = "ready"
        const val STATUS_CANCELLING = "cancelling"
        const val STATUS_DELETING = "deleting"
        const val STATUS_CANCELLED = "cancelled"
        const val STATUS_FAILED = "failed"
    }
}

class SpeechBundleDownloadManager(
    context: Context,
    private val bundleManager: SpeechModelBundleManager,
    private val descriptor: SpeechBundleDescriptor,
    private val storageSpaceProvider: StorageSpaceProvider = AndroidStorageSpaceProvider(context)
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val stateStore = SpeechBundleDownloadStateStore(appContext)
    private val storageBudget = DownloadStorageBudget(appContext)
    private val reconciliationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()

    val state: StateFlow<SpeechBundleDownloadState> = stateStore.state

    val totalDownloadBytes: Long = descriptor.totalDownloadBytes
    val sourceLicenseDisclosure: String = descriptor.components.joinToString(" • ") { component ->
        "${component.id}: ${component.license} (${component.upstreamSource})"
    }

    init {
        reconciliationScope.launch {
            while (currentCoroutineContext().isActive) {
                reconcile()
                delay(RECONCILIATION_INTERVAL_MS)
            }
        }
    }

    suspend fun enqueue(): Boolean = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            reconcileLocked()
            bundleManager.clearDeletionRequest()
            val existing = findCurrentWorkInfo()
            if (existing != null && !existing.state.isFinished) {
                stateStore.setWorkId(existing.id.toString())
                adoptActiveReservation(existing.id.toString())
                if (stateStore.state.value !is SpeechBundleDownloadState.Cancelling &&
                    stateStore.state.value !is SpeechBundleDownloadState.Deleting
                ) {
                    publishQueuedStateIfNeeded()
                }
                return@withLock false
            }
            if (isComplete(bundleManager.readiness.value)) {
                stateStore.set(SpeechBundleDownloadState.Ready)
                stateStore.clearWorkId()
                storageBudget.release(DownloadLane.SHARED_SPEECH)
                return@withLock false
            }

            val request = OneTimeWorkRequestBuilder<SpeechBundleDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(WORK_TAG)
                .build()
            val requiredBytes = descriptor.requiredFreeStorageBytes
            val storage = storageSpaceProvider.measure(bundleManager.runtimeRoot(), requiredBytes)
            val snapshot = when (storage) {
                is StorageSpaceResult.Available -> storage.snapshot
                is StorageSpaceResult.Unavailable -> failToStart(
                    "Sherpa storage path unavailable: ${storage.reason}"
                )
            }
            when (
                val reservation = storageBudget.tryReserve(
                    lane = DownloadLane.SHARED_SPEECH,
                    requiredBytes = snapshot.requiredBytes,
                    availableBytes = snapshot.availableBytes,
                    volumeId = snapshot.volumeId,
                    workId = request.id.toString()
                )
            ) {
                is StorageReservationResult.Reserved -> Unit
                is StorageReservationResult.Insufficient -> failToStart(
                    "Free storage is insufficient for Sherpa Speech Bundle. " +
                        "Free ${reservation.availableBytes / (1024 * 1024)} MB; " +
                        "Sherpa needs at least ${reservation.requiredBytes / (1024 * 1024)} MB " +
                        "plus ${reservation.competingBytes / (1024 * 1024)} MB reserved by another download."
                )
            }

            try {
                workManager.enqueueUniqueWork(workName(), ExistingWorkPolicy.KEEP, request)
                val infos = workManager.getWorkInfosForUniqueWorkFlow(workName()).first()
                val actual = infos.firstOrNull { it.id == request.id }
                    ?: infos.firstOrNull { !it.state.isFinished }
                if (actual == null) {
                    throw IllegalStateException("WorkManager did not publish the Sherpa download request.")
                }
                if (actual.id != request.id) {
                    storageBudget.release(DownloadLane.SHARED_SPEECH, request.id.toString())
                    if (!actual.state.isFinished) {
                        adoptActiveReservation(actual.id.toString())
                        publishQueuedStateIfNeeded()
                        return@withLock false
                    }
                    throw IllegalStateException("WorkManager replaced the Sherpa download request unexpectedly.")
                }
                stateStore.setWorkId(request.id.toString())
                stateStore.set(SpeechBundleDownloadState.Downloading(0L, descriptor.totalDownloadBytes, "queued"))
                true
            } catch (error: Exception) {
                storageBudget.release(DownloadLane.SHARED_SPEECH, request.id.toString())
                val message = "Sherpa download could not be queued: ${error.message ?: "WorkManager error"}"
                stateStore.set(SpeechBundleDownloadState.Failed(message, retryable = true))
                throw IllegalStateException(message, error)
            }
        }
    }

    private fun publishQueuedStateIfNeeded() {
        stateStore.refreshFromDisk()
        if (stateStore.state.value !is SpeechBundleDownloadState.Downloading &&
            stateStore.state.value !is SpeechBundleDownloadState.Verifying &&
            stateStore.state.value !is SpeechBundleDownloadState.Assembling &&
            stateStore.state.value !is SpeechBundleDownloadState.Installing
        ) {
            stateStore.set(SpeechBundleDownloadState.Downloading(0L, descriptor.totalDownloadBytes, "queued"))
        }
    }

    private fun adoptActiveReservation(workId: String) {
        val storage = storageSpaceProvider.measure(bundleManager.runtimeRoot(), descriptor.requiredFreeStorageBytes)
        if (storage is StorageSpaceResult.Available) {
            storageBudget.adopt(
                lane = DownloadLane.SHARED_SPEECH,
                volumeId = storage.snapshot.volumeId,
                workId = workId,
                reservedBytes = storage.snapshot.requiredBytes
            )
        }
    }

    private suspend fun findCurrentWorkInfo(): WorkInfo? {
        val infos = workManager.getWorkInfosForUniqueWorkFlow(workName()).first()
        val persistedWorkId = stateStore.readWorkId()
        return infos.firstOrNull { it.id.toString() == persistedWorkId }
            ?: infos.firstOrNull { !it.state.isFinished }
    }

    private fun failToStart(message: String): Nothing {
        stateStore.set(SpeechBundleDownloadState.Failed(message, retryable = false))
        throw IllegalStateException(message)
    }

    suspend fun reconcile() = withContext(Dispatchers.IO) {
        operationMutex.withLock { reconcileLocked() }
    }

    private suspend fun reconcileLocked() {
        stateStore.refreshFromDisk()
        val work = findCurrentWorkInfo()
        val activeWork = work?.takeUnless { it.state.isFinished }
        var readiness = bundleManager.readiness.value
        if (shouldRevalidateIdleSpeechBundle(readiness, hasActiveWork = activeWork != null)) {
            readiness = bundleManager.refreshReadiness()
        }

        when {
            activeWork != null -> {
                stateStore.setWorkId(activeWork.id.toString())
                adoptActiveReservation(activeWork.id.toString())
                if (stateStore.state.value is SpeechBundleDownloadState.Ready ||
                    stateStore.state.value is SpeechBundleDownloadState.Idle
                ) {
                    publishQueuedStateIfNeeded()
                }
            }
            isComplete(readiness) -> {
                stateStore.set(SpeechBundleDownloadState.Ready)
                stateStore.clearWorkId()
                storageBudget.release(DownloadLane.SHARED_SPEECH)
            }
            work == null -> reconcileWithoutWork(readiness)
            work.state == WorkInfo.State.SUCCEEDED -> {
                stateStore.set(
                    SpeechBundleDownloadState.Failed(
                        "Sherpa download selesai tetapi bundle aktif belum lolos validasi lengkap.",
                        retryable = false
                    )
                )
                storageBudget.release(DownloadLane.SHARED_SPEECH, work.id.toString())
            }
            work.state == WorkInfo.State.CANCELLED -> {
                stateStore.set(SpeechBundleDownloadState.Cancelled)
                stateStore.clearWorkId()
                storageBudget.release(DownloadLane.SHARED_SPEECH, work.id.toString())
            }
            else -> {
                if (stateStore.state.value !is SpeechBundleDownloadState.Failed) {
                    stateStore.set(
                        SpeechBundleDownloadState.Failed(
                            work.outputData.getString(SpeechBundleDownloadWorker.KEY_ERROR)
                                ?: "Sherpa Speech Bundle download failed.",
                            retryable = true
                        )
                    )
                }
                storageBudget.release(DownloadLane.SHARED_SPEECH, work.id.toString())
            }
        }
    }

    private fun reconcileWithoutWork(readiness: SpeechBundleReadiness) {
        when (stateStore.state.value) {
            is SpeechBundleDownloadState.Downloading,
            is SpeechBundleDownloadState.Verifying,
            is SpeechBundleDownloadState.Assembling,
            is SpeechBundleDownloadState.Installing,
            SpeechBundleDownloadState.Cancelling,
            SpeechBundleDownloadState.Deleting -> stateStore.set(
                SpeechBundleDownloadState.Failed(
                    "Sherpa download state was orphaned after restart. Tap Retry to continue safely.",
                    retryable = true
                )
            )
            SpeechBundleDownloadState.Idle,
            SpeechBundleDownloadState.Ready -> if (readiness is SpeechBundleReadiness.Ready) {
                stateStore.set(SpeechBundleDownloadState.Ready)
            }
            else -> Unit
        }
        storageBudget.release(DownloadLane.SHARED_SPEECH)
    }

    fun cancel() {
        reconciliationScope.launch {
            operationMutex.withLock {
                bundleManager.requestDeletion()
                stateStore.set(SpeechBundleDownloadState.Cancelling)
                workManager.cancelUniqueWork(workName())
            }
        }
    }

    fun deletePartialDownload() {
        reconciliationScope.launch { deletePartialDownloadAndWait() }
    }

    suspend fun deletePartialDownloadAndWait() = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            bundleManager.requestDeletion()
            stateStore.set(SpeechBundleDownloadState.Deleting)
            workManager.cancelUniqueWork(workName())
        }
        awaitWorkTerminal()
        operationMutex.withLock {
            bundleManager.downloadRoot().deleteRecursively()
            stateStore.set(SpeechBundleDownloadState.Idle)
            stateStore.clearWorkId()
            storageBudget.release(DownloadLane.SHARED_SPEECH)
        }
    }

    private suspend fun awaitWorkTerminal() {
        while (currentCoroutineContext().isActive) {
            val work = findCurrentWorkInfo()
            if (work == null || work.state.isFinished) return
            delay(WORK_TERMINAL_POLL_INTERVAL_MS)
        }
    }

    private fun isComplete(readiness: SpeechBundleReadiness): Boolean = isCurrentSpeechBundleComplete(readiness, descriptor)

    companion object {
        const val WORK_TAG = "speech-bundle"
        private const val RECONCILIATION_INTERVAL_MS = 1_000L
        private const val WORK_TERMINAL_POLL_INTERVAL_MS = 100L
    }

    private fun workName(): String = "artifact-speech-bundle-${descriptor.bundleVersion}"
}

class SpeechBundleDownloadWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    private val descriptor = AppLocator.speechBundleDescriptor(applicationContext)
    private val catalog = AppLocator.localArtifactCatalog(applicationContext)
    private val bundleManager = AppLocator.speechBundleManager(applicationContext)
    private val stateStore = SpeechBundleDownloadStateStore(applicationContext)
    private val downloader = VerifiedArtifactDownloadManager()
    private val assembler = SpeechBundleArchiveAssembler()
    private val storageBudget = DownloadStorageBudget(applicationContext)
    private val runtime = AppLocator.speechRuntime(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val workId = id.toString()
        try {
            setForeground(createForegroundInfo("Downloading Sherpa Speech Bundle"))
            val downloaded = linkedMapOf<String, File>()
            var completedBytes = 0L
            descriptor.components.forEach { component ->
                val destination = File(bundleManager.downloadRoot(), "${descriptor.bundleVersion}/${component.downloadFileName}")
                val artifact = catalog.requireById(component.id)
                val payload = artifact.files.single { it.role == "download" }
                stateStore.set(SpeechBundleDownloadState.Downloading(completedBytes, descriptor.totalDownloadBytes, component.id))
                downloader.download(artifact, payload, destination, onProgress = { componentBytes ->
                    val total = completedBytes + componentBytes
                    stateStore.set(SpeechBundleDownloadState.Downloading(total, descriptor.totalDownloadBytes, component.id))
                    setProgress(workDataOf(KEY_BYTES to total, KEY_TOTAL to descriptor.totalDownloadBytes, KEY_COMPONENT to component.id))
                }, onVerificationProgress = { progress ->
                    stateStore.set(
                        SpeechBundleDownloadState.Verifying(
                            component = component.id,
                            bytesProcessed = progress.bytesProcessed,
                            totalBytes = progress.totalBytes
                        )
                    )
                })
                downloaded[component.id] = destination
                completedBytes += component.expectedDownloadBytes
            }
            val candidate = File(bundleManager.downloadRoot(), "${descriptor.bundleVersion}/sherpa_bundle.zip")
            val manifest = assembler.assemble(
                descriptor = descriptor,
                downloadedFiles = downloaded,
                destination = candidate
            ) { progress ->
                stateStore.set(
                    SpeechBundleDownloadState.Assembling(
                        component = progress.component,
                        bytesProcessed = progress.bytesProcessed,
                        totalBytes = progress.totalBytes,
                        stage = progress.stage
                    )
                )
            }
            stateStore.set(SpeechBundleDownloadState.Installing("Memvalidasi runtime Sherpa"))
            when (
                val readiness = bundleManager.installBundleFileWithRuntimeValidation(candidate) { manifest ->
                    when (val runtimeState = runtime.preflight(manifest)) {
                        SpeechRuntimeState.Ready -> Unit
                        SpeechRuntimeState.Checking -> error(
                            "Sherpa runtime masih diperiksa. Coba lagi sebentar."
                        )
                        is SpeechRuntimeState.Unavailable -> error(
                            "Sherpa runtime belum tersedia: ${runtimeState.reason}"
                        )
                        is SpeechRuntimeState.Failed -> error(
                            "Sherpa runtime gagal divalidasi: ${runtimeState.message}"
                        )
                    }
                }
            ) {
                is SpeechBundleReadiness.Ready -> {
                    bundleManager.downloadRoot().deleteRecursively()
                    stateStore.set(SpeechBundleDownloadState.Ready)
                    storageBudget.release(DownloadLane.SHARED_SPEECH, workId)
                    Result.success(workDataOf(KEY_BUNDLE_VERSION to manifest.bundleVersion))
                }
                is SpeechBundleReadiness.Invalid -> failure(readiness.reason, retryable = false)
                SpeechBundleReadiness.Checking -> failure("Speech bundle readiness is still being checked.", retryable = true)
                SpeechBundleReadiness.Missing -> failure("Sherpa bundle activation failed.", retryable = false)
            }
        } catch (error: CancellationException) {
            stateStore.set(SpeechBundleDownloadState.Cancelled)
            storageBudget.release(DownloadLane.SHARED_SPEECH, workId)
            throw error
        } catch (error: ResumableDownloadFailure) {
            stateStore.set(SpeechBundleDownloadState.Failed(error.message ?: "Speech download failed.", error.retryable))
            if (error.retryable) {
                Result.retry()
            } else {
                storageBudget.release(DownloadLane.SHARED_SPEECH, workId)
                Result.failure()
            }
        } catch (error: Exception) {
            stateStore.set(SpeechBundleDownloadState.Failed(error.message ?: "Speech bundle installation failed.", false))
            storageBudget.release(DownloadLane.SHARED_SPEECH, workId)
            Result.failure()
        }
    }

    private fun failure(message: String, retryable: Boolean): Result {
        stateStore.set(SpeechBundleDownloadState.Failed(message, retryable))
        if (!retryable) storageBudget.release(DownloadLane.SHARED_SPEECH, id.toString())
        return if (retryable) Result.retry() else Result.failure(workDataOf(KEY_ERROR to message))
    }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sherpa speech download", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Sherpa Speech Bundle")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        return ForegroundInfo(NOTIFICATION_ID, notification, serviceType)
    }

    companion object {
        const val KEY_BYTES = "speech_bytes"
        const val KEY_TOTAL = "speech_total"
        const val KEY_COMPONENT = "speech_component"
        const val KEY_BUNDLE_VERSION = "speech_bundle_version"
        const val KEY_ERROR = "speech_error"
        private const val NOTIFICATION_ID = 2015
        private const val CHANNEL_ID = "speech_bundle_download_channel"
    }
}
