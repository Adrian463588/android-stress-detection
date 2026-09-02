package com.stressdetector.mobile.ml.rag

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * Bounded OOXML reader for visible WordprocessingML content. It reads only
 * `word/document.xml`, rejects external entities, and preserves block-level
 * provenance without materialising the complete XML document.
 */
class DocxTextExtractor(
    private val maxTextCharacters: Int = DocumentIngestionLimits.MAX_TEXT_CHARACTERS,
    private val maxXmlBytes: Long = DocumentIngestionLimits.MAX_DOCX_XML_BYTES,
    private val maxZipEntries: Int = DocumentIngestionLimits.MAX_DOCX_ZIP_ENTRIES
) {
    fun extract(file: File): String = extractBlocks(file, file.name)
        .joinToString("\n", transform = DocumentBlock::text)

    fun extractBlocks(file: File, documentId: String): List<DocumentBlock> {
        require(file.isFile) { "DOCX tidak dapat dibaca." }
        require(file.length() <= DocumentIngestionLimits.MAX_FILE_BYTES) {
            "Ukuran dokumen melebihi batas impor lokal."
        }
        return try {
            ZipFile(file).use { zip ->
                validateArchive(zip)
                val xmlEntry = zip.getEntry(DOCUMENT_XML)
                    ?: throw DocumentParseException("DOCX tidak memiliki word/document.xml")
                parseXml(zip.getInputStream(xmlEntry), documentId)
            }
        } catch (error: DocumentParseException) {
            throw error
        } catch (error: Exception) {
            throw DocumentParseException("DOCX tidak dapat dibaca.", error)
        }.also { blocks ->
            if (blocks.isEmpty()) throw DocumentParseException("DOCX tidak memiliki teks terlihat yang dapat diindeks.")
        }
    }

    private fun validateArchive(zip: ZipFile) {
        var entries = 0
        var declaredUncompressedBytes = 0L
        zip.entries().asSequence().forEach { entry ->
            entries++
            require(entries <= maxZipEntries) { "DOCX memiliki terlalu banyak entri ZIP." }
            if (!entry.isDirectory && entry.size >= 0L) {
                declaredUncompressedBytes += entry.size
                require(declaredUncompressedBytes <= DocumentIngestionLimits.MAX_DOCX_UNCOMPRESSED_BYTES) {
                    "DOCX memiliki data terkompresi di luar batas impor lokal."
                }
                if (entry.name == DOCUMENT_XML) {
                    require(entry.size <= maxXmlBytes) { "DOCX XML melebihi batas impor lokal." }
                }
            }
        }
    }

    private fun parseXml(input: InputStream, documentId: String): List<DocumentBlock> {
        val factory = secureSaxFactory()
        CountingInputStream(input, maxXmlBytes).use { counted ->
            val accumulator = DocumentBlockAccumulator(documentId)
            val sectionPath = mutableListOf<String>()
            val paragraph = StringBuilder()
            val rowCells = mutableListOf<String>()
            val cellText = StringBuilder()
            var paragraphStyle: String? = null
            var paragraphIsList = false
            var paragraphIndex = 0
            var tableRowIndex = 0
            var tableDepth = 0
            var inCell = false
            var inText = false
            var inRun = false
            var hiddenRun = false
            var deletedDepth = 0
            var fieldInstructionDepth = 0
            var visibleCharacterCount = 0

            fun appendVisible(value: String) {
                if (!inRun || hiddenRun || deletedDepth > 0 || fieldInstructionDepth > 0) return
                visibleCharacterCount += value.length
                if (visibleCharacterCount > maxTextCharacters) {
                    throw DocumentParseException("Teks dokumen melebihi batas impor lokal.")
                }
                paragraph.append(value)
            }

            fun updateSectionPath(level: Int, text: String): List<String> {
                while (sectionPath.size >= level) sectionPath.removeAt(sectionPath.lastIndex)
                while (sectionPath.size < level - 1) sectionPath += "Untitled"
                sectionPath += text
                return sectionPath.toList()
            }

            fun flushParagraph() {
                val text = paragraph.toString().trim()
                paragraph.clear()
                if (tableDepth > 0 && inCell) {
                    if (text.isNotBlank()) {
                        if (cellText.isNotEmpty()) cellText.append('\n')
                        cellText.append(text)
                    }
                    paragraphIndex++
                    paragraphStyle = null
                    paragraphIsList = false
                    return
                }
                if (text.isNotBlank()) {
                    val headingLevel = paragraphStyle?.let(HEADING_STYLE::matchEntire)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                    when {
                        headingLevel != null -> accumulator.add(
                            blockType = BlockType.HEADING,
                            text = text,
                            sectionPath = updateSectionPath(headingLevel.coerceIn(1, 6), text),
                            paragraphIndex = paragraphIndex
                        )
                        paragraphIsList -> accumulator.add(
                            blockType = BlockType.LIST_ITEM,
                            text = text,
                            sectionPath = sectionPath,
                            paragraphIndex = paragraphIndex
                        )
                        else -> accumulator.add(
                            blockType = BlockType.PARAGRAPH,
                            text = text,
                            sectionPath = sectionPath,
                            paragraphIndex = paragraphIndex
                        )
                    }
                }
                paragraphIndex++
                paragraphStyle = null
                paragraphIsList = false
            }

            val handler = object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    when (elementName(localName, qName)) {
                        "tbl" -> tableDepth++
                        "tr" -> if (tableDepth > 0) rowCells.clear()
                        "tc" -> if (tableDepth > 0) {
                            inCell = true
                            cellText.clear()
                        }
                        "p" -> {
                            paragraph.clear()
                            paragraphStyle = null
                            paragraphIsList = false
                        }
                        "pStyle" -> paragraphStyle = attributes.wordValue()
                        "numPr" -> paragraphIsList = true
                        "r" -> {
                            inRun = true
                            hiddenRun = false
                        }
                        "vanish", "webHidden", "specVanish" -> if (inRun) hiddenRun = true
                        "del", "moveFrom" -> deletedDepth++
                        "instrText" -> fieldInstructionDepth++
                        "t" -> inText = true
                        "tab" -> appendVisible("\t")
                        "br", "cr" -> appendVisible("\n")
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inText) appendVisible(String(ch, start, length))
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    when (elementName(localName, qName)) {
                        "t" -> inText = false
                        "instrText" -> fieldInstructionDepth = (fieldInstructionDepth - 1).coerceAtLeast(0)
                        "del", "moveFrom" -> deletedDepth = (deletedDepth - 1).coerceAtLeast(0)
                        "r" -> {
                            inRun = false
                            hiddenRun = false
                        }
                        "p" -> flushParagraph()
                        "tc" -> if (tableDepth > 0) {
                            rowCells += cellText.toString().trim()
                            cellText.clear()
                            inCell = false
                        }
                        "tr" -> if (tableDepth > 0) {
                            val rowText = rowCells.joinToString("\t").trim()
                            if (rowText.isNotBlank()) {
                                accumulator.add(
                                    blockType = BlockType.TABLE_ROW,
                                    text = rowText,
                                    sectionPath = sectionPath,
                                    tableRowIndex = tableRowIndex++
                                )
                            }
                            rowCells.clear()
                        }
                        "tbl" -> tableDepth = (tableDepth - 1).coerceAtLeast(0)
                    }
                }
            }
            factory.newSAXParser().parse(InputSource(counted), handler)
            return accumulator.blocks
        }
    }

    private fun secureSaxFactory(): SAXParserFactory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }

    private class CountingInputStream(input: InputStream, private val maxBytes: Long) : java.io.FilterInputStream(input) {
        private var totalBytes = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) increment(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) increment(count.toLong())
            return count
        }

        private fun increment(count: Long) {
            totalBytes += count
            require(totalBytes <= maxBytes) { "DOCX XML melebihi batas impor lokal." }
        }
    }

    private companion object {
        const val DOCUMENT_XML = "word/document.xml"
        const val WORD_NAMESPACE = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val HEADING_STYLE = Regex("(?i)Heading\\s*([1-6])")

        fun elementName(localName: String?, qName: String?): String =
            localName.orEmpty().ifBlank { qName.orEmpty().substringAfterLast(':') }

        fun Attributes?.wordValue(): String? = this?.getValue(WORD_NAMESPACE, "val")
            ?: this?.getValue("w:val")
            ?: this?.getValue("val")
    }
}
