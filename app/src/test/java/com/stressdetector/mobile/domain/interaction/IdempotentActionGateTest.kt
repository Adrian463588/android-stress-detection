package com.stressdetector.mobile.domain.interaction

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdempotentActionGateTest {
    @Test
    fun `duplicate actions execute only once while the first action is in flight`() = runTest {
        val gate = IdempotentActionGate<String>()
        val executions = AtomicInteger(0)

        val results = (1..30).map {
            async {
                gate.run("voice-toggle") {
                    executions.incrementAndGet()
                    delay(50)
                    "completed"
                }
            }
        }.awaitAll()

        assertEquals(1, executions.get())
        assertEquals(1, results.count { it == "completed" })
        assertTrue(results.count { it == null } >= 29)
    }
}
