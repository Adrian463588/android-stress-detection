package com.stressdetector.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.data.db.ClinicalDocumentEntity
import com.stressdetector.mobile.data.db.DocumentChunkEntity
import com.stressdetector.mobile.ml.rag.DocumentProcessingState
import com.stressdetector.mobile.ui.viewmodels.DocumentLibraryViewModel

@Composable
fun DocumentDetailScreen(documentId: String, viewModel: DocumentLibraryViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var document by remember(documentId) { mutableStateOf<ClinicalDocumentEntity?>(null) }
    var chunks by remember(documentId) { mutableStateOf<List<DocumentChunkEntity>>(emptyList()) }
    var showDeleteConfirmation by remember(documentId) { mutableStateOf(false) }
    var isLoading by remember(documentId) { mutableStateOf(true) }
    var loadError by remember(documentId) { mutableStateOf<String?>(null) }
    var loadAttempt by rememberSaveable(documentId) { mutableStateOf(0) }

    LaunchedEffect(documentId, loadAttempt) {
        isLoading = true
        loadError = null
        runCatching {
            document = viewModel.getDocument(documentId)
            chunks = viewModel.getDocumentChunks(documentId)
        }.onFailure { error ->
            loadError = error.message ?: "Detail dokumen tidak dapat dimuat."
        }
        isLoading = false
    }

    val current = document
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                    androidx.compose.material3.Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Kembali"
                    )
                    Text("Kembali")
                }
                current?.let { doc ->
                    TextButton(
                        onClick = { viewModel.reindexDocument(context, doc) },
                        enabled = doc.processingState != DocumentProcessingState.INDEXING.name &&
                            !doc.sourceSha256.isNullOrBlank(),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Indeks ulang") }
                }
            }
        }

        if (isLoading) {
            item {
                Text(
                    "Memuat detail dokumen…",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        } else if (loadError != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Detail dokumen gagal dimuat: $loadError",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                    )
                    TextButton(
                        onClick = { loadAttempt += 1 },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Coba lagi") }
                }
            }
        } else if (current == null) {
            item { Text("Dokumen tidak ditemukan atau sudah dihapus.") }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(current.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(current.sourceFileName ?: "Nama sumber tidak tersedia", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                DetailSection(title = "Status pemrosesan") {
                    DetailLine("Lifecycle", DocumentProcessingState.fromPersisted(current.processingState).name)
                    DetailLine("Embedding", current.embeddingStatus)
                    DetailLine("Extraction", current.extractionStatus)
                    DetailLine("Chunk", current.indexedChunkCount.toString())
                    DetailLine(
                        "Benchmark readiness",
                        if (DocumentProcessingState.fromPersisted(current.processingState) == DocumentProcessingState.READY_SEMANTIC) {
                            "Semantic index tersedia; benchmark runtime tetap harus diverifikasi dari evidence nyata."
                        } else {
                            "Belum siap semantic benchmark"
                        }
                    )
                    current.processingError?.let { DetailLine("Error", it, isError = true) }
                    current.processingErrorCode?.let { DetailLine("Error code", it, isError = true) }
                }
            }
            item {
                DetailSection(title = "Provenance") {
                    DetailLine("Document ID", current.documentId)
                    DetailLine("Source checksum", current.sourceSha256 ?: "belum tersedia", isError = current.sourceSha256.isNullOrBlank())
                    DetailLine("Ingestion", current.ingestionVersion)
                    DetailLine("Source", current.sourceFileName ?: "tidak tersedia")
                    DetailLine("OCR", current.ocrProvenance ?: "tidak dilaporkan")
                }
            }
            item {
                DetailSection(title = "Audit halaman") {
                    Text(current.pageExtractionJson ?: "Audit halaman belum tersedia.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                DetailSection(title = "Preview chunk terindeks") {
                    if (chunks.isEmpty()) {
                        Text("Belum ada chunk yang dapat dipreview.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            chunks.take(PREVIEW_CHUNK_LIMIT).forEach { chunk ->
                                Text(
                                    text = buildString {
                                        append("Chunk ${chunk.chunkIndex}")
                                        chunk.sourcePage?.let { append(" · halaman $it") }
                                        chunk.sourceLocator?.let { append(" · $it") }
                                        append("\n")
                                        append(chunk.textContent)
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (chunk != chunks.take(PREVIEW_CHUNK_LIMIT).last()) Divider()
                            }
                        }
                    }
                }
            }
            item {
                TextButton(
                    onClick = { showDeleteConfirmation = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Hapus dokumen dan indeks lokal") }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Hapus dokumen?") },
            text = { Text("Sumber terenkripsi, chunk, dan indeks lokal dokumen ini akan dihapus.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteDocument(documentId)
                        showDeleteConfirmation = false
                        onBack()
                    }
                ) { Text("Hapus", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun DetailLine(label: String, value: String, isError: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

private const val PREVIEW_CHUNK_LIMIT = 3
