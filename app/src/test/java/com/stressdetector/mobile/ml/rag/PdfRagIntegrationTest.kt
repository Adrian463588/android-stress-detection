package com.stressdetector.mobile.ml.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfRagIntegrationTest {

    @Test
    fun testTextEmbedder_GeneratesNormalizedVectors() {
        val embedder = OnDeviceTextEmbedder(vectorDimension = 128)
        val text = "Clinical guidelines state that stress levels over 70 percent require immediate intervention."

        val vector = embedder.embedText(text)

        assertEquals(128, vector.size)

        // Verify L2 norm is ~1.0
        var normSq = 0.0f
        for (v in vector) {
            normSq += v * v
        }
        val norm = Math.sqrt(normSq.toDouble()).toFloat()
        assertTrue("Norm should be approximately 1.0f", Math.abs(1.0f - norm) < 0.01f)
    }

    @Test
    fun testVectorSerialization_FloatArrayToByteArrayRoundtrip() {
        val embedder = OnDeviceTextEmbedder()
        val originalVector = embedder.embedText("Deep breathing and progressive muscle relaxation.")

        val blob = OnDeviceTextEmbedder.floatArrayToByteArray(originalVector)
        val restoredVector = OnDeviceTextEmbedder.byteArrayToFloatArray(blob)

        assertEquals(originalVector.size, restoredVector.size)
        for (i in originalVector.indices) {
            assertEquals(originalVector[i], restoredVector[i], 0.0001f)
        }
    }

    @Test
    fun testCosineSimilarity_SimilarTextsHaveHigherScore() {
        val embedder = OnDeviceTextEmbedder()

        val query = embedder.embedText("How to manage high stress and anxiety?")
        val relevantDoc = embedder.embedText("Relaxation techniques and CBT reduce high stress and anxiety levels.")
        val irrelevantDoc = embedder.embedText("The weather forecast predicts rain and cloudy skies tomorrow.")

        val scoreRelevant = embedder.cosineSimilarity(query, relevantDoc)
        val scoreIrrelevant = embedder.cosineSimilarity(query, irrelevantDoc)

        assertTrue(
            "Relevant doc score ($scoreRelevant) should be higher than irrelevant doc score ($scoreIrrelevant)",
            scoreRelevant > scoreIrrelevant
        )
    }

    @Test
    fun testTextChunker_SplitsWithOverlap() {
        val chunker = TextChunker { whitespaceTokenizer() }
        val text = (1..100).joinToString(" ") { "word$it" }
        val block = DocumentBlock(
            documentId = "fixture",
            blockType = BlockType.PARAGRAPH,
            text = text,
            pageNumber = 1,
            charStart = 0,
            charEnd = text.length,
            extractionStatus = ExtractionStatus.COMPLETE
        )
        val chunks = chunker.chunkBlock(
            block,
            TextChunker.Config(targetTokens = 50, hardMaximumTokens = 50, overlapTokens = 10)
        )

        assertTrue("Should produce multiple chunks", chunks.size > 1)
        assertNotNull("First chunk should not be null", chunks[0])
    }
}
