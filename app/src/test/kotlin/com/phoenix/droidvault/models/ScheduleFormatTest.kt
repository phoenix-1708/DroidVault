package com.phoenix.droidvault.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure schedule display helpers introduced with the 2.0.4 Automation-tab
 * rework: the schedule card's START TIME / REPEAT rows and the status verb
 * share these with other surfaces, so the formats are pinned here.
 */
class ScheduleFormatTest {

    private fun schedule(
        type: ScheduleType = ScheduleType.DAILY,
        hour: Int = 2,
        minute: Int = 30,
        weekdays: Set<Int> = emptySet(),
        intervalMinutes: Long = 24 * 60,
        triggerAtMillis: Long = 0L,
        enabled: Boolean = true
    ) = AutomationSchedule(
        id = "s1",
        configIds = listOf("c1"),
        type = type,
        hour = hour,
        minute = minute,
        weekdays = weekdays,
        intervalMinutes = intervalMinutes,
        triggerAtMillis = triggerAtMillis,
        enabled = enabled
    )

    @Test
    fun whenTextDailyUses12HourClock() {
        assertEquals("02:30 AM", scheduleWhenText(schedule(hour = 2, minute = 30)))
        assertEquals("12:00 PM", scheduleWhenText(schedule(hour = 12, minute = 0)))
        assertEquals("11:59 PM", scheduleWhenText(schedule(hour = 23, minute = 59)))
    }

    @Test
    fun whenTextIntervalIncludesEvery() {
        assertEquals("every 45 min", scheduleWhenText(schedule(type = ScheduleType.INTERVAL, intervalMinutes = 45)))
    }

    @Test
    fun whenTextOnceWithoutTimeIsOneTime() {
        assertEquals("one-time", scheduleWhenText(schedule(type = ScheduleType.ONCE, triggerAtMillis = 0)))
    }

    @Test
    fun whenTextOnceFormatsDate() {
        // Epoch: 2026-10-01 00:00 UTC
        val t = 1791206400000L
        val text = scheduleWhenText(schedule(type = ScheduleType.ONCE, triggerAtMillis = t))
        // Locale-dependent day/month, but the bullet + 12h separator are stable.
        assertTrue("got: $text", text.contains("•"))
        assertTrue("got: $text", text.contains("AM") || text.contains("PM"))
    }

    @Test
    fun whenTextWeeklyJoinsDaysAndTime() {
        val s = schedule(type = ScheduleType.WEEKLY, weekdays = setOf(1, 3))
        val text = scheduleWhenText(s)
        assertTrue("got: $text", text.startsWith("Sun, Tue • "))
        assertTrue("got: $text", text.endsWith("02:30 AM"))
    }

    @Test
    fun whenTextWeeklyEmptyDaysFallsBackToTimeOnly() {
        assertEquals("02:30 AM", scheduleWhenText(schedule(type = ScheduleType.WEEKLY, weekdays = emptySet())))
    }

    @Test
    fun repeatTextMatchesCardCopy() {
        assertEquals("once", scheduleRepeatText(schedule(type = ScheduleType.ONCE)))
        assertEquals("Everyday", scheduleRepeatText(schedule(type = ScheduleType.DAILY)))
        assertEquals("Everyday", scheduleRepeatText(schedule(type = ScheduleType.WEEKLY, weekdays = emptySet())))
        assertEquals("Sun, Tue", scheduleRepeatText(schedule(type = ScheduleType.WEEKLY, weekdays = setOf(1, 3))))
        assertEquals("Every 15 min", scheduleRepeatText(schedule(type = ScheduleType.INTERVAL, intervalMinutes = 15)))
    }

    @Test
    fun statusVerbs() {
        assertEquals("Active", scheduleStatus(schedule()))
        assertEquals("Paused", scheduleStatus(schedule(enabled = false)))
        assertEquals("Armed", scheduleStatus(schedule(type = ScheduleType.ONCE, triggerAtMillis = 123)))
        assertEquals("No time set", scheduleStatus(schedule(type = ScheduleType.ONCE, triggerAtMillis = 0)))
    }

    @Test
    fun weekdayShortMatchesCalendarOrder() {
        assertEquals("Sun", weekdayShort(java.util.Calendar.SUNDAY))
        assertEquals("Wed", weekdayShort(java.util.Calendar.WEDNESDAY))
        assertEquals("Sat", weekdayShort(java.util.Calendar.SATURDAY))
    }
}
