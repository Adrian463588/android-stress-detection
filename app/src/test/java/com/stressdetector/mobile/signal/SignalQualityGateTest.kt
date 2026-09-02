package com.stressdetector.mobile.signal

import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SessionConfig
import org.junit.Assert.assertFalse
import org.junit.Test

class SignalQualityGateTest {
    @Test
    fun rejectsFlatlineWindow() {
        val samples = PpgType.GREEN.samplingRateHz * SessionConfig.INFERENCE_WINDOW_SECONDS
        val window = WindowSnapshot(
            PpgType.GREEN,
            LongArray(samples) { it * 40L },
            IntArray(samples) { 100 }
        )

        val quality = SignalQualityGate(testLiveMonitorConfig()).evaluate(window, samples)

        assertFalse(quality.isUsable)
    }
}
