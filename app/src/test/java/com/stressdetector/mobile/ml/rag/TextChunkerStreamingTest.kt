package com.stressdetector.mobile.ml.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerStreamingTest {
    private val tokenizer = whitespaceTokenizer()
    private val chunker = TextChunker { tokenizer }

    @Test
    fun `chunks are token bounded overlap versioned and source aligned`() {
        val text = (1..620).joinToString(" ") { "word$it" }
        val block = block(text, sectionPath = listOf("Clinical guidance", "Stress"))

        val chunks = chunker.chunkBlock(block)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.tokenCount <= 256 })
        assertTrue(chunks.all { it.policyVersion == TextChunker.POLICY_VERSION })
        assertTrue(chunks.all { text.substring(it.charStart, it.charEnd) == it.text })
        chunks.zipWithNext().forEach { (first, second) ->
            assertEquals(first.tokenEnd - 32, second.tokenStart)
        }
    }

    @Test
    fun `one oversized sentence is split without silent truncation`() {
        val text = (1..800).joinToString(" ") { "token$it" }
        val chunks = chunker.chunkBlock(block(text), TextChunker.Config(targetTokens = 224, hardMaximumTokens = 256))

        assertTrue(chunks.size >= 4)
        assertTrue(chunks.all { tokenizer.tokenize(it.embeddingText, false).size == it.tokenCount })
        assertTrue(chunks.all { it.tokenCount <= 256 })
        assertEquals(0, chunks.first().tokenStart)
        assertEquals(tokenizer.tokenize(text, false).size, chunks.last().tokenEnd)
    }

    @Test
    fun `separate document blocks are never merged`() {
        val first = chunker.chunkBlock(block("alpha beta gamma", documentId = "doc", charStart = 0))
        val second = chunker.chunkBlock(block("delta epsilon", documentId = "doc", charStart = 17))

        assertEquals("alpha beta gamma", first.single().text)
        assertEquals("delta epsilon", second.single().text)
    }

    private fun block(text: String, documentId: String = "doc-1", sectionPath: List<String> = emptyList(), charStart: Int = 0) =
        DocumentBlock(
            documentId = documentId,
            blockType = BlockType.PARAGRAPH,
            text = text,
            pageNumber = 1,
            sectionPath = sectionPath,
            charStart = charStart,
            charEnd = charStart + text.length,
            extractionStatus = ExtractionStatus.COMPLETE
        )
}

internal fun whitespaceTokenizer(): RagTokenizer = RagTokenizer { text, addSpecialTokens ->
    val tokens = Regex("\\S+").findAll(text).mapIndexed { index, match ->
        RagToken(index.toLong() + 10L, match.range.first, match.range.last + 1)
    }.toMutableList()
    if (addSpecialTokens) {
        tokens.add(0, RagToken(0L, 0, 0))
        tokens.add(RagToken(2L, text.length, text.length))
    }
    RagTokenization(tokens)
}
