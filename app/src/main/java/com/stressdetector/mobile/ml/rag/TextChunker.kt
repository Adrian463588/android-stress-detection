package com.stressdetector.mobile.ml.rag

import java.text.BreakIterator
import java.util.Locale

data class TokenAwareChunk(
    val text: String,
    val embeddingText: String,
    val charStart: Int,
    val charEnd: Int,
    val tokenStart: Int,
    val tokenEnd: Int,
    val tokenCount: Int,
    val policyVersion: String
)

/** Canonical document chunker. One invocation receives exactly one structured DocumentBlock. */
class TextChunker(private val tokenizerProvider: () -> RagTokenizer) {
    data class Config(
        val targetTokens: Int = 224,
        val hardMaximumTokens: Int = 256,
        val overlapTokens: Int = 32,
        val policyVersion: String = POLICY_VERSION
    ) {
        init {
            require(targetTokens > 0)
            require(hardMaximumTokens >= targetTokens)
            require(overlapTokens in 0 until targetTokens)
            require(policyVersion.isNotBlank())
        }
    }

    fun chunkBlock(block: DocumentBlock, config: Config = Config()): List<TokenAwareChunk> {
        require(block.text.isNotBlank()) { "Document block must not be blank." }
        val tokenizer = tokenizerProvider()
        val heading = block.sectionPath.joinToString(" > ").trim()
        val headingTokens = if (heading.isEmpty()) 0 else tokenizer.tokenize("$heading\n", false).size
        val hardBodyLimit = config.hardMaximumTokens - headingTokens
        val targetBodyLimit = (config.targetTokens - headingTokens).coerceAtLeast(1)
        require(hardBodyLimit > 0) { "Section heading consumes the complete chunk token budget." }

        val body = tokenizer.tokenize(block.text, false)
        if (body.size == 0) return emptyList()
        val sentenceEnds = sentenceTokenEnds(block.text, body.tokens)
        val chunks = mutableListOf<TokenAwareChunk>()
        var tokenStart = 0
        while (tokenStart < body.size) {
            val desiredEnd = (tokenStart + targetBodyLimit).coerceAtMost(body.size)
            val hardEnd = (tokenStart + hardBodyLimit).coerceAtMost(body.size)
            val tokenEnd = chooseBoundary(tokenStart, desiredEnd, hardEnd, body.size, sentenceEnds)
            require(tokenEnd > tokenStart) { "Chunking did not advance." }

            val rawStart = body.tokens[tokenStart].charStart.coerceIn(0, block.text.length)
            val rawEnd = body.tokens[tokenEnd - 1].charEnd.coerceIn(rawStart, block.text.length)
            val charStart = skipLeadingWhitespace(block.text, rawStart, rawEnd)
            val charEnd = skipTrailingWhitespace(block.text, charStart, rawEnd)
            require(charEnd > charStart) { "Tokenizer produced an empty source span." }
            val chunkText = block.text.substring(charStart, charEnd)
            val embeddingText = if (heading.isEmpty()) chunkText else "$heading\n$chunkText"
            val exactTokenCount = tokenizer.tokenize(embeddingText, false).size
            require(exactTokenCount <= config.hardMaximumTokens) {
                "Chunk exceeds the ${config.hardMaximumTokens}-token hard limit."
            }
            chunks += TokenAwareChunk(
                text = chunkText,
                embeddingText = embeddingText,
                charStart = block.charStart + charStart,
                charEnd = block.charStart + charEnd,
                tokenStart = tokenStart,
                tokenEnd = tokenEnd,
                tokenCount = exactTokenCount,
                policyVersion = config.policyVersion
            )
            if (tokenEnd == body.size) break
            tokenStart = (tokenEnd - config.overlapTokens).coerceAtLeast(tokenStart + 1)
        }
        return chunks
    }

    fun countChunks(block: DocumentBlock, config: Config = Config()): Int = chunkBlock(block, config).size

    private fun chooseBoundary(start: Int, desiredEnd: Int, hardEnd: Int, totalTokens: Int, sentenceEnds: Set<Int>): Int {
        if (hardEnd == totalTokens) return totalTokens
        val preferred = sentenceEnds.filter { it in (start + 1)..desiredEnd }.maxOrNull()
        if (preferred != null) return preferred
        return sentenceEnds.filter { it in (desiredEnd + 1)..hardEnd }.minOrNull() ?: hardEnd
    }

    private fun sentenceTokenEnds(text: String, tokens: List<RagToken>): Set<Int> {
        val iterator = BreakIterator.getSentenceInstance(Locale.ROOT).apply { setText(text) }
        val characterEnds = buildSet {
            var end = iterator.first()
            while (end != BreakIterator.DONE) {
                if (end > 0) add(end)
                end = iterator.next()
            }
            text.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
            add(text.length)
        }
        return characterEnds.mapNotNullTo(mutableSetOf()) { boundary ->
            val index = tokens.indexOfLast { it.charEnd <= boundary }
            (index + 1).takeIf { it > 0 }
        }
    }

    private fun skipLeadingWhitespace(text: String, start: Int, end: Int): Int {
        var cursor = start
        while (cursor < end && text[cursor].isWhitespace()) cursor++
        return cursor
    }

    private fun skipTrailingWhitespace(text: String, start: Int, end: Int): Int {
        var cursor = end
        while (cursor > start && text[cursor - 1].isWhitespace()) cursor--
        return cursor
    }

    companion object {
        const val POLICY_VERSION = "e5_wordpiece_224_256_32_v1"
    }
}
