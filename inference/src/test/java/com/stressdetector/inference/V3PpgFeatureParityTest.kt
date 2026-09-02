package com.stressdetector.inference

import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.File
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Host-side release-index and real Python-to-Kotlin feature parity contract. */
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
    fun realPythonFeatureVectorsMatchKotlinExtractor() {
        val projectRoot = File("../..").canonicalFile
        listOf("ppg_green", "ppg_ir").forEach { sensorChannel ->
            val artifactRoot =
                File(
                    projectRoot,
                    "data/artifacts/export/stress_v4.1.0-continuous25-ir100-20260807T195905Z/$sensorChannel"
                )
            val fixture = File(artifactRoot, "test_vectors.json")
            val manifestFile = File(artifactRoot, "model_manifest.json")
            assertTrue("real Android parity fixture is required", fixture.isFile)
            assertTrue("model manifest is required", manifestFile.isFile)
            val manifest = JsonParser.parseString(manifestFile.readText()).asJsonObject
            val featureOrder = manifest["feature_order"].asJsonArray.map { it.asString }
            assertFeatureParity(fixture, featureOrder, sensorChannel)
        }
    }

    private fun assertFeatureParity(fixture: File, featureOrder: List<String>, sensorChannel: String) {
        var validCaseCount = 0
        var processedCaseCount = 0
        val observedPhases = mutableSetOf<String>()
        var maximumDifference = 0.0
        var maximumCase = ""
        var maximumFeature = ""
        val fullParity = System.getProperty("fullPpgParity") == "true"
        val sampleStride = if (fullParity) 1 else 10

        JsonReader(fixture.reader()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "cases" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            var kind = ""
                            var caseId = ""
                            var phase = ""
                            var samplingRateHz = Double.NaN
                            var signal = DoubleArray(0)
                            var expected = DoubleArray(0)
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "kind" -> kind = reader.nextString()
                                    "case_id" -> caseId = reader.nextString()
                                    "phase" -> phase = reader.nextString()
                                    "sampling_rate_hz" -> samplingRateHz = reader.nextDouble()
                                    "preprocessed_values" -> signal = readDoubleArray(reader)
                                    "expected_features" -> expected = readDoubleArray(reader)
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()

                            if (kind != "valid_raw_window") continue
                            observedPhases += phase
                            validCaseCount += 1
                            if ((validCaseCount - 1) % sampleStride != 0) continue
                            processedCaseCount += 1
                            assertEquals(featureOrder.size, expected.size)
                            val actual = V3PpgFeatureExtractor.extract(signal, samplingRateHz).values
                            featureOrder.forEachIndexed { index, name ->
                                val difference = kotlin.math.abs(actual.getValue(name).toDouble() - expected[index])
                                if (difference > maximumDifference) {
                                    maximumDifference = difference
                                    maximumCase = caseId
                                    maximumFeature = name
                                }
                            }
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        assertEquals("$sensorChannel valid vector count", 94, validCaseCount)
        assertEquals(
            "$sensorChannel processed parity vector count",
            if (fullParity) 94 else 10,
            processedCaseCount
        )
        assertEquals("$sensorChannel approved fixture phases", setOf("relax", "task"), observedPhases)
        assertTrue(
            "$sensorChannel Python/Kotlin feature parity exceeded 1e-5: " +
                "$maximumDifference at $maximumCase/$maximumFeature",
            maximumDifference < 1.0e-5
        )
    }

    private fun readDoubleArray(reader: JsonReader): DoubleArray {
        val values = mutableListOf<Double>()
        reader.beginArray()
        while (reader.hasNext()) values += reader.nextDouble()
        reader.endArray()
        return values.toDoubleArray()
    }
}
