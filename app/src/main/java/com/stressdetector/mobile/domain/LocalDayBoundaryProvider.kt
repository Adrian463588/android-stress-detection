package com.stressdetector.mobile.domain

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class EpochMillisRange(val startInclusiveMillis: Long, val endExclusiveMillis: Long) {
    init {
        require(endExclusiveMillis > startInclusiveMillis) { "day range must not be empty" }
    }

    fun contains(epochMillis: Long): Boolean = epochMillis in startInclusiveMillis until endExclusiveMillis
}

/**
 * Pure local-calendar boundary logic. End-of-day is the next local midnight,
 * not a fixed 24-hour duration, so DST and timezone transitions remain valid.
 */
class LocalDayBoundaryProvider(private val zoneId: ZoneId = ZoneId.systemDefault()) {
    fun currentDay(clock: Clock = Clock.system(zoneId)): LocalDate = LocalDate.now(clock)

    fun dayFor(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate()

    fun rangeFor(epochMillis: Long): EpochMillisRange = rangeFor(dayFor(epochMillis))

    fun rangeFor(day: LocalDate): EpochMillisRange {
        val start = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        return EpochMillisRange(start, end)
    }

    fun nextBoundaryAfter(epochMillis: Long): Long = rangeFor(epochMillis).endExclusiveMillis
}
