package com.stressdetector.mobile.ml.rag

import com.google.gson.JsonParser
import com.stressdetector.mobile.ml.artifacts.CatalogArtifactEntry
import com.stressdetector.mobile.ml.artifacts.CatalogArtifactFile
import com.stressdetector.mobile.ml.artifacts.CatalogArtifactKind
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class E5EmbeddingContractTest {
    @Test
    fun `tokenizer json unigram metaspace contract produces exact special token sequence`() {
        val json = """
            {
              "normalizer":{"type":"NFKC"},
              "pre_tokenizer":{"type":"Metaspace","replacement":"▁","add_prefix_space":true},
              "model":{"type":"Unigram","unk_id":3,"vocab":[
                ["<s>",0.0],["<pad>",0.0],["</s>",0.0],["<unk>",-10.0],["<mask>",0.0],
                ["▁query",-1.0],[":",-1.0],["▁sehat",-1.0]
              ]}
            }
        """.trimIndent()
        val tokenizer = XlmRobertaUnigramTokenizer.parse(JsonParser.parseString(json).asJsonObject)

        val ids = tokenizer.tokenize("query: sehat", addSpecialTokens = true).ids

        assertArrayEquals(longArrayOf(0, 5, 6, 7, 2), ids)
    }

    @Test
    fun `unigram keeps unknown fallback when a local piece leads to a dead end`() {
        val json = """
            {
              "normalizer":{"type":"NFKC"},
              "pre_tokenizer":{"type":"Metaspace","replacement":"▁","add_prefix_space":true},
              "model":{"type":"Unigram","unk_id":3,"vocab":[
                ["<s>",0.0],["<pad>",0.0],["</s>",0.0],["<unk>",-10.0],["<mask>",0.0],
                ["▁ab",-1000.0]
              ]}
            }
        """.trimIndent()
        val tokenizer = XlmRobertaUnigramTokenizer.parse(JsonParser.parseString(json).asJsonObject)

        val ids = tokenizer.tokenize("ab", addSpecialTokens = true).ids

        assertArrayEquals(longArrayOf(0, 3, 3, 3, 2), ids)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `artifact contract rejects an unpinned embedding revision`() {
        E5EmbeddingRuntimeContract.from(
            CatalogArtifactEntry(
                id = E5EmbeddingRuntimeContract.EXPECTED_ARTIFACT_ID,
                kind = CatalogArtifactKind.MULTILINGUAL_EMBEDDING,
                version = E5EmbeddingRuntimeContract.EXPECTED_ARTIFACT_VERSION,
                revision = "moving-main",
                runtimeVersion = "onnxruntime-1.22.0",
                sourcePage = "https://example.invalid",
                license = "MIT",
                allowedRedirectHosts = listOf("example.invalid"),
                files = listOf(
                    CatalogArtifactFile("model", "model.onnx", sizeBytes = 1, sha256 = "a".repeat(64)),
                    CatalogArtifactFile("tokenizer", "tokenizer.json", sizeBytes = 1, sha256 = "b".repeat(64))
                )
            )
        )
    }

    @Test
    fun `query and passage prefixes are part of tokenizer input`() {
        val inputs = mutableListOf<String>()
        val tokenizer = RagTokenizer { text, addSpecialTokens ->
            inputs += text
            val count = if (addSpecialTokens) 3 else 1
            RagTokenization(List(count) { RagToken(it.toLong(), 0, text.length) })
        }

        LocalEmbeddingModel.prepareInput(tokenizer, "cemas", EmbeddingInputKind.QUERY)
        LocalEmbeddingModel.prepareInput(tokenizer, "panduan", EmbeddingInputKind.PASSAGE)

        assertEquals(listOf("query: cemas", "passage: panduan"), inputs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `embedding input over 512 tokens is rejected not truncated`() {
        val tokenizer = RagTokenizer { text, _ ->
            RagTokenization(List(513) { RagToken(it.toLong(), 0, text.length) })
        }
        LocalEmbeddingModel.prepareInput(tokenizer, "oversized", EmbeddingInputKind.PASSAGE)
    }

    @Test
    fun `attention mask mean pooling returns 384 dimensional l2 vector`() {
        val first = FloatArray(384) { if (it == 0) 1f else 0f }
        val second = FloatArray(384) { if (it == 1) 1f else 0f }
        val ignored = FloatArray(384) { 100f }

        val pooled = LocalEmbeddingModel.attentionMaskMeanPool(
            arrayOf(first, second, ignored),
            longArrayOf(1, 1, 0)
        )

        assertEquals(384, pooled.size)
        assertEquals((1.0 / sqrt(2.0)).toFloat(), pooled[0], 1e-6f)
        assertEquals((1.0 / sqrt(2.0)).toFloat(), pooled[1], 1e-6f)
        assertEquals(1.0, sqrt(pooled.sumOf { (it * it).toDouble() }), 1e-6)
    }

    @Test
    fun `embedding blob uses little endian and round trips 384 values`() {
        val vector = FloatArray(384) { it / 383f }
        val bytes = LocalEmbeddingModel.floatArrayToByteArray(vector)
        val restored = LocalEmbeddingModel.byteArrayToFloatArray(bytes)

        assertEquals(384 * Float.SIZE_BYTES, bytes.size)
        assertArrayEquals(vector, restored, 0f)
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0xC0.toByte(), 0x3F),
            LocalEmbeddingModel.floatArrayToByteArray(floatArrayOf(1.5f))
        )
    }
}
