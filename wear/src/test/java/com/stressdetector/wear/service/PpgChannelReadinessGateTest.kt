package com.stressdetector.wear.service

import com.stressdetector.shared.PpgType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgChannelReadinessGateTest {
    @Test
    fun promotesEachChannelOnlyAfterThreeConfirmedNormalSamples() {
        val gate = PpgChannelReadinessGate(requiredNormalSamples = 3)

        repeat(2) {
            assertFalse(gate.observeConfirmedNormalSample(PpgType.GREEN))
            assertFalse(gate.observeConfirmedNormalSample(PpgType.IR))
        }
        assertTrue(gate.observedAllChannels)
        assertFalse(gate.allChannelsReady)

        assertTrue(gate.observeConfirmedNormalSample(PpgType.GREEN))
        assertFalse(gate.allChannelsReady)
        assertTrue(gate.observeConfirmedNormalSample(PpgType.IR))
        assertTrue(gate.allChannelsReady)
        assertFalse(gate.observeConfirmedNormalSample(PpgType.GREEN))
    }

    @Test
    fun diagnosticRedNeverBlocksGreenIrReadiness() {
        val gate = PpgChannelReadinessGate(requiredNormalSamples = 1)

        assertTrue(gate.observeConfirmedNormalSample(PpgType.GREEN))
        assertTrue(gate.observeConfirmedNormalSample(PpgType.IR))

        assertTrue(gate.allChannelsReady)
        assertFalse(gate.observeConfirmedNormalSample(PpgType.RED))
    }
}
