package com.stressdetector.mobile.audio

import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

/** Numerical graph smoke test only; never release evidence for real human prosody. */
class VerbatimOpenSmileTest {
    @Test
    fun bundledNativeGraphConsumesBoundedPcmWithoutNetworkOrAnotherMicrophone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val samples = FloatArray(32_000) { index ->
            val phase = 2 * PI * 200 * index / 16_000
            (0.5 * sin(phase) + 0.2 * sin(phase * 2) + 0.1 * sin(phase * 3)).toFloat()
        }
        val evidence = OpenSmileProsodyExtractor(context).analyze(samples)
        assertTrue("Native graph must run, not silently degrade: ${evidence.unavailableReason}", evidence.frameCount > 0)
        assertTrue(evidence.features.values.all(Double::isFinite))
    }
}
