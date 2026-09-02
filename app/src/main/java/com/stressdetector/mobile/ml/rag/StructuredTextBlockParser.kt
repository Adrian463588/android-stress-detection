package com.stressdetector.mobile.ml.rag

/** Deterministic block parser for strict UTF-8 text and Markdown imports. */
object StructuredTextBlockParser {
    private val markdownHeading = Regex("^(#{1,6})\\s+(.+?)\\s*$")
    private val markdownList = Regex("^\\s*(?:[-+*]|\\d+[.)])\\s+(.+?)\\s*$")

    fun parse(
        documentId: String,
        text: String,
        format: DocumentFormat,
        pageNumber: Int? = null,
        extractionStatus: ExtractionStatus = ExtractionStatus.COMPLETE
    ): List<DocumentBlock> {
        require(format == DocumentFormat.PLAIN_TEXT || format == DocumentFormat.MARKDOWN) {
            "StructuredTextBlockParser hanya menerima TXT atau Markdown."
        }
        val accumulator = DocumentBlockAccumulator(documentId)
        val sectionPath = mutableListOf<String>()
        val paragraphLines = mutableListOf<String>()
        var paragraphIndex = 0

        fun flushParagraph() {
            val paragraph = paragraphLines.joinToString("\n").trim()
            paragraphLines.clear()
            if (paragraph.isNotBlank()) {
                accumulator.add(
                    blockType = BlockType.PARAGRAPH,
                    text = paragraph,
                    pageNumber = pageNumber,
                    sectionPath = sectionPath,
                    paragraphIndex = paragraphIndex++,
                    extractionStatus = extractionStatus
                )
            }
        }

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (line.isBlank()) {
                flushParagraph()
                return@forEach
            }
            if (format == DocumentFormat.MARKDOWN) {
                val heading = markdownHeading.matchEntire(line)
                if (heading != null) {
                    flushParagraph()
                    val level = heading.groupValues[1].length
                    val headingText = heading.groupValues[2].trim()
                    while (sectionPath.size >= level) sectionPath.removeAt(sectionPath.lastIndex)
                    while (sectionPath.size < level - 1) sectionPath += "Untitled"
                    sectionPath += headingText
                    accumulator.add(
                        blockType = BlockType.HEADING,
                        text = headingText,
                        pageNumber = pageNumber,
                        sectionPath = sectionPath,
                        paragraphIndex = paragraphIndex++,
                        extractionStatus = extractionStatus
                    )
                    return@forEach
                }
                val listItem = markdownList.matchEntire(line)
                if (listItem != null) {
                    flushParagraph()
                    accumulator.add(
                        blockType = BlockType.LIST_ITEM,
                        text = listItem.groupValues[1],
                        pageNumber = pageNumber,
                        sectionPath = sectionPath,
                        paragraphIndex = paragraphIndex++,
                        extractionStatus = extractionStatus
                    )
                    return@forEach
                }
            }
            paragraphLines += line
        }
        flushParagraph()
        return accumulator.blocks
    }
}
