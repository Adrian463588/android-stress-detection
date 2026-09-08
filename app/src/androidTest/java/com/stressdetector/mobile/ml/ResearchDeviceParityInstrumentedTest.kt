package com.stressdetector.mobile.ml

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.stressdetector.inference.AndroidAssetModelArtifactRepository
import com.stressdetector.inference.ModelArtifactResult
import com.stressdetector.inference.StressInferenceEngine
import com.stressdetector.inference.StressModelReadiness
import com.stressdetector.mobile.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the current run-scoped research models on the installed mobile APK.
 * The vectors are the real development feature vectors packaged by the
 * research index; this test does not claim physical sensor acquisition.
 */
@RunWith(AndroidJUnit4::class)
class ResearchDeviceParityInstrumentedTest {
    @Test
    fun currentResearchModelsWriteDeviceParityReceipts() {
        assertTrue("This test must target the research flavor", BuildConfig.RESEARCH_MODEL_MODE)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val assets = targetContext.assets
        val index = JsonParser.parseString(
            assets.open("model_index.json").bufferedReader().use { it.readText() }
        ).asJsonObject

        assertEquals("1.0.0", index["schema_version"].asString)
        assertEquals("research_candidate", index["release_status"].asString)
        val models = index["models"].asJsonArray
        assertTrue("Research index has no deployable model", models.size() > 0)

        val apkSha256 = sha256(File(targetContext.applicationInfo.sourceDir))
        val receiptDirectory = File(targetContext.cacheDir, "device_parity_receipts").apply {
            mkdirs()
        }
        val canPersistReceipt = receiptDirectory.isDirectory
        val repository = AndroidAssetModelArtifactRepository(assets)

        models.forEach { element ->
            val candidate = element.asJsonObject
            val channel = candidate["sensor_channel"].asString
            val version = candidate["model_version"].asString
            val resources = when (val result = repository.load(channel)) {
                is ModelArtifactResult.Ready -> result.resources
                is ModelArtifactResult.Invalid -> error("$channel artifact invalid: ${result.reason}")
                is ModelArtifactResult.Unavailable -> error("$channel artifact unavailable: ${result.reason}")
            }
            assertEquals(version, resources.manifest.model_version)

            val engine = StressInferenceEngine(resources)
            try {
                assertTrue(
                    "$channel readiness=${engine.readiness}",
                    engine.readiness is StressModelReadiness.Ready
                )
                val preflight = requireNotNull(engine.preflightReceipt)
                assertEquals("$channel vector count", 100, preflight.vectorCount)
                assertTrue(
                    "$channel parity exceeded 1e-5: ${preflight.maxAbsDiff}",
                    preflight.maxAbsDiff < 1.0e-5f
                )

                assumeTrue(
                    "Device parity passed, but the instrumented sandbox cannot persist a receipt: $receiptDirectory",
                    canPersistReceipt
                )
                val receipt = sortedMapOf<String, Any>().apply {
                    put("schema_version", "1.0.0")
                    put("device_fingerprint", Build.FINGERPRINT)
                    put("apk_sha256", apkSha256)
                    put("onnx_sha256", resources.manifest.onnx_sha256)
                    put("model_version", resources.manifest.model_version)
                    put("sensor_channel", resources.manifest.sensor_channel)
                    put("vector_count", preflight.vectorCount)
                    put("max_abs_diff", preflight.maxAbsDiff.toDouble())
                    put("timestamp_utc", Instant.now().toString())
                    // This is model parity on a real device, not sensor capture.
                    put("actual_sensor_acquisition", false)
                }
                receipt["receipt_sha256"] = receiptSha256(receipt)
                File(receiptDirectory, "research_device_parity_$channel.json")
                    .writeText(Gson().toJson(receipt))
            } finally {
                engine.close()
            }
        }
    }

    /** Must remain byte-identical to Python's ``_receipt_sha256`` payload. */
    private fun receiptSha256(receipt: Map<String, Any>): String {
        val fields = listOf(
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
            val encoded = when (value) {
                is Boolean -> value.toString().encodeToByteArray()
                is Byte, is Short, is Int, is Long -> value.toString().encodeToByteArray()
                is Double ->
                    java.lang.Long
                        .toUnsignedString(java.lang.Double.doubleToRawLongBits(value), 16)
                        .padStart(16, '0')
                        .encodeToByteArray()
                is String -> value.encodeToByteArray()
                else -> {
                    error("unsupported receipt field type: ${value::class.java.name}")
                }
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
}
