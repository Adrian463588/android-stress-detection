package com.stressdetector.mobile.signal

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the canonical v4.1 exported feature-to-ONNX evidence.
 */
class RawPpgOnnxParityFixtureTest {
    @Test
    fun v41GreenOnnxMatchesAllExportedFeatureVectors() {
        val artifacts = v41GreenArtifacts()
        val manifest = jsonObject(artifacts.manifest)
        val featureOrder = manifest["feature_order"].asJsonArray
        val cases = jsonObject(artifacts.vectors)["cases"].asJsonArray
        val validCases =
            cases.filter {
                it.asJsonObject["kind"].asString == "valid_raw_window"
            }
        val rejectionCases =
            cases.filter {
                it.asJsonObject["kind"].asString != "valid_raw_window"
            }

        assertEquals(34, featureOrder.size())
        assertEquals(100, cases.size())
        assertEquals(94, validCases.size)
        assertEquals(6, rejectionCases.size)
        assertEquals(
            manifest["onnx_sha256"].asString,
            sha256(artifacts.model)
        )

        OrtEnvironment.getEnvironment().useSession(artifacts.model) { environment, session ->
            validCases.forEach { element ->
                val case = element.asJsonObject
                val input = case["input"].asJsonArray.floatVector()
                val expected = case["onnx_probability"].asFloat
                val actual = runOnnx(environment, session, input)

                assertEquals(case["case_id"].asString, featureOrder.size(), input.size)
                assertTrue("${case["case_id"].asString} has non-finite input", input.all(Float::isFinite))
                assertTrue("${case["case_id"].asString} returned non-finite output", actual.isFinite())
                assertTrue(
                    "${case["case_id"].asString} ONNX difference=${abs(actual - expected)}",
                    abs(actual - expected) < ONNX_PARITY_TOLERANCE
                )
            }
        }
    }

    private fun JsonArray.floatVector(): FloatArray = FloatArray(size()) { get(it).asFloat }

    private fun runOnnx(environment: OrtEnvironment, session: OrtSession, input: FloatArray): Float =
        OnnxTensor.createTensor(environment, arrayOf(input)).use { tensor ->
            session.run(mapOf(session.inputNames.single() to tensor)).use { result ->
                scalar(result[0].value)
            }
        }

    private fun scalar(value: Any?): Float = when (value) {
        is Float -> value
        is Double -> value.toFloat()
        is Number -> value.toFloat()
        is FloatArray -> value.firstOrNull() ?: error("empty ONNX output")
        is DoubleArray -> value.firstOrNull()?.toFloat() ?: error("empty ONNX output")
        is Array<*> -> scalar(value.firstOrNull())
        is List<*> -> scalar(value.firstOrNull())
        is OnnxValue -> scalar(value.value)
        else -> error("unsupported ONNX output: ${value?.javaClass?.name}")
    }

    private fun OrtEnvironment.useSession(model: File, block: (OrtEnvironment, OrtSession) -> Unit) {
        createSession(model.readBytes()).use { session -> block(this, session) }
    }

    private fun v41GreenArtifacts(): Artifacts {
        val directory = File(
            repositoryRoot(),
            "data/artifacts/export/stress_v4.1.0-continuous25-ir100-20260807T195905Z/ppg_green"
        )
        return Artifacts(
            manifest = File(directory, "model_manifest.json").requireFile(),
            model = File(directory, "stress_ppg_green_fp32.onnx").requireFile(),
            vectors = File(directory, "test_vectors.json").requireFile()
        )
    }

    private fun repositoryRoot(): File {
        val workingDirectory = System.getProperty("user.dir") ?: "."
        var cursor: File? = File(workingDirectory).canonicalFile
        while (cursor != null) {
            if (File(cursor, "data/artifacts/export").isDirectory) return cursor
            cursor = cursor.parentFile
        }
        error("Cannot locate ProsesData repository root from $workingDirectory")
    }

    private fun jsonObject(file: File): JsonObject = JsonParser.parseString(file.readText()).asJsonObject

    private fun sha256(file: File): String = MessageDigest
        .getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun File.requireFile(): File = apply {
        check(isFile) { "Missing parity artifact: $absolutePath" }
    }

    private data class Artifacts(val manifest: File, val model: File, val vectors: File)

    private companion object {
        const val ONNX_PARITY_TOLERANCE = 1.0e-5f
    }
}
