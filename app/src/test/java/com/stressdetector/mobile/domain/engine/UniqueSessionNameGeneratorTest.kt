package com.stressdetector.mobile.domain.engine

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class UniqueSessionNameGeneratorTest {
    private val generator = UniqueSessionNameGenerator(
        Clock.fixed(
            Instant.parse("2026-08-11T10:30:00Z"),
            ZoneId.of("UTC")
        )
    )

    @Test
    fun `default name uses injected clock`() {
        assertEquals(
            "Verbatim 2026-08-11 10:30",
            generator.next("Sesi Verbatim", emptyList())
        )
    }

    @Test
    fun `duplicate custom name receives deterministic suffix`() {
        assertEquals(
            "Interview (3)",
            generator.next("Interview", listOf("Interview", "Interview (2)"))
        )
    }

    @Test
    fun `comparison is case insensitive but returned spelling is preserved`() {
        assertEquals(
            "Interview (2)",
            generator.next("Interview", listOf(" interview "))
        )
    }
}
