package com.stressdetector.mobile.ml

import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAssetContractTest {
    @Test
    fun canonicalIndexContainsOnlyIndependentAndroidReadyReleaseModels() {
        val indexFile = File("../../data/artifacts/export/model_index.json")
        assertTrue("canonical model index is required", indexFile.isFile)
        val index = JsonParser.parseString(indexFile.readText()).asJsonObject
        val models = index["models"].asJsonArray
        val releaseStatus = index["release_status"].asString
        if (releaseStatus == "blocked_android_evidence_pending") {
            assertTrue("blocked release must not expose model artifacts", models.isEmpty)
            return
        }
        assertEquals("accepted", releaseStatus)
        val channels = models.map { it.asJsonObject["sensor_channel"].asString }.toSet()
        assertTrue(channels.isNotEmpty())
        assertTrue(channels.size <= 3)
        assertTrue(channels.all { it in setOf("ppg_green", "ppg_ir", "ppg_red") })
        assertTrue(models.none { it.asJsonObject["sensor_channel"].asString == "fusion" })
        assertTrue(models.all { it.asJsonObject["android_ready"].asBoolean })
    }

    @Test
    fun canonicalBundlesPreserveChannelIdentitySchemaAndProbabilityVariation() {
        val root = File("../../data/artifacts/export")
        val index = JsonParser.parseString(File(root, "model_index.json").readText()).asJsonObject
        val models = index["models"].asJsonArray
        if (index["release_status"].asString == "blocked_android_evidence_pending") {
            assertTrue("blocked release must not expose model bundles", models.isEmpty)
            return
        }
        models.forEach { modelElement ->
            val model = modelElement.asJsonObject
            val channel = model["sensor_channel"].asString
            val files = model["files"].asJsonObject
            val manifestFile = File(root, files["manifest"].asJsonObject["path"].asString)
            val vectorsFile = File(root, files["test_vectors"].asJsonObject["path"].asString)
            val manifest = JsonParser.parseString(manifestFile.readText()).asJsonObject
            val vectors = JsonParser.parseString(vectorsFile.readText()).asJsonObject
                .getAsJsonArray("cases")
            val probabilities = vectors
                .filter { it.asJsonObject["kind"].asString == "valid_raw_window" }
                .map { it.asJsonObject["onnx_probability"].asFloat }
                .toSet()

            assertEquals(channel, manifest["sensor_channel"].asString)
            assertTrue(manifest["feature_order"].asJsonArray.size() > 0)
            assertEquals(240, manifest["analysis_seconds"].asInt)
            assertTrue(manifest["device_parity_receipt_sha256"].asString.matches(Regex("[0-9a-f]{64}")))
            assertEquals(100, vectors.size())
            assertTrue("$channel canonical probabilities must vary", probabilities.size > 1)
        }
    }
}
