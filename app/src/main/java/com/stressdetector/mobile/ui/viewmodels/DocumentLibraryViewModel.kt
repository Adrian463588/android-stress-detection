package com.stressdetector.mobile.ui.viewmodels

import android.content.Context
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.stressdetector.mobile.data.db.ClinicalDocumentEntity
import com.stressdetector.mobile.data.db.DocumentChunkEntity
import com.stressdetector.mobile.di.AppLocator
import com.stressdetector.mobile.ml.rag.DocumentIngestionService
import com.stressdetector.mobile.ml.rag.DocumentIngestionWorker
import com.stressdetector.mobile.ml.rag.DocumentWorkIdentity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DocumentLibraryViewModel(
    context: Context,
    private val database: com.stressdetector.mobile.data.db.StressDatabase,
    private val ingestionService: DocumentIngestionService = AppLocator.documentIngestionService(context)
) : ViewModel() {
    private val _requestedWorkId = MutableStateFlow<UUID?>(null)

    /** Only the operation requested in this screen instance may surface an error banner. */
    val requestedWorkId: StateFlow<UUID?> = _requestedWorkId.asStateFlow()

    init {
        // Clinical documents remain app-private and are not rehydrated from SAF.
        // Recovery changes stale INDEXING rows into an explicit reindex-required
        // state; it never republishes a partial or stale generation automatically.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ingestionService.recoverStaleIndexing()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: SQLiteException) {
                Log.w(TAG, "Could not recover stale Knowledge Base indexing", error)
            }
        }
    }

    val documents: StateFlow<List<ClinicalDocumentEntity>> = database.documentDao()
        .getAllDocuments()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun ingestDocument(context: Context, uri: Uri) {
        val workRequest = OneTimeWorkRequestBuilder<DocumentIngestionWorker>()
            .setInputData(workDataOf(DocumentIngestionWorker.DATA_KEY_SOURCE_URI to uri.toString()))
            .addTag(DocumentIngestionWorker.WORK_TAG)
            .build()
        enqueueAndTrack(
            context = context,
            // KEEP suppresses only an active duplicate selection. A terminal
            // work record can be retried without exposing the source URI in
            // WorkManager diagnostics.
            uniqueWorkName = DocumentWorkIdentity.importUniqueName(uri.toString()),
            workRequest = workRequest
        )
    }

    @Deprecated("Use ingestDocument; import supports PDF, DOCX, TXT, and Markdown.")
    fun ingestPdf(context: Context, uri: Uri) = ingestDocument(context, uri)

    /**
     * Reuses the encrypted local source. Its unique WorkManager identity tracks
     * document content and the upcoming processing revision, not its URI.
     */
    fun reindexDocument(context: Context, document: ClinicalDocumentEntity, allowPartialExtraction: Boolean = false) {
        val checksum = document.sourceSha256?.takeIf(String::isNotBlank) ?: return
        if (document.processingState == "INDEXING") return
        val nextRevision = (document.processingRevision + 1).coerceAtLeast(1)
        val workRequest = OneTimeWorkRequestBuilder<DocumentIngestionWorker>()
            .setInputData(
                workDataOf(
                    DocumentIngestionWorker.DATA_KEY_DOCUMENT_ID to document.documentId,
                    DocumentIngestionWorker.DATA_KEY_ALLOW_PARTIAL to allowPartialExtraction
                )
            )
            .addTag(DocumentIngestionWorker.WORK_TAG)
            .addTag(DocumentWorkIdentity.reindexTag(document.documentId))
            .build()
        enqueueAndTrack(
            context = context,
            uniqueWorkName = DocumentWorkIdentity.reindexUniqueName(document.documentId, checksum, nextRevision),
            workRequest = workRequest
        )
    }

    /** Publishes only the reviewed partial extraction as a new atomic generation. */
    fun approvePartialExtraction(context: Context, document: ClinicalDocumentEntity) {
        reindexDocument(context, document, allowPartialExtraction = true)
    }

    fun deleteDocument(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ingestionService.delete(id)
        }
    }

    suspend fun getDocument(documentId: String): ClinicalDocumentEntity? = withContext(Dispatchers.IO) {
        database.documentDao().getDocument(documentId)
    }

    suspend fun getDocumentChunks(documentId: String): List<DocumentChunkEntity> = withContext(Dispatchers.IO) {
        database.documentDao().getChunksForDocument(documentId)
    }

    /**
     * KEEP may retain a pre-existing request, therefore [workRequest.id] is
     * not necessarily the work that actually owns [uniqueWorkName]. Resolve
     * the persisted chain after enqueue so the UI observes the real WorkInfo
     * (including an idempotent duplicate already in flight).
     */
    private fun enqueueAndTrack(context: Context, uniqueWorkName: String, workRequest: androidx.work.OneTimeWorkRequest) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val enqueueOperation = workManager.enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.KEEP, workRequest)
        _requestedWorkId.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val actualId = runCatching {
                // WorkManager persists the KEEP decision asynchronously. Wait
                // for that operation before resolving the unique chain, so a
                // retained request cannot be mistaken for the newly-created id.
                enqueueOperation.result.get()
                val uniqueChain = workManager.getWorkInfosForUniqueWork(uniqueWorkName).get()
                uniqueChain.firstOrNull { work ->
                    work.state == androidx.work.WorkInfo.State.RUNNING ||
                        work.state == androidx.work.WorkInfo.State.ENQUEUED
                }?.id
                    ?: uniqueChain.lastOrNull()?.id
                    ?: workManager.getWorkInfoById(workRequest.id).get()?.id
            }.getOrNull()
            _requestedWorkId.value = actualId ?: workRequest.id
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val applicationContext = context.applicationContext
            return DocumentLibraryViewModel(applicationContext, AppLocator.database(applicationContext)) as T
        }
    }

    private companion object {
        const val TAG = "DocumentLibraryViewModel"
    }
}
