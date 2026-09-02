package com.stressdetector.mobile.ml.rag

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Test-only deterministic fixture; never used by the production retrieval path. */
class OnDeviceTextEmbedder(private val vectorDimension: Int = 128) {
    fun embedText(text: String): FloatArray {
        val vector = FloatArray(vectorDimension)
        val tokens = text.lowercase().split(Regex("[^a-z0-9]+"))
            .filter(String::isNotBlank)
        for (index in tokens.indices) {
            val tokenHash = kotlin.math.abs(tokens[index].hashCode())
            vector[tokenHash % vectorDimension] += 1f
            if (index < tokens.lastIndex) {
                val bigramHash = kotlin.math.abs("${tokens[index]}_${tokens[index + 1]}".hashCode())
                vector[bigramHash % vectorDimension] += 1.5f
            }
        }
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) vector.indices.forEach { vector[it] /= norm }
        return vector
    }

    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size || v1.isEmpty()) return 0f
        var dot = 0f
        var norm1 = 0f
        var norm2 = 0f
        v1.indices.forEach { index ->
            dot += v1[index] * v2[index]
            norm1 += v1[index] * v1[index]
            norm2 += v2[index] * v2[index]
        }
        return if (norm1 > 0f && norm2 > 0f) {
            (dot / (sqrt(norm1.toDouble()) * sqrt(norm2.toDouble()))).toFloat()
        } else {
            0f
        }
    }

    companion object {
        fun floatArrayToByteArray(floats: FloatArray): ByteArray =
            ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                floats.forEach(::putFloat)
            }.array()

        fun byteArrayToFloatArray(bytes: ByteArray): FloatArray = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).let { buffer ->
            FloatArray(bytes.size / 4) { buffer.float }
        }
    }
}
