package com.stressdetector.inference

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParityVectorInstrumentedTest {
    @Test
    fun acceptedIndexOrExplicitPendingState() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val index =
            JsonParser
                .parseString(
                    assets.open("model_index.json").bufferedReader().use { it.readText() }
                ).asJsonObject
        assertEquals("1.0.0", index["schema_version"].asString)
        val models = index["models"].asJsonArray
        if (models.size() == 0) {
            assertTrue(index["release_status"].asString.startsWith("blocked_"))
            return
        }
        val policy = PackagedPerformancePolicyLoader.load(assets)
        models.forEach { element ->
            val candidate = element.asJsonObject
            assertEquals("accepted", candidate["release_status"].asString)
            assertTrue(
                policy.minimum_research.accepts(
                    candidate["balanced_accuracy_holdout"].asDouble,
                    candidate["f1_holdout"].asDouble,
                    candidate["roc_auc_holdout"].asDouble
                )
            )
            assertEquals(
                policy.declaredTier(
                    candidate["balanced_accuracy_conservative"].asDouble,
                    candidate["f1_conservative"].asDouble,
                    candidate["roc_auc_conservative"].asDouble
                ).wireName,
                candidate["performance_tier"].asString
            )
            val version = candidate["model_version"].asString
            val sensor = candidate["sensor_channel"].asString
            val files = candidate["files"].asJsonObject
            val manifestName = files["manifest"].asJsonObject["path"].asString.substringAfterLast('/')
            val modelName = files["model"].asJsonObject["path"].asString.substringAfterLast('/')
            val root = "models/$version/$sensor"
            val manifest = assets.open("$root/$manifestName").bufferedReader().use { it.readText() }
            val model = assets.open("$root/$modelName").use { it.readBytes() }
            ModelArtifactLoader.load(manifest, model)
        }
    }

    @Test
    fun acceptedModelRunsEveryRealReleaseVector() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val index =
            JsonParser
                .parseString(
                    assets.open("model_index.json").bufferedReader().use { it.readText() }
                ).asJsonObject
        val models = index["models"].asJsonArray
        if (models.size() == 0) {
            assertTrue(index["release_status"].asString.startsWith("blocked_"))
            return
        }

        models.forEach { element ->
            val candidate = element.asJsonObject
            val sensor = candidate["sensor_channel"].asString
            val version = candidate["model_version"].asString
            val resources =
                when (
                    val result = AndroidAssetModelArtifactRepository(assets).load(sensor)
                ) {
                    is ModelArtifactResult.Ready -> result.resources
                    is ModelArtifactResult.Invalid -> error("$sensor artifact invalid: ${result.reason}")
                    is ModelArtifactResult.Unavailable -> error("$sensor artifact unavailable: ${result.reason}")
                }
            val root = "models/$version/$sensor"
            val vectorsName =
                candidate["files"]
                    .asJsonObject["test_vectors"]
                    .asJsonObject["path"]
                    .asString
                    .substringAfterLast('/')
            val vectors =
                JsonParser
                    .parseString(
                        assets.open("$root/$vectorsName").bufferedReader().use { it.readText() }
                    ).asJsonObject["cases"]
                    .asJsonArray

            var validCount = 0
            var rejectionCount = 0
            OnnxStressClassifier(resources).use { classifier ->
                vectors.forEach { caseElement ->
                    val case = caseElement.asJsonObject
                    if (case["kind"].asString != "valid_raw_window") {
                        rejectionCount += 1
                        assertTrue(case["expected_status"].asString.isNotBlank())
                        assertTrue(!case.has("onnx_probability"))
                        return@forEach
                    }
                    validCount += 1
                    val input = case["input"].asJsonArray.map { it.asFloat }.toFloatArray()
                    val expected =
                        case["onnx_probability"]?.asFloat
                            ?: case["expected_stress_probability"].asFloat
                    val actual = classifier.predict(input)
                    assertTrue("$sensor/${case["case_id"].asString} output is not finite", actual.isFinite())
                    assertTrue("$sensor/${case["case_id"].asString} output is outside [0,1]", actual in 0f..1f)
                    assertTrue(
                        "$sensor/${case["case_id"].asString} ONNX parity exceeded 1e-5: " +
                            "${abs(actual - expected)}",
                        abs(actual - expected) < 1.0e-5f
                    )
                }
            }
            assertEquals("$sensor valid vector count", 94, validCount)
            assertEquals("$sensor rejection vector count", 6, rejectionCount)
            assertEquals("$sensor total vector count", 100, vectors.size())
        }
    }

    @Test
    fun physicalCandidateParityProducesContentAddressedReceipts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val candidateAssets = instrumentation.context.assets
        val index =
            JsonParser
                .parseString(
                    candidateAssets.open("candidate_model_index.json").bufferedReader().use { it.readText() }
                ).asJsonObject
        assertEquals("1.0.0", index["schema_version"].asString)
        assertEquals("candidate_physical_parity_pending", index["release_status"].asString)
        val models = index["models"].asJsonArray
        assertTrue("Physical parity requires at least one candidate", models.size() > 0)

        val targetApk = File(instrumentation.targetContext.applicationInfo.sourceDir)
        val apkSha256 = sha256(targetApk)
        val receiptDirectory =
            File(instrumentation.context.filesDir, "device_parity_receipts")
                .apply { mkdirs() }

        models.forEach { element ->
            val candidate = element.asJsonObject
            assertEquals("candidate_physical_parity_pending", candidate["release_status"].asString)
            assertTrue(!candidate["android_ready"].asBoolean)
            val sensor = candidate["sensor_channel"].asString
            val version = candidate["model_version"].asString
            val files = candidate["files"].asJsonObject
            val root = "candidate_models/$version/$sensor"

            fun read(role: String): ByteArray {
                val name = files[role].asJsonObject["path"].asString.substringAfterLast('/')
                val bytes = candidateAssets.open("$root/$name").use { it.readBytes() }
                assertEquals(files[role].asJsonObject["sha256"].asString, sha256(bytes))
                return bytes
            }

            val modelBytes = read("model")
            val manifestBytes = read("manifest")
            val vectorBytes = read("test_vectors")
            val manifestJson = manifestBytes.decodeToString()
            val manifest = Gson().fromJson(manifestJson, ModelManifest::class.java)
            val preflightVectors = parsePreflightVectors(vectorBytes, manifest.feature_order.size)
            assertEquals(94, preflightVectors.size)
            val resources =
                ModelArtifactLoader.loadCandidateForInstrumentedTest(
                    manifestJson = manifestJson,
                    modelBytes = modelBytes,
                    preflightVectors = preflightVectors
                )
            var maxAbsDiff = 0f
            OnnxStressClassifier(resources).use { classifier ->
                preflightVectors.forEach { vector ->
                    val actual = classifier.predict(vector.input)
                    maxAbsDiff = maxOf(maxAbsDiff, abs(actual - vector.expectedProbability))
                }
            }
            assertTrue("$sensor candidate parity exceeded 1e-5", maxAbsDiff < 1.0e-5f)

            val receipt =
                sortedMapOf<String, Any>().apply {
                    put("schema_version", "1.0.0")
                    put("device_fingerprint", Build.FINGERPRINT)
                    put("apk_sha256", apkSha256)
                    put("onnx_sha256", sha256(modelBytes))
                    put("model_version", version)
                    put("sensor_channel", sensor)
                    put("vector_count", 100)
                    put("max_abs_diff", maxAbsDiff.toDouble())
                    put("timestamp_utc", Instant.now().toString())
                    // Vector parity does not fabricate a physical sensor capture.
                    put("actual_sensor_acquisition", false)
                }
            receipt["receipt_sha256"] = receiptSha256(receipt)
            File(receiptDirectory, "device_parity_receipt_$sensor.json").writeText(
                Gson().toJson(receipt)
            )
        }
    }

    /** Must remain byte-identical to Python's ``_receipt_sha256`` payload. */
    private fun receiptSha256(receipt: Map<String, Any>): String {
        val fields =
            listOf(
                "actual_sensor_acquisition",
                "apk_sha256",
                "device_fingerprint",
                "max_abs_diff",
                "model_version",
                "onnx_sha256",
                "schema_version",
                "sensor_channel",
                "timestamp_utc",
                "vector_count"
            )
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { key ->
            val value = requireNotNull(receipt[key]) { "missing receipt field: $key" }
            val encoded =
                when (value) {
                    is Boolean -> value.toString().encodeToByteArray()
                    is Byte, is Short, is Int, is Long -> value.toString().encodeToByteArray()
                    is Float -> error("receipt floating-point values must be Double")
                    is Double ->
                        java.lang.Long
                            .toUnsignedString(java.lang.Double.doubleToRawLongBits(value), 16)
                            .padStart(16, '0')
                            .encodeToByteArray()
                    is String -> value.encodeToByteArray()
                    else -> error("unsupported receipt field type: ${value::class.java.name}")
                }
            digest.update("$key:${encoded.size}:".encodeToByteArray())
            digest.update(encoded)
            digest.update("\n".encodeToByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
