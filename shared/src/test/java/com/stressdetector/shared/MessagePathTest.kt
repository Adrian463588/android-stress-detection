package com.stressdetector.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class MessagePathTest {
    @Test
    fun connectionHandshakePathsAreStable() {
        assertEquals("/connection/ping", MessagePath.CONNECTION_PING)
        assertEquals("/connection/pong", MessagePath.CONNECTION_PONG)
    }
}
