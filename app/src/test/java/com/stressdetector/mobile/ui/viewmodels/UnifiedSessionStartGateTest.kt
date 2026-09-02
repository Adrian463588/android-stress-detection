package com.stressdetector.mobile.ui.viewmodels

import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedSessionStartGateTest {

    @Test
    fun `ready Sherpa permits a new session even when the PPG monitor is unavailable`() {
        val state = UnifiedSessionUiState(
            speechRuntimeState = SpeechRuntimeState.Ready,
            monitorStatus = "UNAVAILABLE: Watch belum terhubung"
        )

        assertTrue(state.canStartNewSession)
        assertNull(state.startBlockMessage)
        assertEquals("Siap digunakan", state.speechRuntimeState.verbatimStatusLabel())
    }

    @Test
    fun `checking Sherpa blocks start with an actionable message`() {
        val state = UnifiedSessionUiState(
            speechRuntimeState = SpeechRuntimeState.Checking
        )

        assertFalse(state.canStartNewSession)
        assertEquals(
            "Speech bundle sedang diperiksa. Coba lagi sebentar.",
            state.startBlockMessage
        )
    }

    @Test
    fun `missing and failed Sherpa keep start blocked without replacing the cause`() {
        val missing = UnifiedSessionUiState(
            speechRuntimeState = SpeechRuntimeState.Unavailable(
                "Sherpa Speech Bundle belum siap. Unduh atau import melalui Profile."
            )
        )
        val failed = UnifiedSessionUiState(
            speechRuntimeState = SpeechRuntimeState.Failed("Checksum bundle tidak sesuai.")
        )

        assertFalse(missing.canStartNewSession)
        assertEquals(
            "Sherpa Speech Bundle belum siap. Unduh atau import melalui Profile.",
            missing.startBlockMessage
        )
        assertFalse(failed.canStartNewSession)
        assertEquals("Checksum bundle tidak sesuai.", failed.startBlockMessage)
    }

    @Test
    fun `transitional commands do not expose a second start action`() {
        val state = UnifiedSessionUiState(
            speechRuntimeState = SpeechRuntimeState.Ready,
            sessionCommandState = SessionCommandState.Starting
        )

        assertFalse(state.canStartNewSession)
        assertNull(state.startBlockMessage)
    }
}
