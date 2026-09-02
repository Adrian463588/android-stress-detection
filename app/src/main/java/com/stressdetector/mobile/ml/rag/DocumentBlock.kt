package com.stressdetector.mobile.ml.rag

/** Semantic structure preserved by document parsing before chunking. */
enum class BlockType {
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    TABLE_ROW
}

/** Extraction evidence attached to each block and source page. */
enum class ExtractionStatus {
    COMPLETE,
    OCR_COMPLETE,
    PARTIAL,
    FAILED
}

data class DocumentBlock(
    val documentId: String,
    val blockType: BlockType,
    val text: String,
    val pageNumber: Int? = null,
    val sectionPath: List<String> = emptyList(),
    val paragraphIndex: Int? = null,
    val tableRowIndex: Int? = null,
    val charStart: Int,
    val charEnd: Int,
    val extractionStatus: ExtractionStatus
) {
    init {
        require(documentId.isNotBlank()) { "Document ID wajib tersedia." }
        require(text.isNotBlank()) { "Document block tidak boleh kosong." }
        require(pageNumber == null || pageNumber > 0) { "Nomor halaman harus positif." }
        require(charStart >= 0 && charEnd == charStart + text.length) {
            "Offset karakter document block tidak konsisten."
        }
    }
}

data class ParsedDocument(
    val format: DocumentFormat,
    val blocks: List<DocumentBlock>,
    val extractionStatus: ExtractionStatus,
    val extractionAudit: String,
    val pageExtractionJson: String? = null,
    val ocrProvenance: String? = null
) {
    val isRetrievalReady: Boolean
        get() = blocks.isNotEmpty() && extractionStatus in RETRIEVAL_READY_STATUSES

    val indexedText: String get() = blocks.joinToString("\n", transform = DocumentBlock::text)

    private companion object {
        val RETRIEVAL_READY_STATUSES = setOf(ExtractionStatus.COMPLETE, ExtractionStatus.OCR_COMPLETE)
    }
}

/** Builds stable document-wide character offsets without exposing mutable state. */
internal class DocumentBlockAccumulator(private val documentId: String) {
    private val mutableBlocks = mutableListOf<DocumentBlock>()
    private var nextCharOffset = 0

    val blocks: List<DocumentBlock> get() = mutableBlocks.toList()

    fun add(
        blockType: BlockType,
        text: String,
        pageNumber: Int? = null,
        sectionPath: List<String> = emptyList(),
        paragraphIndex: Int? = null,
        tableRowIndex: Int? = null,
        extractionStatus: ExtractionStatus = ExtractionStatus.COMPLETE
    ) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        val start = nextCharOffset
        mutableBlocks += DocumentBlock(
            documentId = documentId,
            blockType = blockType,
            text = normalized,
            pageNumber = pageNumber,
            sectionPath = sectionPath.toList(),
            paragraphIndex = paragraphIndex,
            tableRowIndex = tableRowIndex,
            charStart = start,
            charEnd = start + normalized.length,
            extractionStatus = extractionStatus
        )
        nextCharOffset = start + normalized.length + BLOCK_SEPARATOR_LENGTH
    }

    private companion object {
        const val BLOCK_SEPARATOR_LENGTH = 1
    }
}
