package com.stressdetector.mobile.domain

import com.stressdetector.mobile.data.db.StressSessionEntity
import com.stressdetector.shared.StressLabel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

data class DailySummary(val total: Int, val stressCount: Int, val normalCount: Int) {
    val stressRate: Float get() = if (total == 0) 0f else stressCount.toFloat() / total
}

data class WeeklyTrendItem(val dayLabel: String, val total: Int, val stressCount: Int)

data class CalendarDayItem(
    val dateMillis: Long,
    val dayOfMonth: Int,
    val inCurrentMonth: Boolean,
    val total: Int,
    val stressCount: Int,
    val normalCount: Int
)

object ReportCalculator {
    fun todaySummary(
        nowMillis: Long,
        sessions: List<StressSessionEntity>,
        dayBoundaryProvider: LocalDayBoundaryProvider = defaultDayBoundaryProvider()
    ): DailySummary = dailySummary(nowMillis, sessions, dayBoundaryProvider)

    fun dailySummary(
        dayMillis: Long,
        sessions: List<StressSessionEntity>,
        dayBoundaryProvider: LocalDayBoundaryProvider = defaultDayBoundaryProvider()
    ): DailySummary {
        val daySessions = sessionsForDay(dayMillis, sessions, dayBoundaryProvider)
        return DailySummary(
            total = daySessions.size,
            stressCount = daySessions.count { it.isStressLabel() },
            normalCount = daySessions.count { it.isRelaxLabel() }
        )
    }

    fun sessionsForDay(
        dayMillis: Long,
        sessions: List<StressSessionEntity>,
        dayBoundaryProvider: LocalDayBoundaryProvider = defaultDayBoundaryProvider()
    ): List<StressSessionEntity> {
        val range = dayBoundaryProvider.rangeFor(dayMillis)
        return sessions.filter { range.contains(it.endedAtMillis) }
    }

    fun weeklyTrend(
        nowMillis: Long,
        sessions: List<StressSessionEntity>,
        dayBoundaryProvider: LocalDayBoundaryProvider = defaultDayBoundaryProvider()
    ): List<WeeklyTrendItem> {
        val format = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
        val currentDay = dayBoundaryProvider.dayFor(nowMillis)
        return (6 downTo 0).map { daysAgo ->
            val day = currentDay.minusDays(daysAgo.toLong())
            val range = dayBoundaryProvider.rangeFor(day)
            val daySessions = sessions.filter { range.contains(it.endedAtMillis) }
            WeeklyTrendItem(
                dayLabel = day.format(format),
                total = daySessions.size,
                stressCount = daySessions.count { it.isStressLabel() }
            )
        }
    }

    fun monthCalendar(
        monthMillis: Long,
        sessions: List<StressSessionEntity>,
        dayBoundaryProvider: LocalDayBoundaryProvider = defaultDayBoundaryProvider()
    ): List<CalendarDayItem> {
        val monthStart = dayBoundaryProvider.dayFor(monthMillis).withDayOfMonth(1)
        val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        val offset = (7 + monthStart.dayOfWeek.value - firstDayOfWeek.value) % 7
        val gridStart = monthStart.minusDays(offset.toLong())

        return (0 until 42).map { index ->
            val day = gridStart.plusDays(index.toLong())
            val daySessions = sessionsForDay(
                dayBoundaryProvider.rangeFor(day).startInclusiveMillis,
                sessions,
                dayBoundaryProvider
            )
            CalendarDayItem(
                dateMillis = dayBoundaryProvider.rangeFor(day).startInclusiveMillis,
                dayOfMonth = day.dayOfMonth,
                inCurrentMonth = day.year == monthStart.year && day.month == monthStart.month,
                total = daySessions.size,
                stressCount = daySessions.count { it.isStressLabel() },
                normalCount = daySessions.count { it.isRelaxLabel() }
            )
        }
    }

    fun addMonths(monthMillis: Long, delta: Int, dayBoundaryProvider: LocalDayBoundaryProvider = defaultDayBoundaryProvider()): Long =
        dayBoundaryProvider.rangeFor(
            dayBoundaryProvider.dayFor(monthMillis).withDayOfMonth(1).plusMonths(delta.toLong())
        ).startInclusiveMillis

    private fun defaultDayBoundaryProvider(): LocalDayBoundaryProvider = LocalDayBoundaryProvider(ZoneId.systemDefault())

    private fun StressSessionEntity.isStressLabel(): Boolean = StressLabel.canonical(finalLabel) == StressLabel.STRESS.value

    private fun StressSessionEntity.isRelaxLabel(): Boolean = StressLabel.canonical(finalLabel) == StressLabel.RELAX.value
}
