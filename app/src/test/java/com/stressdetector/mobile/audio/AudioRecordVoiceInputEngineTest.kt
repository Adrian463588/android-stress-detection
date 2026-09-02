package com.stressdetector.mobile.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRecordVoiceInputEngineTest {
    @Test
    fun `voice session identity is nonblank and time scoped`() {
        assertEquals("voice-1234", voiceSessionId(1_234L))
    }
}
