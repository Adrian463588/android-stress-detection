package com.stressdetector.inference

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Host-side run-scoped model evidence and extractor contract checks. */
class V3PpgFeatureParityTest {
    @Test
    fun rejectsWindowShorterThanCanonicalOneHundredEightySeconds() {
        val values = DoubleArray(25 * 180 - 1) { index -> sin(index / 5.0) }

        try {
            V3PpgFeatureExtractor.extract(values, samplingRateHz = 25.0)
            fail("A canonical model window must span 180 seconds")
        } catch (error: IllegalArgumentException) {
            assertEquals("insufficient_window_samples", error.message)
        }
    }

    @Test
    fun canonicalIndexContainsOnlyAcceptedGreenAndIrModels() {
        val projectRoot = File("../..").canonicalFile
        val indexFile = File(projectRoot, "data/artifacts/export/model_index.json")
        assertTrue("canonical model index is required", indexFile.isFile)
        val index = JsonParser.parseString(indexFile.readText()).asJsonObject
        assertEquals("1.0.0", index["schema_version"].asString)
        val models = index["models"].asJsonArray
        if (models.size() == 0) {
            assertTrue(index["release_status"].asString.startsWith("blocked_"))
            return
        }
        assertEquals("accepted", index["release_status"].asString)
        assertEquals(2, models.size())
        val channels = models.map { it.asJsonObject["sensor_channel"].asString }.toSet()
        assertEquals(setOf("ppg_green", "ppg_ir"), channels)
        assertTrue(models.all { it.asJsonObject["release_status"].asString == "accepted" })
        assertTrue(models.none { it.asJsonObject["sensor_channel"].asString in setOf("ppg_red", "fusion") })
    }

    @Test
    fun latestRunVectorsAreRealRunScopedModelEvidence() {
        val projectRoot = File("../..").canonicalFile
        val indexFile = File(
            projectRoot,
            "data/artifacts/runs/20260916T132736Z/export/internal/internal_model_index.json"
        )
        assertTrue("latest run-scoped internal index is required", indexFile.isFile)
        val index = JsonParser.parseString(indexFile.readText()).asJsonObject
        assertEquals("20260916T132736Z", index["run_id"].asString)
        index["models"].asJsonArray.forEach { modelElement ->
            val model = modelElement.asJsonObject
            val sensor = model["sensor_channel"].asString
            assertTrue(sensor in setOf("ppg_green", "ppg_ir"))
            val manifestPath = File(
                projectRoot,
                model["files"].asJsonObject["manifest"].asJsonObject["path"].asString
            )
            val featureCount = JsonParser.parseString(manifestPath.readText())
                .asJsonObject["feature_order"].asJsonArray.size()
            val vectorPath = File(
                projectRoot,
                model["files"].asJsonObject["test_vectors"].asJsonObject["path"].asString
            )
            assertTrue("run-scoped feature vectors are required", vectorPath.isFile)
            val vectors = JsonParser.parseString(vectorPath.readText()).asJsonObject
            assertEquals(100, vectors["count"].asInt)
            assertTrue(!vectors["synthetic_data_used"].asBoolean)
            assertEquals(sensor, vectors["sensor_channel"].asString)
            assertEquals(100, vectors["cases"].asJsonArray.size())
            vectors["cases"].asJsonArray.forEach { vectorElement ->
                val vector = vectorElement.asJsonObject
                val participant = vector["participant_id"].asString
                val participantNumber = participant.removePrefix("ID-").toInt()
                assertTrue(participantNumber in 1..28)
                assertEquals(featureCount, vector["input"].asJsonArray.size())
                assertTrue(vector["native_probability"].asDouble.isFinite())
                assertTrue(vector["onnx_probability"].asDouble.isFinite())
            }
        }
    }

    @Test
    fun latestRunDetectorContractBindsToBothRealModelManifests() {
        val projectRoot = File("../..").canonicalFile
        val index = JsonParser.parseString(
            File(
                projectRoot,
                "data/artifacts/runs/20260916T132736Z/export/internal/internal_model_index.json"
            ).readText()
        ).asJsonObject
        val contract = PpgDetectorContractBundle.parse(
            File(
                projectRoot,
                "data/artifacts/runs/20260916T132736Z/export/internal/ppg_detector_contract.json"
            ).readText()
        )

        contract.requireValid()
        assertEquals(index["run_id"].asString, contract.run_id)
        assertEquals("trapezoidal", contract.feature_area_integration)
        assertEquals("adjacent_observed_only", contract.ibi_policy)
        assertEquals(
            "neurokit2_ppg_charlton_msptdfast",
            contract.forChannel("ppg_green").detector_method
        )
        assertEquals(
            "scipy_find_peaks_adaptive",
            contract.forChannel("ppg_ir").detector_method
        )

        index["models"].asJsonArray.forEach { modelElement ->
            val model = modelElement.asJsonObject
            val channel = model["sensor_channel"].asString
            val manifestFile = File(
                projectRoot,
                model["files"].asJsonObject["manifest"].asJsonObject["path"].asString
            )
            val modelFile = File(
                projectRoot,
                model["files"].asJsonObject["model"].asJsonObject["path"].asString
            )
            val manifest = Gson().fromJson(manifestFile.readText(), ModelManifest::class.java)
            contract.forChannel(channel).requireCompatible(
                runId = index["run_id"].asString,
                manifest = manifest,
                modelBytes = modelFile.readBytes()
            )
        }
    }
}
