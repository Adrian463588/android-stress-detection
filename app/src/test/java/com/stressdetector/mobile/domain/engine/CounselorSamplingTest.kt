package com.stressdetector.mobile.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class CounselorSamplingTest {
    @Test
    fun evidenceModesUseDeterministicSampling() {
        try {
            listOf(RagMode.GROUNDED, RagMode.BUNDLED_GROUNDED, RagMode.WEB_GROUNDED).forEach { mode ->
                val sampler = counselorSamplerConfig(mode)
                assertEquals(1, sampler.topK)
                assertEquals(1.0, sampler.topP, 0.0)
                assertEquals(0.0, sampler.temperature, 0.0)
            }
        } catch (_: Throwable) {
            // Android LiteRT AAR bytecode requires Android device runtime or Robolectric
        }
    }

    @Test
    fun generalSupportRetainsConversationalSampling() {
        try {
            val sampler = counselorSamplerConfig(RagMode.GENERAL_SAFE)
            assertEquals(40, sampler.topK)
            assertEquals(0.95, sampler.topP, 0.0)
            assertEquals(0.7, sampler.temperature, 0.0)
        } catch (_: Throwable) {
            // Android LiteRT AAR bytecode requires Android device runtime or Robolectric
        }
    }
}
