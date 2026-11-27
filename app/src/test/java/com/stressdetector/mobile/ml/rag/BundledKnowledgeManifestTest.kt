package com.stressdetector.mobile.ml.rag

import android.content.Context
import android.content.res.AssetManager
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class BundledKnowledgeManifestTest {

    @Test
    fun manifestDocumentsExistAndChecksumMatches() {
        val manifestFile = File("src/main/canonicalAssets/ai/bundled_knowledge_manifest.json")
        val rawManifest = manifestFile.readText(Charsets.UTF_8)
        val manifestRoot = JsonParser.parseString(rawManifest).asJsonObject

        val schemaVersion = manifestRoot.get("schema_version")?.asString
        assertEquals("bundled_knowledge_manifest_v1", schemaVersion)

        val corpusStatus = manifestRoot.get("corpus_status")?.asString
        if (corpusStatus == "READY") {
            val docs = manifestRoot.getAsJsonArray("documents")
            assertTrue(docs != null && docs.size() > 0)

            for (element in docs) {
                val docObj = element.asJsonObject
                val assetPath = docObj.get("asset_path").asString
                val expectedDocSha256 = docObj.get("source_sha256").asString.lowercase(Locale.ROOT)
                val assetFile = File("src/main/canonicalAssets/$assetPath")
                assertTrue("Asset file must exist: ${assetFile.path}", assetFile.exists())
                val bytes = assetFile.readBytes()
                val actualSha256 = sha256(bytes)
                val rawText = assetFile.readText(Charsets.UTF_8)
                val lfSha256 = sha256(rawText.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
                val crlfSha256 = sha256(rawText.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
                assertTrue("Checksum mismatch for $assetPath", expectedDocSha256 in listOf(actualSha256, lfSha256, crlfSha256))
            }

            val unsignedManifest = manifestRoot.deepCopy().also { it.remove("manifest_sha256") }
            val gsonStr = Gson().toJson(unsignedManifest)
            val expectedManifestSha256 = sha256(gsonStr.toByteArray(Charsets.UTF_8))
            val expectedLfSha256 = sha256(gsonStr.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
            val expectedCrlfSha256 = sha256(gsonStr.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8))

            val actualManifestSha256 = manifestRoot.get("manifest_sha256")?.asString.orEmpty().lowercase(Locale.ROOT)
            assertTrue(
                "Manifest checksum must match Gson serialized unsigned manifest",
                actualManifestSha256 in listOf(expectedManifestSha256, expectedLfSha256, expectedCrlfSha256)
            )
        }
    }

    @Test
    fun assetBundledLocalCorpusLoadsAndReadsAllCounselingDocs() = runBlocking {
        val context = mock(Context::class.java)
        val assetManager = mock(AssetManager::class.java)
        `when`(context.assets).thenReturn(assetManager)

        val manifestBytes = File("src/main/canonicalAssets/ai/bundled_knowledge_manifest.json").readBytes()
        `when`(assetManager.open("ai/bundled_knowledge_manifest.json")).thenAnswer { ByteArrayInputStream(manifestBytes) }

        val corpus = AssetBundledLocalCorpus(context)
        val docsResult = corpus.loadDocuments()
        assertTrue("loadDocuments must succeed: ${docsResult.exceptionOrNull()}", docsResult.isSuccess)

        val docs = docsResult.getOrThrow()
        assertEquals(34, docs.size)

        for (doc in docs) {
            assertTrue("collection missing for ${doc.documentId}", doc.collection.isNotBlank())
            assertTrue("category missing for ${doc.documentId}", doc.category.isNotBlank())
            assertTrue("reviewStatus missing for ${doc.documentId}", doc.reviewStatus.isNotBlank())
            val fileBytes = File("src/main/canonicalAssets/${doc.assetPath}").readBytes()
            `when`(assetManager.open(doc.assetPath)).thenAnswer { ByteArrayInputStream(fileBytes) }
            val readResult = corpus.read(doc)
            assertTrue("read must succeed for ${doc.documentId}: ${readResult.exceptionOrNull()}", readResult.isSuccess)
            assertEquals(fileBytes.size, readResult.getOrThrow().size)
            if (doc.format == "markdown") {
                val text = fileBytes.toString(Charsets.UTF_8)
                assertTrue(
                    "Disallowed archaic label in ${doc.documentId}",
                    listOf("Mentally Retardation", "Mentally Defective", "Borderline Defective").none {
                        text.contains(it, ignoreCase = true)
                    }
                )
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
