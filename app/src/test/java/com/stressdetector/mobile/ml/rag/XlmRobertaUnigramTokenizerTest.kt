package com.stressdetector.mobile.ml.rag

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class XlmRobertaUnigramTokenizerTest {
    @Test
    fun `metaspace unigram emits stable multilingual token ids`() {
        val tokenizerJson = File.createTempFile("e5-tokenizer", ".json")
        try {
            tokenizerJson.writeText(
                """
                {
                  "model": {
                    "type": "Unigram",
                    "unk_id": 3,
                    "vocab": [
                      ["<s>", 0.0], ["<pad>", 0.0], ["</s>", 0.0], ["<unk>", -10.0],
                      ["▁query", -1.0], [":", -1.0], ["▁halo", -1.0], ["▁dunia", -1.0]
                    ]
                  },
                  "pre_tokenizer": {"type": "Metaspace", "replacement": "▁", "prepend_scheme": "always"}
                }
                """.trimIndent()
            )
            val tokenizer = XlmRobertaUnigramTokenizer.load(tokenizerJson)
            assertArrayEquals(
                longArrayOf(0, 4, 5, 6, 7, 2),
                tokenizer.tokenize("query: halo dunia", addSpecialTokens = true).ids
            )
        } finally {
            tokenizerJson.delete()
        }
    }
}
