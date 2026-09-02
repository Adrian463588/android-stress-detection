package com.stressdetector.mobile.domain

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDayBoundaryProviderTest {
    @Test
    fun dayRangeUsesTheNextLocalMidnightInsteadOfFixedTwentyFourHours() {
        val provider = LocalDayBoundaryProvider(ZoneId.of("America/New_York"))
        val range = provider.rangeFor(LocalDate.of(2026, 3, 8))

        assertEquals(23L * 60L * 60L * 1_000L, range.endExclusiveMillis - range.startInclusiveMillis)
        assertTrue(range.contains(range.startInclusiveMillis))
        assertFalse(range.contains(range.endExclusiveMillis))
    }

    @Test
    fun nextDayBoundaryBelongsOnlyToTheNextLocalDay() {
        val provider = LocalDayBoundaryProvider(ZoneId.of("Asia/Jakarta"))
        val first = provider.rangeFor(LocalDate.of(2026, 8, 12))
        val second = provider.rangeFor(LocalDate.of(2026, 8, 13))

        assertFalse(first.contains(second.startInclusiveMillis))
        assertTrue(second.contains(second.startInclusiveMillis))
        assertEquals(second.startInclusiveMillis, provider.nextBoundaryAfter(first.startInclusiveMillis))
    }
}
