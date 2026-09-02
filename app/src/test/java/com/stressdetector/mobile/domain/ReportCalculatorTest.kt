package com.stressdetector.mobile.domain

import com.stressdetector.mobile.data.db.StressSessionEntity
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportCalculatorTest {
    @Test
    fun todaySummaryCountsStressAndNormal() {
        val now = fixedNoon()
        val sessions = listOf(
            session("a", now, "Stress"),
            session("b", now, "Normal"),
            session("c", now - 2 * DAY, "Stress")
        )

        val summary = ReportCalculator.todaySummary(now, sessions)

        assertEquals(2, summary.total)
        assertEquals(1, summary.stressCount)
        assertEquals(1, summary.normalCount)
    }

    @Test
    fun weeklyTrendReturnsSevenDays() {
        val trend = ReportCalculator.weeklyTrend(fixedNoon(), emptyList())

        assertEquals(7, trend.size)
    }

    @Test
    fun monthCalendarCountsSessionsByDay() {
        val now = fixedNoon()
        val sessions = listOf(
            session("a", now, "Stress"),
            session("b", now, "Normal")
        )

        val calendar = ReportCalculator.monthCalendar(now, sessions)
        val selectedDay = calendar.first { it.dayOfMonth == 10 && it.inCurrentMonth }

        assertEquals(42, calendar.size)
        assertEquals(2, selectedDay.total)
        assertEquals(1, selectedDay.stressCount)
        assertEquals(1, selectedDay.normalCount)
    }

    @Test
    fun sessionsForDayFiltersOnlySelectedDate() {
        val now = fixedNoon()
        val sessions = listOf(
            session("a", now, "Stress"),
            session("b", now - DAY, "Normal")
        )

        val selected = ReportCalculator.sessionsForDay(now, sessions)

        assertEquals(1, selected.size)
        assertEquals("a", selected.first().sessionId)
    }

    @Test
    fun emptySelectedDayReturnsZeroSummary() {
        val now = fixedNoon()
        val emptyDay = now + DAY
        val summary = ReportCalculator.dailySummary(emptyDay, listOf(session("a", now, "Stress")))

        assertEquals(0, summary.total)
        assertEquals(0, summary.stressCount)
        assertEquals(0, summary.normalCount)
    }

    @Test
    fun addMonthsMovesCalendarMonth() {
        val june = fixedNoon()
        val july = Calendar.getInstance().apply { timeInMillis = ReportCalculator.addMonths(june, 1) }
        val may = Calendar.getInstance().apply { timeInMillis = ReportCalculator.addMonths(june, -1) }

        assertEquals(Calendar.JULY, july.get(Calendar.MONTH))
        assertEquals(Calendar.MAY, may.get(Calendar.MONTH))
    }

    @Test
    fun sessionsForDayUsesLocalMidnightInsteadOfAddingTwentyFourHours() {
        val provider = LocalDayBoundaryProvider(ZoneId.of("America/New_York"))
        val range = provider.rangeFor(LocalDate.of(2026, 3, 8))
        val sessions = listOf(
            session("inside", range.endExclusiveMillis - 1, "Stress"),
            session("next-day", range.endExclusiveMillis, "Normal")
        )

        val selected = ReportCalculator.sessionsForDay(
            dayMillis = range.startInclusiveMillis,
            sessions = sessions,
            dayBoundaryProvider = provider
        )

        assertEquals(listOf("inside"), selected.map(StressSessionEntity::sessionId))
    }

    private fun session(id: String, endedAt: Long, label: String) = StressSessionEntity(
        sessionId = id,
        startedAtMillis = endedAt - 300_000L,
        endedAtMillis = endedAt,
        durationMillis = 300_000L,
        primarySensor = "ppg_green",
        finalLabel = label,
        confidence = 0.9f,
        qualityTier = "good",
        stressProbability = if (label == "Stress") 0.8f else 0.2f,
        greenSampleCount = 1500,
        irSampleCount = 6000,
        errorReason = null
    )

    private fun fixedNoon(): Long = Calendar.getInstance().apply {
        set(2026, Calendar.JUNE, 10, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
