package com.stressdetector.mobile.ml.rag

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

sealed interface EmbeddingResult {
    data class Success(val vector: FloatArray) : EmbeddingResult
    data class Unavailable(val reason: String) : EmbeddingResult
    data class Failed(val reason: String) : EmbeddingResult
}

enum class EmbeddingInputKind(val prefix: String) {
    QUERY("query: "),
    PASSAGE("passage: ")
}

/** multilingual-e5-small ONNX runtime with its catalog-pinned XLM-R Unigram tokenizer. */
open class LocalEmbeddingModel(
    _context: Context,
    private val modelFile: File,
    private val tokenizerFile: File,
    private val runtimeContract: E5EmbeddingRuntimeContract? = null
) : Closeable {
    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: RagTokenizer? = null
    private var initializationError: String? = null
    private var lastInitializationAttemptMs = 0L

    open fun embedQuery(text: String): EmbeddingResult = embed(text, EmbeddingInputKind.QUERY)

    open fun embedPassage(text: String): EmbeddingResult = embed(text, EmbeddingInputKind.PASSAGE)

    fun preflight(): Result<Unit> = runCatching {
        ensureInitialized()
        check(session != null && tokenizer != null) {
            initializationError ?: "Embedding runtime preflight failed."
        }
    }

    /** Backward-compatible ingestion default. Queries must call [embedQuery]. */
    open fun embedText(text: String): EmbeddingResult = embedPassage(text)

    /** Chunking requires the exact tokenizer, but remains usable when only ONNX is unavailable. */
    fun tokenizerForChunking(): Result<RagTokenizer> = runCatching {
        ensureTokenizer()
        tokenizer ?: error(initializationError ?: "Embedding tokenizer is unavailable.")
    }

    @Synchronized
    private fun embed(text: String, kind: EmbeddingInputKind): EmbeddingResult {
        ensureInitialized()
        val currentSession = session
            ?: return EmbeddingResult.Unavailable(initializationError ?: "Embedding model is unavailable")
        val currentEnvironment = environment
            ?: return EmbeddingResult.Unavailable(initializationError ?: "Embedding runtime is unavailable")
        val currentTokenizer = tokenizer
            ?: return EmbeddingResult.Unavailable(initializationError ?: "Embedding tokenizer is unavailable")
        if (text.isBlank()) return EmbeddingResult.Failed("Cannot embed empty text")

        val ids = runCatching { prepareInput(currentTokenizer, text, kind) }
            .getOrElse { error -> return EmbeddingResult.Failed(error.message ?: "Embedding input is invalid.") }
        val tokenIds = LongArray(MAX_TOKENS)
        val attentionMask = LongArray(MAX_TOKENS)
        ids.forEachIndexed { index, id ->
            tokenIds[index] = id
            attentionMask[index] = 1L
        }
        val shape = longArrayOf(1L, MAX_TOKENS.toLong())
        return try {
            OnnxTensor.createTensor(currentEnvironment, LongBuffer.wrap(tokenIds), shape).use { inputIds ->
                OnnxTensor.createTensor(currentEnvironment, LongBuffer.wrap(attentionMask), shape).use { mask ->
                    val inputs = mutableMapOf("input_ids" to inputIds, "attention_mask" to mask)
                    if ("token_type_ids" in currentSession.inputNames) {
                        OnnxTensor.createTensor(
                            currentEnvironment,
                            LongBuffer.wrap(LongArray(MAX_TOKENS)),
                            shape
                        ).use { tokenTypes ->
                            inputs["token_type_ids"] = tokenTypes
                            runAndPool(currentSession, inputs, attentionMask)
                        }
                    } else {
                        runAndPool(currentSession, inputs, attentionMask)
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Embedding inference failed", error)
            EmbeddingResult.Failed(error.message ?: "Embedding inference failed")
        }
    }

    private fun runAndPool(currentSession: OrtSession, inputs: Map<String, OnnxTensor>, attentionMask: LongArray): EmbeddingResult =
        currentSession.run(inputs).use { results ->
            val tokenVectors = extractTokenVectors(results.get(0).value)
            require(tokenVectors.size >= attentionMask.count { it == 1L }) {
                "Embedding output sequence is shorter than the attention mask."
            }
            val dimensions = tokenVectors.firstOrNull()?.size ?: error("Embedding output is empty.")
            require(dimensions == EMBEDDING_DIMENSIONS) {
                "Embedding output must have $EMBEDDING_DIMENSIONS dimensions, found $dimensions."
            }
            EmbeddingResult.Success(attentionMaskMeanPool(tokenVectors, attentionMask))
        }

    @Synchronized
    private fun ensureInitialized() {
        if (session != null && tokenizer != null) return
        val now = System.currentTimeMillis()
        if (initializationError != null && now - lastInitializationAttemptMs < RETRY_INTERVAL_MS) return
        lastInitializationAttemptMs = now
        try {
            runtimeContract?.verifyModel(modelFile)
            require(modelFile.isFile && modelFile.length() > 0L) { "Embedding ONNX is missing." }
            ensureTokenizer()
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            val opened = try {
                env.createSession(modelFile.absolutePath, options)
            } finally {
                options.close()
            }
            require(opened.inputNames.containsAll(setOf("input_ids", "attention_mask"))) {
                "Embedding ONNX input contract is incompatible."
            }
            val outputInfo = opened.outputInfo.values.firstOrNull()?.info as? TensorInfo
                ?: error("Embedding ONNX must expose a tensor output.")
            require(outputInfo.shape.size == 3 && outputInfo.shape.last() == EMBEDDING_DIMENSIONS.toLong()) {
                "Embedding ONNX must expose token embeddings with shape [batch, sequence, $EMBEDDING_DIMENSIONS]."
            }
            environment = env
            session = opened
            initializationError = null
        } catch (error: UnsatisfiedLinkError) {
            initializationError = "ONNX Runtime native library is unavailable"
            Log.e(TAG, "ONNX Runtime native library not loaded", error)
        } catch (error: Exception) {
            initializationError = error.message ?: "Embedding model initialization failed"
            Log.e(TAG, "Embedding initialization failed", error)
        }
    }

    @Synchronized
    private fun ensureTokenizer() {
        if (tokenizer != null) return
        runtimeContract?.verifyTokenizer(tokenizerFile)
        require(tokenizerFile.isFile && tokenizerFile.length() > 0L) { "Embedding tokenizer.json is missing." }
        tokenizer = XlmRobertaUnigramTokenizer.load(tokenizerFile)
    }

    @Synchronized
    override fun close() {
        session?.close()
        session = null
        tokenizer = null
        // OrtEnvironment is process-global. Closing the session deterministically
        // releases this model without invalidating other ONNX consumers.
        environment = null
        initializationError = null
        lastInitializationAttemptMs = 0L
    }

    private fun extractTokenVectors(value: Any?): Array<FloatArray> = when (value) {
        is Array<*> -> {
            val batch = value.firstOrNull() ?: error("Embedding output batch is empty.")
            when (batch) {
                is Array<*> -> batch.map { it as? FloatArray ?: error("Unsupported embedding output shape.") }
                    .toTypedArray()
                is FloatArray -> arrayOf(batch)
                else -> error("Unsupported embedding output shape.")
            }
        }
        else -> error("Embedding output is not a float tensor.")
    }

    companion object {
        const val EMBEDDING_DIMENSIONS = 384
        const val MAX_TOKENS = 512
        const val VERSION = "multilingual-e5-small-614241f622f53c4eeff9890bdc4f31cfecc418b3"
        private const val RETRY_INTERVAL_MS = 5_000L
        private const val TAG = "LocalEmbeddingModel"

        fun floatArrayToByteArray(floats: FloatArray): ByteArray {
            val buffer = java.nio.ByteBuffer.allocate(floats.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            floats.forEach(buffer::putFloat)
            return buffer.array()
        }

        fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
            require(bytes.size % Float.SIZE_BYTES == 0) { "Embedding byte array is truncated." }
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
        }

        fun cosineSimilarity(first: FloatArray, second: FloatArray): Float {
            if (first.size != second.size || first.isEmpty()) return 0f
            var dot = 0f
            var firstNorm = 0f
            var secondNorm = 0f
            first.indices.forEach { index ->
                dot += first[index] * second[index]
                firstNorm += first[index] * first[index]
                secondNorm += second[index] * second[index]
            }
            if (firstNorm <= 0f || secondNorm <= 0f) return 0f
            return (dot / sqrt((firstNorm * secondNorm).toDouble())).toFloat()
        }

        internal fun attentionMaskMeanPool(tokenVectors: Array<FloatArray>, attentionMask: LongArray): FloatArray {
            require(tokenVectors.isNotEmpty()) { "Embedding output is empty." }
            val dimensions = tokenVectors.first().size
            require(dimensions == EMBEDDING_DIMENSIONS) {
                "Embedding output must have $EMBEDDING_DIMENSIONS dimensions, found $dimensions."
            }
            val pooled = FloatArray(dimensions)
            var validTokens = 0
            tokenVectors.forEachIndexed { index, vector ->
                if (index >= attentionMask.size || attentionMask[index] == 0L) return@forEachIndexed
                require(vector.size == dimensions) { "Embedding output has inconsistent dimensions." }
                vector.indices.forEach { dimension ->
                    require(vector[dimension].isFinite()) { "Embedding output contains a non-finite value." }
                    pooled[dimension] += vector[dimension]
                }
                validTokens++
            }
            require(validTokens > 0) { "Embedding attention mask is empty." }
            pooled.indices.forEach { pooled[it] /= validTokens.toFloat() }
            return l2Normalize(pooled)
        }

        internal fun l2Normalize(vector: FloatArray): FloatArray {
            val result = vector.copyOf()
            val norm = sqrt(result.sumOf { (it * it).toDouble() }).toFloat()
            require(norm > 0f && norm.isFinite()) { "Embedding norm is invalid." }
            result.indices.forEach { result[it] /= norm }
            return result
        }

        internal fun prepareInput(tokenizer: RagTokenizer, text: String, kind: EmbeddingInputKind): LongArray {
            require(text.isNotBlank()) { "Cannot embed empty text." }
            val encoded = tokenizer.tokenize(kind.prefix + text, addSpecialTokens = true)
            require(encoded.size <= MAX_TOKENS) {
                "Embedding input has ${encoded.size} tokens; maximum is $MAX_TOKENS. Input was not truncated."
            }
            return encoded.ids
        }
    }
}
