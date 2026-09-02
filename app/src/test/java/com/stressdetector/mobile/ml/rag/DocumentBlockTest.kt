package com.stressdetector.mobile.ml.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentBlockTest {
    @Test
    fun `markdown parser preserves heading list paragraph and offsets`() {
        val blocks = StructuredTextBlockParser.parse(
            documentId = "doc-md",
            text = "# Grounding\n\nEvidence paragraph.\n\n- First check\n- Second check",
            format = DocumentFormat.MARKDOWN
        )

        assertEquals(
            listOf(BlockType.HEADING, BlockType.PARAGRAPH, BlockType.LIST_ITEM, BlockType.LIST_ITEM),
            blocks.map(DocumentBlock::blockType)
        )
        assertEquals(listOf("Grounding"), blocks[1].sectionPath)
        assertTrue(blocks.all { it.extractionStatus == ExtractionStatus.COMPLETE })
        assertTrue(blocks.zipWithNext().all { (left, right) -> left.charEnd <= right.charStart })
    }

    @Test
    fun `plain text parser does not interpret markdown markers`() {
        val blocks = StructuredTextBlockParser.parse(
            documentId = "doc-text",
            text = "# Literal text\n\nSecond paragraph",
            format = DocumentFormat.PLAIN_TEXT
        )

        assertEquals(listOf(BlockType.PARAGRAPH, BlockType.PARAGRAPH), blocks.map(DocumentBlock::blockType))
        assertEquals("# Literal text", blocks.first().text)
    }
}
