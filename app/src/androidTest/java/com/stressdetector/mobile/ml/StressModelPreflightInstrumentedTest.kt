package com.stressdetector.mobile.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stressdetector.inference.AndroidAssetModelArtifactRepository
import com.stressdetector.inference.ModelArtifactResult
import com.stressdetector.inference.StressInferenceEngine
import com.stressdetector.inference.StressModelReadiness
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies accepted packaged assets, or the fail-closed state when release is blocked. */
@RunWith(AndroidJUnit4::class)
class StressModelPreflightInstrumentedTest {
    @Test
    fun packagedModelsPassPreflightOrRemainUnavailableUntilReleaseIsAccepted() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val repository = AndroidAssetModelArtifactRepository(assets)
        val artifacts = listOf("ppg_green", "ppg_ir").map { channel ->
            channel to repository.load(channel)
        }

        if (artifacts.all { it.second is ModelArtifactResult.Ready }) {
            artifacts.forEach { (channel, artifact) ->
                val engine = StressInferenceEngine((artifact as ModelArtifactResult.Ready).resources)
                try {
                    assertTrue(
                        "$channel readiness=${engine.readiness}",
                        engine.readiness is StressModelReadiness.Ready
                    )
                } finally {
                    engine.close()
                }
            }
        } else {
            artifacts.forEach { (channel, artifact) ->
                assertTrue(
                    "$channel artifact=$artifact",
                    artifact is ModelArtifactResult.Unavailable
                )
            }
        }
    }
}
