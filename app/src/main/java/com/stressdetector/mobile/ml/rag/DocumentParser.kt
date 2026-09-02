package com.stressdetector.mobile.ml.rag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

open class DocumentParseException(
    message: String,
    cause: Throwable? = null,
    val failure: DocumentProcessingFailure = DocumentProcessingFailure(
        code = DocumentProcessingFailureCode.UNKNOWN,
        userMessage = message
    )
) : IllegalStateException(message, cause)

class DocumentAccessException(message: String, cause: Throwable? = null) :
    DocumentParseException(
        message = message,
        cause = cause,
        failure = DocumentProcessingFailure(
            code = DocumentProcessingFailureCode.PERMISSION_REVOKED,
            userMessage = "Akses dokumen dicabut. Pilih ulang dokumen dari penyimpanan."
        )
    )

enum class PageExtractionStatus {
    COMPLETE,
    OCR_COMPLETE,
    PARTIAL,
    FAILED
}

data class DocumentPageText(
    val pageNumber: Int,
    val text: String,
    val status: PageExtractionStatus = PageExtractionStatus.COMPLETE,
    val ocrProvenance: String? = null
)

data class PdfExtractionReport(
    val pages: List<DocumentPageText>,
    val limitReached: Boolean = false,
    val totalPageCount: Int = pages.size,
    val firstUnexaminedPage: Int? = null
) {
    val indexablePages: List<DocumentPageText> get() = pages.filter { it.text.isNotBlank() }
    val indexedText: String get() = indexablePages.joinToString("\n") { it.text }.trim()
    val extractionStatus: ExtractionStatus
        get() = when {
            pages.isEmpty() || indexablePages.isEmpty() -> ExtractionStatus.FAILED
            limitReached || pages.any { it.status == PageExtractionStatus.PARTIAL || it.status == PageExtractionStatus.FAILED } -> {
                ExtractionStatus.PARTIAL
            }
            pages.all { it.status == PageExtractionStatus.OCR_COMPLETE } -> ExtractionStatus.OCR_COMPLETE
            else -> ExtractionStatus.COMPLETE
        }
    val isRetrievalReady: Boolean
        get() = extractionStatus == ExtractionStatus.COMPLETE || extractionStatus == ExtractionStatus.OCR_COMPLETE

    fun auditSummary(): String {
        // Keep this projection explicit. Besides being easier to audit, it
        // avoids generating an inline grouping class that can confuse the
        // Kotlin incremental compiler after a Room/KSP rebuild.
        val counts = linkedMapOf<PageExtractionStatus, Int>()
        pages.forEach { page ->
            counts[page.status] = (counts[page.status] ?: 0) + 1
        }
        val pageCounts = counts.entries
            .sortedBy { it.key.name }
            .joinToString(separator = ";") { (status, count) -> "${status.name}=$count" }
        val unexamined = firstUnexaminedPage?.let { first ->
            ";UNEXAMINED_PAGES=$first..$totalPageCount"
        }.orEmpty()
        return if (limitReached) "$pageCounts;PARTIAL_LIMIT_REACHED=true$unexamined" else pageCounts
    }

    fun pageAuditJson(): String {
        val pageEntries = pages.joinToString { page ->
            "{\"page\":${page.pageNumber},\"status\":\"${page.status.name}\",\"ocr\":" +
                (page.ocrProvenance?.let { "\"${it.jsonEscaped()}\"" } ?: "null") + "}"
        }
        val unexaminedEntry = firstUnexaminedPage?.let { first ->
            "{\"unexamined_from\":$first,\"unexamined_to\":$totalPageCount,\"reason\":\"PARTIAL_LIMIT_REACHED\"}"
        }
        return listOfNotNull(pageEntries.takeIf(String::isNotBlank), unexaminedEntry)
            .joinToString(prefix = "[", postfix = "]")
    }
}

/** Local, bundled OCR boundary for scan-only PDF pages. */
interface PdfPageOcrEngine {
    suspend fun extractPageText(pdfFile: File, pageIndex: Int): PdfOcrResult

    data object Unavailable : PdfPageOcrEngine {
        override suspend fun extractPageText(pdfFile: File, pageIndex: Int): PdfOcrResult =
            PdfOcrResult.Unavailable("OCR lokal belum tersedia pada build ini.")
    }
}

sealed interface PdfOcrResult {
    data class Extracted(val text: String, val provenance: String) : PdfOcrResult
    data class Unavailable(val reason: String) : PdfOcrResult
    data class Failed(val reason: String) : PdfOcrResult
}

class DocumentParser(
    private val context: Context,
    private val pdfOcrEngine: PdfPageOcrEngine = MlKitPdfPageOcrEngine(context.applicationContext),
    private val docxTextExtractor: DocxTextExtractor = DocxTextExtractor()
) {

    init {
        runCatching { PDFBoxResourceLoader.init(context.applicationContext) }
    }

    suspend fun extractText(uri: Uri): String {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        return when (DocumentFormats.detect(mimeType, uri.toString())) {
            DocumentFormat.PDF -> extractPdfReport(uri).indexedText.requireReadableText()
            DocumentFormat.DOCX -> withTemporaryDocument(uri, ".docx") { file -> docxTextExtractor.extract(file) }
            DocumentFormat.PLAIN_TEXT,
            DocumentFormat.MARKDOWN
            -> try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    StrictUtf8TextReader.read(inputStream, DocumentIngestionLimits.MAX_TEXT_CHARACTERS)
                }?.trim()?.also { require(it.isNotBlank()) { "The selected clinical document contains no readable text" } }
                    ?: throw DocumentParseException("Unable to open the selected clinical document")
            } catch (error: SecurityException) {
                throw DocumentAccessException("Akses dokumen dicabut. Pilih ulang dokumen dari penyimpanan.", error)
            } catch (error: DocumentParseException) {
                throw error
            } catch (error: Exception) {
                throw DocumentParseException("Clinical document text extraction failed", error)
            }
        }
    }

    suspend fun extractText(file: File): String {
        requireFileWithinLimits(file)
        val format = DocumentFormats.detect(null, file.name)
        DocumentFormats.verifySignature(format, file)
        return when (format) {
            DocumentFormat.PDF -> extractPdfReport(file).indexedText.requireReadableText()
            DocumentFormat.DOCX -> docxTextExtractor.extract(file)
            DocumentFormat.PLAIN_TEXT,
            DocumentFormat.MARKDOWN
            -> try {
                file.inputStream().use { input ->
                    StrictUtf8TextReader.read(input, DocumentIngestionLimits.MAX_TEXT_CHARACTERS)
                }.trim().also {
                    require(it.isNotBlank()) { "The selected clinical document contains no readable text" }
                }
            } catch (error: DocumentParseException) {
                throw error
            } catch (error: Exception) {
                throw DocumentParseException("Clinical document text extraction failed", error)
            }
        }
    }

    suspend fun extractDocument(
        file: File,
        documentId: String,
        format: DocumentFormat = DocumentFormats.detect(null, file.name)
    ): ParsedDocument {
        requireFileWithinLimits(file)
        DocumentFormats.verifySignature(format, file)
        return when (format) {
            DocumentFormat.PDF -> {
                val report = extractPdfReport(file)
                ParsedDocument(
                    format = format,
                    blocks = pdfBlocks(documentId, report),
                    extractionStatus = report.extractionStatus,
                    extractionAudit = report.auditSummary(),
                    pageExtractionJson = report.pageAuditJson(),
                    ocrProvenance = report.pages
                        .mapNotNull(DocumentPageText::ocrProvenance)
                        .distinct()
                        .joinToString(";")
                        .ifBlank { null }
                )
            }
            DocumentFormat.DOCX -> {
                val blocks = docxTextExtractor.extractBlocks(file, documentId)
                ParsedDocument(
                    format = format,
                    blocks = blocks,
                    extractionStatus = ExtractionStatus.COMPLETE,
                    extractionAudit = "COMPLETE=${blocks.size}"
                )
            }
            DocumentFormat.PLAIN_TEXT,
            DocumentFormat.MARKDOWN
            -> {
                val text = file.inputStream().use { input ->
                    StrictUtf8TextReader.read(input, DocumentIngestionLimits.MAX_TEXT_CHARACTERS)
                }.trim().requireReadableText()
                val blocks = StructuredTextBlockParser.parse(documentId, text, format)
                ParsedDocument(
                    format = format,
                    blocks = blocks,
                    extractionStatus = ExtractionStatus.COMPLETE,
                    extractionAudit = "COMPLETE=${blocks.size}"
                )
            }
        }
    }

    suspend fun extractPdfPages(uri: Uri): List<DocumentPageText> = extractPdfReport(uri).indexablePages

    suspend fun extractPdfReport(uri: Uri): PdfExtractionReport {
        val temporaryPdf = File.createTempFile("rag_pdf_", ".pdf", context.cacheDir)
        return try {
            copyUriBounded(uri, temporaryPdf)
            extractPdfReport(temporaryPdf)
        } catch (error: DocumentParseException) {
            throw error
        } catch (error: Exception) {
            throw DocumentParseException("Clinical PDF tidak dapat dibaca dari URI.", error)
        } finally {
            temporaryPdf.delete()
        }
    }

    suspend fun extractPdfPages(file: File): List<DocumentPageText> = extractPdfReport(file).indexablePages

    suspend fun extractPdfReport(file: File): PdfExtractionReport = try {
        requireFileWithinLimits(file)
        DocumentFormats.verifySignature(DocumentFormat.PDF, file)
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { document ->
            extractPages(document, file)
        }
    } catch (error: DocumentParseException) {
        throw error
    } catch (error: Exception) {
        throw DocumentParseException("Clinical PDF text extraction failed", error)
    }

    suspend fun copyToKnowledgeBase(uri: Uri, documentId: String, displayName: String): File {
        val destination = knowledgeBaseFile(documentId, displayName)
        return try {
            copyUriBounded(uri, destination)
            destination
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    /** Creates a short-lived plaintext staging file for parsing only. */
    suspend fun copyToStagingFile(uri: Uri, displayName: String): File {
        val suffix = displayName.substringAfterLast('.', "bin")
            .replace(Regex("[^A-Za-z0-9]"), "")
            .take(8)
            .ifBlank { "bin" }
        val destination = File.createTempFile("rag_stage_", ".$suffix", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                copyInputStreamBounded(input, destination)
            } ?: throw DocumentAccessException("Akses dokumen dicabut. Pilih ulang dokumen dari penyimpanan.")
            destination
        } catch (error: Exception) {
            destination.delete()
            if (error is DocumentParseException) throw error
            throw DocumentParseException("Dokumen tidak dapat disalin ke staging lokal.", error)
        }
    }

    fun copyToStagingFile(input: InputStream, displayName: String): File {
        val suffix = displayName.substringAfterLast('.', "bin")
            .replace(Regex("[^A-Za-z0-9]"), "")
            .take(8)
            .ifBlank { "bin" }
        val destination = File.createTempFile("rag_stage_", ".$suffix", context.cacheDir)
        return try {
            input.use { copyInputStreamBounded(it, destination) }
            destination
        } catch (error: Exception) {
            destination.delete()
            throw DocumentParseException("Dokumen tidak dapat disalin ke staging lokal.", error)
        }
    }

    private fun copyInputStreamBounded(input: InputStream, destination: File) {
        var total = 0L
        destination.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= DocumentIngestionLimits.MAX_FILE_BYTES) {
                    "Ukuran dokumen melebihi batas impor lokal."
                }
                output.write(buffer, 0, count)
            }
        }
    }

    fun copyLocalToKnowledgeBase(source: File, documentId: String, displayName: String): File {
        requireFileWithinLimits(source)
        val destination = knowledgeBaseFile(documentId, displayName)
        return try {
            source.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            destination
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    fun knowledgeBaseFile(documentId: String, displayName: String): File {
        val directory = File(context.filesDir, "app_knowledge_base").also(File::mkdirs)
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return File(directory, "${documentId}_$safeName")
    }

    private suspend fun extractPages(document: PDDocument, sourceFile: File): PdfExtractionReport {
        require(document.numberOfPages > 0) { "PDF tidak memiliki halaman." }
        var totalCharacters = 0
        val pages = ArrayList<DocumentPageText>()
        var limitReached = false
        for (index in 0 until document.numberOfPages) {
            currentCoroutineContext().ensureActive()
            val stripper = PDFTextStripper().apply {
                startPage = index + 1
                endPage = index + 1
            }
            val pageText = stripper.getText(document).trim()
            val page = if (pageText.isNotBlank()) {
                DocumentPageText(index + 1, pageText)
            } else {
                when (val ocr = pdfOcrEngine.extractPageText(sourceFile, index)) {
                    is PdfOcrResult.Extracted -> DocumentPageText(
                        pageNumber = index + 1,
                        text = ocr.text.trim(),
                        status = if (ocr.text.isBlank()) PageExtractionStatus.FAILED else PageExtractionStatus.OCR_COMPLETE,
                        ocrProvenance = ocr.provenance
                    )
                    is PdfOcrResult.Unavailable -> DocumentPageText(
                        pageNumber = index + 1,
                        text = "",
                        status = PageExtractionStatus.PARTIAL,
                        ocrProvenance = ocr.reason
                    )
                    is PdfOcrResult.Failed -> DocumentPageText(
                        pageNumber = index + 1,
                        text = "",
                        status = PageExtractionStatus.FAILED,
                        ocrProvenance = ocr.reason
                    )
                }
            }
            if (page.text.length > DocumentIngestionLimits.MAX_PAGE_TEXT_CHARACTERS ||
                totalCharacters + page.text.length > DocumentIngestionLimits.MAX_TEXT_CHARACTERS
            ) {
                pages += page.copy(
                    text = "",
                    status = PageExtractionStatus.PARTIAL,
                    ocrProvenance = "PARTIAL_LIMIT_REACHED"
                )
                limitReached = true
                break
            }
            totalCharacters += page.text.length
            pages += page
        }
        return PdfExtractionReport(
            pages = pages,
            limitReached = limitReached,
            totalPageCount = document.numberOfPages,
            firstUnexaminedPage = (pages.lastOrNull()?.pageNumber?.plus(1))
                ?.takeIf { it <= document.numberOfPages }
        )
    }

    private suspend fun copyUriBounded(uri: Uri, destination: File) {
        var copiedBytes = 0L
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copiedBytes += count
                        require(copiedBytes <= DocumentIngestionLimits.MAX_FILE_BYTES) {
                            "Ukuran dokumen melebihi batas impor lokal."
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw DocumentParseException("Unable to open the selected clinical document")
        } catch (error: SecurityException) {
            throw DocumentAccessException(
                "Akses dokumen dicabut. Pilih ulang dokumen dari penyimpanan.",
                error
            )
        }
    }

    private suspend fun <T> withTemporaryDocument(uri: Uri, suffix: String, block: (File) -> T): T {
        val temporary = File.createTempFile("rag_document_", suffix, context.cacheDir)
        return try {
            copyUriBounded(uri, temporary)
            block(temporary)
        } catch (error: DocumentParseException) {
            throw error
        } catch (error: Exception) {
            throw DocumentParseException("Dokumen tidak dapat dibaca dari URI.", error)
        } finally {
            temporary.delete()
        }
    }

    private fun requireFileWithinLimits(file: File) {
        require(file.isFile && file.length() > 0L) { "Dokumen lokal tidak dapat dibaca." }
        require(file.length() <= DocumentIngestionLimits.MAX_FILE_BYTES) {
            "Ukuran dokumen melebihi batas impor lokal."
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun String.requireReadableText(): String {
        if (isNotBlank()) return this
        throw DocumentParseException(
            "PDF tidak memiliki teks yang dapat diindeks. Halaman hasil scan membutuhkan OCR lokal."
        )
    }

    private fun pdfBlocks(documentId: String, report: PdfExtractionReport): List<DocumentBlock> {
        val accumulator = DocumentBlockAccumulator(documentId)
        var paragraphIndex = 0
        report.indexablePages.forEach { page ->
            val status = when (page.status) {
                PageExtractionStatus.COMPLETE -> ExtractionStatus.COMPLETE
                PageExtractionStatus.OCR_COMPLETE -> ExtractionStatus.OCR_COMPLETE
                PageExtractionStatus.PARTIAL -> ExtractionStatus.PARTIAL
                PageExtractionStatus.FAILED -> ExtractionStatus.FAILED
            }
            page.text
                .split(PARAGRAPH_BREAK)
                .map(String::trim)
                .filter(String::isNotBlank)
                .forEach { paragraph ->
                    accumulator.add(
                        blockType = BlockType.PARAGRAPH,
                        text = paragraph,
                        pageNumber = page.pageNumber,
                        paragraphIndex = paragraphIndex++,
                        extractionStatus = status
                    )
                }
        }
        return accumulator.blocks
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 32 * 1024
        val PARAGRAPH_BREAK = Regex("(?:\\r?\\n\\s*){2,}")
    }
}

/** Explicit limits prevent a hostile or malformed import from exhausting local storage or memory. */
object DocumentIngestionLimits {
    /** Source-byte ceiling; extracted text remains capped independently. */
    /** Exact 100 MiB source cap; PDF page count is intentionally unrestricted. */
    const val MAX_FILE_BYTES = 104_857_600L
    const val MAX_PAGE_TEXT_CHARACTERS = 250_000
    const val MAX_TEXT_CHARACTERS = 2_000_000
    const val MAX_CHUNKS = 3_000
    const val MAX_DOCX_XML_BYTES = 64L * 1024L * 1024L
    const val MAX_DOCX_ZIP_ENTRIES = 2_048
    const val MAX_DOCX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L
}

/**
 * Rasterizes only a textless page and runs the ML Kit bundled Latin recognizer.
 * The recognizer is on-device; no page image or transcript is sent to a service.
 */
class MlKitPdfPageOcrEngine(private val context: Context) : PdfPageOcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun extractPageText(pdfFile: File, pageIndex: Int): PdfOcrResult = try {
        val bitmap = renderPage(pdfFile, pageIndex)
        try {
            val text = recognizer.processAwait(InputImage.fromBitmap(bitmap, 0)).trim()
            PdfOcrResult.Extracted(text, PROVENANCE)
        } finally {
            bitmap.recycle()
        }
    } catch (error: Exception) {
        PdfOcrResult.Failed(error.message ?: "OCR lokal gagal membaca halaman scan.")
    }

    private fun renderPage(pdfFile: File, pageIndex: Int): Bitmap {
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(pageIndex in 0 until renderer.pageCount) { "Indeks halaman PDF tidak valid." }
                renderer.openPage(pageIndex).use { page ->
                    val scale = minOf(
                        MAX_RENDER_DIMENSION.toFloat() / page.width.coerceAtLeast(1),
                        MAX_RENDER_DIMENSION.toFloat() / page.height.coerceAtLeast(1),
                        2f
                    )
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    require(width.toLong() * height <= MAX_RENDER_PIXELS) {
                        "Halaman scan terlalu besar untuk OCR lokal."
                    }
                    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }

    private suspend fun com.google.mlkit.vision.text.TextRecognizer.processAwait(image: InputImage): String =
        suspendCancellableCoroutine { continuation ->
            process(image)
                .addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result.text) }
                .addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        }

    private companion object {
        const val PROVENANCE = "mlkit-text-recognition-latin-bundled-16.0.1"
        const val MAX_RENDER_DIMENSION = 2_048
        const val MAX_RENDER_PIXELS = 4_194_304L
    }
}

private fun String.jsonEscaped(): String = buildString(length) {
    for (character in this@jsonEscaped) {
        when (character) {
            '\\' -> append("\\\\")
            '\"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}
