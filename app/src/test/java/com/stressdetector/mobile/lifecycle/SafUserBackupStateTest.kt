package com.stressdetector.mobile.lifecycle

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafUserBackupStateTest {
    private val committed = SafUserBackupState(
        enabled = true,
        running = true,
        pendingChanges = true,
        lastSuccessfulAtMillis = 10L,
        lastGeneration = "completed-generation"
    )

    @Test
    fun `cancellation releases controls before rethrowing and preserves pending receipt`() {
        val state = MutableStateFlow(committed)
        val cancellation = CancellationException("test cancellation")

        assertSame(cancellation, assertThrows(CancellationException::class.java) { state.reportOperationFailure(cancellation) })

        assertEquals(committed.copy(running = false), state.value)
        assertNull(state.value.error)
    }

    @Test
    fun `ordinary failure preserves receipt and explains the operation error`() {
        val state = MutableStateFlow(committed)

        state.reportOperationFailure(IOException("test provider failure"))

        assertEquals(committed.copy(running = false, error = "test provider failure"), state.value)
    }

    @Test
    fun `failure without message is still visible`() {
        val state = MutableStateFlow(committed)

        state.reportOperationFailure(IOException())

        assertFalse(state.value.running)
        assertEquals("Cadangan SAF gagal.", state.value.error)
    }

    @Test
    fun `optional cache failure keeps committed receipt and disables automation`() {
        val state = committed.withAutomationCache(IOException("private cache path must not be shown"))

        assertFalse(state.enabled)
        assertFalse(state.running)
        assertTrue(state.pendingChanges)
        assertEquals(committed.lastSuccessfulAtMillis, state.lastSuccessfulAtMillis)
        assertEquals(committed.lastGeneration, state.lastGeneration)
        val warning = requireNotNull(state.error)
        assertTrue(warning.contains("cadangan otomatis belum aktif"))
        assertFalse(warning.contains("private cache path"))
    }

    @Test
    fun `successful cache activates automation without changing the receipt`() {
        assertEquals(committed.copy(running = false), committed.copy(enabled = false, error = "old error").withAutomationCache(null))
    }
}
