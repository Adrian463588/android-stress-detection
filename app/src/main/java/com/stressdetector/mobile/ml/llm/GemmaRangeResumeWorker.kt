package com.stressdetector.mobile.ml.llm

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Network
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stressdetector.mobile.di.AppLocator
import com.stressdetector.mobile.ml.artifacts.LocalArtifactId
import com.stressdetector.mobile.ml.artifacts.VerifiedArtifactDownloadManager
import com.stressdetector.mobile.ml.download.DownloadLane
import com.stressdetector.mobile.ml.download.DownloadStorageBudget
import com.stressdetector.mobile.ml.download.ResumableDownloadFailure
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Canonical Gemma transfer, verification, and atomic activation worker. */
class GemmaRangeResumeWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val NOTIFICATION_ID = 2005
        private const val CHANNEL_ID = "model_download_channel"
    }

    private val modelManager = AppLocator.modelManager(applicationContext)
    private val downloader = VerifiedArtifactDownloadManager()
    private val storageBudget = DownloadStorageBudget(applicationContext)
    private val networkEvaluator = AndroidGemmaNetworkEvaluator(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val workId = id.toString()
        try {
            val destination = prepareDestination()
            val candidateIsComplete = destination.isFile &&
                destination.length() == ModelManager.MODEL_SIZE_BYTES
            if (!candidateIsComplete) {
                val network = networkEvaluator.evaluate()
                if (!network.mayDownload) {
                    setProgress(
                        workDataOf(
                            ModelManager.PROGRESS_STAGE to "WaitingForNetwork",
                            ModelManager.PROGRESS_NETWORK_STATE to network.state.name,
                            ModelManager.PROGRESS_ERROR to network.userMessage
                        )
                    )
                    return@withContext Result.retry()
                }
                setForeground(createForegroundInfo("Downloading Gemma 4 E2B"))
                val transferNetwork = networkEvaluator.validatedWifiNetwork()
                if (transferNetwork == null) {
                    val latest = networkEvaluator.evaluate()
                    setProgress(
                        workDataOf(
                            ModelManager.PROGRESS_STAGE to "WaitingForNetwork",
                            ModelManager.PROGRESS_NETWORK_STATE to latest.state.name,
                            ModelManager.PROGRESS_ERROR to
                                "Validasi Wi-Fi berubah sebelum transfer dimulai. ${latest.userMessage}"
                        )
                    )
                    return@withContext Result.retry()
                }
                downloadOrReuse(destination, transferNetwork)
            } else {
                setProgress(
                    workDataOf(
                        ModelManager.PROGRESS_STAGE to "Verifying",
                        ModelManager.PROGRESS_BYTES to destination.length(),
                        ModelManager.PROGRESS_TOTAL to ModelManager.MODEL_SIZE_BYTES
                    )
                )
            }

            setProgress(
                workDataOf(
                    ModelManager.PROGRESS_STAGE to "Verifying",
                    ModelManager.PROGRESS_BYTES to 0L,
                    ModelManager.PROGRESS_TOTAL to ModelManager.MODEL_SIZE_BYTES
                )
            )
            if (!candidateIsComplete) {
                setForeground(createForegroundInfo("Verifying Gemma 4 E2B checksum"))
            }
            val readiness = modelManager.verifyAndPublishDownloadedModel(
                candidate = destination,
                onProgress = { progress ->
                    currentCoroutineContext().ensureActive()
                    setProgress(
                        workDataOf(
                            ModelManager.PROGRESS_STAGE to "Verifying",
                            ModelManager.PROGRESS_BYTES to progress.bytesProcessed,
                            ModelManager.PROGRESS_TOTAL to progress.totalBytes
                        )
                    )
                },
                onStage = { stage ->
                    val label = when (stage) {
                        ModelDownloadStage.PREFLIGHTING -> "Preflighting"
                        ModelDownloadStage.PUBLISHING -> "Publishing"
                        else -> stage.name.lowercase().replaceFirstChar(Char::uppercase)
                    }
                    if (!candidateIsComplete) {
                        setForeground(createForegroundInfo("$label Gemma 4 E2B"))
                    }
                    setProgress(
                        workDataOf(
                            ModelManager.PROGRESS_STAGE to label,
                            ModelManager.PROGRESS_BYTES to ModelManager.MODEL_SIZE_BYTES,
                            ModelManager.PROGRESS_TOTAL to ModelManager.MODEL_SIZE_BYTES
                        )
                    )
                }
            )
            when (readiness) {
                is ModelReadiness.Ready -> {
                    storageBudget.release(DownloadLane.GEMMA, workId)
                    setProgress(workDataOf(ModelManager.PROGRESS_STAGE to "Ready"))
                    Result.success()
                }
                is ModelReadiness.UnsupportedDevice -> failure(readiness.reason, retryable = false)
                is ModelReadiness.Corrupted -> {
                    if (readiness.candidateShouldBeDeleted) modelManager.discardInvalidCandidate()
                    failure(readiness.reason, retryable = false)
                }
                is ModelReadiness.Untrusted -> {
                    modelManager.discardInvalidCandidate()
                    failure(readiness.reason, retryable = false)
                }
                ModelReadiness.Missing -> failure("Gemma 4 E2B candidate file is missing.", retryable = false)
            }
        } catch (cancelled: CancellationException) {
            // The downloader keeps its .part file. The next enqueue can resume it.
            storageBudget.release(DownloadLane.GEMMA, workId)
            throw cancelled
        } catch (error: ResumableDownloadFailure) {
            if (error.retryable) {
                modelManager.markTransferRetry(error.message ?: "Transfer Gemma akan dicoba lagi.")
                setProgress(
                    workDataOf(
                        ModelManager.PROGRESS_STAGE to "Retrying",
                        ModelManager.PROGRESS_ERROR to (error.message ?: "Transfer Gemma akan dicoba lagi."),
                        ModelManager.PROGRESS_BYTES to modelManager.getDownloadCandidateFile().length(),
                        ModelManager.PROGRESS_TOTAL to ModelManager.MODEL_SIZE_BYTES
                    )
                )
                Result.retry()
            } else {
                failure(error.message ?: "Gemma 4 E2B download failed.", retryable = false)
            }
        } catch (error: LinkageError) {
            // Keep ABI/linkage failures recoverable and visible instead of crashing WorkManager.
            failure(error.message ?: "Gemma 4 E2B runtime dependency is incompatible.", retryable = false)
        } catch (error: Exception) {
            failure(error.message ?: "Gemma 4 E2B download failed.", retryable = false)
        }
    }

    private suspend fun downloadOrReuse(destination: File, network: Network?) {
        val artifact = AppLocator.localArtifactCatalog(applicationContext)
            .require(LocalArtifactId.GEMMA_4_E2B)
        val payload = artifact.files.single()
        downloader.download(
            artifact = artifact,
            file = payload,
            destination = destination,
            network = network,
            onProgress = { downloaded ->
                currentCoroutineContext().ensureActive()
                setProgress(
                    workDataOf(
                        ModelManager.PROGRESS_STAGE to "Downloading",
                        ModelManager.PROGRESS_BYTES to downloaded,
                        ModelManager.PROGRESS_TOTAL to ModelManager.MODEL_SIZE_BYTES
                    )
                )
            }
        )
    }

    private fun prepareDestination(): File {
        val destination = modelManager.getDownloadCandidateFile()
        val partial = File(destination.parentFile, "${destination.name}.part")
        val legacyCandidate = modelManager.getLegacyDownloadCandidateFile()
        val resume = modelManager.getResumeModelFile()
        val legacy = modelManager.getPartialModelFile()

        // Older builds used `*.download.part`, which LiteRT-LM cannot dispatch
        // as a model even when its bytes and checksum are correct. Rename it
        // in-place before verification; this preserves the existing download.
        if (!destination.isFile && legacyCandidate?.isFile == true) {
            movePartial(legacyCandidate, destination)
        }
        if (!partial.isFile) {
            when {
                resume.isFile -> movePartial(resume, partial)
                legacy.isFile -> movePartial(legacy, partial)
            }
        }
        if (destination.isFile && destination.length() > ModelManager.MODEL_SIZE_BYTES) {
            if (!destination.delete()) {
                throw ResumableDownloadFailure(
                    "Oversized Gemma candidate cannot be removed.",
                    retryable = false
                )
            }
        }
        return destination
    }

    private fun movePartial(source: File, target: File) {
        if (!source.renameTo(target)) {
            throw ResumableDownloadFailure(
                "Partial Gemma artifact could not be migrated for resume.",
                retryable = false
            )
        }
    }

    private suspend fun failure(message: String, retryable: Boolean): Result {
        setProgress(
            workDataOf(
                ModelManager.PROGRESS_STAGE to "Failed",
                ModelManager.PROGRESS_ERROR to message,
                ModelManager.PROGRESS_RETRYABLE to retryable
            )
        )
        if (!retryable) storageBudget.release(DownloadLane.GEMMA, id.toString())
        return if (retryable) {
            Result.retry()
        } else {
            Result.failure(
                workDataOf(
                    ModelManager.PROGRESS_ERROR to message,
                    ModelManager.PROGRESS_RETRYABLE to false
                )
            )
        }
    }

    private fun createForegroundInfo(title: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Gemma 4 E2B")
            .setContentText(title)
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
}
