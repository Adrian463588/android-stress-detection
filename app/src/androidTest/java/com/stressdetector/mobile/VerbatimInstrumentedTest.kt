package com.stressdetector.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stressdetector.inference.ModelArtifactLoader
import com.stressdetector.mobile.ml.llm.ModelManager
import com.stressdetector.mobile.ml.llm.ModelReadiness
import com.stressdetector.mobile.ml.llm.ModelStorage
import com.stressdetector.mobile.ml.llm.ModelVerificationProgress
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the Clinical Companion App.
 * These run on a physical Dimensity 8400 Ultra device or emulator to verify offline execution.
 */
@RunWith(AndroidJUnit4::class)
class VerbatimInstrumentedTest {

    @Test
    fun verifyModelArtifactLoaderGate() {
        val dummyManifestBytes = """
            {
              "model_family": "deep_learning",
              "model_version": "stress_v4.0.0-dl",
              "preprocessing_version": "v3.0.0",
              "feature_version": "raw_waveform",
              "sensor_channel": "ppg_ir",
              "deployment_gate_passed": false,
              "android_contract_sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              "onnx_sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            }
        """.trimIndent()
        val dummyModelBytes = ByteArray(0)

        try {
            ModelArtifactLoader.load(dummyManifestBytes, dummyModelBytes)
            fail("Should throw deployment_gate_failed exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("deployment_gate_failed") == true || e.message?.contains("model_unavailable") == true)
        }
    }

    @Test
    fun verifyIncompleteArtifactIsNotReady() {
        runBlocking {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val testDir = File(appContext.cacheDir, "model-manager-instrumented-test")
            testDir.deleteRecursively()
            testDir.mkdirs()
            val modelManager = ModelManager(
                context = appContext,
                storageOverride = object : ModelStorage {
                    override fun activeModelFile(): File = File(testDir, ModelManager.MODEL_FILE_NAME)
                    override fun candidateModelFile(): File = File(testDir, ".candidate")
                    override fun resumeModelFile(): File = File(testDir, ".resume")
                    override fun legacyPartialModelFile(): File = File(testDir, ".partial")
                    override fun deleteActiveAndLegacyMetadata(): Boolean = true
                    override suspend fun inspectActive(): ModelReadiness = ModelReadiness.Missing
                    override suspend fun verifyCandidate(
                        candidate: File,
                        onProgress: suspend (ModelVerificationProgress) -> Unit
                    ): Result<Unit> = Result.failure(UnsupportedOperationException())
                    override suspend fun publishAtomically(candidate: File): ModelReadiness = error("Not used by this test")
                    override suspend fun snapshotCandidate(candidate: File, resume: File, bytes: Long) = error("Not used by this test")
                    override suspend fun stageDownloadedUri(uri: android.net.Uri, resolver: android.content.ContentResolver): File =
                        error("Not used by this test")
                    override fun deleteCandidates() = Unit
                }
            )
            val modelFile = modelManager.getModelFile()
            modelFile.writeText("dummy weights")

            val readiness = modelManager.refreshReadiness()
            assertFalse("An incomplete artifact must not be ready", readiness is ModelReadiness.Ready)
            assertFalse("An incomplete artifact must not be published as ready", modelManager.isModelReady())

            // Clean up
            testDir.deleteRecursively()
        }
    }
}
