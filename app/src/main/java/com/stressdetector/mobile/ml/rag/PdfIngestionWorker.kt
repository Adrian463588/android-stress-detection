package com.stressdetector.mobile.ml.rag

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stressdetector.mobile.di.AppLocator
import kotlinx.coroutines.CancellationException

open class DocumentIngestionWorker(private val context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val INDEX_NOTIFICATION_ID = 1_001
        private const val RAG_NOTIFICATION_CHANNEL = "rag_channel"

        /** Keeps persisted WorkManager data from earlier PDF-only builds readable. */
        const val DATA_KEY_SOURCE_URI = "pdf_uri"

        @Deprecated("Use DATA_KEY_SOURCE_URI; the stored key stays stable for queued legacy work.")
        const val DATA_KEY_URI = DATA_KEY_SOURCE_URI

        /** The stable tag is intentionally retained for pre-existing work chains. */
        const val WORK_TAG = "pdf_ingestion"

        /** Existing encrypted app-private source to index again; no SAF URI is required. */
        const val DATA_KEY_DOCUMENT_ID = "document_id"
        const val DATA_KEY_ALLOW_PARTIAL = "allow_partial_extraction"
        const val DATA_KEY_RESULT = "chunk_count"
        const val DATA_KEY_ERROR = "error"
        const val DATA_KEY_ERROR_CODE = "error_code"
        const val DATA_KEY_STAGE = "stage"
        const val DATA_KEY_COMPLETED = "completed"
        const val DATA_KEY_TOTAL = "total"

        /** Determinate fraction in the closed interval 0..1. */
        const val DATA_KEY_PERCENT = "percent"
        const val DATA_KEY_DUPLICATE_DOCUMENT_ID = "duplicate_document_id"
        const val DATA_KEY_REQUIRES_ACKNOWLEDGEMENT = "requires_acknowledgement"
    }

    override suspend fun doWork(): Result {
        val request = requestFromInput()
            ?: return Result.failure(workDataOf(DATA_KEY_ERROR to "Sumber dokumen tidak tersedia"))
        val notificationManager = NotificationManagerCompat.from(context)
        notifyIfPermitted(
            notificationManager,
            INDEX_NOTIFICATION_ID,
            NotificationCompat.Builder(context, RAG_NOTIFICATION_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle("Memproses dokumen lokal")
                .setContentText("Menyiapkan indeks dan sitasi...")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        )

        return try {
            toWorkResult(ingest(request))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: DocumentParseException) {
            failureResult(error)
        } catch (error: SecurityException) {
            failureResult(error)
        } catch (error: java.io.IOException) {
            failureResult(error)
        } catch (error: IllegalArgumentException) {
            failureResult(error)
        } catch (error: IllegalStateException) {
            failureResult(error)
        } finally {
            runCatching { notificationManager.cancel(INDEX_NOTIFICATION_ID) }
        }
    }

    private fun requestFromInput(): DocumentIngestionRequest? = DocumentIngestionRequest(
        documentId = inputData.getString(DATA_KEY_DOCUMENT_ID),
        sourceUri = inputData.getString(DATA_KEY_SOURCE_URI)?.let(Uri::parse),
        allowPartialExtraction = inputData.getBoolean(DATA_KEY_ALLOW_PARTIAL, false)
    ).takeIf { request -> !request.documentId.isNullOrBlank() || request.sourceUri != null }

    private suspend fun ingest(request: DocumentIngestionRequest): DocumentIngestionResult {
        val ingestionService = AppLocator.documentIngestionService(context)
        return if (request.documentId.isNullOrBlank()) {
            ingestionService.ingest(
                requireNotNull(request.sourceUri),
                allowPartialExtraction = request.allowPartialExtraction,
                onProgress = ::publishProgress
            )
        } else {
            ingestionService.reindex(
                documentId = requireNotNull(request.documentId),
                allowPartialExtraction = request.allowPartialExtraction,
                onProgress = ::publishProgress
            )
        }
    }

    private fun toWorkResult(result: DocumentIngestionResult): Result = when (result) {
        is DocumentIngestionResult.Indexed -> Result.success(
            workDataOf(
                DATA_KEY_RESULT to result.chunkCount,
                "embedding_status" to result.embeddingStatus,
                "extraction_status" to result.extractionStatus,
                DATA_KEY_PERCENT to 1f
            )
        )
        is DocumentIngestionResult.AlreadyIndexed -> Result.success(
            workDataOf(
                DATA_KEY_RESULT to 0,
                DATA_KEY_DUPLICATE_DOCUMENT_ID to result.documentId
            )
        )
        is DocumentIngestionResult.Partial -> Result.success(
            workDataOf(
                DATA_KEY_RESULT to 0,
                "partial_document_id" to result.documentId,
                "extraction_status" to result.extractionStatus,
                DATA_KEY_REQUIRES_ACKNOWLEDGEMENT to true,
                DATA_KEY_ERROR to result.reason,
                DATA_KEY_ERROR_CODE to result.failureCode
            )
        )
    }

    private fun failureResult(error: Throwable): Result {
        val failure = DocumentProcessingFailure.from(error)
        return Result.failure(
            workDataOf(
                DATA_KEY_ERROR to failure.userMessage,
                DATA_KEY_ERROR_CODE to failure.code.name
            )
        )
    }

    private suspend fun publishProgress(progress: DocumentIngestionProgress) {
        val fraction = RagImportProgressCalculator.fraction(
            stage = progress.stage,
            completed = progress.completed,
            total = progress.total
        )
        val base = workDataOf(
            DATA_KEY_STAGE to progress.stage.name,
            DATA_KEY_COMPLETED to progress.completed,
            DATA_KEY_TOTAL to progress.total
        )
        setProgress(
            if (fraction == null) {
                base
            } else {
                workDataOf(
                    DATA_KEY_STAGE to progress.stage.name,
                    DATA_KEY_COMPLETED to progress.completed,
                    DATA_KEY_TOTAL to progress.total,
                    DATA_KEY_PERCENT to fraction
                )
            }
        )
    }

    private fun notifyIfPermitted(
        notificationManager: NotificationManagerCompat,
        notificationId: Int,
        notification: android.app.Notification
    ) {
        val notificationPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (notificationPermissionGranted && notificationManager.areNotificationsEnabled()) {
            runCatching { notificationManager.notify(notificationId, notification) }
        }
    }
}

private data class DocumentIngestionRequest(val documentId: String?, val sourceUri: Uri?, val allowPartialExtraction: Boolean)

/**
 * Runtime-compatible class name for WorkManager requests written by old
 * PDF-only builds. New work must use [DocumentIngestionWorker].
 */
@Deprecated("Use DocumentIngestionWorker for all new import jobs.")
class PdfIngestionWorker(context: Context, workerParams: WorkerParameters) : DocumentIngestionWorker(context, workerParams)
