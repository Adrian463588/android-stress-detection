package com.stressdetector.mobile.ml.rag

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocxTextExtractorTest {
    @Test
    fun `extracts paragraphs tabs and line breaks without reading binary zip as text`() {
        val file = createDocx(
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
                "<w:p><w:r><w:t>Green</w:t><w:tab/><w:t>25 Hz</w:t><w:br/><w:t>stable</w:t></w:r></w:p>" +
                "</w:body></w:document>"
        )
        try {
            assertEquals("Green\t25 Hz\nstable", DocxTextExtractor().extract(file))
        } finally {
            file.delete()
        }
    }

    @Test(expected = DocumentParseException::class)
    fun `malformed zip fails closed`() {
        val file = File.createTempFile("invalid_", ".docx")
        try {
            file.writeText("not a zip")
            DocxTextExtractor().extract(file)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `text limit is enforced while XML is streamed`() {
        val file =
            createDocx(
                "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:t>12345</w:t></w:p></w:body></w:document>"
            )
        try {
            val error = runCatching { DocxTextExtractor(maxTextCharacters = 4).extract(file) }.exceptionOrNull()
            assertTrue(error is DocumentParseException)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `extracts visible structured blocks and excludes hidden deleted and field instruction text`() {
        val file = createDocx(
            """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
              <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Assessment</w:t></w:r></w:p>
              <w:p><w:r><w:t>Visible paragraph</w:t></w:r>
                <w:r><w:rPr><w:vanish/></w:rPr><w:t>HIDDEN</w:t></w:r>
                <w:del><w:r><w:delText>DELETED</w:delText></w:r></w:del>
                <w:r><w:instrText>INSTRUCTION</w:instrText></w:r>
              </w:p>
              <w:p><w:pPr><w:numPr><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>First item</w:t></w:r></w:p>
              <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Name</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Value</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            </w:body></w:document>
            """.trimIndent()
        )
        try {
            val blocks = DocxTextExtractor().extractBlocks(file, "doc-1")

            assertEquals(
                listOf(BlockType.HEADING, BlockType.PARAGRAPH, BlockType.LIST_ITEM, BlockType.TABLE_ROW),
                blocks.map(DocumentBlock::blockType)
            )
            assertEquals(listOf("Assessment"), blocks[1].sectionPath)
            assertEquals(0, blocks[3].tableRowIndex)
            assertEquals("Name\tValue", blocks[3].text)
            assertTrue(blocks.zipWithNext().all { (left, right) -> left.charEnd <= right.charStart })
            val visible = blocks.joinToString("\n", transform = DocumentBlock::text)
            assertFalse(visible.contains("HIDDEN"))
            assertFalse(visible.contains("DELETED"))
            assertFalse(visible.contains("INSTRUCTION"))
        } finally {
            file.delete()
        }
    }

    private fun createDocx(xml: String): File = File.createTempFile("fixture_", ".docx").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
