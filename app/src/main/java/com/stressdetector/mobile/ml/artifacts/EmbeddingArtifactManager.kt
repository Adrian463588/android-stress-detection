package com.stressdetector.mobile.ml.artifacts

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
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stressdetector.mobile.di.AppLocator
import com.stressdetector.mobile.ml.rag.E5EmbeddingRuntimeContract
import com.stressdetector.mobile.ml.rag.EmbeddingResult
import com.stressdetector.mobile.ml.rag.LocalEmbeddingModel
import com.stressdetector.mobile.ml.storage.ModelStorageManager
import com.stressdetector.mobile.ml.storage.ModelStorageSlot
import com.stressdetector.mobile.ml.storage.SafGenerationPayload
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed interface ArtifactState {
    data object Missing : ArtifactState
    data object Queued : ArtifactState
    data class Downloading(val bytes: Long, val totalBytes: Long, val fileName: String) : ArtifactState
    data object Verifying : ArtifactState
    data object Preflighting : ArtifactState
    data class Ready(val mirrorState: MirrorState) : ArtifactState
    data class RetryableFailure(val reason: String) : ArtifactState
    data class Corrupt(val reason: String) : ArtifactState
    data class RuntimeIncompatible(val reason: String) : ArtifactState
}

enum class MirrorState { IN_SYNC, LOCAL_ONLY, SAF_ONLY, MISSING, CORRUPT, OUTDATED }

/** Owns download, runtime preflight, app-private activation, and SAF mirroring for E5. */
class EmbeddingArtifactManager(
    context: Context,
    private val catalog: LocalArtifactCatalog,
    private val durableStorage: ModelStorageManager
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val artifact = catalog.require(LocalArtifactId.MULTILINGUAL_E5_SMALL)
    private val mutableState = MutableStateFlow<ArtifactState>(ArtifactState.Missing)
    val state: StateFlow<ArtifactState> = mutableState.asStateFlow()

    private val root = File(appContext.filesDir, "local_artifacts/multilingual-e5-small")
    private val active = File(root, "active-${artifact.revision.take(12)}")
    private val candidate = File(root, ".candidate-${artifact.revision.take(12)}")

    fun modelFile(): File = File(active, MODEL_FILE_NAME)
    fun tokenizerFile(): File = File(active, TOKENIZER_FILE_NAME)
    fun runtimeContract(): E5EmbeddingRuntimeContract = E5EmbeddingRuntimeContract.from(artifact)

    suspend fun reconcile(): ArtifactState = withContext(Dispatchers.IO) {
        // SAF is the durable source of truth after reinstall. Do not require a
        // canonical completion marker before looking for a verified legacy tree.
        if (!validateActive() && durableStorage.state.value.connected) restoreFromSaf()
        val result = when {
            !validateActive() -> ArtifactState.Missing
            !preflightActive() -> ArtifactState.RuntimeIncompatible("Embedding ONNX/tokenizer gagal preflight.")
            durableStorage.state.value.connected && mirrorComplete() -> ArtifactState.Ready(MirrorState.IN_SYNC)
            else -> ArtifactState.Ready(MirrorState.LOCAL_ONLY)
        }
        publish(result)
        result
    }

    fun enqueue() {
        // Downloads are explicit recovery actions. A ready or runtime-incompatible
        // artifact must never enter a WorkManager download loop during reconciliation.
        if (state.value !is ArtifactState.Missing && state.value !is ArtifactState.Corrupt) return
        val request = OneTimeWorkRequestBuilder<EmbeddingArtifactWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(workName())
            .build()
        workManager.enqueueUniqueWork(workName(), ExistingWorkPolicy.KEEP, request)
        publish(ArtifactState.Queued)
    }

    fun discardPartial() {
        workManager.cancelUniqueWork(workName())
        candidate.deleteRecursively()
        publish(if (validateActive()) ArtifactState.Ready(MirrorState.LOCAL_ONLY) else ArtifactState.Missing)
    }

    internal fun artifact(): CatalogArtifactEntry = artifact
    internal fun candidateDirectory(): File = candidate

    internal fun publish(value: ArtifactState) {
        mutableState.value = value
    }

    internal suspend fun activateCandidate(): ArtifactState {
        publish(ArtifactState.Verifying)
        require(validateDirectory(candidate)) { "Embedding candidate does not match the catalog." }
        publish(ArtifactState.Preflighting)
        LocalEmbeddingModel(
            appContext,
            File(candidate, MODEL_FILE_NAME),
            File(candidate, TOKENIZER_FILE_NAME),
            runtimeContract()
        ).use {
            it.preflight().getOrThrow()
        }
        root.mkdirs()
        val previous = File(root, ".active.previous")
        previous.deleteRecursively()
        if (active.exists()) check(active.renameTo(previous)) { "Active embedding backup failed." }
        try {
            check(candidate.renameTo(active)) { "Embedding activation could not be published atomically." }
            previous.deleteRecursively()
        } catch (error: Throwable) {
            active.deleteRecursively()
            previous.renameTo(active)
            throw error
        }
        val mirror = syncToSaf().fold(
            onSuccess = { MirrorState.IN_SYNC },
            onFailure = {
                durableStorage.reportError("Embedding lokal siap, tetapi mirror SAF gagal: ${it.message}")
                MirrorState.LOCAL_ONLY
            }
        )
        enqueueReindex()
        return ArtifactState.Ready(mirror).also(::publish)
    }

    suspend fun syncToSaf(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!durableStorage.state.value.connected || !validateActive()) return@withContext Result.success(Unit)
        runCatching {
            durableStorage.publishGeneration(
                slot = ModelStorageSlot.MULTILINGUAL_EMBEDDING,
                generation = durableGeneration(),
                version = artifact.version,
                revision = artifact.revision,
                payloads = artifact.files.map { contract ->
                    val source = activeFile(contract)
                    SafGenerationPayload(
                        source = source,
                        fileName = source.name,
                        expectedSizeBytes = contract.sizeBytes,
                        expectedSha256 = contract.sha256
                    )
                }
            ).getOrThrow()
        }
    }

    private suspend fun restoreFromSaf() {
        if (!durableStorage.state.value.connected) return
        active.mkdirs()
        val restored = artifact.files.all { contract ->
            val target = activeFile(contract)
            durableStorage.restoreArtifactIfMissing(
                ModelStorageSlot.MULTILINGUAL_EMBEDDING,
                target.name,
                target,
                contract.sizeBytes,
                contract.sha256,
                durableGeneration()
            )
        }
        if (!restored || !validateActive() || !preflightActive()) active.deleteRecursively()
    }

    private suspend fun mirrorComplete(): Boolean = durableStorage.readText(
        ModelStorageSlot.MULTILINGUAL_EMBEDDING,
        COMPLETE_MANIFEST,
        durableGeneration()
    )
        ?.contains("\"revision\":\"${artifact.revision}\"") == true

    private fun durableGeneration(): String = "${artifact.version}-${artifact.files.first().sha256.take(12)}"

    private fun validateActive(): Boolean = validateDirectory(active)

    private fun validateDirectory(directory: File): Boolean = artifact.files.all { contract ->
        val file = when (contract.path.substringAfterLast('/')) {
            "model.onnx" -> File(directory, MODEL_FILE_NAME)
            "tokenizer.json" -> File(directory, TOKENIZER_FILE_NAME)
            else -> return@all false
        }
        file.isFile && file.length() == contract.sizeBytes && sha256(file).equals(contract.sha256, true)
    }

    private fun preflightActive(): Boolean = LocalEmbeddingModel(
        appContext,
        modelFile(),
        tokenizerFile(),
        runtimeContract()
    ).use { it.preflight().isSuccess }

    private fun activeFile(contract: CatalogArtifactFile): File = when (contract.path.substringAfterLast('/')) {
        "model.onnx" -> modelFile()
        "tokenizer.json" -> tokenizerFile()
        else -> error("Unexpected embedding file ${contract.path}")
    }

    private fun sha256(file: File): String = java.security.MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun workName() = "artifact-${artifact.id}-${artifact.version}"

    private fun enqueueReindex() {
        val request = OneTimeWorkRequestBuilder<EmbeddingReindexWorker>()
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            "embedding-reindex-${artifact.version}",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        const val MODEL_FILE_NAME = "model.onnx"
        const val TOKENIZER_FILE_NAME = "tokenizer.json"
        const val COMPLETE_MANIFEST = "complete-manifest.json"
    }
}

class EmbeddingReindexWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val manager = AppLocator.embeddingArtifactManager(applicationContext)
        if (manager.reconcile() !is ArtifactState.Ready) return@withContext Result.retry()
        val dao = AppLocator.database(applicationContext).documentDao()
        LocalEmbeddingModel(applicationContext, manager.modelFile(), manager.tokenizerFile()).use { embedder ->
            if (embedder.preflight().isFailure) return@withContext Result.retry()
            var offset = 0
            while (true) {
                val page = dao.getChunksPage(REINDEX_PAGE_SIZE, offset)
                if (page.isEmpty()) break
                val updated = page.mapNotNull { chunk ->
                    if (chunk.embeddingVersion == LocalEmbeddingModel.VERSION && chunk.embeddingBlob != null) {
                        null
                    } else {
                        when (val result = embedder.embedPassage(chunk.textContent)) {
                            is EmbeddingResult.Success -> chunk.copy(
                                embeddingBlob = LocalEmbeddingModel.floatArrayToByteArray(result.vector),
                                embeddingVersion = LocalEmbeddingModel.VERSION
                            )
                            is EmbeddingResult.Unavailable -> return@withContext Result.retry()
                            is EmbeddingResult.Failed -> null
                        }
                    }
                }
                if (updated.isNotEmpty()) dao.insertChunks(updated)
                offset += page.size
                if (page.size < REINDEX_PAGE_SIZE) break
            }
        }
        Result.success()
    }

    private companion object {
        const val REINDEX_PAGE_SIZE = 32
    }
}

class EmbeddingArtifactWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val manager = AppLocator.embeddingArtifactManager(applicationContext)
    private val downloader = VerifiedArtifactDownloadManager()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        setForeground(foregroundInfo())
        val artifact = manager.artifact()
        val candidate = manager.candidateDirectory().also { it.mkdirs() }
        var completed = 0L
        val total = artifact.files.sumOf(CatalogArtifactFile::sizeBytes)
        try {
            artifact.files.forEach { contract ->
                val destination = File(candidate, contract.path.substringAfterLast('/'))
                downloader.download(artifact, contract, destination, onProgress = { current ->
                    manager.publish(ArtifactState.Downloading(completed + current, total, destination.name))
                })
                completed += contract.sizeBytes
            }
            manager.activateCandidate()
            Result.success()
        } catch (error: com.stressdetector.mobile.ml.download.ResumableDownloadFailure) {
            manager.publish(
                if (error.retryable) {
                    ArtifactState.RetryableFailure(error.message ?: "Embedding download failed.")
                } else {
                    ArtifactState.Corrupt(error.message ?: "Embedding verification failed.")
                }
            )
            if (error.retryable) Result.retry() else Result.failure()
        } catch (error: Exception) {
            manager.publish(ArtifactState.RuntimeIncompatible(error.message ?: "Embedding activation failed."))
            Result.failure()
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Embedding model download", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Multilingual E5")
            .setContentText("Downloading verified local embedding model")
            .setOngoing(true)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        return ForegroundInfo(NOTIFICATION_ID, notification, serviceType)
    }

    private companion object {
        const val CHANNEL_ID = "embedding_artifact_download"
        const val NOTIFICATION_ID = 2016
    }
}
