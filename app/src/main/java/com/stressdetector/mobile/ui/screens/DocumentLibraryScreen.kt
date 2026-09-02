package com.stressdetector.mobile.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DismissValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismiss
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDismissState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.stressdetector.mobile.data.db.ClinicalDocumentEntity
import com.stressdetector.mobile.ml.rag.DocumentIngestionWorker
import com.stressdetector.mobile.ml.rag.DocumentProcessingState
import com.stressdetector.mobile.ui.viewmodels.DocumentLibraryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val SHORT_CHECKSUM_LENGTH = 8

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun DocumentLibraryScreen(
    viewModel: DocumentLibraryViewModel,
    windowSizeClass: WindowWidthSizeClass,
    onBack: () -> Unit,
    onDocumentSelected: (String) -> Unit
) {
    val documents by viewModel.documents.collectAsStateWithLifecycleCompat()
    val context = LocalContext.current
    val horizontalPadding = when (windowSizeClass) {
        WindowWidthSizeClass.Expanded -> com.stressdetector.mobile.ui.AppSpacing.Large
        WindowWidthSizeClass.Medium -> com.stressdetector.mobile.ui.AppSpacing.ExtraLarge
        else -> com.stressdetector.mobile.ui.AppSpacing.Screen
    }
    val workManager = remember { WorkManager.getInstance(context) }
    val workInfos by workManager.getWorkInfosByTagFlow(DocumentIngestionWorker.WORK_TAG)
        .collectAsState(initial = emptyList())
    val requestedWorkId by viewModel.requestedWorkId.collectAsState()
    val requestedWork = workInfos.firstOrNull { work -> work.id == requestedWorkId }
    // WorkManager survives process death. Render every active tagged chain so
    // reopening the KB cannot lose progress when the in-memory requested UUID
    // is gone; terminal banners remain scoped to the exact requested UUID.
    val activeWorks = workInfos.filter { work ->
        work.state == WorkInfo.State.RUNNING || work.state == WorkInfo.State.ENQUEUED
    }
    val isIngesting = activeWorks.isNotEmpty()
    val ingestionError = requestedWork
        ?.takeIf { it.state == WorkInfo.State.FAILED }
        ?.outputData?.getString(DocumentIngestionWorker.DATA_KEY_ERROR)
    val duplicateDocumentId = requestedWork
        ?.takeIf { it.state == WorkInfo.State.SUCCEEDED }
        ?.outputData?.getString(DocumentIngestionWorker.DATA_KEY_DUPLICATE_DOCUMENT_ID)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val permissionPersisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                true
            }.getOrDefault(false)
            if (permissionPersisted) {
                viewModel.ingestDocument(context, it)
            } else {
                android.widget.Toast.makeText(
                    context,
                    "Dokumen tidak dapat diberi akses persisten. Pilih ulang dari penyimpanan lokal.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    launcher.launch(
                        arrayOf(
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "text/plain",
                            "text/markdown"
                        )
                    )
                }
            ) {
                androidx.compose.material3.Icon(Icons.Default.Add, contentDescription = "Tambah dokumen lokal")
            }
        },
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Dokumen RAG",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        if (documents.isEmpty() && !isIngesting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Belum ada dokumen. Tekan + untuk menambahkan PDF, DOCX, atau teks.",
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                activeWorks.forEach { work ->
                    val stage = work.progress.getString(DocumentIngestionWorker.DATA_KEY_STAGE)
                    val completed = work.progress.getInt(DocumentIngestionWorker.DATA_KEY_COMPLETED, 0)
                    val total = work.progress.getInt(DocumentIngestionWorker.DATA_KEY_TOTAL, 0)
                    val progressFraction = work.progress
                        .getFloat(DocumentIngestionWorker.DATA_KEY_PERCENT, Float.NaN)
                        .takeIf { it in 0f..1f }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (progressFraction != null) {
                            LinearProgressIndicator(
                                progress = progressFraction,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            text = when {
                                stage == "EMBEDDING" && total > 0 -> "Membuat embedding lokal: $completed/$total"
                                stage != null -> "Mengimpor dokumen: ${stage.lowercase(Locale.ROOT)}"
                                else -> "Menunggu impor dokumen lokal..."
                            },
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        Text(
                            text = when {
                                progressFraction != null -> {
                                    "${(progressFraction * 100f).roundToInt()}% · " +
                                        "$completed/${total.coerceAtLeast(0)}"
                                }
                                stage != null -> "${stage.lowercase(Locale.ROOT)} · menyiapkan jumlah pekerjaan"
                                else -> "Tahap belum melaporkan jumlah pekerjaan"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
                duplicateDocumentId?.let { existingId ->
                    Text(
                        text = "Dokumen sudah terindeks; memakai dokumen yang sama ($existingId).",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                ingestionError?.let { error ->
                    Text(
                        text = "Impor dokumen gagal: $error",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .semantics { liveRegion = LiveRegionMode.Assertive }
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(documents, key = { it.documentId }) { doc ->
                        var showDialog by remember { mutableStateOf(false) }
                        var showPartialApproval by remember(doc.documentId, doc.processingRevision) {
                            mutableStateOf(false)
                        }
                        var showProcessingDetails by remember(doc.documentId, doc.processingRevision) {
                            mutableStateOf(false)
                        }

                        if (showDialog) {
                            AlertDialog(
                                onDismissRequest = { showDialog = false },
                                title = { Text("Delete Document") },
                                text = { Text("Are you sure you want to delete ${doc.title}?") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        viewModel.deleteDocument(doc.documentId)
                                        showDialog = false
                                    }) {
                                        Text("Delete", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDialog = false }) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }
                        if (showPartialApproval) {
                            AlertDialog(
                                onDismissRequest = { showPartialApproval = false },
                                title = { Text("Setujui ekstraksi parsial?") },
                                text = {
                                    Text(
                                        "Hanya halaman yang berhasil diekstrak pada audit akan diindeks. " +
                                            "Dokumen tetap tidak retrieval-ready sampai publikasi generasi baru " +
                                            "berhasil secara atomik."
                                    )
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        viewModel.approvePartialExtraction(context, doc)
                                        showPartialApproval = false
                                    }) {
                                        Text("Setujui dan indeks ulang")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showPartialApproval = false }) {
                                        Text("Batal")
                                    }
                                }
                            )
                        }

                        val dismissState = rememberDismissState(
                            confirmValueChange = {
                                if (it == DismissValue.DismissedToStart || it == DismissValue.DismissedToEnd) {
                                    showDialog = true
                                    false // Don't dismiss immediately, wait for dialog confirmation
                                } else {
                                    false
                                }
                            }
                        )

                        SwipeToDismiss(
                            state = dismissState,
                            background = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Text("Delete", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                                }
                            },
                            dismissContent = {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = doc.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        doc.sourceSha256?.takeIf(String::isNotBlank)?.let { checksum ->
                                            Text(
                                                text = "Identitas konten: ${checksum.take(SHORT_CHECKSUM_LENGTH)}…",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                            )
                                        }
                                        val dateStr = SimpleDateFormat(
                                            "MMM dd, yyyy",
                                            Locale.getDefault()
                                        ).format(Date(doc.uploadedAtMillis))
                                        Text(
                                            text = "Added: $dateStr",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                        val processing = documentStatusPresentation(doc)
                                        Text(
                                            text = processing.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (processing.isError) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                            }
                                        )
                                        when (processing.action) {
                                            DocumentStatusAction.RETRY,
                                            DocumentStatusAction.REINDEX -> {
                                                if (doc.sourceSha256.isNullOrBlank()) {
                                                    Text(
                                                        "Salinan sumber tidak tersedia; impor ulang dokumen diperlukan.",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                } else {
                                                    TextButton(onClick = { viewModel.reindexDocument(context, doc) }) {
                                                        Text(
                                                            if (processing.action == DocumentStatusAction.RETRY) {
                                                                "Coba lagi"
                                                            } else {
                                                                "Indeks ulang"
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                            DocumentStatusAction.REVIEW_PARTIAL -> {
                                                TextButton(onClick = { showProcessingDetails = !showProcessingDetails }) {
                                                    Text(
                                                        if (showProcessingDetails) {
                                                            "Sembunyikan detail"
                                                        } else {
                                                            "Tinjau ekstraksi parsial"
                                                        }
                                                    )
                                                }
                                            }
                                            null -> Unit
                                        }
                                        if (showProcessingDetails) {
                                            Text(
                                                "Kode: ${doc.processingErrorCode ?: "PARTIAL_EXTRACTION"}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                            )
                                            doc.pageExtractionJson?.takeIf(String::isNotBlank)?.let { pageAudit ->
                                                Text(
                                                    "Audit halaman: $pageAudit",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                                )
                                            }
                                            TextButton(onClick = { showPartialApproval = true }) {
                                                Text("Setujui ekstraksi parsial dan indeks ulang")
                                            }
                                        }
                                        if (doc.isProcessed) {
                                            Text(
                                                text = "Audit ekstraksi: ${doc.extractionStatus} · ${doc.indexedChunkCount} chunk",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                            )
                                        }
                                        TextButton(
                                            onClick = { onDocumentSelected(doc.documentId) },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        ) { Text("Buka detail dokumen") }
                                        TextButton(
                                            onClick = { showDialog = true },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error
                                            )
                                        ) { Text("Hapus dokumen") }
                                        doc.ocrProvenance?.let { provenance ->
                                            Text(
                                                text = "OCR: $provenance",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

private enum class DocumentStatusAction(val label: String) {
    RETRY("Coba lagi"),
    REINDEX("Indeks ulang"),
    REVIEW_PARTIAL("Tinjau ekstraksi parsial")
}

private data class DocumentStatusPresentation(val message: String, val isError: Boolean = false, val action: DocumentStatusAction? = null)

private fun documentStatusPresentation(doc: ClinicalDocumentEntity): DocumentStatusPresentation =
    when (DocumentProcessingState.fromPersisted(doc.processingState)) {
        DocumentProcessingState.PENDING -> DocumentStatusPresentation(
            message = "Status: menunggu antrean pengindeksan lokal",
            action = DocumentStatusAction.RETRY
        )
        DocumentProcessingState.INDEXING -> DocumentStatusPresentation("Status: sedang diindeks secara lokal")
        DocumentProcessingState.PARTIAL -> DocumentStatusPresentation(
            message = "Status: ekstraksi parsial · ${doc.processingError ?: "tinjau halaman yang gagal"}",
            isError = true,
            action = DocumentStatusAction.REVIEW_PARTIAL
        )
        DocumentProcessingState.READY_LEXICAL ->
            DocumentStatusPresentation(
                "Status: terindeks secara lexical; aktivasi jawaban RAG menunggu benchmark lokal"
            )
        DocumentProcessingState.READY_SEMANTIC ->
            DocumentStatusPresentation(
                "Status: terindeks semantic dan lexical; aktivasi jawaban RAG menunggu benchmark lokal"
            )
        DocumentProcessingState.REINDEX_REQUIRED -> DocumentStatusPresentation(
            message = "Status: indeks ulang diperlukan · ${doc.processingError ?: "proses sebelumnya terhenti"}",
            isError = true,
            action = DocumentStatusAction.REINDEX
        )
        DocumentProcessingState.FAILED -> DocumentStatusPresentation(
            message = "Status: gagal${doc.processingErrorCode?.let { " ($it)" } ?: ""} · " +
                (doc.processingError ?: "periksa kembali dokumen"),
            isError = true,
            action = DocumentStatusAction.RETRY
        )
    }
