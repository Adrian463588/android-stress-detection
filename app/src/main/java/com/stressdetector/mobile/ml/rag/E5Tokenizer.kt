package com.stressdetector.mobile.ml.rag

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.text.Normalizer

/** Token produced by the tokenizer, mapped back to the source character range. */
data class RagToken(val id: Long, val charStart: Int, val charEnd: Int)

data class RagTokenization(val tokens: List<RagToken>) {
    val ids: LongArray get() = tokens.map(RagToken::id).toLongArray()
    val size: Int get() = tokens.size
}

/** Small boundary used by both chunking and embedding; implementations must never truncate. */
fun interface RagTokenizer {
    fun tokenize(text: String, addSpecialTokens: Boolean): RagTokenization
}

/**
 * Local tokenizer.json implementation for the XLM-R Unigram + Metaspace contract used by
 * intfloat/multilingual-e5-small. Unsupported tokenizer contracts fail closed.
 */
internal class XlmRobertaUnigramTokenizer private constructor(
    pieces: List<Piece>,
    private val unknownId: Long,
    private val beginningOfSentenceId: Long,
    private val endOfSentenceId: Long
) : RagTokenizer {
    internal data class Piece(val token: String, val score: Double, val id: Long)

    private val oneCodePointPieces = pieces
        .filter { it.token.codePointCount(0, it.token.length) == 1 }
        .groupBy { it.token.codePointAt(0) }
    private val multiCodePointPieces = pieces
        .filter { it.token.codePointCount(0, it.token.length) > 1 }
        .groupBy { prefixKey(it.token, 0) }
        .mapValues { (_, candidates) -> candidates.sortedByDescending { it.token.length } }

    override fun tokenize(text: String, addSpecialTokens: Boolean): RagTokenization {
        if (text.isEmpty()) {
            return if (addSpecialTokens) {
                RagTokenization(
                    listOf(
                        RagToken(beginningOfSentenceId, 0, 0),
                        RagToken(endOfSentenceId, 0, 0)
                    )
                )
            } else {
                RagTokenization(emptyList())
            }
        }
        val normalized = normalizeWithOffsets(text)
        val body = unigram(normalized)
        if (!addSpecialTokens) return RagTokenization(body)
        return RagTokenization(
            buildList(body.size + 2) {
                add(RagToken(beginningOfSentenceId, 0, 0))
                addAll(body)
                add(RagToken(endOfSentenceId, text.length, text.length))
            }
        )
    }

    private fun unigram(normalized: NormalizedInput): List<RagToken> {
        val value = normalized.value
        val bestScore = DoubleArray(value.length + 1) { Double.NEGATIVE_INFINITY }
        val previous = IntArray(value.length + 1) { -1 }
        val selected = arrayOfNulls<Piece>(value.length + 1)
        bestScore[0] = 0.0

        var start = 0
        while (start < value.length) {
            if (bestScore[start].isFinite()) {
                val firstCodePoint = value.codePointAt(start)
                val candidates = buildList {
                    addAll(oneCodePointPieces[firstCodePoint].orEmpty())
                    addAll(multiCodePointPieces[prefixKey(value, start)].orEmpty())
                }
                candidates.forEach { piece ->
                    if (!value.startsWith(piece.token, start)) return@forEach
                    val end = start + piece.token.length
                    if (!isCodePointBoundary(value, end)) return@forEach
                    val score = bestScore[start] + piece.score
                    if (score > bestScore[end]) {
                        bestScore[end] = score
                        previous[end] = start
                        selected[end] = piece
                    }
                }
                // Keep an unknown-token path even when a longer piece matches.
                // A locally matching piece can lead to a dead end later in the
                // Viterbi graph; omitting this edge would make valid Unicode
                // input fail instead of deterministically emitting <unk>.
                val unknownEnd = start + Character.charCount(firstCodePoint)
                val unknownScore = bestScore[start] + UNKNOWN_SCORE
                if (unknownScore > bestScore[unknownEnd]) {
                    bestScore[unknownEnd] = unknownScore
                    previous[unknownEnd] = start
                    selected[unknownEnd] = Piece(
                        value.substring(start, unknownEnd),
                        UNKNOWN_SCORE,
                        unknownId
                    )
                }
            }
            start += Character.charCount(value.codePointAt(start))
        }

        require(previous[value.length] >= 0) { "Tokenizer could not produce a complete Unigram path." }
        val reversed = mutableListOf<RagToken>()
        var cursor = value.length
        while (cursor > 0) {
            val from = previous[cursor]
            val piece = selected[cursor] ?: error("Tokenizer path is incomplete.")
            require(from >= 0 && from < cursor) { "Tokenizer path did not advance." }
            reversed += RagToken(
                id = piece.id,
                charStart = normalized.sourceStart(from, cursor),
                charEnd = normalized.sourceEnd(from, cursor)
            )
            cursor = from
        }
        return reversed.asReversed()
    }

    companion object {
        private const val METASPACE = "▁"
        private const val UNKNOWN_SCORE = -100.0
        private val SPECIAL_TOKENS = setOf("<s>", "<pad>", "</s>", "<unk>", "<mask>")

        fun load(file: File): XlmRobertaUnigramTokenizer =
            file.reader(Charsets.UTF_8).use { parse(JsonParser.parseReader(it).asJsonObject) }

        internal fun parse(root: JsonObject): XlmRobertaUnigramTokenizer {
            val model = root.getAsJsonObject("model")
            require(model?.get("type")?.asString == "Unigram") {
                "Tokenizer must use the XLM-R Unigram model."
            }
            require(root.containsContractNode("Metaspace")) {
                "Tokenizer must declare the XLM-R Metaspace pre-tokenizer."
            }
            require(!root.containsContractNode("Lowercase")) {
                "Lowercasing is incompatible with the pinned multilingual E5 tokenizer."
            }
            val vocabulary = model.getAsJsonArray("vocab").mapIndexed { index, element ->
                val pair = element.asJsonArray
                Piece(pair[0].asString, pair[1].asDouble, index.toLong())
            }
            val byToken = vocabulary.associateBy(Piece::token)
            val unknownId = model.get("unk_id")?.asLong ?: byToken.getValue("<unk>").id
            return XlmRobertaUnigramTokenizer(
                pieces = vocabulary.filter { it.token !in SPECIAL_TOKENS && it.token.isNotEmpty() },
                unknownId = unknownId,
                beginningOfSentenceId = byToken.getValue("<s>").id,
                endOfSentenceId = byToken.getValue("</s>").id
            )
        }

        private fun JsonElement.containsContractNode(expectedType: String): Boolean = when {
            isJsonObject -> {
                val obj = asJsonObject
                obj.get("type")?.asString == expectedType || obj.entrySet().any { it.value.containsContractNode(expectedType) }
            }
            isJsonArray -> asJsonArray.any { it.containsContractNode(expectedType) }
            else -> false
        }

        private fun prefixKey(value: String, start: Int): String {
            val firstEnd = start + Character.charCount(value.codePointAt(start))
            if (firstEnd >= value.length) return value.substring(start, firstEnd)
            val secondEnd = firstEnd + Character.charCount(value.codePointAt(firstEnd))
            return value.substring(start, secondEnd)
        }

        private fun isCodePointBoundary(value: String, index: Int): Boolean =
            index == value.length || index == 0 || !Character.isLowSurrogate(value[index])

        private fun normalizeWithOffsets(source: String): NormalizedInput {
            val output = StringBuilder()
            val starts = mutableListOf<Int>()
            val ends = mutableListOf<Int>()
            output.append(METASPACE)
            starts += 0
            ends += 0

            var index = 0
            var pendingWhitespaceStart: Int? = null
            while (index < source.length) {
                val codePoint = source.codePointAt(index)
                val end = index + Character.charCount(codePoint)
                if (Character.isWhitespace(codePoint)) {
                    if (pendingWhitespaceStart == null) pendingWhitespaceStart = index
                    index = end
                    continue
                }
                pendingWhitespaceStart?.let { whitespaceStart ->
                    if (output.lastOrNull()?.toString() != METASPACE) {
                        output.append(METASPACE)
                        starts += whitespaceStart
                        ends += index
                    }
                    pendingWhitespaceStart = null
                }
                val normalizedCodePoint = Normalizer.normalize(source.substring(index, end), Normalizer.Form.NFKC)
                normalizedCodePoint.forEach { character ->
                    output.append(character)
                    starts += index
                    ends += end
                }
                index = end
            }
            return NormalizedInput(output.toString(), starts.toIntArray(), ends.toIntArray())
        }
    }
}

private data class NormalizedInput(val value: String, val starts: IntArray, val ends: IntArray) {
    fun sourceStart(from: Int, to: Int): Int = (from until to).minOfOrNull { starts[it] } ?: 0

    fun sourceEnd(from: Int, to: Int): Int = (from until to).maxOfOrNull { ends[it] } ?: sourceStart(from, to)
}
