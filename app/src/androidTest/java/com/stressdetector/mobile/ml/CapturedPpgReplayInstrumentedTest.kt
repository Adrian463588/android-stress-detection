package com.stressdetector.mobile.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.mobile.diagnostics.DebugLivePpgWindowEvidence
import com.stressdetector.mobile.domain.HomePpgProcessingCoordinator
import com.stressdetector.mobile.domain.PredictionAvailability
import com.stressdetector.mobile.signal.AssetLiveMonitorRuntimeContract
import com.stressdetector.mobile.signal.PpgPreprocessResult
import com.stressdetector.mobile.signal.PreparedPpgWindowSlicer
import com.stressdetector.mobile.signal.SignalQualityGate
import com.stressdetector.shared.PpgType
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in replay of private, actual Watch batches. Never creates a sensor recording or history result. */
@RunWith(AndroidJUnit4::class)
class CapturedPpgReplayInstrumentedTest {
    @Test
    fun replayActualCaptureThroughInstalledPreprocessingQualityAndOnnx() {
        val key = InstrumentationRegistry.getArguments().getString("ppgCaptureKey")
        assumeTrue("Requires an existing private Watch capture", key?.matches(Regex("[0-9a-f]{32}")) == true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "debug_live_ppg_captures/$key")
        val manifestFile = File(source, "capture_manifest.json")
        val manifest = JsonParser.parseString(manifestFile.readText()).asJsonObject
        assertTrue(manifest["finalized"].asBoolean)
        assertEquals(0, manifest["droppedWriteCount"].asInt)
        val originals = Gson().fromJson(
            File(source, "window_evidence.json").readText(),
            Array<DebugLivePpgWindowEvidence>::class.java
        )
        val evidence = mutableListOf<DebugLivePpgWindowEvidence>()
        StressInferenceRuntime(context).use { runtime ->
            val coordinator = HomePpgProcessingCoordinator(
                runtime,
                SignalQualityGate(AssetLiveMonitorRuntimeContract(context).loadConfig())
            )
            PpgType.productionChannels.forEach { channel ->
                val samples = sortedMapOf<Long, Pair<Int, Long>>()
                source.listFiles().orEmpty().filter { it.name.startsWith("${channel.assetSuffix}_") }.forEach { file ->
                    val batch = JsonParser.parseString(file.readText()).asJsonObject
                    val times = batch["timestampsMillis"].asJsonArray.map { it.asLong }
                    val values = batch["rawValues"].asJsonArray.map { it.asInt }
                    val start = batch["elapsedStartMillis"].asLong
                    times.forEachIndexed { index, time ->
                        val elapsed = if (times.size == 1) batch["elapsedEndMillis"].asLong else start + time - times.first()
                        if (elapsed in 30_000L until 270_000L) samples[time] = values[index] to elapsed
                    }
                }
                val snapshot = WindowSnapshot(
                    type = channel,
                    timestampsMillis = samples.keys.toLongArray(),
                    rawValues = samples.values.map { it.first }.toIntArray(),
                    elapsedTimestampsMillis = samples.values.map { it.second }.toLongArray(),
                    sessionElapsedStartMillis = samples.values.first().second,
                    sessionElapsedEndMillis = samples.values.last().second,
                    sessionId = manifest["sessionId"].asString
                )
                val core = (runtime.prepareSession(snapshot) as PpgPreprocessResult.Ready).window
                val windows = PreparedPpgWindowSlicer().modelWindows(core)
                assertEquals(7, windows.size)
                val previous = originals.filter { it.channel == channel.assetSuffix }
                    .sortedBy { it.timestampsMillis.first() }
                assertEquals(7, previous.size)
                windows.forEachIndexed { index, window ->
                    val result = coordinator.processPrepared(window)
                    assertTrue(result.rejectionReason, result.availability is PredictionAvailability.Ready)
                    assertEquals(previous[index].timestampsMillis, window.timestampsMillis.toList())
                    assertEquals(previous[index].rawValues, window.rawValues.toList())
                    evidence += previous[index].copy(
                        windowId = requireNotNull(window.windowId),
                        filteredValues = window.filteredValues.toList(),
                        filteredValuesSha256 = window.filteredValuesSha256,
                        inferenceTrace = requireNotNull(result.inferenceTrace)
                    )
                }
            }
        }
        val output = File(context.filesDir, "debug_live_ppg_replays/$key-${System.currentTimeMillis()}")
        check(!output.exists() && output.mkdirs())
        source.listFiles().orEmpty().filter { it.extension == "json" }.forEach { it.copyTo(File(output, it.name)) }
        manifest.addProperty("verificationExecution", "REPLAY")
        manifest.addProperty("processingApkSha256", sha256(File(context.applicationInfo.sourceDir)))
        manifest.addProperty("sourceCaptureManifestSha256", sha256(manifestFile))
        manifest.addProperty("inferenceTraceFile", "inference_traces.json")
        File(output, "capture_manifest.json").writeText(Gson().toJson(manifest))
        File(output, "window_evidence.json").writeText(Gson().toJson(evidence))
        File(output, "inference_traces.json").writeText(Gson().toJson(evidence.mapNotNull { it.inferenceTrace }))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
