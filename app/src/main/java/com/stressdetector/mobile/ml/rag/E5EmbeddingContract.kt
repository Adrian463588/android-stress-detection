package com.stressdetector.mobile.ml.rag

import com.stressdetector.mobile.ml.artifacts.CatalogArtifactEntry
import com.stressdetector.mobile.ml.artifacts.CatalogArtifactKind
import java.io.File
import java.security.MessageDigest

enum class EmbeddingPooling { ATTENTION_MASK_MEAN }
enum class EmbeddingNormalization { L2 }
enum class EmbeddingTokenizerContract { XLM_ROBERTA_UNIGRAM_METASPACE }

/** Pinned runtime contract. A catalog entry is data provenance, not permission to change this ABI. */
data class E5EmbeddingRuntimeContract(
    val artifactId: String,
    val artifactVersion: String,
    val revision: String,
    val modelSizeBytes: Long,
    val modelSha256: String,
    val tokenizerSizeBytes: Long,
    val tokenizerSha256: String,
    val dimensions: Int = LocalEmbeddingModel.EMBEDDING_DIMENSIONS,
    val maximumTokens: Int = LocalEmbeddingModel.MAX_TOKENS,
    val pooling: EmbeddingPooling = EmbeddingPooling.ATTENTION_MASK_MEAN,
    val normalization: EmbeddingNormalization = EmbeddingNormalization.L2,
    val tokenizerContract: EmbeddingTokenizerContract = EmbeddingTokenizerContract.XLM_ROBERTA_UNIGRAM_METASPACE
) {
    init {
        require(artifactId == EXPECTED_ARTIFACT_ID)
        require(artifactVersion == EXPECTED_ARTIFACT_VERSION)
        require(revision == EXPECTED_REVISION)
        require(dimensions == 384 && maximumTokens == 512)
        require(pooling == EmbeddingPooling.ATTENTION_MASK_MEAN)
        require(normalization == EmbeddingNormalization.L2)
        require(tokenizerContract == EmbeddingTokenizerContract.XLM_ROBERTA_UNIGRAM_METASPACE)
        require(modelSizeBytes > 0 && tokenizerSizeBytes > 0)
        require(SHA256.matches(modelSha256.lowercase()) && SHA256.matches(tokenizerSha256.lowercase()))
    }

    fun verifyTokenizer(file: File) = verify(file, tokenizerSizeBytes, tokenizerSha256, "tokenizer.json")

    fun verifyModel(file: File) = verify(file, modelSizeBytes, modelSha256, "model.onnx")

    private fun verify(file: File, expectedSize: Long, expectedSha256: String, label: String) {
        require(file.isFile && file.length() == expectedSize) { "$label does not match the pinned size." }
        require(file.sha256().equals(expectedSha256, ignoreCase = true)) {
            "$label does not match the pinned SHA-256."
        }
    }

    companion object {
        const val EXPECTED_ARTIFACT_ID = "multilingual_e5_small"
        const val EXPECTED_ARTIFACT_VERSION = "multilingual-e5-small-onnx-fp32"
        const val EXPECTED_REVISION = "614241f622f53c4eeff9890bdc4f31cfecc418b3"
        private val SHA256 = Regex("^[a-f0-9]{64}$")

        fun from(entry: CatalogArtifactEntry): E5EmbeddingRuntimeContract {
            require(entry.kind == CatalogArtifactKind.MULTILINGUAL_EMBEDDING)
            val model = entry.files.singleOrNull { it.role == "model" && it.path.substringAfterLast('/') == "model.onnx" }
                ?: error("Embedding catalog must contain exactly one model.onnx contract.")
            val tokenizer = entry.files.singleOrNull {
                it.role == "tokenizer" && it.path.substringAfterLast('/') == "tokenizer.json"
            } ?: error("Embedding catalog must contain exactly one tokenizer.json contract.")
            return E5EmbeddingRuntimeContract(
                artifactId = entry.id,
                artifactVersion = entry.version,
                revision = entry.revision,
                modelSizeBytes = model.sizeBytes,
                modelSha256 = model.sha256,
                tokenizerSizeBytes = tokenizer.sizeBytes,
                tokenizerSha256 = tokenizer.sha256
            )
        }
    }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
