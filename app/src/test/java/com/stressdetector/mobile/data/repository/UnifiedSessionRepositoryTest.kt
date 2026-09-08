package com.stressdetector.mobile.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedSessionRepositoryTest {
    @Test
    fun `failed finalization is never persisted as completed`() {
        assertEquals("FAILED", sessionStatusForFinalization("FAILED"))
        assertEquals("FAILED", sessionStatusForFinalization("ERROR_FINALIZATION_FAILED"))
    }

    @Test
    fun `process death keeps an explicit interrupted status`() {
        assertEquals(
            "INTERRUPTED",
            sessionStatusForFinalization("INTERRUPTED_PROCESS_DEATH")
        )
    }

    @Test
    fun `completed artifact publication remains completed`() {
        assertEquals("COMPLETED", sessionStatusForFinalization("COMPLETED"))
    }
}
