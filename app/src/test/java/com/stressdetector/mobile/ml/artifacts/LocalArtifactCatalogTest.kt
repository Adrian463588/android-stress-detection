package com.stressdetector.mobile.ml.artifacts

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalArtifactCatalogTest {
    private fun catalog(): LocalArtifactCatalog {
        val file = listOf(
            File("src/main/canonicalAssets/ai/artifact_catalog.json"),
            File("app/src/main/canonicalAssets/ai/artifact_catalog.json")
        ).first(File::isFile)
        return LocalArtifactCatalog.parse(file.readText())
    }

    @Test
    fun `Gemma and E5 contracts are immutable and complete`() {
        val catalog = catalog()
        val gemma = catalog.require(LocalArtifactId.GEMMA_4_E2B)
        assertEquals("6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94", gemma.revision)
        assertEquals(2_588_147_712L, gemma.files.single().sizeBytes)
        assertEquals(
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            gemma.files.single().sha256
        )
        val embedding = catalog.require(LocalArtifactId.MULTILINGUAL_E5_SMALL)
        assertEquals(384, embedding.runtimeContract?.dimensions)
        assertEquals(512, embedding.runtimeContract?.maxTokens)
        assertEquals(setOf("query: ", "passage: "), embedding.runtimeContract?.prefixes?.toSet())
        assertEquals(
            setOf("model.onnx", "tokenizer.json"),
            embedding.files.map { it.path.substringAfterLast('/') }.toSet()
        )
        catalog.all().flatMap(CatalogArtifactEntry::files).mapNotNull(CatalogArtifactFile::url).forEach { url ->
            assertTrue(url.startsWith("https://"))
            assertFalse(url.contains("/resolve/main/"))
            assertFalse(url.contains("/latest/"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `moving Hugging Face revisions are rejected`() {
        val file = listOf(
            File("src/main/canonicalAssets/ai/artifact_catalog.json"),
            File("app/src/main/canonicalAssets/ai/artifact_catalog.json")
        ).first(File::isFile)
        LocalArtifactCatalog.parse(
            file.readText().replace(
                "/resolve/6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94/",
                "/resolve/main/"
            )
        )
    }
}
