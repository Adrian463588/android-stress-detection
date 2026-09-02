package com.stressdetector.mobile.ml.rag

import com.stressdetector.mobile.data.db.ClinicalDocumentEntity
import java.io.FileNotFoundException
import java.util.Locale
import java.util.UUID

/**
 * Canonical processing states persisted by the document ingestion boundary.
 * Unknown legacy values deliberately project to [FAILED] so consumers never
 * treat an unrecognised state as retrieval-ready.
 */
enum class DocumentProcessingState {
    PENDING,
    INDEXING,
    PARTIAL,
    READY_LEXICAL,
    READY_SEMANTIC,
    REINDEX_REQUIRED,
    FAILED;

    companion object {
        fun fromPersisted(value: String): DocumentProcessingState = entries.firstOrNull { it.name == value } ?: FAILED
    }
}

enum class DocumentProcessingFailureCode {
    PERMISSION_REVOKED,
    SOURCE_MISSING,
    FILE_TOO_LARGE,
    UNSUPPORTED_FORMAT,
    PDF_ENCRYPTED,
    PDF_PAGE_LIMIT_EXCEEDED,
    PDF_TEXT_LIMIT_EXCEEDED,
    OCR_UNAVAILABLE,
    OCR_FAILED,
    TOKENIZER_UNAVAILABLE,
    MALFORMED_DOCUMENT,
    PARTIAL_EXTRACTION,
    PARTIAL_LIMIT_REACHED,
    INTERRUPTED,
    UNKNOWN
}

/** Safe, machine-readable failure propagated from parsing through WorkManager. */
data class DocumentProcessingFailure(val code: DocumentProcessingFailureCode, val userMessage: String) {
    companion object {
        fun partialExtraction(): DocumentProcessingFailure = DocumentProcessingFailure(
            code = DocumentProcessingFailureCode.PARTIAL_EXTRACTION,
            userMessage = "Ekstraksi dokumen parsial; tinjau halaman yang gagal sebelum digunakan."
        )

        fun noIndexableContent(): DocumentProcessingFailure = DocumentProcessingFailure(
            code = DocumentProcessingFailureCode.MALFORMED_DOCUMENT,
            userMessage = "Dokumen tidak memiliki konten yang dapat diindeks."
        )

        fun reindexRequired(): DocumentProcessingFailure = DocumentProcessingFailure(
            code = DocumentProcessingFailureCode.INTERRUPTED,
            userMessage = "Indexing terhenti; indeks ulang diperlukan."
        )

        fun sourceMissing(): DocumentProcessingFailure = DocumentProcessingFailure(
            code = DocumentProcessingFailureCode.SOURCE_MISSING,
            userMessage = "Salinan sumber lokal tidak tersedia; impor ulang dokumen."
        )

        fun from(error: Throwable): DocumentProcessingFailure {
            val known = generateSequence(error) { it.cause }
                .filterIsInstance<DocumentParseException>()
                .map(DocumentParseException::failure)
                .firstOrNull()
            if (known != null && known.code != DocumentProcessingFailureCode.UNKNOWN) return known

            if (generateSequence(error) { it.cause }.any { it is SecurityException }) {
                return DocumentProcessingFailure(
                    DocumentProcessingFailureCode.PERMISSION_REVOKED,
                    "Akses dokumen dicabut. Pilih ulang dokumen dari penyimpanan."
                )
            }
            if (generateSequence(error) { it.cause }.any { it is FileNotFoundException }) {
                return sourceMissing()
            }

            val detail = generateSequence(error) { it.cause }
                .joinToString(" ") { "${it::class.java.simpleName} ${it.message.orEmpty()}" }
                .lowercase(Locale.ROOT)
            return when {
                "ukuran dokumen" in detail || "file too large" in detail -> DocumentProcessingFailure(
                    DocumentProcessingFailureCode.FILE_TOO_LARGE,
                    "Ukuran dokumen melebihi batas impor lokal."
                )
                "partial_limit_reached" in detail -> DocumentProcessingFailure(
                    DocumentProcessingFailureCode.PARTIAL_LIMIT_REACHED,
                    "Dokumen melebihi batas pemrosesan lokal dan hanya diproses sebagian."
                )
                "jumlah halaman pdf" in detail || "page limit" in detail -> DocumentProcessingFailure(
                    DocumentProcessingFailureCode.PDF_PAGE_LIMIT_EXCEEDED,
                    "Jumlah halaman PDF melebihi batas impor lokal."
                )
                "teks halaman" in detail || "total teks pdf" in detail || "text limit" in detail -> DocumentProcessingFailure(
                    DocumentProcessingFailureCode.PDF_TEXT_LIMIT_EXCEEDED,
                    "Teks PDF melebihi batas impor lokal."
                )
                "password" in detail || "encrypted" in detail -> DocumentProcessingFailure(
                    DocumentProcessingFailureCode.PDF_ENCRYPTED,
                    "PDF terenkripsi dan tidak dapat diekstrak secara lokal."
                )
                "tokenizer" in detail -> DocumentProcessingFailure(
                    DocumentProcessingFailureCode.TOKENIZER_UNAVAILABLE,
                    "Tokenizer lokal belum tersedia untuk pengindeksan."
                )
                "ocr" in detail && ("unavailable" in detail || "belum tersedia" in detail) -> DocumentProcessingFailure(
                    DocumentProcessingFailureCode.OCR_UNAVAILABLE,
                    "OCR lokal belum tersedia untuk halaman hasil pemindaian."
                )
                "ocr" in detail -> DocumentProcessingFailure(
                    DocumentProcessingFailureCode.OCR_FAILED,
                    "OCR lokal gagal memproses halaman pemindaian."
                )
                "signature" in detail || "format" in detail -> DocumentProcessingFailure(
                    DocumentProcessingFailureCode.UNSUPPORTED_FORMAT,
                    "Format atau tanda tangan dokumen tidak didukung."
                )
                "pdf" in detail || "readable text" in detail || "konten" in detail -> DocumentProcessingFailure(
                    DocumentProcessingFailureCode.MALFORMED_DOCUMENT,
                    "Dokumen tidak dapat diekstrak sebagai konten yang dapat diindeks."
                )
                else -> DocumentProcessingFailure(
                    DocumentProcessingFailureCode.UNKNOWN,
                    "Pengindeksan dokumen gagal. Lihat detail teknis untuk diagnosis."
                )
            }
        }
    }
}

/** Stable weighted progress contract shared by the Worker and Knowledge Base UI. */
data class RagImportProgress(
    val workId: UUID? = null,
    val uniqueWorkName: String? = null,
    val documentId: String? = null,
    val stage: DocumentIngestionProgress.Stage,
    val completed: Int = 0,
    val total: Int = 0,
    val percent: Float? = null,
    val state: String? = null,
    val errorCode: String? = null
)

object RagImportProgressCalculator {
    private const val COPYING_START = 0f
    private const val COPYING_END = 0.15f
    private const val EXTRACTING_START = 0.15f
    private const val EXTRACTING_END = 0.40f
    private const val CHUNKING_START = 0.40f
    private const val CHUNKING_END = 0.55f
    private const val EMBEDDING_START = 0.55f
    private const val EMBEDDING_END = 0.95f
    private const val SAVING_START = 0.95f
    private const val SAVING_END = 1f

    /** Returns a 0..1 determinate fraction, or null while a stage has no total. */
    fun fraction(stage: DocumentIngestionProgress.Stage, completed: Int, total: Int): Float? {
        if (total <= 0) return null
        val (start, end) = when (stage) {
            DocumentIngestionProgress.Stage.COPYING -> COPYING_START to COPYING_END
            DocumentIngestionProgress.Stage.EXTRACTING -> EXTRACTING_START to EXTRACTING_END
            DocumentIngestionProgress.Stage.CHUNKING -> CHUNKING_START to CHUNKING_END
            DocumentIngestionProgress.Stage.EMBEDDING -> EMBEDDING_START to EMBEDDING_END
            DocumentIngestionProgress.Stage.SAVING -> SAVING_START to SAVING_END
        }
        val ratio = (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        return (start + ((end - start) * ratio)).coerceIn(0f, 1f)
    }
}

/** One exhaustive projection for UI/worker consumers without exposing raw exceptions. */
data class DocumentProcessingSnapshot(
    val state: DocumentProcessingState,
    val failureCode: DocumentProcessingFailureCode?,
    val message: String?,
    val processingStartedAtMillis: Long,
    val processingUpdatedAtMillis: Long,
    val processingRevision: Int,
    val isRetrievalReady: Boolean
)

fun ClinicalDocumentEntity.toProcessingSnapshot(): DocumentProcessingSnapshot {
    val state = DocumentProcessingState.fromPersisted(processingState)
    val failureCode = processingErrorCode
        ?.let { raw -> DocumentProcessingFailureCode.entries.firstOrNull { it.name == raw } }
        ?: when (state) {
            DocumentProcessingState.PARTIAL -> DocumentProcessingFailureCode.PARTIAL_EXTRACTION
            DocumentProcessingState.FAILED -> DocumentProcessingFailureCode.UNKNOWN
            else -> null
        }
    return DocumentProcessingSnapshot(
        state = state,
        failureCode = failureCode,
        message = processingError,
        processingStartedAtMillis = processingStartedAtMillis,
        processingUpdatedAtMillis = processingUpdatedAtMillis,
        processingRevision = processingRevision,
        isRetrievalReady = state == DocumentProcessingState.READY_LEXICAL ||
            state == DocumentProcessingState.READY_SEMANTIC
    )
}
